plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.suixing.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.suixing.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 246
        versionName = "0.8.235"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        create("sideload") {
            storeFile = file("sideload.keystore")
            storePassword = "lingshu"
            keyAlias = "lingshu"
            keyPassword = "lingshu"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // A stable self-signed key is committed for sideload builds so that
            // successive CI artifacts can be installed over each other.
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // bash / busybox 以 `jniLibs/arm64-v8a/libbash.so`、`libbusybox.so` 随包分发（GPL 静态
        // 二进制，来源 Debian bash-static / busybox-static，AArch64）。**必须 legacy packaging**：
        // AGP 默认把 .so 留在 APK 里靠 mmap 加载，那样没有可执行文件路径，execve 起不来。
        // 解压后它们落在 nativeLibraryDir —— 那是 Android 10+ 唯一允许 app 执行自己文件的目录
        // （filesDir 因 W^X 被禁）。
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // MockK stands in for the Android-bound collaborators (CapabilityHub hooks Shizuku in
    // its init, SkillStore/MemoryStore want a Context) without ever running their
    // constructors, which is what makes engine-level tests possible on the JVM.
    testImplementation("io.mockk:mockk:1.13.13")
}


































































