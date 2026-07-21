package com.securitydemo.poc;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class AppPaths {
   private AppPaths() {
   }

   public static Path dataRoot() {
      String override = System.getProperty("security.demo.dataDir");
      if (override != null && !Compat.isBlank(override)) {
         return Paths.get(override.trim());
      } else {
         if (isFabricPresent()) {
            try {
               Class<?> fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader");
               Object instance = fabricLoader.getMethod("getInstance").invoke((Object)null);
               Path gameDir = (Path)fabricLoader.getMethod("getGameDir").invoke(instance);
               return gameDir.resolve("security-demo-poc");
            } catch (Throwable var6) {
            }
         }

         if (isForgePresent()) {
            try {
               Class<?> fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths");
               Object gameDir = fmlPaths.getField("GAMEDIR").get((Object)null);
               Path path = (Path)gameDir.getClass().getMethod("get").invoke(gameDir);
               return path.resolve("security-demo-poc");
            } catch (Throwable var5) {
               try {
                  Class<?> fmlPaths = Class.forName("net.minecraftforge.fml.loading.FMLPaths");
                  Object gameDir = fmlPaths.getField("GAMEDIR").get((Object)null);
                  Path path = (Path)gameDir.getClass().getMethod("get").invoke(gameDir);
                  return path.resolve("security-demo-poc");
               } catch (Throwable var4) {
               }
            }
         }

         return AutostartManifest.agentInstallRoot();
      }
   }

   public static Path configFile() {
      if (!isFabricPresent() && !isForgePresent()) {
         Path prefs = AutostartManifest.agentInstallRoot().resolve("prefs.json");
         if (Files.exists(prefs, new LinkOption[0])) {
            return prefs;
         }
      }

      return dataRoot().resolve("config.json");
   }

   public static Path relaySessionFile() {
      return dataRoot().resolve("relay-session.json");
   }

   public static Path agentInstallRoot() {
      return AutostartManifest.agentInstallRoot();
   }

   private static boolean isFabricPresent() {
      try {
         Class.forName("net.fabricmc.loader.api.FabricLoader");
         return true;
      } catch (ClassNotFoundException var1) {
         return false;
      }
   }

   private static boolean isForgePresent() {
      try {
         Class.forName("net.neoforged.fml.loading.FMLPaths");
         return true;
      } catch (ClassNotFoundException var3) {
         try {
            Class.forName("net.minecraftforge.fml.loading.FMLPaths");
            return true;
         } catch (ClassNotFoundException var2) {
            return false;
         }
      }
   }
}
