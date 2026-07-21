package com.securitydemo.poc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class LocalScreenStreamServer {
   private static final Gson GSON = new Gson();
   private static final String HTML = loadHtml();
   private HttpServer server;
   private Thread captureThread;
   private final AtomicBoolean running = new AtomicBoolean(false);
   private final AtomicReference<byte[]> latestFrame = new AtomicReference(new byte[0]);
   private final StreamRuntimeState state = new StreamRuntimeState();

   private static String loadHtml() {
      try {
         return Compat.readUtf8Resource(LocalScreenStreamServer.class, "/security-demo-poc/local-stream.html");
      } catch (IOException e) {
         throw new ExceptionInInitializerError(e);
      }
   }

   public void start(int port, int fps, int quality, float scale, boolean adaptive) throws IOException {
      if (!this.running.get()) {
         this.state.applyManual(fps, quality, scale, adaptive);
         int safePort = Compat.clamp(port, 1024, 65535);
         this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", safePort), 0);
         this.server.createContext("/", this::handleIndex);
         this.server.createContext("/stream", this::handleStream);
         this.server.createContext("/api/settings", this::handleSettings);
         this.server.createContext("/api/mouse", this::handleMouse);
         this.server.setExecutor(Executors.newFixedThreadPool(4, (r) -> {
            Thread thread = new Thread(r, "security-demo-poc-http");
            thread.setDaemon(true);
            return thread;
         }));
         this.server.start();
         this.running.set(true);
         this.captureThread = new Thread(this::captureLoop, "security-demo-poc-capture");
         this.captureThread.setDaemon(true);
         this.captureThread.start();
         PocLogger.get().info("Screen mirror started at http://127.0.0.1:{}/", safePort);
      }
   }

   public void stop() {
      this.running.set(false);
      if (this.captureThread != null) {
         this.captureThread.interrupt();
         this.captureThread = null;
      }

      if (this.server != null) {
         this.server.stop(0);
         this.server = null;
      }

   }

   public String url(int port) {
      return "http://127.0.0.1:" + port + "/";
   }

   private void captureLoop() {
      while(true) {
         if (this.running.get() && !Thread.currentThread().isInterrupted()) {
            int fps = this.state.deliveryFps();
            long frameDelayMs = Math.max(1L, 1000L / (long)fps);
            long started = System.currentTimeMillis();

            try {
               byte[] frame = ScreenFrames.captureJpeg(this.state.captureQuality(), this.state.captureScale());
               this.latestFrame.set(frame);
               this.state.lastFrameBytes = (long)frame.length;
            } catch (Exception e) {
               PocLogger.get().warn("Screen capture frame failed", (Throwable)e);
            }

            long captureMs = System.currentTimeMillis() - started;
            this.state.tuneAfterCapture(captureMs);
            long sleepMs = Math.max(1L, frameDelayMs - captureMs);

            try {
               Thread.sleep(sleepMs);
               continue;
            } catch (InterruptedException var12) {
               Thread.currentThread().interrupt();
            }
         }

         return;
      }
   }

   private void handleIndex(HttpExchange exchange) throws IOException {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
         exchange.sendResponseHeaders(405, -1L);
      } else {
         writeText(exchange, 200, "text/html; charset=utf-8", HTML);
      }
   }

   private void handleSettings(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      if ("GET".equalsIgnoreCase(method)) {
         writeJson(exchange, 200, this.settingsJson());
      } else if (!"POST".equalsIgnoreCase(method)) {
         exchange.sendResponseHeaders(405, -1L);
      } else {
         JsonObject body = readJsonBody(exchange);
         if (body.has("preset")) {
            this.state.applyPreset(body.get("preset").getAsString());
         } else {
            int fps = body.has("fps") ? body.get("fps").getAsInt() : this.state.targetFps;
            int quality = body.has("quality") ? body.get("quality").getAsInt() : this.state.targetQuality;
            float scale = body.has("scale") ? body.get("scale").getAsFloat() : this.state.targetScale;
            boolean adaptive = !body.has("adaptive") || body.get("adaptive").getAsBoolean();
            this.state.applyManual(fps, quality, scale, adaptive);
         }

         writeJson(exchange, 200, this.settingsJson());
      }
   }

   private void handleMouse(HttpExchange exchange) throws IOException {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
         exchange.sendResponseHeaders(405, -1L);
      } else {
         JsonObject body = readJsonBody(exchange);
         if (body.has("action") && body.has("x") && body.has("y")) {
            String action = body.get("action").getAsString();
            double x = body.get("x").getAsDouble();
            double y = body.get("y").getAsDouble();
            int button = body.has("button") ? body.get("button").getAsInt() : 0;
            boolean doubleClick = body.has("double") && body.get("double").getAsBoolean();

            try {
               if ("move".equals(action)) {
                  RemoteInputController.moveNormalized(x, y);
               } else if ("down".equals(action)) {
                  RemoteInputController.pressNormalized(x, y, button);
               } else if ("up".equals(action)) {
                  RemoteInputController.releaseNormalized(x, y, button);
               } else {
                  if (!"click".equals(action)) {
                     writeJson(exchange, 400, "{\"ok\":false}");
                     return;
                  }

                  RemoteInputController.clickNormalized(x, y, button, doubleClick);
               }

               writeJson(exchange, 200, "{\"ok\":true}");
            } catch (Exception e) {
               PocLogger.get().warn("Mouse action failed: {}", action, e);
               writeJson(exchange, 500, "{\"ok\":false}");
            }

         } else {
            writeJson(exchange, 400, "{\"ok\":false}");
         }
      }
   }

   private void handleStream(HttpExchange exchange) throws IOException {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
         exchange.sendResponseHeaders(405, -1L);
      } else {
         exchange.getResponseHeaders().add("Content-Type", "multipart/x-mixed-replace; boundary=frame");
         exchange.getResponseHeaders().add("Cache-Control", "no-cache, no-store, must-revalidate");
         exchange.sendResponseHeaders(200, 0L);
         OutputStream out = exchange.getResponseBody();
         byte[] boundaryPrefix = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ".getBytes(StandardCharsets.UTF_8);
         byte[] boundaryMiddle = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
         byte[] boundaryEnd = "\r\n".getBytes(StandardCharsets.UTF_8);
         byte[] lastSent = new byte[0];

         try {
            while(this.running.get()) {
               byte[] frame = (byte[])this.latestFrame.get();
               if (frame.length == 0) {
                  Thread.sleep(100L);
               } else if (frame == lastSent) {
                  Thread.sleep(Math.max(10L, 1000L / (long)Math.max(10, this.state.deliveryFps())));
               } else {
                  out.write(boundaryPrefix);
                  out.write(Integer.toString(frame.length).getBytes(StandardCharsets.UTF_8));
                  out.write(boundaryMiddle);
                  out.write(frame);
                  out.write(boundaryEnd);
                  out.flush();
                  lastSent = frame;
                  Thread.sleep(Math.max(10L, 1000L / (long)Math.max(10, this.state.deliveryFps())));
               }
            }
         } catch (IOException var12) {
            PocLogger.get().debug("Stream client disconnected");
         } catch (InterruptedException var13) {
            Thread.currentThread().interrupt();
         } finally {
            exchange.close();
         }

      }
   }

   private String settingsJson() {
      JsonObject json = new JsonObject();
      json.addProperty("fps", this.state.targetFps);
      json.addProperty("quality", this.state.targetQuality);
      json.addProperty("scale", this.state.targetScale);
      json.addProperty("adaptive", this.state.adaptive);
      json.addProperty("effectiveFps", this.state.effectiveFps);
      json.addProperty("effectiveQuality", this.state.effectiveQuality);
      json.addProperty("effectiveScale", this.state.effectiveScale);
      json.addProperty("lastCaptureMs", this.state.lastCaptureMs);
      json.addProperty("lastFrameKb", Math.max(0L, this.state.lastFrameBytes / 1024L));
      json.addProperty("maxFps", 60);
      return GSON.toJson(json);
   }

   private static JsonObject readJsonBody(HttpExchange exchange) throws IOException {
      InputStream input = exchange.getRequestBody();

      JsonObject var6;
      label43: {
         try {
            String raw = Compat.readUtf8(input);
            if (Compat.isBlank(raw)) {
               var6 = new JsonObject();
               break label43;
            }

            var6 = Compat.parseJsonObject(raw);
         } catch (Throwable var5) {
            if (input != null) {
               try {
                  input.close();
               } catch (Throwable var4) {
                  var5.addSuppressed(var4);
               }
            }

            throw var5;
         }

         if (input != null) {
            input.close();
         }

         return var6;
      }

      if (input != null) {
         input.close();
      }

      return var6;
   }

   private static void writeJson(HttpExchange exchange, int code, String json) throws IOException {
      writeText(exchange, code, "application/json; charset=utf-8", json);
   }

   private static void writeText(HttpExchange exchange, int code, String contentType, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", contentType);
      exchange.sendResponseHeaders(code, (long)bytes.length);
      OutputStream out = exchange.getResponseBody();

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

   }
}
