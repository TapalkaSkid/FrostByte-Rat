package com.securitydemo.poc;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class FfmpegAudioCapture implements AutoCloseable {
   private static final PocLogger.Log LOGGER = PocLogger.get();
   private volatile Process process;
   private volatile Thread thread;
   private volatile boolean running;
   private volatile int sampleRate = 44100;
   private volatile int channels = 1;

   int sampleRate() {
      return this.sampleRate;
   }

   int channels() {
      return this.channels;
   }

   void start(Path ffmpeg, String deviceName, int sampleRate, int channels, Consumer<byte[]> onChunk) throws IOException {
      this.stop();
      if (ffmpeg != null && Files.isRegularFile(ffmpeg, new LinkOption[0])) {
         if (deviceName != null && !Compat.isBlank(deviceName)) {
            String dshowDevice = FfmpegCapture.resolveDshowAudioDeviceName(deviceName);
            int targetRate = sampleRate > 0 ? sampleRate : '걄';
            int targetChannels = Compat.clamp(channels, 1, 2);
            IOException lastError = null;

            for(int ch : new int[]{targetChannels, 1, 2}) {
               for(int rate : new int[]{targetRate, 48000, 44100, 16000}) {
                  try {
                     this.launch(ffmpeg, dshowDevice, rate, ch, onChunk);
                     this.sampleRate = rate;
                     this.channels = ch;
                     return;
                  } catch (IOException e) {
                     lastError = e;
                  }
               }
            }

            throw lastError != null ? lastError : new IOException("Unable to open audio device: " + dshowDevice);
         } else {
            throw new IOException("Missing audio device name");
         }
      } else {
         throw new IOException("ffmpeg not found");
      }
   }

   private void launch(Path ffmpeg, String deviceName, int rate, int ch, Consumer<byte[]> onChunk) throws IOException {
      ProcessBuilder builder = new ProcessBuilder(new String[]{ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-f", "dshow", "-i", "audio=" + deviceName, "-ac", String.valueOf(ch), "-ar", String.valueOf(rate), "-f", "s16le", "-"});
      builder.redirectError(Redirect.PIPE);
      this.process = builder.start();
      this.running = true;
      AtomicReference<String> stderr = new AtomicReference("");
      Thread stderrReader = new Thread(() -> readStderr(this.process, stderr), "security-demo-poc-ffmpeg-audio-err");
      stderrReader.setDaemon(true);
      stderrReader.start();
      CountDownLatch firstChunk = new CountDownLatch(1);
      Consumer<byte[]> wrapped = (chunk) -> {
         onChunk.accept(chunk);
         firstChunk.countDown();
      };
      this.thread = new Thread(() -> this.pump(this.process.getInputStream(), wrapped), "security-demo-poc-ffmpeg-audio");
      this.thread.setDaemon(true);
      this.thread.start();

      try {
         if (!firstChunk.await(4L, TimeUnit.SECONDS)) {
            this.running = false;
            this.process.destroyForcibly();
            this.process = null;
            String detail = (String)stderr.get();
            throw new IOException(Compat.isBlank(detail) ? "No audio from " + deviceName : detail);
         }
      } catch (InterruptedException var12) {
         Thread.currentThread().interrupt();
         throw new IOException("Audio capture interrupted");
      }
   }

   private static void readStderr(Process process, AtomicReference<String> stderr) {
      try {
         InputStream err = process.getErrorStream();

         try {
            byte[] buf = new byte[512];
            StringBuilder out = new StringBuilder();

            int read;
            while((read = err.read(buf)) != -1) {
               String text = new String(buf, 0, read, StandardCharsets.UTF_8);
               out.append(text);

               for(String line : text.split("\\R")) {
                  if (!Compat.isBlank(line)) {
                     LOGGER.warn("ffmpeg audio: {}", line.trim());
                  }
               }
            }

            stderr.set(out.toString().trim());
         } catch (Throwable var12) {
            if (err != null) {
               try {
                  err.close();
               } catch (Throwable var11) {
                  var12.addSuppressed(var11);
               }
            }

            throw var12;
         }

         if (err != null) {
            err.close();
         }
      } catch (IOException var13) {
      }

   }

   private void pump(InputStream input, Consumer<byte[]> onChunk) {
      byte[] buffer = new byte[8192];

      try {
         BufferedInputStream stream = new BufferedInputStream(input);

         int read;
         try {
            while(this.running && (read = stream.read(buffer)) > 0) {
               byte[] chunk = new byte[read];
               System.arraycopy(buffer, 0, chunk, 0, read);
               onChunk.accept(chunk);
            }
         } catch (Throwable var8) {
            try {
               stream.close();
            } catch (Throwable var7) {
               var8.addSuppressed(var7);
            }

            throw var8;
         }

         stream.close();
      } catch (Exception e) {
         LOGGER.warn("ffmpeg audio capture ended: {}", e.getMessage());
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

   public void close() {
      this.stop();
   }
}
