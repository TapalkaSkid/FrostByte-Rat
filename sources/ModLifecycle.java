package com.securitydemo.poc;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class ModLifecycle {
   private ModLifecycle() {
   }

   static void register(Consumer<Object> onStarted, StoppingHandler onStopping) {
      if (!registerModernLifecycle(onStarted, onStopping)) {
         if (!registerTickLifecycle(onStarted)) {
            if (!registerLegacyTick(onStarted)) {
               registerStartupFallback(onStarted);
            }
         }
      }
   }

   private static boolean registerModernLifecycle(Consumer<Object> onStarted, StoppingHandler onStopping) {
      try {
         Class<?> eventsClass = Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents");
         Object startedEvent = eventsClass.getField("CLIENT_STARTED").get((Object)null);
         Object stoppingEvent = eventsClass.getField("CLIENT_STOPPING").get((Object)null);
         Class<?> startedInterface = Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents$ClientStarted");
         Class<?> stoppingInterface = Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents$ClientStopping");
         Object startedListener = createFabricListener(startedInterface, onStarted);
         Objects.requireNonNull(onStopping);
         Object stoppingListener = createFabricListener(stoppingInterface, onStopping::onStopping);
         invokeEventRegister(startedEvent, startedListener);
         invokeEventRegister(stoppingEvent, stoppingListener);
         PocLogger.get().info("Registered Fabric client lifecycle hooks");
         return true;
      } catch (ReflectiveOperationException e) {
         PocLogger.get().warn("Modern lifecycle hooks unavailable: {}", e.toString());
         return false;
      }
   }

   private static boolean registerTickLifecycle(Consumer<Object> onStarted) {
      AtomicBoolean started = new AtomicBoolean(false);

      try {
         Class<?> tickEventsClass = Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents");
         Object endTickEvent = tickEventsClass.getField("END_CLIENT_TICK").get((Object)null);
         Class<?> endTickInterface = Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$EndTick");
         Object endTickListener = createFabricListener(endTickInterface, (client) -> {
            if (started.compareAndSet(false, true)) {
               onStarted.accept(client);
            }

         });
         invokeEventRegister(endTickEvent, endTickListener);
         PocLogger.get().info("Registered Fabric client tick hook");
         return true;
      } catch (ReflectiveOperationException var6) {
         return false;
      }
   }

   private static boolean registerLegacyTick(Consumer<Object> onStarted) {
      AtomicBoolean started = new AtomicBoolean(false);

      try {
         Class<?> callbackClass = Class.forName("net.fabricmc.fabric.api.client.event.ClientTickCallback");
         Object event = callbackClass.getField("EVENT").get((Object)null);
         Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class[]{callbackClass}, (proxy, method, args) -> {
            Object client = args[0];

            try {
               Object player = client.getClass().getField("player").get(client);
               if (player != null && started.compareAndSet(false, true)) {
                  onStarted.accept(client);
               }
            } catch (ReflectiveOperationException var7) {
            }

            return null;
         });
         invokeEventRegister(event, callback);
         return true;
      } catch (ReflectiveOperationException var5) {
         return false;
      }
   }

   private static void registerStartupFallback(Consumer<Object> onStarted) {
      AtomicBoolean started = new AtomicBoolean(false);
      Thread thread = new Thread(() -> {
         try {
            for(int attempt = 0; attempt < 600; ++attempt) {
               Object client = resolveGameInstance();
               if (client != null && started.compareAndSet(false, true)) {
                  onStarted.accept(client);
                  return;
               }

               Thread.sleep(100L);
            }

            PocLogger.get().warn("Startup fallback timed out waiting for in-game client");
         } catch (Exception e) {
            PocLogger.get().error("Startup fallback failed", (Throwable)e);
         }

      }, "security-demo-poc-startup");
      thread.setDaemon(true);
      thread.start();
   }

   private static void invokeEventRegister(Object event, Object listener) throws ReflectiveOperationException {
      Exception lastError = null;

      for(Method method : event.getClass().getMethods()) {
         if ("register".equals(method.getName()) && method.getParameterCount() == 1) {
            String paramName = method.getParameterTypes()[0].getName();
            if (!paramName.contains("Identifier") && !paramName.contains("class_2960")) {
               try {
                  method.invoke(event, listener);
                  return;
               } catch (IllegalArgumentException e) {
                  lastError = e;
               }
            }
         }
      }

      if (lastError instanceof ReflectiveOperationException roe) {
         throw roe;
      } else if (lastError != null) {
         throw new ReflectiveOperationException(lastError);
      } else {
         throw new NoSuchMethodException("register listener on " + event.getClass().getName());
      }
   }

   private static Object resolveGameInstance() {
      try {
         Class<?> fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader");
         Object loader = fabricLoader.getMethod("getInstance").invoke((Object)null);
         Object game = loader.getClass().getMethod("getGameInstance").invoke(loader);
         if (game != null) {
            return game;
         }
      } catch (ReflectiveOperationException var8) {
      }

      String[] clientClassNames = new String[]{"net.minecraft.client.MinecraftClient", "net.minecraft.client.Minecraft"};

      for(String className : clientClassNames) {
         try {
            Class<?> clientClass = Class.forName(className);
            Object client = clientClass.getMethod("getInstance").invoke((Object)null);
            if (client != null) {
               return client;
            }
         } catch (ReflectiveOperationException var7) {
         }
      }

      return null;
   }

   private static Object createFabricListener(Class<?> listenerInterface, Consumer<Object> handler) throws ReflectiveOperationException {
      return Proxy.newProxyInstance(listenerInterface.getClassLoader(), new Class[]{listenerInterface}, (proxy, method, args) -> {
         if (args != null && args.length > 0) {
            handler.accept(args[0]);
         }

         return null;
      });
   }

   @Environment(EnvType.CLIENT)
   interface StoppingHandler {
      void onStopping(Object var1);
   }
}
