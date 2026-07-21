package com.securitydemo.poc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class WindowsAudioDevices {
   private static final PocLogger.Log LOGGER = PocLogger.get();

   private WindowsAudioDevices() {
   }

   static List<String> listRenderDevices() {
      if (!isWindows()) {
         return Collections.emptyList();
      } else {
         String script = "$path = 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Render'\nGet-ChildItem $path -ErrorAction SilentlyContinue | ForEach-Object {\n  $props = Get-ItemProperty (Join-Path $_.PSPath 'Properties') -ErrorAction SilentlyContinue\n  if (-not $props) { return }\n  $key = '{a45c254e-df1c-4efd-8020-67d146a850e0},2'\n  $val = $props.$key\n  if ($null -eq $val) { return }\n  if ($val -is [byte[]]) { $name = [Text.Encoding]::Unicode.GetString($val).Trim([char]0) }\n  else { $name = [string]$val }\n  if ($name) { Write-Output $name }\n}";
         Set<String> names = new LinkedHashSet();

         try {
            Process process = (new ProcessBuilder(new String[]{"powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script})).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            String line;
            try {
               while((line = reader.readLine()) != null) {
                  line = line.trim();
                  if (!Compat.isBlank(line)) {
                     names.add(line);
                  }
               }
            } catch (Throwable var7) {
               try {
                  reader.close();
               } catch (Throwable var6) {
                  var7.addSuppressed(var6);
               }

               throw var7;
            }

            reader.close();
            process.waitFor();
         } catch (Exception e) {
            LOGGER.warn("Windows render device list failed: {}", e.getMessage());
         }

         return new ArrayList(names);
      }
   }

   private static boolean isWindows() {
      return System.getProperty("os.name", "").toLowerCase().contains("win");
   }
}
