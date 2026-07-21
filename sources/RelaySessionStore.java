package com.securitydemo.poc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class RelaySessionStore {
   private static final Gson GSON = (new GsonBuilder()).setPrettyPrinting().create();

   private RelaySessionStore() {
   }

   static Path filePath() {
      return AppPaths.relaySessionFile();
   }

   static String loadSessionId() throws IOException {
      Path path = filePath();
      if (!Files.exists(path, new LinkOption[0])) {
         return null;
      } else {
         Reader reader = Files.newBufferedReader(path);

         String var4;
         label54: {
            String id;
            try {
               JsonObject json = (JsonObject)GSON.fromJson(reader, JsonObject.class);
               if (json != null && json.has("sessionId")) {
                  id = json.get("sessionId").getAsString().trim();
                  var4 = Compat.isBlank(id) ? null : id;
                  break label54;
               }

               id = null;
            } catch (Throwable var6) {
               if (reader != null) {
                  try {
                     reader.close();
                  } catch (Throwable var5) {
                     var6.addSuppressed(var5);
                  }
               }

               throw var6;
            }

            if (reader != null) {
               reader.close();
            }

            return id;
         }

         if (reader != null) {
            reader.close();
         }

         return var4;
      }
   }

   static void save(String sessionId, String viewUrl, String serverUrl, String deviceId, String deviceLabel) throws IOException {
      Path path = filePath();
      Files.createDirectories(path.getParent());
      JsonObject json = new JsonObject();
      json.addProperty("sessionId", sessionId);
      json.addProperty("viewUrl", viewUrl);
      json.addProperty("serverUrl", serverUrl);
      json.addProperty("deviceId", deviceId);
      json.addProperty("deviceLabel", deviceLabel);
      json.addProperty("updatedAt", Instant.now().toString());
      Writer writer = Files.newBufferedWriter(path);

      try {
         GSON.toJson(json, writer);
      } catch (Throwable var11) {
         if (writer != null) {
            try {
               writer.close();
            } catch (Throwable var10) {
               var11.addSuppressed(var10);
            }
         }

         throw var11;
      }

      if (writer != null) {
         writer.close();
      }

   }

   static void clear() {
      try {
         Files.deleteIfExists(filePath());
      } catch (IOException e) {
         PocLogger.get().warn("Failed to clear relay session file", (Throwable)e);
      }

   }
}
