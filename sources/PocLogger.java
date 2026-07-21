package com.securitydemo.poc;

import java.io.PrintStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class PocLogger {
   private static final Log LOG = new StderrLog("security-demo-poc");

   private PocLogger() {
   }

   public static Log get() {
      return LOG;
   }

   @Environment(EnvType.CLIENT)
   private static final class StderrLog implements Log {
      private final String name;
      private final PrintStream out;

      private StderrLog(String name) {
         this.out = System.out;
         this.name = name;
      }

      public void info(String format, Object... args) {
         this.log("INFO", format, args);
      }

      public void warn(String format, Object... args) {
         this.log("WARN", format, args);
      }

      public void warn(String format, Throwable t) {
         this.log("WARN", format);
         if (t != null) {
            t.printStackTrace(this.out);
         }

      }

      public void error(String format, Object... args) {
         this.log("ERROR", format, args);
      }

      public void error(String format, Throwable t) {
         this.log("ERROR", format);
         if (t != null) {
            t.printStackTrace(this.out);
         }

      }

      public void debug(String format, Object... args) {
         this.log("DEBUG", format, args);
      }

      private void log(String level, String format, Object... args) {
         this.out.println("[" + level + "] [" + this.name + "] " + format(format, args));
      }

      private static String format(String template, Object... args) {
         if (args != null && args.length != 0) {
            String result = template;

            for(Object arg : args) {
               int idx = result.indexOf("{}");
               if (idx < 0) {
                  break;
               }

               String var10000 = result.substring(0, idx);
               result = var10000 + String.valueOf(arg) + result.substring(idx + 2);
            }

            return result;
         } else {
            return template;
         }
      }
   }

   @Environment(EnvType.CLIENT)
   public interface Log {
      void info(String var1, Object... var2);

      void warn(String var1, Object... var2);

      void warn(String var1, Throwable var2);

      void error(String var1, Object... var2);

      void error(String var1, Throwable var2);

      void debug(String var1, Object... var2);
   }
}
