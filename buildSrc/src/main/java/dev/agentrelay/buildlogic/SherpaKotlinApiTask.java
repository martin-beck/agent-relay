package dev.agentrelay.buildlogic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Selects the minimal pinned sherpa Kotlin JNI API required by the speech adapter. */
@CacheableTask
public abstract class SherpaKotlinApiTask extends DefaultTask {
  private static final List<String> API_FILES =
      List.of(
          "FeatureConfig.kt",
          "HomophoneReplacerConfig.kt",
          "OnlineRecognizer.kt",
          "OnlineStream.kt",
          "QnnConfig.kt");

  @Inject
  protected abstract FileSystemOperations getFileSystemOperations();

  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getSourceDirectory();

  @OutputDirectory
  public abstract DirectoryProperty getOutputDirectory();

  @TaskAction
  public final void generate() throws IOException {
    Path source = getSourceDirectory().get().getAsFile().toPath().resolve("sherpa-onnx/kotlin-api");
    Path output = getOutputDirectory().get().getAsFile().toPath();

    getFileSystemOperations()
        .sync(
            spec -> {
              spec.from(source);
              spec.into(output);
              spec.include(API_FILES);
              spec.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
            });

    List<String> actual;
    try (var files = Files.list(output)) {
      actual =
          files
              .filter(Files::isRegularFile)
              .map(path -> path.getFileName().toString())
              .sorted()
              .toList();
    }
    if (!actual.equals(API_FILES.stream().sorted().toList())) {
      throw new IOException("Generated sherpa Kotlin API was incomplete");
    }
  }
}
