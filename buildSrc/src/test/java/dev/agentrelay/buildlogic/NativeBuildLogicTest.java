package dev.agentrelay.buildlogic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipEncodingHelper;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeBuildLogicTest {
  private static final String ARCHIVE_ROOT = "sherpa-1.0";
  private static final byte[] CONTENT = "verified native input".getBytes(StandardCharsets.UTF_8);

  @TempDir Path temporaryDirectory;

  @Test
  void extractsOnlyTheRequiredSherpaTree() throws Exception {
    Path archive = temporaryDirectory.resolve("source.tar.gz");
    writeArchive(
        archive,
        List.of(
            file("CMakeLists.txt", "root"),
            file("LICENSE", "license"),
            file("cmake/", ""),
            file("cmake/show-info.cmake", "info"),
            file("sherpa-onnx/CMakeLists.txt", "sherpa"),
            file("sherpa-onnx/csrc/CMakeLists.txt", "csrc"),
            file("sherpa-onnx/jni/CMakeLists.txt", "jni"),
            file("sherpa-onnx/kotlin-api/OnlineStream.kt", "api"),
            file("docs/ignored.txt", "ignored"),
            file("cmake\\ignored.txt", "ignored")));

    Path output = temporaryDirectory.resolve("output");
    sourceExtractTask(archive, output).extract();

    assertEquals("api", Files.readString(output.resolve("sherpa-onnx/kotlin-api/OnlineStream.kt")));
    assertFalse(Files.exists(output.resolve("docs/ignored.txt")));
    try (var paths = Files.walk(output)) {
      assertFalse(paths.anyMatch(Files::isSymbolicLink));
    }

    writeArchive(
        archive,
        List.of(
            file("CMakeLists.txt", "root-v2"),
            file("LICENSE", "license"),
            file("cmake/show-info.cmake", "info"),
            file("sherpa-onnx/CMakeLists.txt", "sherpa"),
            file("sherpa-onnx/csrc/CMakeLists.txt", "csrc"),
            file("sherpa-onnx/jni/CMakeLists.txt", "jni"),
            file("sherpa-onnx/kotlin-api/OnlineStream.kt", "api-v2")));
    sourceExtractTask(archive, output).extract();

    assertEquals(
        "api-v2", Files.readString(output.resolve("sherpa-onnx/kotlin-api/OnlineStream.kt")));
    assertFalse(Files.exists(output.resolve("docs/ignored.txt")));
  }

  @Test
  void failedDirectoryReplacementRestoresVerifiedOutput() throws Exception {
    Path output = Files.createDirectory(temporaryDirectory.resolve("replace-output"));
    Files.writeString(output.resolve("verified.txt"), "verified");
    Path missingStaging = temporaryDirectory.resolve("missing-staging");

    assertThrows(
        IOException.class, () -> SherpaSourceExtractTask.replaceDirectory(missingStaging, output));

    assertEquals("verified", Files.readString(output.resolve("verified.txt")));
    try (var paths = Files.list(temporaryDirectory)) {
      assertFalse(
          paths.anyMatch(
              path -> path.getFileName().toString().startsWith("replace-output.backup-")));
    }

    Path absentOutput = temporaryDirectory.resolve("absent-output");
    assertThrows(
        IOException.class,
        () -> SherpaSourceExtractTask.replaceDirectory(missingStaging, absentOutput));
    assertFalse(Files.exists(absentOutput));

    Path filesystemRoot = temporaryDirectory.toAbsolutePath().getRoot();
    assertNotNull(filesystemRoot);
    assertThrows(
        IOException.class,
        () -> SherpaSourceExtractTask.replaceDirectory(missingStaging, filesystemRoot));
  }

  @Test
  void rejectsPathTraversalBeforeWritingOutsideTheOutput() throws Exception {
    Path archive = temporaryDirectory.resolve("traversal.tar.gz");
    writeArchive(archive, List.of(file("cmake/../../escaped.txt", "hostile")));
    Path output = temporaryDirectory.resolve("output");

    IOException failure =
        assertThrows(IOException.class, () -> sourceExtractTask(archive, output).extract());

    assertTrue(failure.getMessage().contains("escaped its output"));
    assertFalse(Files.exists(temporaryDirectory.resolve("escaped.txt")));
  }

  @Test
  void rejectsLinksAndDuplicateArchiveEntries() throws Exception {
    Path linkArchive = temporaryDirectory.resolve("link.tar.gz");
    writeArchive(linkArchive, List.of(symbolicLink("cmake/link", "../../outside")));
    IOException linkFailure =
        assertThrows(
            IOException.class,
            () ->
                sourceExtractTask(linkArchive, temporaryDirectory.resolve("link-output"))
                    .extract());
    assertTrue(linkFailure.getMessage().contains("unsafe entry"));

    Path duplicateArchive = temporaryDirectory.resolve("duplicate.tar.gz");
    writeArchive(
        duplicateArchive,
        List.of(file("cmake/duplicate.cmake", "one"), file("cmake/duplicate.cmake", "two")));
    IOException duplicateFailure =
        assertThrows(
            IOException.class,
            () ->
                sourceExtractTask(duplicateArchive, temporaryDirectory.resolve("duplicate-output"))
                    .extract());
    assertTrue(duplicateFailure.getMessage().contains("duplicate entry"));
  }

  @Test
  void rejectsOversizedArchiveEntriesBeforeExtraction() throws Exception {
    Path archive = temporaryDirectory.resolve("oversized.tar.gz");
    writeOversizedArchive(archive, 128L * 1024L * 1024L + 1L);

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                sourceExtractTask(archive, temporaryDirectory.resolve("oversized-output"))
                    .extract());

    assertTrue(failure.getMessage().contains("entry was oversized"));
  }

  @Test
  void rejectsTruncatedAndIncompleteArchivesAndPreservesVerifiedOutput() throws Exception {
    Path truncated = temporaryDirectory.resolve("truncated.tar.gz");
    writeArchive(truncated, List.of(file("CMakeLists.txt", "root")));
    byte[] archiveBytes = Files.readAllBytes(truncated);
    Files.write(truncated, java.util.Arrays.copyOf(archiveBytes, archiveBytes.length / 2));
    assertThrows(
        IOException.class,
        () ->
            sourceExtractTask(truncated, temporaryDirectory.resolve("truncated-output")).extract());

    Path incomplete = temporaryDirectory.resolve("incomplete.tar.gz");
    writeArchive(incomplete, List.of(file("CMakeLists.txt", "root")));
    Path output = temporaryDirectory.resolve("cleaned-output");
    Files.createDirectories(output);
    Files.writeString(output.resolve("stale.txt"), "stale");
    assertThrows(IOException.class, () -> sourceExtractTask(incomplete, output).extract());
    assertEquals("stale", Files.readString(output.resolve("stale.txt")));
  }

  @Test
  void refusesToReplaceASymbolicLinkOutputDirectory() throws Exception {
    Path archive = temporaryDirectory.resolve("unused.tar.gz");
    writeArchive(archive, List.of(file("CMakeLists.txt", "root")));
    Path realDirectory = Files.createDirectory(temporaryDirectory.resolve("real"));
    Path outputLink = temporaryDirectory.resolve("output-link");
    Files.createSymbolicLink(outputLink, realDirectory);

    IOException failure =
        assertThrows(IOException.class, () -> sourceExtractTask(archive, outputLink).extract());

    assertTrue(failure.getMessage().contains("output was a symbolic link"));
  }

  @Test
  void validatesDownloadUrisAndRedirectStatuses() throws Exception {
    VerifiedDownloadTask task = downloadTask(temporaryDirectory.resolve("download.bin"));
    task.getAllowedRedirectHosts().set(List.of("downloads.example.test"));
    Method validateUri = VerifiedDownloadTask.class.getDeclaredMethod("validateUri", URI.class);
    validateUri.setAccessible(true);

    assertEquals(
        URI.create("https://downloads.example.test/file"),
        validateUri.invoke(task, URI.create("https://DOWNLOADS.example.test/file")));
    for (String rejected :
        List.of(
            "http://downloads.example.test/file",
            "https://user@downloads.example.test/file",
            "https://downloads.example.test:444/file",
            "https://downloads.example.test/file#fragment",
            "https://evil.example.test/file")) {
      assertInvocationIOException(validateUri, task, URI.create(rejected));
    }

    Method isRedirect = VerifiedDownloadTask.class.getDeclaredMethod("isRedirect", int.class);
    isRedirect.setAccessible(true);
    for (int status : List.of(301, 302, 303, 307, 308)) {
      assertEquals(true, isRedirect.invoke(null, status));
    }
    assertEquals(false, isRedirect.invoke(null, 200));
    assertEquals(false, isRedirect.invoke(null, 300));
  }

  @Test
  void verifiesCachedDownloadsWithoutNetworkAndRejectsBadChecksums() throws Exception {
    Path target = temporaryDirectory.resolve("cached.bin");
    Files.write(target, CONTENT);
    VerifiedDownloadTask task = downloadTask(target);
    configureDownload(task, CONTENT.length, sha256(CONTENT));
    task.getDownloadUrl().set("https://not-used.invalid/archive");
    task.getAllowedRedirectHosts().set(List.of("not-used.invalid"));

    task.provision();
    assertArrayEquals(CONTENT, Files.readAllBytes(target));

    Method verify = VerifiedDownloadTask.class.getDeclaredMethod("verify", Path.class);
    verify.setAccessible(true);
    task.getExpectedSha256().set("0".repeat(64));
    assertInvocationIOException(verify, task, target);
  }

  @Test
  void failedDownloadPreservesExistingTargetAndCleansPartialFile() throws Exception {
    Path target = temporaryDirectory.resolve("download.bin");
    Files.writeString(target, "stale");
    VerifiedDownloadTask task = downloadTask(target);
    configureDownload(task, CONTENT.length, sha256(CONTENT));
    task.getDownloadUrl().set("http://downloads.example.test/archive");
    task.getAllowedRedirectHosts().set(List.of("downloads.example.test"));

    assertThrows(IOException.class, task::provision);
    assertEquals("stale", Files.readString(target));
    try (var paths = Files.list(temporaryDirectory)) {
      assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".partial")));
    }
  }

  private SherpaSourceExtractTask sourceExtractTask(Path archive, Path output) {
    Project project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build();
    SherpaSourceExtractTask task =
        project.getTasks().register("extractSherpa", SherpaSourceExtractTask.class).get();
    task.getInputArchive().set(archive.toFile());
    task.getArchiveRoot().set(ARCHIVE_ROOT);
    task.getOutputDirectory().set(output.toFile());
    return task;
  }

  private VerifiedDownloadTask downloadTask(Path output) {
    Project project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build();
    VerifiedDownloadTask task =
        project.getTasks().register("verifiedDownload", VerifiedDownloadTask.class).get();
    task.getOutputFile().set(output.toFile());
    return task;
  }

  private static void configureDownload(VerifiedDownloadTask task, long size, String checksum) {
    task.getExpectedSizeBytes().set(size);
    task.getExpectedSha256().set(checksum);
  }

  private static void assertInvocationIOException(Method method, Object receiver, Object argument) {
    InvocationTargetException failure =
        assertThrows(InvocationTargetException.class, () -> method.invoke(receiver, argument));
    assertTrue(failure.getCause() instanceof IOException);
  }

  private static ArchiveEntry file(String relativeName, String content) {
    return new ArchiveEntry(relativeName, content.getBytes(StandardCharsets.UTF_8), null);
  }

  private static ArchiveEntry symbolicLink(String relativeName, String target) {
    return new ArchiveEntry(relativeName, new byte[0], target);
  }

  private static void writeArchive(Path archive, List<ArchiveEntry> entries) throws IOException {
    try (OutputStream fileOutput = Files.newOutputStream(archive);
        GzipCompressorOutputStream gzipOutput = new GzipCompressorOutputStream(fileOutput);
        TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(gzipOutput)) {
      for (ArchiveEntry fixture : entries) {
        TarArchiveEntry entry;
        if (fixture.linkTarget() == null) {
          entry = new TarArchiveEntry(ARCHIVE_ROOT + "/" + fixture.relativeName());
        } else {
          entry =
              new TarArchiveEntry(
                  ARCHIVE_ROOT + "/" + fixture.relativeName(), TarArchiveEntry.LF_SYMLINK);
          entry.setLinkName(fixture.linkTarget());
        }
        entry.setSize(fixture.content().length);
        tarOutput.putArchiveEntry(entry);
        tarOutput.write(fixture.content());
        tarOutput.closeArchiveEntry();
      }
    }
  }

  private static void writeOversizedArchive(Path archive, long size) throws IOException {
    try (OutputStream fileOutput = Files.newOutputStream(archive);
        GzipCompressorOutputStream gzipOutput = new GzipCompressorOutputStream(fileOutput)) {
      TarArchiveEntry entry = new TarArchiveEntry(ARCHIVE_ROOT + "/cmake/oversized.bin");
      entry.setSize(size);
      byte[] header = new byte[512];
      entry.writeEntryHeader(
          header, ZipEncodingHelper.getZipEncoding(StandardCharsets.UTF_8), false);
      gzipOutput.write(header);
    }
  }

  private static String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }

  static String sha256ForTesting(byte[] content) throws Exception {
    return sha256(content);
  }

  private record ArchiveEntry(String relativeName, byte[] content, String linkTarget) {}
}
