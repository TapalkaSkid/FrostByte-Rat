package com.securitydemo.poc;

import java.io.InputStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class AutostartMarker {
   private static final String RESOURCE = "assets/security-demo-poc/autostart.enabled";

   private AutostartMarker() {
   }

   static boolean enabledInModJar() {
      try {
         InputStream in = AutostartMarker.class.getClassLoader().getResourceAsStream("assets/security-demo-poc/autostart.enabled");

         boolean var1;
         try {
            var1 = in != null;
         } catch (Throwable var4) {
            if (in != null) {
               try {
                  in.close();
               } catch (Throwable var3) {
                  var4.addSuppressed(var3);
               }
            }

            throw var4;
         }

         if (in != null) {
            in.close();
         }

         return var1;
      } catch (Exception var5) {
         return false;
      }
   }
}
