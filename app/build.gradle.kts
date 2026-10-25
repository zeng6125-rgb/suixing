plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.suixing.agent"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.suixing.agent"
        minSdk = 26
        targetSdk = 37
        versionCode = 523
        versionName = "0.8.500"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        create("sideload") {
            storeFile = file("sideload.keystore")
            // 别名/密码是更名前的旧名，**不能改**：app/sideload.keystore 就是用这个名字生成的，
            // 改了等于换签名 ⇒ 已安装的版本无法覆盖安装。
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

    // **Kotlin 2.4 起 `kotlinOptions` 已被移除**（报 "Using 'jvmTarget: String' is an error"），
    // 迁移到 compilerOptions DSL。本次升 BOM 2026.09.00 + Kotlin 2.4.21 时同步改的。
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // **Compose 编译器度量**（性能审计用）：产出模块报告与类报告，逐个 Composable
            // 标明 skippable/restartable。是判断「有没有实际优化」的唯一硬指标 —— 只写文件，
            // 不影响产物行为。报告位置：app/build/compose-reports/。
            freeCompilerArgs.addAll(
                "-P",
                "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" +
                    layout.buildDirectory.get().asFile.path + "/compose-reports",
                "-P",
                "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=" +
                    layout.buildDirectory.get().asFile.path + "/compose-metrics",
            )
        }
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

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // **图片加载换 Coil 3**（issue #44 第 10 项）：替代手写的 BitmapFactory 降采样 +
    // 容量 4 的 LRU。Coil 提供内存/磁盘双缓存、按目标尺寸自动采样、生命周期感知，
    // 且解码在自己 Dispatcher 上（组合期零阻塞）。只装 compose 适配，不装网络层：
    // 随行的图片都来自内存里的 base64，没有远程 URL。
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")

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


































































