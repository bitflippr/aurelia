import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
  id("aurelia.compose")
  id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
  id("org.jlleitschuh.gradle.ktlint")
}

fun environmentValue(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val configuredVersionCode = environmentValue("AURELIA_VERSION_CODE")
val appVersionCode =
  configuredVersionCode?.toIntOrNull()?.takeIf { it > 0 }
    ?: if (configuredVersionCode == null) {
      1
    } else {
      throw GradleException("AURELIA_VERSION_CODE must be a positive integer")
    }
val appVersionName = environmentValue("AURELIA_VERSION_NAME") ?: "0.1.0"

kotlin {
  jvmToolchain(17)
}

android {
  namespace = "com.aurelia.app"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aurelia.app"
    minSdk = 34
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersionName
    vectorDrawables {
      useSupportLibrary = true
    }
  }

  buildFeatures {
    compose = true
  }

  composeOptions {
    kotlinCompilerExtensionVersion = "1.5.15"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }

  buildTypes {
    debug {
    }
    getByName("release") {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
      signingConfig = signingConfigs.getByName("debug")
    }
    create("fast") {
      initWith(getByName("debug"))
      isDebuggable = false
      isMinifyEnabled = true
      isDefault = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  buildToolsVersion = "36.0.0"
  ndkVersion = "29.0.14206865"
}

android.sourceSets["main"]
  .jniLibs.directories
  .add("src/main/jniLibs")

val projectRoot: String by lazy {
  file("$rootDir/../../..").canonicalPath
}

tasks.register<Exec>("buildRustHost") {
  workingDir = file(projectRoot)
  commandLine("cargo", "build", "-p", "aurelia-core")
}

tasks.register<Exec>("generateUniffiBindings") {
  val libName =
    if (org.gradle.internal.os.OperatingSystem
        .current()
        .isWindows
    ) {
      "aurelia_core.dll"
    } else if (org.gradle.internal.os.OperatingSystem
        .current()
        .isMacOsX
    ) {
      "libaurelia_core.dylib"
    } else {
      "libaurelia_core.so"
    }
  workingDir = file(projectRoot)
  commandLine(
    "cargo",
    "run",
    "-p",
    "uniffi-bindgen",
    "--",
    "generate",
    "--library",
    "target/debug/$libName",
    "--language",
    "kotlin",
    "--config",
    "apps/mobile/android/app/src/main/java/uniffi/aurelia_core/uniffi.toml",
    "--out-dir",
    "apps/mobile/android/app/src/main/java",
    "--no-format",
  )
  dependsOn("buildRustHost")
}

tasks.register<Exec>("buildRustAndroid") {
  val properties = Properties()
  val localPropertiesFile = file("$rootDir/local.properties")
  if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { properties.load(it) }
  }
  val ndkDir =
    properties.getProperty("cargo.ndk.dir")
      ?: properties.getProperty("ndk.dir")
      ?: System.getenv("ANDROID_NDK_HOME")
      ?: throw GradleException(
        "NDK not found. Set cargo.ndk.dir or ndk.dir in local.properties or ANDROID_NDK_HOME env var",
      )

  environment("ANDROID_NDK_HOME", ndkDir)
  workingDir = file(projectRoot)
  commandLine(
    "cargo",
    "ndk",
    "-t",
    "arm64-v8a",
    "-t",
    "x86_64",
    "-o",
    "apps/mobile/android/app/src/main/jniLibs",
    "build",
    "-p",
    "aurelia-core",
    "--release",
  )
}

tasks.register("copyUniffiLibs") {
  dependsOn("buildRustAndroid")
  doLast {
    val archs = listOf("arm64-v8a", "x86_64")
    archs.forEach { arch ->
      val srcFile = file("src/main/jniLibs/$arch/libaurelia_core.so")
      // The library name must match what UniFFI bindings expect: "aurelia_core"
      // which translates to "libaurelia_core.so" on Android
      // Keep the original filename - do not rename it
      if (!srcFile.exists()) {
        throw GradleException("Rust library not found: ${srcFile.absolutePath}. Run 'cargo ndk' build first.")
      }
    }
  }
}

tasks.named("preBuild") {
  dependsOn("generateUniffiBindings", "copyUniffiLibs")
}

dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2026.01.00")
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation("androidx.activity:activity-compose:1.12.2")
  implementation("androidx.compose.material3:material3:1.5.0-alpha12")
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.animation:animation-graphics")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
  implementation("androidx.navigation:navigation-compose:2.9.6")
  implementation("androidx.media3:media3-exoplayer:1.9.0")
  implementation("androidx.media3:media3-common:1.9.0")
  implementation("androidx.media3:media3-session:1.9.0")
  implementation("com.google.android.material:material:1.13.0")
  //noinspection Aligned16KB
  implementation("net.java.dev.jna:jna:5.18.1@aar")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation("io.coil-kt:coil-compose:2.7.0")
  implementation("androidx.palette:palette:1.0.0")
  implementation("com.squareup.okhttp3:okhttp:5.3.2")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
  implementation("com.google.ai.edge.litertlm:litertlm-android:0.12.0")
  implementation("androidx.graphics:graphics-shapes:1.1.0")
  implementation("androidx.work:work-runtime-ktx:2.10.1")

  debugImplementation("androidx.compose.ui:ui-tooling")

  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
