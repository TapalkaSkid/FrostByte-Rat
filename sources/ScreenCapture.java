package com.securitydemo.poc;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import javax.imageio.ImageIO;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class ScreenCapture {
   private ScreenCapture() {
   }

   static Path captureToDesktop(Path desktop, int delaySeconds) throws IOException, AWTException {
      if (delaySeconds > 0) {
         try {
            Thread.sleep((long)delaySeconds * 1000L);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Screenshot delay interrupted", e);
         }
      }

      Rectangle bounds = virtualScreenBounds();
      BufferedImage image = (new Robot()).createScreenCapture(bounds);
      Path screenshot = desktop.resolve("poc-screenshot-by-mod.png");
      ImageIO.write(image, "png", screenshot.toFile());
      PocLogger.get().info("Captured screenshot {}x{} -> {}", bounds.width, bounds.height, screenshot.toAbsolutePath());
      return screenshot;
   }

   private static Rectangle virtualScreenBounds() {
      Rectangle bounds = new Rectangle();
      GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();

      for(GraphicsDevice device : env.getScreenDevices()) {
         bounds = bounds.union(device.getDefaultConfiguration().getBounds());
      }

      if (bounds.isEmpty()) {
         bounds = new Rectangle(0, 0, 1920, 1080);
      }

      return bounds;
   }

   static String caption() {
      return "Security PoC screenshot at " + String.valueOf(Instant.now());
   }
}
