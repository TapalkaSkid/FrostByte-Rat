package com.securitydemo.poc;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamResolution;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class VideoCapture implements AutoCloseable {
   private static final PocLogger.Log LOGGER = PocLogger.get();
   private static final String[] KNOWN_VIRTUAL_CAMS = new String[]{"OBS Virtual Camera", "OBS-Camera", "Streamlabs Virtual Webcam"};
   private volatile Webcam webcam;
   private volatile FfmpegCapture ffmpegCapture;
   private volatile Thread thread;
   private volatile boolean running;
   private volatile String activeDeviceName;
   private volatile Dimension activeSize;
   private volatile boolean ffmpegBackend;

   String activeDeviceName() {
      return this.activeDeviceName;
   }

   Dimension activeSize() {
      return this.activeSize;
   }

   static List<VideoDevice> listDevices() {
      Set<String> names = new LinkedHashSet();

      for(String name : FfmpegCapture.listDirectShowDevices()) {
         names.add(name);
      }

      try {
         for(Webcam cam : Webcam.getWebcams()) {
            names.add(cam.getName());
         }

         Webcam defaultCam = Webcam.getDefault();
         if (defaultCam != null) {
            names.add(defaultCam.getName());
         }
      } catch (Exception e) {
         LOGGER.warn("Webcam enumeration failed: {}", e.getMessage());
      }

      for(String fallback : KNOWN_VIRTUAL_CAMS) {
         names.add(fallback);
      }

      List<VideoDevice> devices = new ArrayList();
      int index = 0;

      for(String name : names) {
         devices.add(new VideoDevice(index++, name, name));
      }

      return devices;
   }

   void start(String deviceName, int fps, float quality, float scale, Consumer<byte[]> onFrame) throws IOException {
      this.stop();
      String resolvedName = resolveDeviceName(deviceName);
      IOException lastError = null;
      Path ffmpeg = FfmpegCapture.findExecutable();
      if (ffmpeg != null) {
         try {
            String dshowName = FfmpegCapture.resolveDshowDeviceName(resolvedName);
            this.ffmpegCapture = new FfmpegCapture();
            this.ffmpegCapture.start(ffmpeg, dshowName, fps, quality, scale, onFrame);
            this.ffmpegBackend = true;
            this.activeDeviceName = dshowName;
            int baseW = Math.max(160, Math.round(640.0F * Compat.clamp(scale, 0.25F, 1.0F)));
            int baseH = Math.max(120, Math.round(480.0F * Compat.clamp(scale, 0.25F, 1.0F)));
            this.activeSize = new Dimension(baseW, baseH);
            LOGGER.info("Webcam capture started via ffmpeg dshow: {} @ {}fps", dshowName, fps);
            return;
         } catch (IOException e) {
            lastError = e;
            LOGGER.warn("ffmpeg dshow failed for {}: {}", resolvedName, e.getMessage());
         }
      }

      Webcam selected = pickWebcam(resolvedName);
      if (selected != null) {
         try {
            this.startSarxos(selected, fps, quality, scale, onFrame);
            return;
         } catch (IOException e) {
            lastError = e;
            LOGGER.warn("Sarxos webcam failed for {}: {}", resolvedName, e.getMessage());
         }
      } else {
         for(VideoDevice listed : listDevices()) {
            Webcam candidate = pickWebcam(listed.name);
            if (candidate != null) {
               try {
                  this.startSarxos(candidate, fps, quality, scale, onFrame);
                  return;
               } catch (IOException e) {
                  lastError = e;
                  LOGGER.warn("Sarxos webcam failed for {}: {}", listed.name, e.getMessage());
               }
            }
         }
      }

      if (lastError != null) {
         throw lastError;
      } else {
         throw new IOException("Камера не найдена. Запусти OBS Virtual Camera или разреши доступ к камере для Java.");
      }
   }

   private void startSarxos(Webcam selected, int fps, float quality, float scale, Consumer<byte[]> onFrame) throws IOException {
      Dimension size = pickSize(selected);
      selected.setViewSize(size);
      if (!selected.open()) {
         throw new IOException("Unable to open webcam: " + selected.getName());
      } else {
         this.webcam = selected;
         this.ffmpegBackend = false;
         this.activeDeviceName = selected.getName();
         this.activeSize = size;
         this.running = true;
         int targetFps = Compat.clamp(fps, 1, 30);
         long frameDelayMs = Math.max(1L, 1000L / (long)targetFps);
         float clampedQuality = Compat.clamp(quality, 0.1F, 1.0F);
         float clampedScale = Compat.clamp(scale, 0.25F, 1.0F);
         BufferedImage first = waitFirstFrame(selected, 8000L);
         if (first == null) {
            selected.close();
            this.webcam = null;
            this.running = false;
            throw new IOException("Нет кадров с камеры: " + selected.getName());
         } else {
            onFrame.accept(encodeJpeg(downscale(first, clampedScale), clampedQuality));
            this.thread = new Thread(() -> {
               while(true) {
                  if (this.running) {
                     long started = System.currentTimeMillis();
                     Webcam active = this.webcam;
                     if (active != null && active.isOpen()) {
                        try {
                           BufferedImage frame = active.getImage();
                           if (frame != null) {
                              BufferedImage processed = downscale(frame, clampedScale);
                              onFrame.accept(encodeJpeg(processed, clampedQuality));
                           }
                        } catch (Exception e) {
                           LOGGER.warn("Webcam frame capture failed: {}", e.getMessage());
                        }
                     }

                     long elapsed = System.currentTimeMillis() - started;
                     long sleepMs = Math.max(1L, frameDelayMs - elapsed);

                     try {
                        Thread.sleep(sleepMs);
                        continue;
                     } catch (InterruptedException var15) {
                        Thread.currentThread().interrupt();
                     }
                  }

                  return;
               }
            }, "security-demo-poc-webcam");
            this.thread.setDaemon(true);
            this.thread.start();
            LOGGER.info("Webcam capture started: {} {}x{} @ {}fps", this.activeDeviceName, size.width, size.height, targetFps);
         }
      }
   }

   private static BufferedImage waitFirstFrame(Webcam webcam, long timeoutMs) {
      long deadline = System.currentTimeMillis() + timeoutMs;

      while(System.currentTimeMillis() < deadline) {
         if (!webcam.isOpen()) {
            return null;
         }

         try {
            BufferedImage frame = webcam.getImage();
            if (frame != null) {
               return frame;
            }

            Thread.sleep(50L);
         } catch (InterruptedException var6) {
            Thread.currentThread().interrupt();
            return null;
         } catch (Exception e) {
            LOGGER.warn("Webcam first frame failed: {}", e.getMessage());
         }
      }

      return null;
   }

   private static String resolveDeviceName(String deviceName) throws IOException {
      if (deviceName != null && !Compat.isBlank(deviceName)) {
         return deviceName.trim();
      } else {
         Webcam defaultCam = Webcam.getDefault();
         if (defaultCam != null) {
            return defaultCam.getName();
         } else {
            List<VideoDevice> devices = listDevices();
            if (devices.isEmpty()) {
               throw new IOException("No camera devices found");
            } else {
               return ((VideoDevice)devices.get(0)).name;
            }
         }
      }
   }

   private static Webcam pickWebcam(String deviceName) {
      try {
         List<Webcam> webcams = Webcam.getWebcams();
         if (webcams.isEmpty()) {
            return Webcam.getDefault();
         }

         if (deviceName == null || Compat.isBlank(deviceName)) {
            return (Webcam)webcams.get(0);
         }

         String wanted = deviceName.trim().toLowerCase(Locale.ROOT);

         for(Webcam cam : webcams) {
            String name = cam.getName().toLowerCase(Locale.ROOT);
            if (name.equals(wanted) || name.contains(wanted) || wanted.contains(name)) {
               return cam;
            }
         }
      } catch (Exception e) {
         LOGGER.warn("Webcam pick failed: {}", e.getMessage());
      }

      return null;
   }

   private static Dimension pickSize(Webcam webcam) {
      Dimension[] sizes = webcam.getViewSizes();
      if (sizes != null && sizes.length != 0) {
         Dimension preferred = WebcamResolution.VGA.getSize();

         for(Dimension size : sizes) {
            if (size.width == preferred.width && size.height == preferred.height) {
               return size;
            }
         }

         return sizes[0];
      } else {
         return WebcamResolution.VGA.getSize();
      }
   }

   private static BufferedImage downscale(BufferedImage source, float scale) {
      if (scale >= 0.99F) {
         return source;
      } else {
         int width = Math.max(160, Math.round((float)source.getWidth() * scale));
         int height = Math.max(120, Math.round((float)source.getHeight() * scale));
         BufferedImage scaled = new BufferedImage(width, height, 1);
         Graphics2D graphics = scaled.createGraphics();
         graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
         graphics.drawImage(source, 0, 0, width, height, (ImageObserver)null);
         graphics.dispose();
         return scaled;
      }
   }

   private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
      Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
      if (!writers.hasNext()) {
         ByteArrayOutputStream fallback = new ByteArrayOutputStream();
         ImageIO.write(image, "jpg", fallback);
         return fallback.toByteArray();
      } else {
         ImageWriter writer = (ImageWriter)writers.next();
         ImageWriteParam params = writer.getDefaultWriteParam();
         params.setCompressionMode(2);
         params.setCompressionQuality(quality);
         ByteArrayOutputStream output = new ByteArrayOutputStream();

         try {
            ImageOutputStream stream = ImageIO.createImageOutputStream(output);

            try {
               writer.setOutput(stream);
               writer.write((IIOMetadata)null, new IIOImage(image, (List)null, (IIOMetadata)null), params);
            } catch (Throwable var14) {
               if (stream != null) {
                  try {
                     stream.close();
                  } catch (Throwable var13) {
                     var14.addSuppressed(var13);
                  }
               }

               throw var14;
            }

            if (stream != null) {
               stream.close();
            }
         } finally {
            writer.dispose();
         }

         return output.toByteArray();
      }
   }

   void stop() {
      this.running = false;
      if (this.thread != null) {
         this.thread.interrupt();
         this.thread = null;
      }

      Webcam active = this.webcam;
      this.webcam = null;
      if (active != null && active.isOpen()) {
         active.close();
      }

      if (this.ffmpegCapture != null) {
         this.ffmpegCapture.stop();
         this.ffmpegCapture = null;
      }

      this.activeDeviceName = null;
      this.activeSize = null;
      this.ffmpegBackend = false;
   }

   public void close() {
      this.stop();
   }

   @Environment(EnvType.CLIENT)
   static final class VideoDevice {
      final int index;
      final String name;
      final String description;

      VideoDevice(int index, String name, String description) {
         this.index = index;
         this.name = name;
         this.description = description;
      }
   }
}
