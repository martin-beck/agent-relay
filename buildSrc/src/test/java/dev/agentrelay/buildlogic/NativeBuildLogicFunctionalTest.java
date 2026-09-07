package dev.agentrelay.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeBuildLogicFunctionalTest {
  private static final String ARCHIVE_ROOT = "sherpa-functional";

  @TempDir Path projectDirectory;

  @Test
  void extractionIsCacheableAndRestoresOnlyVerifiedOutputs() throws Exception {
    writeSettings();
    writeExtractionBuild();
    writeArchive(
        projectDirectory.resolve("source.tar.gz"),
        List.of(
            entry("CMakeLists.txt", "root"),
            entry("LICENSE", "license"),
            entry("cmake/show-info.cmake", "info"),
            entry("sherpa-onnx/CMakeLists.txt", "sherpa"),
            entry("sherpa-onnx/csrc/CMakeLists.txt", "csrc"),
            entry("sherpa-onnx/jni/CMakeLists.txt", "jni"),
            entry("sherpa-onnx/kotlin-api/OnlineRecognizer.kt", "api"),
            entry("docs/not-selected.txt", "ignored")));

    BuildResult first = run("extractSherpaSource", "--build-cache");
    BuildTask firstTask = first.task(":extractSherpaSource");
    assertNotNull(firstTask);
    assertEquals(TaskOutcome.SUCCESS, firstTask.getOutcome());
    Path output = projectDirectory.resolve("build/extracted");
    assertEquals(
        "api", Files.readString(output.resolve("sherpa-onnx/kotlin-api/OnlineRecognizer.kt")));
    assertFalse(Files.exists(output.resolve("docs/not-selected.txt")));

    deleteRecursively(output);
    BuildResult restored = run("extractSherpaSource", "--build-cache");
    BuildTask restoredTask = restored.task(":extractSherpaSource");
    assertNotNull(restoredTask);
    assertEquals(TaskOutcome.FROM_CACHE, restoredTask.getOutcome());
    assertEquals(
        "api", Files.readString(output.resolve("sherpa-onnx/kotlin-api/OnlineRecognizer.kt")));
  }

  @Test
  void extractionRejectsTraversalInARealGradleExecution() throws Exception {
    writeSettings();
    writeExtractionBuild();
    writeArchive(
        projectDirectory.resolve("source.tar.gz"),
        List.of(entry("cmake/../../outside.txt", "hostile")));

    BuildResult failure = runAndFail("extractSherpaSource", "--no-build-cache");

    assertTrue(failure.getOutput().contains("archive entry escaped its output"));
    assertFalse(Files.exists(projectDirectory.resolve("outside.txt")));
    assertNoPublishedOutput();
    assertNoTransactionResidue();
  }

  @Test
  void linksAndDuplicatesFailWithoutPublishingPartialOutputs() throws Exception {
    writeSettings();
    writeExtractionBuild();
    writeArchive(
        projectDirectory.resolve("source.tar.gz"),
        List.of(entry("cmake/duplicate.cmake", "one"), entry("cmake/duplicate.cmake", "two")));

    BuildResult duplicate = runAndFail("extractSherpaSource", "--no-build-cache");

    assertTrue(duplicate.getOutput().contains("archive contained a duplicate entry"));
    assertNoPublishedOutput();
    assertNoTransactionResidue();

    writeArchive(
        projectDirectory.resolve("source.tar.gz"),
        List.of(symbolicLink("cmake/host-link", "../../outside")));
    BuildResult link = runAndFail("extractSherpaSource", "--no-build-cache");

    assertTrue(link.getOutput().contains("archive contained an unsafe entry"));
    assertNoPublishedOutput();
    assertNoTransactionResidue();
  }

  @Test
  void failedReplacementPreservesVerifiedOutputAndRetryPublishesOnlyNewContent() throws Exception {
    writeSettings();
    writeExtractionBuild();
    Path archive = projectDirectory.resolve("source.tar.gz");
    writeArchive(archive, completeEntries("api-v1"));
    assertEquals(TaskOutcome.SUCCESS, requiredTask(run("extractSherpaSource")).getOutcome());
    Path api =
        projectDirectory.resolve("build/extracted/sherpa-onnx/kotlin-api/OnlineRecognizer.kt");
    assertEquals("api-v1", Files.readString(api));

    writeArchive(archive, List.of(entry("CMakeLists.txt", "incomplete")));
    BuildResult failure = runAndFail("extractSherpaSource", "--no-build-cache");

    assertTrue(failure.getOutput().contains("source extraction was incomplete"));
    assertEquals("api-v1", Files.readString(api));
    assertNoTransactionResidue();

    writeArchive(archive, completeEntries("api-v2"));
    assertEquals(TaskOutcome.SUCCESS, requiredTask(run("extractSherpaSource")).getOutcome());
    assertEquals("api-v2", Files.readString(api));
    assertNoTransactionResidue();
  }

  @Test
  void truncatedArchiveFailsWithoutOutputAndOriginalInputRestoresFromBuildCache() throws Exception {
    writeSettings();
    writeExtractionBuild();
    Path archive = projectDirectory.resolve("source.tar.gz");
    writeArchive(archive, completeEntries("cache-original"));
    byte[] original = Files.readAllBytes(archive);
    assertEquals(
        TaskOutcome.SUCCESS,
        requiredTask(run("extractSherpaSource", "--build-cache")).getOutcome());
    deleteRecursively(projectDirectory.resolve("build/extracted"));

    Files.write(archive, java.util.Arrays.copyOf(original, original.length / 2));
    runAndFail("extractSherpaSource", "--build-cache");
    assertNoPublishedOutput();
    assertNoTransactionResidue();

    Files.write(archive, original);
    assertEquals(
        TaskOutcome.FROM_CACHE,
        requiredTask(run("extractSherpaSource", "--build-cache")).getOutcome());
    assertEquals(
        "cache-original",
        Files.readString(
            projectDirectory.resolve(
                "build/extracted/sherpa-onnx/kotlin-api/OnlineRecognizer.kt")));
  }

  private void writeSettings() throws IOException {
    Files.writeString(
        projectDirectory.resolve("settings.gradle"),
        "rootProject.name = 'native-build-logic-fixture'\n");
  }

  private void writeExtractionBuild() throws IOException {
    Files.writeString(
        projectDirectory.resolve("build.gradle"),
        """
        plugins {
          id 'dev.agentrelay.native-build-logic'
        }

        tasks.named('extractSherpaSource') {
          inputArchive = layout.projectDirectory.file('source.tar.gz')
          archiveRoot = '__ARCHIVE_ROOT__'
          outputDirectory = layout.buildDirectory.dir('extracted')
        }
        """
            .replace("__ARCHIVE_ROOT__", ARCHIVE_ROOT));
  }

  private BuildResult run(String... arguments) {
    return runner(arguments).build();
  }

  private BuildResult runAndFail(String... arguments) {
    return runner(arguments).buildAndFail();
  }

  private GradleRunner runner(String... arguments) {
    return GradleRunner.create()
        .withProjectDir(projectDirectory.toFile())
        .withPluginClasspath()
        .withArguments(arguments)
        .forwardOutput();
  }

  private static FixtureEntry entry(String name, String content) {
    return new FixtureEntry(name, content.getBytes(StandardCharsets.UTF_8), null);
  }

  private static FixtureEntry symbolicLink(String name, String target) {
    return new FixtureEntry(name, new byte[0], target);
  }

  private static List<FixtureEntry> completeEntries(String apiContent) {
    return List.of(
        entry("CMakeLists.txt", "root"),
        entry("LICENSE", "license"),
        entry("cmake/show-info.cmake", "info"),
        entry("sherpa-onnx/CMakeLists.txt", "sherpa"),
        entry("sherpa-onnx/csrc/CMakeLists.txt", "csrc"),
        entry("sherpa-onnx/jni/CMakeLists.txt", "jni"),
        entry("sherpa-onnx/kotlin-api/OnlineRecognizer.kt", apiContent));
  }

  private static void writeArchive(Path archive, List<FixtureEntry> entries) throws IOException {
    try (OutputStream fileOutput = Files.newOutputStream(archive);
        GzipCompressorOutputStream gzipOutput = new GzipCompressorOutputStream(fileOutput);
        TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(gzipOutput)) {
      for (FixtureEntry fixture : entries) {
        TarArchiveEntry archiveEntry;
        if (fixture.linkTarget() == null) {
          archiveEntry = new TarArchiveEntry(ARCHIVE_ROOT + "/" + fixture.name());
        } else {
          archiveEntry =
              new TarArchiveEntry(ARCHIVE_ROOT + "/" + fixture.name(), TarArchiveEntry.LF_SYMLINK);
          archiveEntry.setLinkName(fixture.linkTarget());
        }
        archiveEntry.setSize(fixture.content().length);
        tarOutput.putArchiveEntry(archiveEntry);
        tarOutput.write(fixture.content());
        tarOutput.closeArchiveEntry();
      }
    }
  }

  private BuildTask requiredTask(BuildResult result) {
    BuildTask task = result.task(":extractSherpaSource");
    assertNotNull(task);
    return task;
  }

  private void assertNoTransactionResidue() throws IOException {
    Path build = projectDirectory.resolve("build");
    if (!Files.isDirectory(build)) {
      return;
    }
    try (var paths = Files.list(build)) {
      assertFalse(
          paths.anyMatch(
              path -> {
                String name = path.getFileName().toString();
                return name.startsWith("extracted.partial-")
                    || name.startsWith("extracted.backup-");
              }));
    }
  }

  private void assertNoPublishedOutput() throws IOException {
    Path output = projectDirectory.resolve("build/extracted");
    if (!Files.exists(output)) {
      return;
    }
    try (var paths = Files.walk(output)) {
      assertTrue(paths.skip(1).findAny().isEmpty());
    }
  }

  private static void deleteRecursively(Path target) throws IOException {
    try (var paths = Files.walk(target)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  private record FixtureEntry(String name, byte[] content, String linkTarget) {}
}
