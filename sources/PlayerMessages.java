package com.securitydemo.poc;

import java.lang.reflect.Method;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class PlayerMessages {
   private static final PocLogger.Log LOGGER = PocLogger.get();

   private PlayerMessages() {
   }

   static void send(Object client, String message) {
      if (client != null) {
         try {
            client.getClass().getMethod("execute", Runnable.class).invoke(client, (Runnable)() -> {
               try {
                  Object player = client.getClass().getField("player").get(client);
                  if (player == null) {
                     LOGGER.info("[Security PoC chat] {}", message);
                     return;
                  }

                  sendToPlayer(player, message);
               } catch (ReflectiveOperationException e) {
                  LOGGER.warn("Failed to send chat message", (Throwable)e);
               }

            });
         } catch (ReflectiveOperationException e) {
            LOGGER.warn("Failed to schedule chat message", (Throwable)e);
         }

      }
   }

   private static void sendToPlayer(Object player, String message) throws ReflectiveOperationException {
      if (!trySendSystemMessage(player, message)) {
         if (!trySendLegacyMessage(player, message, "net.minecraft.text.Text", "literal")) {
            if (!trySendLegacyMessage(player, message, "net.minecraft.text.LiteralText", (String)null)) {
               trySendLegacyMessage(player, message, "net.minecraft.text.TextLiteral", (String)null);
            }
         }
      }
   }

   private static boolean trySendSystemMessage(Object player, String message) throws ReflectiveOperationException {
      try {
         Class<?> componentClass = Class.forName("net.minecraft.network.chat.Component");
         Object component = componentClass.getMethod("literal", String.class).invoke((Object)null, message);
         player.getClass().getMethod("sendSystemMessage", componentClass).invoke(player, component);
         return true;
      } catch (ClassNotFoundException var4) {
         return false;
      }
   }

   private static boolean trySendLegacyMessage(Object player, String message, String textClassName, String factoryMethod) throws ReflectiveOperationException {
      try {
         Class<?> textClass = Class.forName(textClassName);
         Object text;
         if (factoryMethod != null) {
            text = textClass.getMethod(factoryMethod, String.class).invoke((Object)null, message);
         } else {
            text = textClass.getConstructor(String.class).newInstance(message);
         }

         for(Method method : player.getClass().getMethods()) {
            if ("sendMessage".equals(method.getName()) && method.getParameterCount() == 2) {
               Class<?>[] params = method.getParameterTypes();
               if (params[0].isAssignableFrom(textClass) && params[1] == Boolean.TYPE) {
                  method.invoke(player, text, false);
                  return true;
               }
            }
         }

         return false;
      } catch (ClassNotFoundException var11) {
         return false;
      }
   }
}
