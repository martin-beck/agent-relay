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
    return new FixtureEntry(name, content.getBytes(StandardCharsets.UTF_8));
  }

  private static void writeArchive(Path archive, List<FixtureEntry> entries) throws IOException {
    try (OutputStream fileOutput = Files.newOutputStream(archive);
        GzipCompressorOutputStream gzipOutput = new GzipCompressorOutputStream(fileOutput);
        TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(gzipOutput)) {
      for (FixtureEntry fixture : entries) {
        TarArchiveEntry archiveEntry = new TarArchiveEntry(ARCHIVE_ROOT + "/" + fixture.name());
        archiveEntry.setSize(fixture.content().length);
        tarOutput.putArchiveEntry(archiveEntry);
        tarOutput.write(fixture.content());
        tarOutput.closeArchiveEntry();
      }
    }
  }

  private static void deleteRecursively(Path target) throws IOException {
    try (var paths = Files.walk(target)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  private record FixtureEntry(String name, byte[] content) {}
}
