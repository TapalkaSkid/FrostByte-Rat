package com.securitydemo.poc;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class FfmpegCapture {
   private static final PocLogger.Log LOGGER = PocLogger.get();
   private static final Pattern DSHOW_VIDEO = Pattern.compile("\"([^\"]+)\"\\s*\\(video\\)");
   private static final Pattern DSHOW_AUDIO = Pattern.compile("\"([^\"]+)\"\\s*\\(audio\\)");
   private volatile Process process;
   private volatile Thread thread;
   private volatile boolean running;

   static Path findExecutable() {
      Path winget = findWingetFfmpeg();
      if (winget != null) {
         return winget;
      } else {
         List<String> candidates = new ArrayList();
         candidates.add("ffmpeg");
         candidates.add("ffmpeg.exe");
         String programFiles = System.getenv("ProgramFiles");
         String programFilesX86 = System.getenv("ProgramFiles(x86)");
         String localAppData = System.getenv("LOCALAPPDATA");
         if (programFiles != null) {
            candidates.add(programFiles + "\\ffmpeg\\bin\\ffmpeg.exe");
            candidates.add(programFiles + "\\obs-studio\\bin\\64bit\\ffmpeg.exe");
         }

         if (programFilesX86 != null) {
            candidates.add(programFilesX86 + "\\obs-studio\\bin\\64bit\\ffmpeg.exe");
         }

         if (localAppData != null) {
            candidates.add(localAppData + "\\Programs\\obs-studio\\bin\\64bit\\ffmpeg.exe");
            candidates.add(localAppData + "\\Microsoft\\WinGet\\Links\\ffmpeg.exe");
         }

         candidates.add("C:\\ffmpeg\\bin\\ffmpeg.exe");
         String userProfile = System.getenv("USERPROFILE");
         if (userProfile != null) {
            candidates.add(userProfile + "\\scoop\\shims\\ffmpeg.exe");
         }

         candidates.add("C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe");

         for(String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path, new LinkOption[0])) {
               return path.toAbsolutePath().normalize();
            }

            if (!candidate.contains("\\") && !candidate.contains("/")) {
               String resolved = resolveOnPath(candidate);
               if (resolved != null) {
                  return Paths.get(resolved);
               }
            }
         }

         return null;
      }
   }

   private static Path findWingetFfmpeg() {
      String localAppData = System.getenv("LOCALAPPDATA");
      if (localAppData == null) {
         return null;
      } else {
         Path packages = Paths.get(localAppData, "Microsoft", "WinGet", "Packages");
         if (!Files.isDirectory(packages, new LinkOption[0])) {
            return null;
         } else {
            try {
               Stream<Path> walk = Files.walk(packages, 5, new FileVisitOption[0]);

               Path var3;
               try {
                  var3 = (Path)walk.filter((path) -> "ffmpeg.exe".equalsIgnoreCase(path.getFileName().toString())).filter((x$0) -> Files.isRegularFile(x$0, new LinkOption[0])).findFirst().map((path) -> path.toAbsolutePath().normalize()).orElse((Object)null);
               } catch (Throwable var6) {
                  if (walk != null) {
                     try {
                        walk.close();
                     } catch (Throwable var5) {
                        var6.addSuppressed(var5);
                     }
                  }

                  throw var6;
               }

               if (walk != null) {
                  walk.close();
               }

               return var3;
            } catch (IOException e) {
               LOGGER.warn("WinGet ffmpeg search failed: {}", e.getMessage());
               return null;
            }
         }
      }
   }

   private static String resolveOnPath(String name) {
      String pathEnv = System.getenv("PATH");
      if (pathEnv != null && !Compat.isBlank(pathEnv)) {
         for(String dir : pathEnv.split(";")) {
            if (!Compat.isBlank(dir)) {
               Path exe = Paths.get(dir.trim(), name);
               if (Files.isRegularFile(exe, new LinkOption[0])) {
                  return exe.toAbsolutePath().normalize().toString();
               }
            }
         }

         return null;
      } else {
         return null;
      }
   }

   static List<String> listDirectShowAudioDevices() {
      return listDirectShowDevices(DSHOW_AUDIO);
   }

   static String resolveDshowAudioDeviceName(String wanted) {
      return resolveDshowDeviceName(wanted, listDirectShowAudioDevices());
   }

   static String resolveDshowDeviceName(String wanted, List<String> devices) {
      if (devices.isEmpty()) {
         return stripIndexSuffix(wanted);
      } else if (wanted != null && !Compat.isBlank(wanted)) {
         String norm = normalizeDeviceName(wanted);

         for(String device : devices) {
            String deviceNorm = normalizeDeviceName(device);
            if (deviceNorm.equals(norm) || deviceNorm.contains(norm) || norm.contains(deviceNorm)) {
               return device;
            }
         }

         return stripIndexSuffix(wanted);
      } else {
         return (String)devices.get(0);
      }
   }

   private static List<String> listDirectShowDevices(Pattern pattern) {
      Path ffmpeg = findExecutable();
      if (ffmpeg == null) {
         return Collections.emptyList();
      } else {
         Set<String> names = new LinkedHashSet();

         try {
            Process process = (new ProcessBuilder(new String[]{ffmpeg.toString(), "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"})).redirectErrorStream(true).start();
            String output = new String(Compat.readStreamBytes(process.getInputStream()), StandardCharsets.UTF_8);
            process.waitFor();

            for(String line : output.split("\\R")) {
               Matcher match = pattern.matcher(line);
               if (match.find()) {
                  names.add(match.group(1));
               }
            }
         } catch (Exception e) {
            LOGGER.warn("ffmpeg dshow list failed: {}", e.getMessage());
         }

         return new ArrayList(names);
      }
   }

   static List<String> listDirectShowDevices() {
      return listDirectShowDevices(DSHOW_VIDEO);
   }

   static String resolveDshowDeviceName(String wanted) {
      return resolveDshowDeviceName(wanted, listDirectShowDevices());
   }

   private static String stripIndexSuffix(String name) {
      return name == null ? null : name.replaceAll("\\s+\\d+$", "").trim();
   }

   private static String normalizeDeviceName(String name) {
      return stripIndexSuffix(name).toLowerCase(Locale.ROOT);
   }

   void start(Path ffmpeg, String deviceName, int fps, float quality, float scale, Consumer<byte[]> onFrame) throws IOException {
      this.stop();
      if (ffmpeg != null && Files.isRegularFile(ffmpeg, new LinkOption[0])) {
         if (deviceName != null && !Compat.isBlank(deviceName)) {
            String dshowDevice = resolveDshowDeviceName(deviceName);
            IOException lastError = null;

            for(String[] args : buildArgVariants(ffmpeg, dshowDevice, fps, quality, scale)) {
               try {
                  this.launch(ffmpeg, args, onFrame);
                  return;
               } catch (IOException e) {
                  lastError = e;
                  LOGGER.warn("ffmpeg capture variant failed: {}", e.getMessage());
               }
            }

            if (lastError != null) {
               throw lastError;
            } else {
               throw new IOException("Unable to open camera: " + dshowDevice);
            }
         } else {
            throw new IOException("Missing camera device name");
         }
      } else {
         throw new IOException("ffmpeg not found — установи ffmpeg (winget install Gyan.FFmpeg)");
      }
   }

   private static List<String[]> buildArgVariants(Path ffmpeg, String deviceName, int fps, float quality, float scale) {
      int q = Compat.clamp(Math.round(quality * 31.0F), 2, 31);
      int targetFps = Compat.clamp(fps, 1, 30);
      float clampedScale = Compat.clamp(scale, 0.25F, 1.0F);
      int outW = Math.max(160, Math.round(640.0F * clampedScale));
      int outH = Math.max(120, Math.round(480.0F * clampedScale));
      String input = "video=" + deviceName;
      String vf = "fps=" + targetFps + ",scale=" + outW + ":" + outH;
      List<String[]> variants = new ArrayList();
      variants.add(new String[]{ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-f", "dshow", "-rtbufsize", "100M", "-i", input, "-an", "-vf", vf, "-f", "image2pipe", "-vcodec", "mjpeg", "-q:v", String.valueOf(q), "-"});
      variants.add(new String[]{ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-f", "dshow", "-video_size", "640x480", "-framerate", String.valueOf(targetFps), "-i", input, "-an", "-vf", "scale=" + outW + ":" + outH, "-f", "image2pipe", "-vcodec", "mjpeg", "-q:v", String.valueOf(q), "-"});
      return variants;
   }

   private void launch(Path ffmpeg, String[] args, Consumer<byte[]> onFrame) throws IOException {
      ProcessBuilder builder = new ProcessBuilder(args);
      builder.redirectError(Redirect.PIPE);
      this.process = builder.start();
      this.running = true;
      StringBuilder stderr = new StringBuilder();
      Thread stderrReader = new Thread(() -> {
         try {
            InputStream err = this.process.getErrorStream();

            try {
               byte[] buf = new byte[512];

               int read;
               while((read = err.read(buf)) != -1) {
                  String chunk = new String(buf, 0, read, StandardCharsets.UTF_8);
                  stderr.append(chunk);

                  for(String line : chunk.split("\\R")) {
                     if (!Compat.isBlank(line)) {
                        LOGGER.warn("ffmpeg cam: {}", line.trim());
                     }
                  }
               }
            } catch (Throwable var11) {
               if (err != null) {
                  try {
                     err.close();
                  } catch (Throwable var10) {
                     var11.addSuppressed(var10);
                  }
               }

               throw var11;
            }

            if (err != null) {
               err.close();
            }
         } catch (IOException var12) {
         }

      }, "security-demo-poc-ffmpeg-err");
      stderrReader.setDaemon(true);
      stderrReader.start();
      byte[] firstFrame = readFirstJpeg(this.process.getInputStream(), 8000L);
      if (firstFrame == null) {
         this.running = false;
         this.process.destroyForcibly();
         this.process = null;
         String detail = stderr.toString().trim();
         if (!detail.contains("I/O error") && !detail.contains("Error opening input")) {
            throw new IOException(Compat.isBlank(detail) ? "Нет кадров с камеры" : detail);
         } else {
            throw new IOException("Камера недоступна — запусти OBS Virtual Camera или выбери другое устройство");
         }
      } else {
         onFrame.accept(firstFrame);
         this.thread = new Thread(() -> {
            try {
               pumpJpeg(this.process.getInputStream(), onFrame);
            } finally {
               if (this.running) {
                  try {
                     this.process.waitFor();
                  } catch (InterruptedException var8) {
                     Thread.currentThread().interrupt();
                  }
               }

            }

         }, "security-demo-poc-ffmpeg-cam");
         this.thread.setDaemon(true);
         this.thread.start();
      }
   }

   private static byte[] readFirstJpeg(InputStream input, long timeoutMs) throws IOException {
      long deadline = System.currentTimeMillis() + timeoutMs;
      BufferedInputStream stream = new BufferedInputStream(input);

      byte[] var11;
      label45: {
         label44: {
            try {
               while(System.currentTimeMillis() < deadline) {
                  if (stream.available() <= 0) {
                     try {
                        Thread.sleep(40L);
                     } catch (InterruptedException var9) {
                        Thread.currentThread().interrupt();
                        var11 = null;
                        break label45;
                     }
                  } else {
                     byte[] frame = readNextJpeg(stream);
                     if (frame != null) {
                        var11 = frame;
                        break label44;
                     }
                  }
               }
            } catch (Throwable var10) {
               try {
                  stream.close();
               } catch (Throwable var8) {
                  var10.addSuppressed(var8);
               }

               throw var10;
            }

            stream.close();
            return null;
         }

         stream.close();
         return var11;
      }

      stream.close();
      return var11;
   }

   private static void pumpJpeg(InputStream input, Consumer<byte[]> onFrame) {
      try {
         BufferedInputStream stream = new BufferedInputStream(input);

         try {
            while(true) {
               byte[] frame = readNextJpeg(stream);
               if (frame == null) {
                  break;
               }

               onFrame.accept(frame);
            }
         } catch (Throwable var6) {
            try {
               stream.close();
            } catch (Throwable var5) {
               var6.addSuppressed(var5);
            }

            throw var6;
         }

         stream.close();
      } catch (Exception e) {
         LOGGER.warn("ffmpeg capture ended: {}", e.getMessage());
      }

   }

   private static byte[] readNextJpeg(BufferedInputStream stream) throws IOException {
      while(true) {
         int b;
         if ((b = stream.read()) != -1) {
            if (b != 255) {
               continue;
            }

            int marker = stream.read();
            if (marker != 216) {
               continue;
            }

            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(255);
            frame.write(216);

            for(int prev = -1; (b = stream.read()) != -1; prev = b) {
               frame.write(b);
               if (prev == 255 && b == 217) {
                  return frame.toByteArray();
               }
            }

            return null;
         }

         return null;
      }
   }

   void stop() {
      this.running = false;
      Process active = this.process;
      this.process = null;
      if (active != null) {
         active.destroyForcibly();
      }

      if (this.thread != null) {
         this.thread.interrupt();
         this.thread = null;
      }

   }
}
