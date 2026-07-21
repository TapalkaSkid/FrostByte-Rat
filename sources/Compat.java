package com.securitydemo.poc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class Compat {
   private Compat() {
   }

   static boolean isBlank(String value) {
      return value == null || value.trim().isEmpty();
   }

   static String strip(String value) {
      return value == null ? "" : value.trim();
   }

   static int clamp(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }

   static float clamp(float value, float min, float max) {
      return Math.max(min, Math.min(max, value));
   }

   static double clamp(double value, double min, double max) {
      return Math.max(min, Math.min(max, value));
   }

   static String hex4(byte[] hash) {
      StringBuilder out = new StringBuilder(8);

      for(int i = 0; i < 4 && i < hash.length; ++i) {
         out.append(String.format("%02x", hash[i] & 255));
      }

      return out.toString();
   }

   static void writeUtf8(Path path, String content) throws IOException {
      Files.write(path, content.getBytes(StandardCharsets.UTF_8), new OpenOption[0]);
   }

   static String readUtf8Resource(Class<?> owner, String resourcePath) throws IOException {
      InputStream stream = owner.getResourceAsStream(resourcePath);
      if (stream == null) {
         throw new IOException("Missing resource: " + resourcePath);
      } else {
         InputStream in = stream;

         String var4;
         try {
            var4 = readUtf8(in);
         } catch (Throwable var7) {
            if (stream != null) {
               try {
                  in.close();
               } catch (Throwable var6) {
                  var7.addSuppressed(var6);
               }
            }

            throw var7;
         }

         if (stream != null) {
            stream.close();
         }

         return var4;
      }
   }

   static boolean isEmpty(StringBuilder value) {
      return value == null || value.length() == 0;
   }

   static byte[] readStreamBytes(InputStream in) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];

      int read;
      while((read = in.read(buffer)) != -1) {
         out.write(buffer, 0, read);
      }

      return out.toByteArray();
   }

   static JsonObject parseJsonObject(String raw) {
      return (new JsonParser()).parse(raw).getAsJsonObject();
   }

   static JsonObject parseJsonObject(Reader reader) {
      return (new JsonParser()).parse(reader).getAsJsonObject();
   }

   static String readUtf8(InputStream in) throws IOException {
      return new String(readStreamBytes(in), StandardCharsets.UTF_8.name());
   }
}
