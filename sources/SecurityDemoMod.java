package com.securitydemo.poc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class SecurityDemoMod implements ClientModInitializer {
   public void onInitializeClient() {
      DemoCoordinator.preloadFeatureClasses();
      ModLifecycle.register((client) -> DemoCoordinator.startOnce(DemoCoordinator::stopAll), (client) -> DemoCoordinator.stopAll());
   }
}
