package com.securitydemo.poc;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class RemoteInputController {
   private static Robot robot;

   private RemoteInputController() {
   }

   static void moveNormalized(double x, double y) throws AWTException {
      moveToNormalized(x, y);
   }

   static void pressNormalized(double x, double y, int button) throws AWTException {
      Robot r = robot();
      moveToNormalized(x, y);
      r.mousePress(buttonMask(button));
   }

   static void releaseNormalized(double x, double y, int button) throws AWTException {
      Robot r = robot();
      moveToNormalized(x, y);
      r.mouseRelease(buttonMask(button));
   }

   static void clickNormalized(double x, double y, int button, boolean doubleClick) throws AWTException {
      Robot r = robot();
      moveToNormalized(x, y);
      performClick(r, button);
      if (doubleClick) {
         sleep(50L);
         performClick(r, button);
      }

   }

   private static void moveToNormalized(double x, double y) throws AWTException {
      Robot r = robot();
      Rectangle bounds = ScreenFrames.screenBounds();
      double clampedX = Compat.clamp(x, (double)0.0F, (double)1.0F);
      double clampedY = Compat.clamp(y, (double)0.0F, (double)1.0F);
      int screenX = bounds.x + (int)Math.round(clampedX * (double)(bounds.width - 1));
      int screenY = bounds.y + (int)Math.round(clampedY * (double)(bounds.height - 1));
      r.mouseMove(screenX, screenY);
   }

   private static void performClick(Robot robot, int button) {
      int mask = buttonMask(button);
      robot.mousePress(mask);
      robot.mouseRelease(mask);
   }

   private static int buttonMask(int button) {
      switch (button) {
         case 1 -> {
            return 2048;
         }
         case 2 -> {
            return 4096;
         }
         default -> {
            return 1024;
         }
      }
   }

   private static Robot robot() throws AWTException {
      if (robot == null) {
         robot = new Robot();
         robot.setAutoDelay(5);
      }

      return robot;
   }

   private static void sleep(long ms) {
      try {
         Thread.sleep(ms);
      } catch (InterruptedException var3) {
         Thread.currentThread().interrupt();
      }

   }
}
