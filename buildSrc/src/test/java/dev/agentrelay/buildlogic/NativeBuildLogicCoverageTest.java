/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.buildlogic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeBuildLogicCoverageTest {
  private static final List<String> ABIS = List.of("arm64-v8a", "armeabi-v7a", "x86", "x86_64");
  private static final List<String> KOTLIN_API_FILES =
      List.of(
          "FeatureConfig.kt",
          "HomophoneReplacerConfig.kt",
          "OnlineRecognizer.kt",
          "OnlineStream.kt",
          "QnnConfig.kt");
  private static final byte[] DOWNLOAD_CONTENT =
      "verified download content".getBytes(StandardCharsets.UTF_8);

  @TempDir Path temporaryDirectory;

  @Test
  void pluginRegistersTheFunctionalExtractionTask() {
    Project project = project();

    new NativeBuildLogicPlugin().apply(project);

    assertInstanceOf(
        SherpaSourceExtractTask.class, project.getTasks().getByName("extractSherpaSource"));
  }

  @Test
  void onnxExtractionSelectsOnlyRequiredFiles() throws Exception {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("headers/onnxruntime_c_api.h", bytes("header"));
    for (String abi : ABIS) {
      entries.put("jni/" + abi + "/libonnxruntime.so", bytes(abi));
    }
    entries.put("ignored/private.txt", bytes("ignored"));
    Path archive = temporaryDirectory.resolve("onnx.zip");
    writeZip(archive, entries);
    Path output = temporaryDirectory.resolve("onnx-output");

    onnxTask(archive, output).extract();

    assertEquals("header", Files.readString(output.resolve("headers/onnxruntime_c_api.h")));
    for (String abi : ABIS) {
      assertEquals(abi, Files.readString(output.resolve("jni/" + abi + "/libonnxruntime.so")));
    }
    assertFalse(Files.exists(output.resolve("ignored/private.txt")));
  }

  @Test
  void onnxExtractionRejectsAnIncompleteRuntime() throws Exception {
    Path archive = temporaryDirectory.resolve("incomplete-onnx.zip");
    writeZip(archive, Map.of("headers/onnxruntime_c_api.h", bytes("header")));

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                onnxTask(archive, temporaryDirectory.resolve("incomplete-onnx-output")).extract());

    assertTrue(failure.getMessage().contains("incomplete"));
  }

  @Test
  void onnxExtractionRejectsPathTraversalBeforeWritingOutside() throws Exception {
    Path archive = temporaryDirectory.resolve("traversal-onnx.zip");
    writeZipEntries(archive, List.of(zipFile("headers/../../escaped.txt", bytes("hostile"))));
    Path output = temporaryDirectory.resolve("traversal-onnx-output");

    IOException failure =
        assertThrows(IOException.class, () -> onnxTask(archive, output).extract());

    assertTrue(failure.getMessage().contains("unsafe path"));
    assertFalse(Files.exists(temporaryDirectory.resolve("escaped.txt")));
    assertFalse(Files.exists(output));
  }

  @Test
  void onnxExtractionRejectsDuplicateEntries() throws Exception {
    Path archive = temporaryDirectory.resolve("duplicate-onnx.zip");
    writeZipEntries(
        archive,
        List.of(
            zipFile("headers/onnxruntime_c_api.h", bytes("one")),
            zipFile("headers/onnxruntime_c_api.h", bytes("two"))));
    Path output = temporaryDirectory.resolve("duplicate-onnx-output");

    IOException failure =
        assertThrows(IOException.class, () -> onnxTask(archive, output).extract());

    assertTrue(failure.getMessage().contains("duplicate entry"));
    assertFalse(Files.exists(output));
  }

  @Test
  void onnxExtractionRejectsSymbolicLinks() throws Exception {
    Path archive = temporaryDirectory.resolve("link-onnx.zip");
    writeZipEntries(archive, List.of(zipLink("headers/onnxruntime_c_api.h", "../../outside")));
    Path output = temporaryDirectory.resolve("link-onnx-output");

    IOException failure =
        assertThrows(IOException.class, () -> onnxTask(archive, output).extract());

    assertTrue(failure.getMessage().contains("symbolic link"));
    assertFalse(Files.exists(output));
  }

  @Test
  void kotlinApiGenerationSelectsTheExactPinnedSurface() throws Exception {
    Path source = temporaryDirectory.resolve("source");
    Path api = source.resolve("sherpa-onnx/kotlin-api");
    Files.createDirectories(api);
    for (String name : KOTLIN_API_FILES) {
      Files.writeString(api.resolve(name), name);
    }
    Files.writeString(api.resolve("OfflineRecognizer.kt"), "ignored");
    Path output = temporaryDirectory.resolve("kotlin-output");

    kotlinApiTask(source, output).generate();

    try (var files = Files.list(output)) {
      assertEquals(KOTLIN_API_FILES.stream().sorted().toList(), fileNames(files.toList()));
    }
  }

  @Test
  void kotlinApiGenerationRejectsMissingPinnedFiles() throws Exception {
    Path source = temporaryDirectory.resolve("missing-source");
    Path api = source.resolve("sherpa-onnx/kotlin-api");
    Files.createDirectories(api);
    Files.writeString(api.resolve("FeatureConfig.kt"), "partial");

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                kotlinApiTask(source, temporaryDirectory.resolve("missing-kotlin-output"))
                    .generate());

    assertTrue(failure.getMessage().contains("incomplete"));
  }

  @Test
  void androidRuntimePassesEveryPinnedInputToTheBuildScript() throws Exception {
    Path source = Files.createDirectory(temporaryDirectory.resolve("runtime-source"));
    Path onnx = Files.createDirectory(temporaryDirectory.resolve("runtime-onnx"));
    Path ndk = Files.createDirectory(temporaryDirectory.resolve("runtime-ndk"));
    Path patch = writeFile("metadata.patch", "patch");
    Path license = writeFile("LICENSE.onnxruntime", "license");
    Path notices = writeFile("NOTICE.onnxruntime", "notices");
    Path script =
        writeFile(
            "fake-build.sh",
            """
            set -eu
            jni=
            resources=
            while [ "$#" -gt 0 ]; do
              case "$1" in
                --jni-output) shift; jni=$1 ;;
                --resources-output) shift; resources=$1 ;;
              esac
              shift
            done
            test -n "$jni"
            test -n "$resources"
            mkdir -p "$jni" "$resources"
            printf 'executed' > "$resources/executed.txt"
            """);
    Path jni = temporaryDirectory.resolve("runtime-jni");
    Path resources = temporaryDirectory.resolve("runtime-resources");
    SherpaAndroidRuntimeTask task =
        androidRuntimeTask(source, onnx, ndk, script, patch, license, notices, jni, resources);

    task.buildRuntime();

    assertTrue(Files.isDirectory(jni));
    assertEquals("executed", Files.readString(resources.resolve("executed.txt")));
  }

  @Test
  void verifiedDownloadFollowsAllowedRedirectAndReplacesTheTarget() throws Exception {
    Path parent = Files.createDirectory(temporaryDirectory.resolve("download"));
    Path target = parent.resolve("target.bin");
    Files.writeString(target, "existing verified artifact");
    VerifiedDownloadTask task = downloadTask(target, DOWNLOAD_CONTENT.length);
    task.getDownloadUrl().set("https://downloads.example.test/start");
    task.getAllowedRedirectHosts().set(List.of("downloads.example.test", "cdn.example.test"));
    AtomicInteger requests = new AtomicInteger();
    List<FakeHttpURLConnection> connections = new ArrayList<>();
    task.setConnectionOpenerForTesting(
        uri -> {
          FakeHttpURLConnection connection;
          if (requests.getAndIncrement() == 0) {
            assertEquals(URI.create("https://downloads.example.test/start"), uri);
            connection = connection(uri, 302, "https://cdn.example.test/archive", -1, new byte[0]);
          } else {
            assertEquals(URI.create("https://cdn.example.test/archive"), uri);
            connection = connection(uri, 200, null, DOWNLOAD_CONTENT.length, DOWNLOAD_CONTENT);
          }
          connections.add(connection);
          return connection;
        });

    task.provision();

    assertEquals(2, requests.get());
    assertArrayEquals(DOWNLOAD_CONTENT, Files.readAllBytes(target));
    assertTrue(connections.stream().allMatch(FakeHttpURLConnection::wasDisconnected));
  }

  @Test
  void verifiedDownloadRejectsDeclaredSizeAndCleansPartialFiles() throws Exception {
    Path parent = Files.createDirectory(temporaryDirectory.resolve("wrong-size"));
    Path target = parent.resolve("target.bin");
    Files.writeString(target, "stale");
    VerifiedDownloadTask task = downloadTask(target, DOWNLOAD_CONTENT.length);
    task.setConnectionOpenerForTesting(
        uri -> connection(uri, 200, null, DOWNLOAD_CONTENT.length + 1L, DOWNLOAD_CONTENT));

    IOException failure = assertThrows(IOException.class, task::provision);

    assertTrue(failure.getMessage().contains("response size differed"));
    assertEquals("stale", Files.readString(target));
    assertNoPartialFiles(parent);
  }

  @Test
  void verifiedDownloadPreservesExistingTargetOnWrongChecksumAndCleansTemporaryFile()
      throws Exception {
    Path parent = Files.createDirectory(temporaryDirectory.resolve("wrong-checksum"));
    Path target = parent.resolve("target.bin");
    Files.writeString(target, "existing verified artifact");
    VerifiedDownloadTask task = downloadTask(target, DOWNLOAD_CONTENT.length);
    task.getExpectedSha256().set("0".repeat(64));
    FakeHttpURLConnection connection =
        connection(
            URI.create("https://downloads.example.test/archive"),
            200,
            null,
            DOWNLOAD_CONTENT.length,
            DOWNLOAD_CONTENT);
    task.setConnectionOpenerForTesting(uri -> connection);

    IOException failure = assertThrows(IOException.class, task::provision);

    assertTrue(failure.getMessage().contains("checksum differed"));
    assertEquals("existing verified artifact", Files.readString(target));
    assertNoPartialFiles(parent);
    assertTrue(connection.wasDisconnected());
  }

  @Test
  void verifiedDownloadRejectsRedirectLimitAndCleansEveryConnection() throws Exception {
    Path parent = Files.createDirectory(temporaryDirectory.resolve("redirect-limit"));
    Path target = parent.resolve("target.bin");
    Files.writeString(target, "existing verified artifact");
    VerifiedDownloadTask task = downloadTask(target, DOWNLOAD_CONTENT.length);
    AtomicInteger requests = new AtomicInteger();
    List<FakeHttpURLConnection> connections = new ArrayList<>();
    task.setConnectionOpenerForTesting(
        uri -> {
          int request = requests.getAndIncrement();
          FakeHttpURLConnection connection =
              connection(
                  uri,
                  302,
                  "https://downloads.example.test/redirect-" + (request + 1),
                  -1,
                  new byte[0]);
          connections.add(connection);
          return connection;
        });

    IOException failure = assertThrows(IOException.class, task::provision);

    assertTrue(failure.getMessage().contains("redirect limit"));
    assertEquals(6, requests.get());
    assertTrue(connections.stream().allMatch(FakeHttpURLConnection::wasDisconnected));
    assertEquals("existing verified artifact", Files.readString(target));
    assertNoPartialFiles(parent);
  }

  @Test
  void verifiedDownloadRejectsRedirectToDisallowedHostAndDisconnects() throws Exception {
    Path parent = Files.createDirectory(temporaryDirectory.resolve("redirect-host"));
    Path target = parent.resolve("target.bin");
    Files.writeString(target, "existing verified artifact");
    VerifiedDownloadTask task = downloadTask(target, DOWNLOAD_CONTENT.length);
    FakeHttpURLConnection connection =
        connection(
            URI.create("https://downloads.example.test/archive"),
            302,
            "https://untrusted.example.test/archive",
            -1,
            new byte[0]);
    task.setConnectionOpenerForTesting(uri -> connection);

    IOException failure = assertThrows(IOException.class, task::provision);

    assertTrue(failure.getMessage().contains("URI was not allowed"));
    assertTrue(connection.wasDisconnected());
    assertEquals("existing verified artifact", Files.readString(target));
    assertNoPartialFiles(parent);
  }

  @Test
  void verifiedDownloadRejectsTruncatedAndOversizedBodies() throws Exception {
    Path truncatedParent = temporaryDirectory.resolve("truncated");
    Path truncatedTarget = truncatedParent.resolve("target.bin");
    VerifiedDownloadTask truncated = downloadTask(truncatedTarget, DOWNLOAD_CONTENT.length + 1L);
    truncated.setConnectionOpenerForTesting(
        uri -> connection(uri, 200, null, -1, DOWNLOAD_CONTENT));
    IOException truncatedFailure = assertThrows(IOException.class, truncated::provision);
    assertTrue(truncatedFailure.getMessage().contains("truncated"));
    assertNoPartialFiles(truncatedParent);

    Path oversizedParent = temporaryDirectory.resolve("oversized");
    Path oversizedTarget = oversizedParent.resolve("target.bin");
    VerifiedDownloadTask oversized = downloadTask(oversizedTarget, DOWNLOAD_CONTENT.length - 1L);
    oversized.setConnectionOpenerForTesting(
        uri -> connection(uri, 200, null, -1, DOWNLOAD_CONTENT));
    IOException oversizedFailure = assertThrows(IOException.class, oversized::provision);
    assertTrue(oversizedFailure.getMessage().contains("exceeded its size"));
    assertNoPartialFiles(oversizedParent);
  }

  @Test
  void verifiedDownloadRejectsInvalidRedirectAndHttpStatus() throws Exception {
    Path redirectTarget = temporaryDirectory.resolve("redirect/target.bin");
    VerifiedDownloadTask redirect = downloadTask(redirectTarget, DOWNLOAD_CONTENT.length);
    redirect.setConnectionOpenerForTesting(uri -> connection(uri, 302, null, -1, new byte[0]));
    IOException redirectFailure = assertThrows(IOException.class, redirect::provision);
    assertTrue(redirectFailure.getMessage().contains("redirect was invalid"));

    Path statusTarget = temporaryDirectory.resolve("status/target.bin");
    VerifiedDownloadTask status = downloadTask(statusTarget, DOWNLOAD_CONTENT.length);
    status.setConnectionOpenerForTesting(uri -> connection(uri, 503, null, -1, new byte[0]));
    IOException statusFailure = assertThrows(IOException.class, status::provision);
    assertTrue(statusFailure.getMessage().contains("request failed"));
  }

  private Project project() {
    return ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build();
  }

  private OnnxRuntimeExtractTask onnxTask(Path archive, Path output) {
    OnnxRuntimeExtractTask task =
        project().getTasks().register("extractOnnx", OnnxRuntimeExtractTask.class).get();
    task.getInputArchive().set(archive.toFile());
    task.getOutputDirectory().set(output.toFile());
    return task;
  }

  private SherpaKotlinApiTask kotlinApiTask(Path source, Path output) {
    SherpaKotlinApiTask task =
        project().getTasks().register("generateKotlinApi", SherpaKotlinApiTask.class).get();
    task.getSourceDirectory().set(source.toFile());
    task.getOutputDirectory().set(output.toFile());
    return task;
  }

  private SherpaAndroidRuntimeTask androidRuntimeTask(
      Path source,
      Path onnx,
      Path ndk,
      Path script,
      Path patch,
      Path license,
      Path notices,
      Path jni,
      Path resources) {
    SherpaAndroidRuntimeTask task =
        project().getTasks().register("buildRuntime", SherpaAndroidRuntimeTask.class).get();
    task.getSourceDirectory().set(source.toFile());
    task.getOnnxRuntimeDirectory().set(onnx.toFile());
    task.getNdkDirectory().set(ndk.toFile());
    task.getBuildScript().set(script.toFile());
    task.getMetadataPatch().set(patch.toFile());
    task.getOnnxRuntimeLicense().set(license.toFile());
    task.getOnnxRuntimeNotices().set(notices.toFile());
    task.getJniDirectory().set(jni.toFile());
    task.getResourcesDirectory().set(resources.toFile());
    task.getCmakeExecutable().set("cmake-test");
    task.getNinjaExecutable().set("ninja-test");
    task.getSherpaVersion().set("1.0");
    task.getSherpaSourceRevision().set("sherpa-revision");
    task.getSherpaSourceArchiveSha256().set("a".repeat(64));
    task.getSourceDateEpoch().set("1");
    task.getOnnxRuntimeVersion().set("2.0");
    task.getOnnxRuntimeSourceRevision().set("onnx-source-revision");
    task.getOnnxRuntimeBinaryRevision().set("onnx-binary-revision");
    task.getOnnxRuntimeArchiveSha256().set("b".repeat(64));
    task.getNdkVersion().set("ndk-test");
    task.getCmakeVersion().set("cmake-version-test");
    task.getNinjaVersion().set("ninja-version-test");
    return task;
  }

  private VerifiedDownloadTask downloadTask(Path target, long expectedSize) throws Exception {
    VerifiedDownloadTask task =
        project()
            .getTasks()
            .register("download" + target.hashCode(), VerifiedDownloadTask.class)
            .get();
    task.getOutputFile().set(target.toFile());
    task.getDownloadUrl().set("https://downloads.example.test/archive");
    task.getAllowedRedirectHosts().set(List.of("downloads.example.test"));
    task.getExpectedSizeBytes().set(expectedSize);
    task.getExpectedSha256().set(NativeBuildLogicTest.sha256ForTesting(DOWNLOAD_CONTENT));
    return task;
  }

  private Path writeFile(String name, String content) throws IOException {
    Path file = temporaryDirectory.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  private static byte[] bytes(String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }

  private static List<String> fileNames(List<Path> files) {
    return files.stream().map(path -> path.getFileName().toString()).sorted().toList();
  }

  private static void writeZip(Path archive, Map<String, byte[]> entries) throws IOException {
    writeZipEntries(
        archive,
        entries.entrySet().stream()
            .map(entry -> zipFile(entry.getKey(), entry.getValue()))
            .toList());
  }

  private static void writeZipEntries(Path archive, List<ZipFixture> entries) throws IOException {
    try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive)) {
      for (ZipFixture fixture : entries) {
        ZipArchiveEntry entry = new ZipArchiveEntry(fixture.name());
        if (fixture.symbolicLink()) {
          entry.setUnixMode(UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM);
        }
        entry.setSize(fixture.content().length);
        output.putArchiveEntry(entry);
        output.write(fixture.content());
        output.closeArchiveEntry();
      }
    }
  }

  private static ZipFixture zipFile(String name, byte[] content) {
    return new ZipFixture(name, content, false);
  }

  private static ZipFixture zipLink(String name, String target) {
    return new ZipFixture(name, bytes(target), true);
  }

  private static FakeHttpURLConnection connection(
      URI uri, int status, String location, long declaredSize, byte[] content) throws IOException {
    return new FakeHttpURLConnection(uri.toURL(), status, location, declaredSize, content);
  }

  private static void assertNoPartialFiles(Path directory) throws IOException {
    try (var files = Files.list(directory)) {
      assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".partial")));
    }
  }

  private static final class FakeHttpURLConnection extends HttpURLConnection {
    private final int status;
    private final String location;
    private final long declaredSize;
    private final byte[] content;
    private boolean disconnected;

    FakeHttpURLConnection(URL url, int status, String location, long declaredSize, byte[] content) {
      super(url);
      this.status = status;
      this.location = location;
      this.declaredSize = declaredSize;
      this.content = content.clone();
    }

    @Override
    public void disconnect() {
      disconnected = true;
    }

    boolean wasDisconnected() {
      return disconnected;
    }

    @Override
    public boolean usingProxy() {
      return false;
    }

    @Override
    public void connect() {}

    @Override
    public int getResponseCode() {
      return status;
    }

    @Override
    public String getHeaderField(String name) {
      return "Location".equals(name) ? location : null;
    }

    @Override
    public long getContentLengthLong() {
      return declaredSize;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(content);
    }
  }

  private record ZipFixture(String name, byte[] content, boolean symbolicLink) {}
}
