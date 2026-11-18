import org.gradle.internal.os.OperatingSystem
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

abstract class RustAndroidCompile : Exec() {
    @get:OutputDirectory
    abstract val jniDirectory: DirectoryProperty
}

abstract class RustAndroidGlue : Exec() {
    @get:OutputDirectory
    abstract val classesDirectory: DirectoryProperty
}

val rustProduction = path == ":app"

val rustNdkVersion = "28.2.13676358"
android {
    namespace = "com.suixing.agent.rust"
    compileSdk = 37
    ndkVersion = rustNdkVersion
    defaultConfig {
        applicationId = if (rustProduction) "com.suixing.agent" else "com.suixing.agent.rust"
        minSdk = 26
        targetSdk = 37
        versionCode = if (rustProduction) providers.gradleProperty("jagent.versionCode").get().toInt() else 1
        versionName = if (rustProduction) providers.gradleProperty("jagent.versionName").get() else "0.1.0"
        manifestPlaceholders["jagentLabel"] = if (rustProduction) "JAgent" else "JAgent Rust"
        val oldComponents = mapOf(
            "AgentService" to "com.suixing.agent.service.AgentForegroundService",
            "TerminalService" to "com.suixing.agent.service.TerminalForegroundService",
            "SchedulerService" to "com.suixing.agent.service.SchedulerService",
            "FloatConsoleService" to "com.suixing.agent.service.FloatConsoleService",
            "AccessibilityService" to "com.suixing.agent.service.SuixingAccessibilityService",
            "BootReceiver" to "com.suixing.agent.service.BootReceiver",
            "VirtualScreenActivity" to "com.suixing.agent.virtualscreen.VirtualScreenActivity",
        )
        oldComponents.forEach { (key, old) ->
            manifestPlaceholders["jagent$key"] = if (rustProduction) old else "com.suixing.agent.rust.platform.Rust$key"
        }
        manifestPlaceholders["jagentLauncher"] = if (rustProduction) "com.suixing.agent.MainActivity" else "com.suixing.agent.rust.platform.RustLauncher"
    }
    signingConfigs {
        create("sideload") {
            storeFile = rootProject.file("app/sideload.keystore")
            storePassword = "lingshu"
            keyAlias = "lingshu"
            keyPassword = "lingshu"
        }
    }
    buildFeatures { buildConfig = false }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (rustProduction) signingConfig = signingConfigs.getByName("sideload")
        }
    }
    sourceSets.getByName("main").jniLibs.srcDir(rootProject.file("app/src/main/jniLibs"))
    sourceSets.getByName("main").assets.srcDir(rootProject.file("app/src/main/assets"))
    packaging.jniLibs.useLegacyPackaging = true
}

