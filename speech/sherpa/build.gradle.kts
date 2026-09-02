import dev.agentrelay.buildlogic.OnnxRuntimeExtractTask
import dev.agentrelay.buildlogic.SherpaAndroidRuntimeTask
import dev.agentrelay.buildlogic.SherpaKotlinApiTask
import dev.agentrelay.buildlogic.SherpaSourceExtractTask
import dev.agentrelay.buildlogic.VerifiedDownloadTask

plugins {
    alias(libs.plugins.android.library)
}

val sherpaOnnxVersion = libs.versions.sherpaOnnx.get()
val sherpaSourceRevisionPinned = "142807252687d81b40d6315f23470a1512a00de3"
val sherpaSourceDateEpochPinned = "1783423294"
val sherpaSourceArchiveSha256Pinned = "f0dc7c9b41b8691313daee671e826eb23946fa1320559a8d37e84f8774af76b2"
val sherpaSourceArchiveBytes = 9_840_362L
val sherpaSourceArchiveRoot = "sherpa-onnx-$sherpaSourceRevisionPinned"
val onnxRuntimeVersionPinned = "1.27.0"
val onnxRuntimeSourceRevisionPinned = "8f0278c77bf44b0cc83c098c6c722b92a36ac4b5"
val onnxRuntimeBinaryRevisionPinned = "05ffc5a560d4e74cf6a80e86dfc5800a1474cce1"
val onnxRuntimeArchiveSha256Pinned = "a78f303a26b5e75c84c8b2a97fa2ddb400b2d1b5e069bec19aa229ccd3597fdb"
val onnxRuntimeArchiveBytes = 32_794_323L
val ndkVersionPinned = "28.2.13676358"
val cmakeVersionPinned = "3.28.3"
val ninjaVersionPinned = "1.11.1"
val verifiedDependencies = layout.buildDirectory.dir("verified-dependencies")

val provisionSherpaSource =
    tasks.register<VerifiedDownloadTask>("provisionSherpaSource") {
        downloadUrl.set(
            "https://github.com/k2-fsa/sherpa-onnx/archive/$sherpaSourceRevisionPinned.tar.gz",
        )
        expectedSha256.set(sherpaSourceArchiveSha256Pinned)
        expectedSizeBytes.set(sherpaSourceArchiveBytes)
        allowedRedirectHosts.set(listOf("github.com", "codeload.github.com"))
        outputFile.set(
            verifiedDependencies.map {
                it.file("sherpa-onnx-$sherpaSourceRevisionPinned.tar.gz")
            },
        )
    }

val provisionOnnxRuntime =
    tasks.register<VerifiedDownloadTask>("provisionOnnxRuntime") {
        downloadUrl.set(
            "https://github.com/csukuangfj/onnxruntime-libs/releases/download/" +
                "v$onnxRuntimeVersionPinned/onnxruntime-android-$onnxRuntimeVersionPinned.zip",
        )
        expectedSha256.set(onnxRuntimeArchiveSha256Pinned)
        expectedSizeBytes.set(onnxRuntimeArchiveBytes)
        allowedRedirectHosts.set(
            listOf(
                "github.com",
                "release-assets.githubusercontent.com",
            ),
        )
        outputFile.set(
            verifiedDependencies.map {
                it.file("onnxruntime-android-$onnxRuntimeVersionPinned.zip")
            },
        )
    }

val provisionOnnxRuntimeLicense =
    tasks.register<VerifiedDownloadTask>("provisionOnnxRuntimeLicense") {
        downloadUrl.set(
            "https://raw.githubusercontent.com/microsoft/onnxruntime/" +
                "v$onnxRuntimeVersionPinned/LICENSE",
        )
        expectedSha256.set("2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c")
        expectedSizeBytes.set(1_073L)
        allowedRedirectHosts.set(listOf("raw.githubusercontent.com"))
        outputFile.set(
            verifiedDependencies.map {
                it.file("onnxruntime-$onnxRuntimeVersionPinned-LICENSE")
            },
        )
    }

val provisionOnnxRuntimeNotices =
    tasks.register<VerifiedDownloadTask>("provisionOnnxRuntimeNotices") {
        downloadUrl.set(
            "https://raw.githubusercontent.com/microsoft/onnxruntime/" +
                "v$onnxRuntimeVersionPinned/ThirdPartyNotices.txt",
        )
        expectedSha256.set("0e07b95f3a8d6230037707c5c4a2b554d12c4cb67369669ac255635528ffcee2")
        expectedSizeBytes.set(325_054L)
        allowedRedirectHosts.set(listOf("raw.githubusercontent.com"))
        outputFile.set(
            verifiedDependencies.map {
                it.file("onnxruntime-$onnxRuntimeVersionPinned-ThirdPartyNotices.txt")
            },
        )
    }

