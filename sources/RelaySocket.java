package com.securitydemo.poc;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

@Environment(EnvType.CLIENT)
final class RelaySocket {
   private final WebSocketClient client;

   private RelaySocket(WebSocketClient client) {
      this.client = client;
   }

   static RelaySocket connect(URI uri, final Listener listener, int timeoutMs) throws IOException {
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<Throwable> failure = new AtomicReference();
      WebSocketClient client = new WebSocketClient(uri) {
         public void onOpen(ServerHandshake handshakedata) {
            latch.countDown();
            listener.onOpen(new RelaySocket(this));
         }

         public void onMessage(String message) {
            listener.onText(message);
         }

         public void onMessage(ByteBuffer bytes) {
            byte[] data = new byte[bytes.remaining()];
            bytes.get(data);
            listener.onBinary(data);
         }

         public void onClose(int code, String reason, boolean remote) {
            listener.onClose(code, reason);
         }

         public void onError(Exception ex) {
            if (latch.getCount() > 0L) {
               failure.set(ex);
               latch.countDown();
            }

            listener.onError(ex);
         }
      };
      client.setConnectionLostTimeout(Math.max(10, timeoutMs / 1000));
      client.connect();

      try {
         if (!latch.await((long)timeoutMs, TimeUnit.MILLISECONDS)) {
            client.close();
            throw new IOException("WebSocket connect timeout");
         }
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new IOException("WebSocket connect interrupted", e);
      }

      Throwable error = (Throwable)failure.get();
      if (error != null) {
         throw new IOException("WebSocket connect failed: " + error.getMessage(), error);
      } else {
         return new RelaySocket(client);
      }
   }

   void sendText(String message) {
      this.client.send(message);
   }

   void sendBinary(byte[] data) {
      this.client.send(data);
   }

   void close() {
      if (this.client.isOpen()) {
         this.client.close();
      }

   }

   boolean isOpen() {
      return this.client.isOpen();
   }

   @Environment(EnvType.CLIENT)
   interface Listener {
      void onOpen(RelaySocket var1);

      void onText(String var1);

      void onBinary(byte[] var1);

      void onClose(int var1, String var2);

      void onError(Throwable var1);
   }
}
