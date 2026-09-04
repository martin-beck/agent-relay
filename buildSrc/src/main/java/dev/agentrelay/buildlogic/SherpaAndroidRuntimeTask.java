package dev.agentrelay.buildlogic;

import java.io.File;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

/** Builds and validates the pinned, TTS-free sherpa-onnx JNI runtime for Android. */
@CacheableTask
public abstract class SherpaAndroidRuntimeTask extends DefaultTask {
  @Inject
  protected abstract ExecOperations getExecOperations();

  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getSourceDirectory();

  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getOnnxRuntimeDirectory();

  @InputFile
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract RegularFileProperty getBuildScript();

  @InputFile
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract RegularFileProperty getMetadataPatch();

  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getOnnxRuntimeLicense();

  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getOnnxRuntimeNotices();

  @Internal
  public abstract DirectoryProperty getNdkDirectory();

  @Internal
  public abstract Property<String> getCmakeExecutable();

  @Internal
  public abstract Property<String> getNinjaExecutable();

  @Input
  public abstract Property<String> getSherpaVersion();

  @Input
  public abstract Property<String> getSherpaSourceRevision();

  @Input
  public abstract Property<String> getSherpaSourceArchiveSha256();

  @Input
  public abstract Property<String> getSourceDateEpoch();

  @Input
  public abstract Property<String> getOnnxRuntimeVersion();

  @Input
  public abstract Property<String> getOnnxRuntimeSourceRevision();

  @Input
  public abstract Property<String> getOnnxRuntimeBinaryRevision();

  @Input
  public abstract Property<String> getOnnxRuntimeArchiveSha256();

  @Input
  public abstract Property<String> getNdkVersion();

  @Input
  public abstract Property<String> getCmakeVersion();

  @Input
  public abstract Property<String> getNinjaVersion();

  @OutputDirectory
  public abstract DirectoryProperty getJniDirectory();

  @OutputDirectory
  public abstract DirectoryProperty getResourcesDirectory();

  @TaskAction
  public final void buildRuntime() {
    File script = getBuildScript().get().getAsFile();
    getExecOperations()
        .exec(
            spec -> {
              spec.setExecutable("bash");
              spec.args(
                  script.getAbsolutePath(),
                  "--source-dir",
                  getSourceDirectory().get().getAsFile().getAbsolutePath(),
                  "--onnx-dir",
                  getOnnxRuntimeDirectory().get().getAsFile().getAbsolutePath(),
                  "--metadata-patch",
                  getMetadataPatch().get().getAsFile().getAbsolutePath(),
                  "--onnx-license",
                  getOnnxRuntimeLicense().get().getAsFile().getAbsolutePath(),
                  "--onnx-notices",
                  getOnnxRuntimeNotices().get().getAsFile().getAbsolutePath(),
                  "--ndk-dir",
                  getNdkDirectory().get().getAsFile().getAbsolutePath(),
                  "--cmake",
                  getCmakeExecutable().get(),
                  "--ninja",
                  getNinjaExecutable().get(),
                  "--jni-output",
                  getJniDirectory().get().getAsFile().getAbsolutePath(),
                  "--resources-output",
                  getResourcesDirectory().get().getAsFile().getAbsolutePath(),
                  "--work-dir",
                  getTemporaryDir().getAbsolutePath(),
                  "--sherpa-version",
                  getSherpaVersion().get(),
                  "--sherpa-revision",
                  getSherpaSourceRevision().get(),
                  "--sherpa-archive-sha256",
                  getSherpaSourceArchiveSha256().get(),
                  "--source-date-epoch",
                  getSourceDateEpoch().get(),
                  "--onnx-version",
                  getOnnxRuntimeVersion().get(),
                  "--onnx-source-revision",
                  getOnnxRuntimeSourceRevision().get(),
                  "--onnx-binary-revision",
                  getOnnxRuntimeBinaryRevision().get(),
                  "--onnx-archive-sha256",
                  getOnnxRuntimeArchiveSha256().get(),
                  "--ndk-version",
                  getNdkVersion().get(),
                  "--cmake-version",
                  getCmakeVersion().get(),
                  "--ninja-version",
                  getNinjaVersion().get());
            })
        .assertNormalExitValue();
  }
}