val isWindows = OperatingSystem.current().isWindows
val nativeHost = if (isWindows) "windows-x86_64" else if (OperatingSystem.current().isMacOsX) "darwin-x86_64" else "linux-x86_64"
val rustSource = layout.projectDirectory.file("Cargo.toml")
val rustOutput = layout.buildDirectory.dir("cargo")
val sdkRoot = androidComponents.sdkComponents.sdkDirectory
val targets = linkedMapOf(
    "arm64-v8a" to ("aarch64-linux-android" to "aarch64-linux-android"),
    "armeabi-v7a" to ("armv7-linux-androideabi" to "armv7a-linux-androideabi"),
    "x86" to ("i686-linux-android" to "i686-linux-android"),
    "x86_64" to ("x86_64-linux-android" to "x86_64-linux-android"),
)
val compileRustLibraries = tasks.register<RustAndroidCompile>("compileRustLibrariesRelease") {
    group = "build"
    description = "Compile and link the Rust application for the four Android ABIs."
    inputs.files(fileTree("src") { include("**/*.rs") }, file("Cargo.toml"), file("Cargo.lock"), file("rust-toolchain.toml"))
    inputs.files(rootProject.fileTree("app/src/main/rust/suixing-search/src") { include("**/*.rs") })
    inputs.files(rootProject.file("app/src/main/rust/suixing-search/Cargo.toml"))
    // The Rust frontend embeds these source files into each ABI library.
    inputs.files(
        rootProject.file("THIRD_PARTY_NOTICES.md"),
        rootProject.file("app/src/main/res/drawable-xxxhdpi/brand_mark_round.png"),
        file("src/ui/chat-icons.png"),
    )
    val outputLibraries = targets.mapValues { (_, pair) ->
        rustOutput.map { it.file("${pair.first}/release/libsuixing_rust_app.so") }
    }
    outputs.files(outputLibraries.values)
    jniDirectory.set(layout.buildDirectory.dir("generated/rustJniRelease"))
    workingDir(projectDir)
    doFirst {
        val ndk = sdkRoot.get().dir("ndk/$rustNdkVersion").asFile
        val tools = ndk.resolve("toolchains/llvm/prebuilt/$nativeHost/bin")
        val archiver = tools.resolve("llvm-ar${if (isWindows) ".exe" else ""}")
        targets.values.forEach { (target, clangTarget) ->
            val clang = tools.resolve("${clangTarget}26-clang${if (isWindows) ".cmd" else ""}")
            check(clang.isFile) { "The prepared NDK compiler is missing: $clang" }
            environment("CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER", clang.absolutePath)
            environment("CC_${target.replace('-', '_')}", clang.absolutePath)
            environment("AR_${target.replace('-', '_')}", archiver.absolutePath)
        }
        environment("ANDROID_NDK_HOME", ndk.absolutePath)
        environment("RUSTFLAGS", "-C relocation-model=pic -C link-arg=-Wl,-z,max-page-size=16384")
    }
    // Keep all targets in one Cargo invocation so they share one build graph.
    commandLine(
        listOf("rustup", "run", "1.100.0", "cargo", "build", "--manifest-path", rustSource.asFile.absolutePath,
            "--release", "--locked", "--offline", "--target-dir", rustOutput.get().asFile.absolutePath,
            "--jobs", gradle.startParameter.maxWorkerCount.coerceIn(1, 4).toString()) +
            targets.values.flatMap { (target, _) -> listOf("--target", target) },
    )
    doLast {
        outputLibraries.forEach { (abi, outputLibrary) ->
            copy {
                from(outputLibrary)
                into(jniDirectory.dir(abi))
            }
        }
    }
}
val compileRustRelease = tasks.register("compileRustRelease") {
    group = "build"
    description = "Compile and link the complete Rust frontend for the four Android ABIs."
    dependsOn(compileRustLibraries)
}
val generateRustAndroidGlue = tasks.register<RustAndroidGlue>("generateRustAndroidGlueRelease") {
    group = "build"
    description = "Compile the Rust Android framework class generator and produce platform entry classes."
    inputs.files(fileTree("android-glue/src") { include("**/*.rs") },
        file("android-glue/Cargo.toml"), file("android-glue/Cargo.lock"))
    classesDirectory.set(layout.buildDirectory.dir("generated/rustAndroidClasses/release"))
    workingDir(projectDir)
    doFirst {
        // ScopedArtifact.CLASSES owns the final output path. Resolve it after
        // the variant artifact operation has configured this task.
        // A copied source may retain a timestamp older than Cargo's cached
        // executable. Bind Cargo's compiler fingerprint to the same contents
        // Gradle observes, so those copies cannot silently emit older classes.
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        inputs.files.files.filter { it.isFile }.sortedBy { it.absolutePath }.forEach { sourceDigest.update(it.readBytes()) }
        val sourceIdentity = sourceDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        environment("RUSTFLAGS", "-C metadata=jagent_glue_$sourceIdentity")
        commandLine("rustup", "run", "1.100.0", "cargo", "run", "--manifest-path",
            file("android-glue/Cargo.toml").absolutePath, "--release", "--locked", "--offline",
            "--target-dir", layout.buildDirectory.dir("androidGlueRunner").get().asFile.absolutePath,
            "--", "--output", classesDirectory.get().asFile.absolutePath,
            "--package", "com.suixing.agent.rust.platform", "--library", "suixing_rust_app")
    }
}
compileRustRelease.configure { dependsOn(generateRustAndroidGlue) }
// The Rust framework classes and native libraries are wired for Release only.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    variant.enable = false
}
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
        .use(generateRustAndroidGlue)
        .toAppend(ScopedArtifact.CLASSES) { it.classesDirectory }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(compileRustLibraries) { it.jniDirectory }
}
