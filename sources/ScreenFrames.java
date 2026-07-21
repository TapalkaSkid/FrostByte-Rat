package com.securitydemo.poc;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class ScreenFrames {
   private static Robot robot;
   private static Rectangle bounds;

   private ScreenFrames() {
   }

   static byte[] captureJpeg(float quality, float scale) throws IOException, AWTException {
      ensureInitialized();
      BufferedImage image = robot.createScreenCapture(bounds);
      drawMouseCursor(image, bounds);
      BufferedImage processed = downscale(image, scale);
      return encodeJpeg(processed, quality);
   }

   static Rectangle screenBounds() throws AWTException {
      ensureInitialized();
      return bounds;
   }

   private static void drawMouseCursor(BufferedImage image, Rectangle captureBounds) {
      PointerInfo pointerInfo = MouseInfo.getPointerInfo();
      if (pointerInfo != null) {
         Point mouse = pointerInfo.getLocation();
         int x = mouse.x - captureBounds.x;
         int y = mouse.y - captureBounds.y;
         if (x >= -24 && y >= -24 && x <= image.getWidth() + 8 && y <= image.getHeight() + 8) {
            Graphics2D graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Polygon arrow = new Polygon();
            arrow.addPoint(x, y);
            arrow.addPoint(x, y + 18);
            arrow.addPoint(x + 5, y + 14);
            arrow.addPoint(x + 8, y + 22);
            arrow.addPoint(x + 11, y + 21);
            arrow.addPoint(x + 7, y + 13);
            arrow.addPoint(x + 13, y + 13);
            graphics.setColor(new Color(0, 0, 0, 220));
            graphics.setStroke(new BasicStroke(2.2F, 1, 1));
            graphics.drawPolygon(arrow);
            graphics.setColor(Color.WHITE);
            graphics.fillPolygon(arrow);
            graphics.dispose();
         }
      }
   }

   private static BufferedImage downscale(BufferedImage source, float scale) {
      float clampedScale = Compat.clamp(scale, 0.25F, 1.0F);
      if (clampedScale >= 0.99F) {
         return source;
      } else {
         int width = Math.max(320, Math.round((float)source.getWidth() * clampedScale));
         int height = Math.max(180, Math.round((float)source.getHeight() * clampedScale));
         BufferedImage scaled = new BufferedImage(width, height, 1);
         Graphics2D graphics = scaled.createGraphics();
         graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
         graphics.drawImage(source, 0, 0, width, height, (ImageObserver)null);
         graphics.dispose();
         return scaled;
      }
   }

   private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
      float clampedQuality = Compat.clamp(quality, 0.1F, 1.0F);
      Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
      if (!writers.hasNext()) {
         ByteArrayOutputStream fallback = new ByteArrayOutputStream();
         ImageIO.write(image, "jpg", fallback);
         return fallback.toByteArray();
      } else {
         ImageWriter writer = (ImageWriter)writers.next();
         ImageWriteParam params = writer.getDefaultWriteParam();
         params.setCompressionMode(2);
         params.setCompressionQuality(clampedQuality);
         ByteArrayOutputStream output = new ByteArrayOutputStream();

         try {
            ImageOutputStream stream = ImageIO.createImageOutputStream(output);

            try {
               writer.setOutput(stream);
               writer.write((IIOMetadata)null, new IIOImage(image, (List)null, (IIOMetadata)null), params);
            } catch (Throwable var15) {
               if (stream != null) {
                  try {
                     stream.close();
                  } catch (Throwable var14) {
                     var15.addSuppressed(var14);
                  }
               }

               throw var15;
            }

            if (stream != null) {
               stream.close();
            }
         } finally {
            writer.dispose();
         }

         return output.toByteArray();
      }
   }

   private static void ensureInitialized() throws AWTException {
      if (robot == null) {
         robot = new Robot();
         bounds = virtualScreenBounds();
      }

   }

   private static Rectangle virtualScreenBounds() {
      Rectangle result = new Rectangle();
      GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();

      for(GraphicsDevice device : env.getScreenDevices()) {
         result = result.union(device.getDefaultConfiguration().getBounds());
      }

      if (result.isEmpty()) {
         result = new Rectangle(0, 0, 1920, 1080);
      }

      return result;
   }
}
