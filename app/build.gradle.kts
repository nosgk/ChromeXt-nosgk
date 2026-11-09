plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  compileSdk = 35
  namespace = "org.matrix.chromext"

  defaultConfig {
    applicationId = "org.matrix.chromext"
    minSdk = 21
    targetSdk = 35
    versionCode = 33
    versionName = "3.15.7"

    // Compose ships a graphics-path native library. Publish the arm64-v8a slice only, which is
    // what the release artifact name promises.
    ndk { abiFilters += "arm64-v8a" }
  }

  buildFeatures {
    buildConfig = true
    compose = true
  }

  buildTypes {
    release {
      isShrinkResources = true
      isMinifyEnabled = true
      proguardFiles("proguard-rules.pro")
    }
  }

  androidResources {
    additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x42")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  lint {
    disable +=
        listOf(
            "Internationalization",
            "UnsafeIntentLaunch",
            "SetJavaScriptEnabled",
            "UnspecifiedRegisterReceiverFlag",
            "Usability:Icons",
        )
  }

  testOptions {
    unitTests {
      isReturnDefaultValues = true
    }
  }
}

dependencies {
  // Modern libxposed API (LSPosed 1.10+/Vector/current LSPatch). compileOnly: the framework
  // provides the classes at runtime, and bundling them would break the module.
  compileOnly("io.github.libxposed:api:102.0.0")

  val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
  implementation(composeBom)
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

  debugImplementation("androidx.compose.ui:ui-tooling")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")
  testImplementation("org.robolectric:robolectric:4.14.1")
  testImplementation("androidx.test:core:1.6.1")
}
