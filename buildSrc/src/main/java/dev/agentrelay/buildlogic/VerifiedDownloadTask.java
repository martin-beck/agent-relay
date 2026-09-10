/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.buildlogic;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/** Downloads one immutable public build dependency and verifies it before Gradle can consume it. */
@CacheableTask
public abstract class VerifiedDownloadTask extends DefaultTask {
  private static final int CONNECT_TIMEOUT_MILLIS = 30_000;
  private static final int READ_TIMEOUT_MILLIS = 120_000;
  private static final int MAX_REDIRECTS = 5;
  private static final int BUFFER_BYTES = 64 * 1024;
  private ConnectionOpener connectionOpener = VerifiedDownloadTask::openConnection;

  @FunctionalInterface
  interface ConnectionOpener {
    HttpURLConnection open(URI uri) throws IOException;
  }

  @Input
  public abstract Property<String> getDownloadUrl();

  @Input
  public abstract Property<String> getExpectedSha256();

  @Input
  public abstract Property<Long> getExpectedSizeBytes();

  @Input
  public abstract ListProperty<String> getAllowedRedirectHosts();

  @OutputFile
  public abstract RegularFileProperty getOutputFile();

  @TaskAction
  public final void provision() throws IOException {
    Path target = getOutputFile().get().getAsFile().toPath();
    if (isVerified(target)) {
      return;
    }

    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("Verified dependency output has no parent directory");
    }
    Files.createDirectories(parent);
    Path targetName = target.getFileName();
    if (targetName == null) {
      throw new IOException("Verified dependency output has no file name");
    }
    Path temporary = Files.createTempFile(parent, targetName.toString(), ".partial");
    try {
      download(temporary);
      verify(temporary);
      moveIntoPlace(temporary, target);
      getLogger().lifecycle("Provisioned verified dependency {}", target.getFileName());
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private boolean isVerified(Path file) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(file)
        || Files.size(file) != getExpectedSizeBytes().get()) {
      return false;
    }
    return sha256(file).equals(getExpectedSha256().get());
  }

  private void download(Path destination) throws IOException {
    URI current = validateUri(URI.create(getDownloadUrl().get()));
    for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
      HttpURLConnection connection = connectionOpener.open(current);
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
      connection.setReadTimeout(READ_TIMEOUT_MILLIS);
      connection.setRequestMethod("GET");
      connection.setRequestProperty("Accept", "application/octet-stream");
      try {
        int status = connection.getResponseCode();
        if (isRedirect(status)) {
          String location = connection.getHeaderField("Location");
          if (location == null) {
            throw new IOException("Verified dependency redirect was invalid");
          }
          if (redirects == MAX_REDIRECTS) {
            throw new IOException("Verified dependency exceeded its redirect limit");
          }
          current = validateUri(current.resolve(location));
          continue;
        }
        if (status != HttpURLConnection.HTTP_OK) {
          throw new IOException("Verified dependency request failed");
        }

        long declaredBytes = connection.getContentLengthLong();
        long expectedBytes = getExpectedSizeBytes().get();
        if (declaredBytes >= 0 && declaredBytes != expectedBytes) {
          throw new IOException("Verified dependency response size differed");
        }

        try (InputStream input = new BufferedInputStream(connection.getInputStream());
            OutputStream output = new BufferedOutputStream(Files.newOutputStream(destination))) {
          byte[] buffer = new byte[BUFFER_BYTES];
          long copied = 0L;
          int read = input.read(buffer);
          while (read != -1) {
            copied = Math.addExact(copied, read);
            if (copied > expectedBytes) {
              throw new IOException("Verified dependency exceeded its size");
            }
            output.write(buffer, 0, read);
            read = input.read(buffer);
          }
          if (copied != expectedBytes) {
            throw new IOException("Verified dependency was truncated");
          }
        }
        return;
      } finally {
        connection.disconnect();
      }
    }
    throw new IOException("Verified dependency exceeded its redirect limit");
  }

  private URI validateUri(URI uri) throws IOException {
    String host = uri.getHost();
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || host == null
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null
        || (uri.getPort() != -1 && uri.getPort() != 443)
        || getAllowedRedirectHosts().get().stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .noneMatch(host.toLowerCase(Locale.ROOT)::equals)) {
      throw new IOException("Verified dependency URI was not allowed");
    }
    return uri;
  }

  final void setConnectionOpenerForTesting(ConnectionOpener value) {
    connectionOpener = value;
  }

  private static HttpURLConnection openConnection(URI uri) throws IOException {
    return (HttpURLConnection) uri.toURL().openConnection();
  }

  private void verify(Path file) throws IOException {
    if (Files.size(file) != getExpectedSizeBytes().get()
        || !sha256(file).equals(getExpectedSha256().get())) {
      throw new IOException("Verified dependency checksum differed");
    }
  }

  private static String sha256(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
    try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
      byte[] buffer = new byte[BUFFER_BYTES];
      int read = input.read(buffer);
      while (read != -1) {
        digest.update(buffer, 0, read);
        read = input.read(buffer);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static boolean isRedirect(int status) {
    return status == HttpURLConnection.HTTP_MOVED_PERM
        || status == HttpURLConnection.HTTP_MOVED_TEMP
        || status == HttpURLConnection.HTTP_SEE_OTHER
        || status == 307
        || status == 308;
  }

  private static void moveIntoPlace(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
