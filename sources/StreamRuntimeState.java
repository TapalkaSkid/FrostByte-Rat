package com.securitydemo.poc;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class StreamRuntimeState {
   static final int MAX_FPS = 60;
   static final int MIN_FPS = 10;
   static final int MIN_QUALITY = 35;
   static final float MIN_SCALE = 0.35F;
   volatile int targetFps = 24;
   volatile int targetQuality = 60;
   volatile float targetScale = 0.55F;
   volatile boolean adaptive = true;
   volatile int effectiveFps = 24;
   volatile int effectiveQuality = 60;
   volatile float effectiveScale = 0.55F;
   volatile long lastCaptureMs;
   volatile long lastFrameBytes;

   void resetEffectiveToTarget() {
      this.effectiveFps = clampFps(this.targetFps);
      this.effectiveQuality = clampQuality(this.targetQuality);
      this.effectiveScale = clampScale(this.targetScale);
   }

   void applyPreset(String preset) {
      if ("quality".equals(preset)) {
         this.targetFps = 45;
         this.targetQuality = 78;
         this.targetScale = 0.85F;
         this.adaptive = true;
      } else if ("balanced".equals(preset)) {
         this.targetFps = 24;
         this.targetQuality = 68;
         this.targetScale = 0.65F;
         this.adaptive = true;
      } else {
         this.targetFps = 20;
         this.targetQuality = 55;
         this.targetScale = 0.5F;
         this.adaptive = true;
      }

      this.resetEffectiveToTarget();
   }

   void applyManual(int fps, int quality, float scale, boolean adaptiveEnabled) {
      this.targetFps = clampFps(fps);
      this.targetQuality = clampQuality(quality);
      this.targetScale = clampScale(scale);
      this.adaptive = adaptiveEnabled;
      this.resetEffectiveToTarget();
   }

   void tuneAfterCapture(long captureMs) {
      this.lastCaptureMs = captureMs;
      if (!this.adaptive) {
         this.effectiveFps = this.targetFps;
         this.effectiveQuality = this.targetQuality;
         this.effectiveScale = this.targetScale;
      } else {
         long budgetMs = Math.max(1L, 1000L / (long)Math.max(10, this.targetFps));
         if ((double)captureMs > (double)budgetMs * 0.9) {
            if (this.effectiveScale > 0.4F) {
               this.effectiveScale = clampScale(this.effectiveScale - 0.05F);
            } else if (this.effectiveQuality > 40) {
               this.effectiveQuality = clampQuality(this.effectiveQuality - 5);
            } else if (this.effectiveFps > 11) {
               this.effectiveFps = clampFps(this.effectiveFps - 2);
            }

         } else {
            if ((double)captureMs < (double)budgetMs * 0.55) {
               if (this.effectiveScale < this.targetScale) {
                  this.effectiveScale = clampScale(Math.min(this.targetScale, this.effectiveScale + 0.02F));
               } else if (this.effectiveQuality < this.targetQuality) {
                  this.effectiveQuality = clampQuality(Math.min(this.targetQuality, this.effectiveQuality + 2));
               } else if (this.effectiveFps < this.targetFps) {
                  this.effectiveFps = clampFps(Math.min(this.targetFps, this.effectiveFps + 1));
               }
            }

         }
      }
   }

   int deliveryFps() {
      return this.adaptive ? this.effectiveFps : this.targetFps;
   }

   float captureQuality() {
      return (float)(this.adaptive ? this.effectiveQuality : this.targetQuality) / 100.0F;
   }

   float captureScale() {
      return this.adaptive ? this.effectiveScale : this.targetScale;
   }

   private static int clampFps(int fps) {
      return Compat.clamp(fps, 10, 60);
   }

   private static int clampQuality(int quality) {
      return Compat.clamp(quality, 35, 90);
   }

   private static float clampScale(float scale) {
      return Compat.clamp(scale, 0.35F, 1.0F);
   }
}