val extractedSherpaSource = layout.buildDirectory.dir("verified-sources/sherpa-onnx")
val extractSherpaSource =
    tasks.register<SherpaSourceExtractTask>("extractSherpaSource") {
        dependsOn(provisionSherpaSource)
        inputArchive.set(provisionSherpaSource.flatMap { it.outputFile })
        archiveRoot.set(sherpaSourceArchiveRoot)
        outputDirectory.set(extractedSherpaSource)
    }

val extractedOnnxRuntime = layout.buildDirectory.dir("verified-sources/onnxruntime")
val extractOnnxRuntime =
    tasks.register<OnnxRuntimeExtractTask>("extractOnnxRuntime") {
        dependsOn(provisionOnnxRuntime)
        inputArchive.set(provisionOnnxRuntime.flatMap { it.outputFile })
        outputDirectory.set(extractedOnnxRuntime)
    }

val generatedSherpaApi = layout.buildDirectory.dir("generated/sherpa/kotlin")
val generateSherpaKotlinApi =
    tasks.register<SherpaKotlinApiTask>("generateSherpaKotlinApi") {
        dependsOn(extractSherpaSource)
        sourceDirectory.set(extractedSherpaSource)
        outputDirectory.set(generatedSherpaApi)
    }

val buildSherpaAndroidRuntime =
    tasks.register<SherpaAndroidRuntimeTask>("buildSherpaAndroidRuntime") {
        dependsOn(extractSherpaSource, extractOnnxRuntime)
        sourceDirectory.set(extractedSherpaSource)
        onnxRuntimeDirectory.set(extractedOnnxRuntime)
        buildScript.set(layout.projectDirectory.file("native/build-android-runtime.sh"))
        metadataPatch.set(
            layout.projectDirectory.file(
                "native/patches/0001-reproducible-build-metadata.patch",
            ),
        )
        onnxRuntimeLicense.set(provisionOnnxRuntimeLicense.flatMap { it.outputFile })
        onnxRuntimeNotices.set(provisionOnnxRuntimeNotices.flatMap { it.outputFile })
        ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
        cmakeExecutable.set("cmake")
        ninjaExecutable.set("ninja")
        sherpaVersion.set(sherpaOnnxVersion)
        sherpaSourceRevision.set(sherpaSourceRevisionPinned)
        sherpaSourceArchiveSha256.set(sherpaSourceArchiveSha256Pinned)
        sourceDateEpoch.set(sherpaSourceDateEpochPinned)
        onnxRuntimeVersion.set(onnxRuntimeVersionPinned)
        onnxRuntimeSourceRevision.set(onnxRuntimeSourceRevisionPinned)
        onnxRuntimeBinaryRevision.set(onnxRuntimeBinaryRevisionPinned)
        onnxRuntimeArchiveSha256.set(onnxRuntimeArchiveSha256Pinned)
        ndkVersion.set(ndkVersionPinned)
        cmakeVersion.set(cmakeVersionPinned)
        ninjaVersion.set(ninjaVersionPinned)
        jniDirectory.set(layout.buildDirectory.dir("generated/sherpa/runtime/jni"))
        resourcesDirectory.set(
            layout.buildDirectory.dir("generated/sherpa/runtime/resources"),
        )
    }

android {
    namespace = "dev.agentrelay.speech.sherpa"
    compileSdk = 36
    ndkVersion = ndkVersionPinned

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources.enable = false

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        requireNotNull(variant.sources.kotlin).addGeneratedSourceDirectory(
            generateSherpaKotlinApi,
        ) { task -> task.outputDirectory }
        requireNotNull(variant.sources.jniLibs).addGeneratedSourceDirectory(
            buildSherpaAndroidRuntime,
        ) { task -> task.jniDirectory }
        requireNotNull(variant.sources.resources).addGeneratedSourceDirectory(
            buildSherpaAndroidRuntime,
        ) { task -> task.resourcesDirectory }
    }
}

dependencies {
    api(project(":speech:android"))
    api(libs.kotlinx.coroutines.core)
    implementation(project(":speech:api"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
