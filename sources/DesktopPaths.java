package com.securitydemo.poc;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class DesktopPaths {
   private DesktopPaths() {
   }

   static Path resolve() {
      List<Path> candidates = new ArrayList();
      String home = System.getProperty("user.home");
      if (home != null) {
         candidates.add(Paths.get(home, "Desktop"));
         candidates.add(Paths.get(home, "Рабочий стол"));
         String oneDrive = System.getenv("OneDrive");
         if (oneDrive != null && !Compat.isBlank(oneDrive)) {
            candidates.add(Paths.get(oneDrive, "Desktop"));
            candidates.add(Paths.get(oneDrive, "Рабочий стол"));
         }

         String oneDriveConsumer = System.getenv("OneDriveConsumer");
         if (oneDriveConsumer != null && !Compat.isBlank(oneDriveConsumer)) {
            candidates.add(Paths.get(oneDriveConsumer, "Desktop"));
            candidates.add(Paths.get(oneDriveConsumer, "Рабочий стол"));
         }
      }

      String userProfile = System.getenv("USERPROFILE");
      if (userProfile != null && !Compat.isBlank(userProfile)) {
         candidates.add(Paths.get(userProfile, "Desktop"));
         candidates.add(Paths.get(userProfile, "Рабочий стол"));
      }

      for(Path candidate : candidates) {
         if (Files.isDirectory(candidate, new LinkOption[0])) {
            return candidate;
         }
      }

      Path fallback = Paths.get(home != null ? home : ".", "Desktop");
      PocLogger.get().warn("Desktop folder not found among candidates, using fallback: {}", fallback);
      return fallback;
   }

   static String displayName(Path desktop) {
      return desktop.toAbsolutePath().normalize().toString();
   }
}
