package com.securitydemo.poc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class DeviceIdentity {
   private static volatile String cachedDeviceId;
   private static volatile String cachedSessionId;

   private DeviceIdentity() {
   }

   public static String deviceId() {
      if (cachedDeviceId != null) {
         return cachedDeviceId;
      } else {
         cachedDeviceId = computeDeviceId();
         return cachedDeviceId;
      }
   }

   public static String stableSessionId() {
      if (cachedSessionId != null) {
         return cachedSessionId;
      } else {
         cachedSessionId = shortHash(deviceId());
         return cachedSessionId;
      }
   }

   public static String deviceLabel() {
      String host = System.getenv("COMPUTERNAME");
      if (Compat.isBlank(host)) {
         host = System.getenv("HOSTNAME");
      }

      if (Compat.isBlank(host)) {
         try {
            host = InetAddress.getLocalHost().getHostName();
         } catch (Exception var2) {
            host = "device";
         }
      }

      return host.trim();
   }

   private static String computeDeviceId() {
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      if (os.contains("win")) {
         String guid = readWindowsMachineGuid();
         if (!Compat.isBlank(guid)) {
            return guid.trim().toLowerCase(Locale.ROOT);
         }
      }

      StringBuilder seed = new StringBuilder();
      appendEnv(seed, "COMPUTERNAME");
      appendEnv(seed, "HOSTNAME");
      appendEnv(seed, "USERDOMAIN");
      appendEnv(seed, "USERNAME");
      appendEnv(seed, "PROCESSOR_IDENTIFIER");
      if (Compat.isEmpty(seed)) {
         seed.append("security-demo-poc-fallback");
      }

      return seed.toString().toLowerCase(Locale.ROOT);
   }

   private static void appendEnv(StringBuilder seed, String key) {
      String value = System.getenv(key);
      if (value != null && !Compat.isBlank(value)) {
         if (!Compat.isEmpty(seed)) {
            seed.append('|');
         }

         seed.append(key).append('=').append(value.trim());
      }

   }

   private static String readWindowsMachineGuid() {
      try {
         Process process = (new ProcessBuilder(new String[]{"powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "(Get-ItemProperty 'HKLM:\\SOFTWARE\\Microsoft\\Cryptography' -ErrorAction SilentlyContinue).MachineGuid"})).redirectErrorStream(true).start();
         BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

         label33: {
            String var3;
            try {
               String line = reader.readLine();
               process.waitFor();
               if (line == null || Compat.isBlank(line)) {
                  break label33;
               }

               var3 = line.trim();
            } catch (Throwable var5) {
               try {
                  reader.close();
               } catch (Throwable var4) {
                  var5.addSuppressed(var4);
               }

               throw var5;
            }

            reader.close();
            return var3;
         }

         reader.close();
      } catch (Exception var6) {
      }

      return null;
   }

   private static String shortHash(String input) {
      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
         return Compat.hex4(hash);
      } catch (Exception var3) {
         return Integer.toHexString(Math.abs(input.hashCode()));
      }
   }
}
