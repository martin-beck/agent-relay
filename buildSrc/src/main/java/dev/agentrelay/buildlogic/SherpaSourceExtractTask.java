package dev.agentrelay.buildlogic;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Extracts the exact native and Kotlin API subset from the verified sherpa source archive. */
@CacheableTask
public abstract class SherpaSourceExtractTask extends DefaultTask {
  private static final int BUFFER_BYTES = 64 * 1024;
  private static final long MAX_ENTRY_BYTES = 128L * 1024L * 1024L;
  private static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;

  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getInputArchive();

  @Input
  public abstract Property<String> getArchiveRoot();

  @OutputDirectory
  public abstract DirectoryProperty getOutputDirectory();

  @TaskAction
  public final void extract() throws IOException {
    String root = getArchiveRoot().get();
    Path output = getOutputDirectory().get().getAsFile().toPath();
    deleteRecursively(output);
    Files.createDirectories(output);

    Set<String> extracted = new HashSet<>();
    long totalBytes = 0L;
    try (InputStream fileInput =
            new BufferedInputStream(
                Files.newInputStream(getInputArchive().get().getAsFile().toPath()));
        GZIPInputStream gzipInput = new GZIPInputStream(fileInput, BUFFER_BYTES);
        TarArchiveInputStream archive = new TarArchiveInputStream(gzipInput)) {
      while (true) {
        TarArchiveEntry entry = archive.getNextEntry();
        if (entry == null) {
          break;
        }
        String relative = includedRelativePath(entry.getName(), root);
        if (relative == null) {
          continue;
        }
        if (!extracted.add(relative)) {
          throw new IOException("Sherpa source archive contained a duplicate entry");
        }
        if (entry.isSymbolicLink() || entry.isLink() || (!entry.isDirectory() && !entry.isFile())) {
          throw new IOException("Sherpa source archive contained an unsafe entry");
        }

        Path destination = output.resolve(relative).normalize();
        if (!destination.startsWith(output)
            || Files.isSymbolicLink(destination)
            || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("Sherpa source archive entry escaped its output");
        }
        if (entry.isDirectory()) {
          Files.createDirectories(destination);
          continue;
        }
        if (entry.getSize() < 0 || entry.getSize() > MAX_ENTRY_BYTES) {
          throw new IOException("Sherpa source archive entry was oversized");
        }
        totalBytes = Math.addExact(totalBytes, entry.getSize());
        if (totalBytes > MAX_TOTAL_BYTES) {
          throw new IOException("Sherpa source archive was oversized");
        }
        Path destinationParent = destination.getParent();
        if (destinationParent == null) {
          throw new IOException("Sherpa source archive entry had no output parent");
        }
        Files.createDirectories(destinationParent);
        try (OutputStream fileOutput =
            new BufferedOutputStream(Files.newOutputStream(destination))) {
          byte[] buffer = new byte[BUFFER_BYTES];
          long copied = 0L;
          while (copied < entry.getSize()) {
            int read =
                archive.read(buffer, 0, (int) Math.min(buffer.length, entry.getSize() - copied));
            if (read < 0) {
              throw new IOException("Sherpa source archive entry was truncated");
            }
            fileOutput.write(buffer, 0, read);
            copied += read;
          }
        }
      }
    }

    requireFile(output.resolve("CMakeLists.txt"));
    requireFile(output.resolve("LICENSE"));
    requireFile(output.resolve("cmake/show-info.cmake"));
    requireFile(output.resolve("sherpa-onnx/CMakeLists.txt"));
    requireFile(output.resolve("sherpa-onnx/csrc/CMakeLists.txt"));
    requireFile(output.resolve("sherpa-onnx/jni/CMakeLists.txt"));
    try (var paths = Files.walk(output)) {
      if (paths.anyMatch(Files::isSymbolicLink)) {
        throw new IOException("Sherpa source extraction contained a symbolic link");
      }
    }
  }

  private static String includedRelativePath(String name, String root) throws IOException {
    String prefix = root + "/";
    if (!name.startsWith(prefix) || name.indexOf('\\') >= 0) {
      return null;
    }
    String relative = name.substring(prefix.length());
    if (relative.isEmpty()
        || "CMakeLists.txt".equals(relative)
        || "LICENSE".equals(relative)
        || relative.startsWith("cmake/")
        || "sherpa-onnx/CMakeLists.txt".equals(relative)
        || relative.startsWith("sherpa-onnx/csrc/")
        || relative.startsWith("sherpa-onnx/jni/")
        || relative.startsWith("sherpa-onnx/kotlin-api/")) {
      return relative.isEmpty() ? null : relative;
    }
    return null;
  }

  private static void requireFile(Path file) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
      throw new IOException("Sherpa source extraction was incomplete");
    }
  }

  private static void deleteRecursively(Path target) throws IOException {
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (Files.isSymbolicLink(target)) {
      throw new IOException("Sherpa source output was a symbolic link");
    }
    try (var paths = Files.walk(target)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
