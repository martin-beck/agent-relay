package dev.agentrelay.buildlogic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Extracts only headers and the four verified Android libraries from ONNX Runtime. */
@CacheableTask
public abstract class OnnxRuntimeExtractTask extends DefaultTask {
    private static final List<String> ABIS =
            List.of("arm64-v8a", "armeabi-v7a", "x86", "x86_64");

    @Inject
    protected abstract ArchiveOperations getArchiveOperations();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputArchive();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public final void extract() throws IOException {
        Path output = getOutputDirectory().get().getAsFile().toPath();

        getFileSystemOperations()
                .sync(
                        spec -> {
                            spec.from(
                                    getArchiveOperations()
                                            .zipTree(
                                                    getInputArchive()
                                                            .get()
                                                            .getAsFile()));
                            spec.include("headers/**");
                            for (String abi : ABIS) {
                                spec.include("jni/" + abi + "/libonnxruntime.so");
                            }
                            spec.setIncludeEmptyDirs(false);
                            spec.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
                            spec.into(output);
                        });

        requireFile(output.resolve("headers/onnxruntime_c_api.h"));
        for (String abi : ABIS) {
            requireFile(output.resolve("jni/" + abi + "/libonnxruntime.so"));
        }
        try (var paths = Files.walk(output)) {
            if (paths.anyMatch(Files::isSymbolicLink)) {
                throw new IOException("ONNX Runtime extraction contained a symbolic link");
            }
        }
    }

    private static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("ONNX Runtime extraction was incomplete");
        }
    }
}
