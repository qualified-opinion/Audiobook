plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "com.janreins.audiobook"
  compileSdk { version = release(37) }

  defaultConfig {
    applicationId = "com.janreins.audiobook"
    minSdk = 26
    targetSdk = 36
    // The release workflow passes AUDIOBOOK_VERSION_CODE (100 + run number, so always above the
    // old hard-coded 2) and AUDIOBOOK_VERSION_NAME (the tag without its "v", or 1.1.<run>).
    // Local builds keep 2 / 1.1.0.
    versionCode = providers.environmentVariable("AUDIOBOOK_VERSION_CODE").orNull?.let {
      it.toIntOrNull() ?: throw GradleException("AUDIOBOOK_VERSION_CODE must be a whole number, got '$it'.")
    } ?: 2
    versionName = providers.environmentVariable("AUDIOBOOK_VERSION_NAME").orNull?.takeIf { it.isNotBlank() } ?: "1.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    // Release key from environment variables (set by .github/workflows/release.yml from the
    // repository secrets, or by hand). There is deliberately no fallback (no debug key, no default
    // alias, no unsigned build): a release APK signed with anything else can't be installed over
    // the previous release. If the variables are missing, checkReleaseSigning (below) stops any
    // release packaging task with a clear message. Debug builds, tests and lint don't need them.
    create("release") {
      storeFile = providers.environmentVariable("AUDIOBOOK_KEYSTORE_FILE").orNull
        ?.takeIf { it.isNotBlank() }?.let { file(it) }
      storePassword = providers.environmentVariable("AUDIOBOOK_KEYSTORE_PASSWORD").orNull
      keyAlias = providers.environmentVariable("AUDIOBOOK_KEY_ALIAS").orNull
      keyPassword = providers.environmentVariable("AUDIOBOOK_KEY_PASSWORD").orNull
    }
  }

  buildTypes {
    release {
      signingConfig = signingConfigs.getByName("release")
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Fails release packaging (assembleRelease, bundleRelease, ...) when the release key isn't
// configured. The check runs at execution time, so configuring the project, debug builds,
// unit tests and lint all work without the variables.
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
  group = "verification"
  description = "Fails unless the AUDIOBOOK_* release signing environment variables are set."
  // Locals only (no script-level vals) so the doLast block is configuration-cache safe.
  val releaseSigningVars = listOf(
    "AUDIOBOOK_KEYSTORE_FILE",
    "AUDIOBOOK_KEYSTORE_PASSWORD",
    "AUDIOBOOK_KEY_ALIAS",
    "AUDIOBOOK_KEY_PASSWORD",
  )
  val releaseSigningEnv = releaseSigningVars.associateWith { providers.environmentVariable(it) }
  val appProjectDir = layout.projectDirectory
  doLast {
    val missing = releaseSigningEnv.filterValues { it.orNull.isNullOrBlank() }.keys
    if (missing.isNotEmpty()) {
      throw GradleException(
        "Release signing is not configured: missing ${missing.joinToString()}. " +
          "Set ${releaseSigningVars.joinToString()} to sign the release build " +
          "(release builds are never signed with the debug key or left unsigned)."
      )
    }
    // Resolved like file(...) in the signing config: relative paths are relative to app/.
    val keystore = appProjectDir.file(releaseSigningEnv.getValue("AUDIOBOOK_KEYSTORE_FILE").get()).asFile
    if (!keystore.isFile) {
      throw GradleException(
        "Release signing is not configured: AUDIOBOOK_KEYSTORE_FILE points to '$keystore', which doesn't exist."
      )
    }
  }
}
tasks.matching {
  it.name in setOf("validateSigningRelease", "packageRelease", "signReleaseBundle", "packageReleaseBundle")
}.configureEach {
  dependsOn(checkReleaseSigning)
}

// Essential dependencies for the pure-offline, privacy-first Audiobook player
dependencies {
  // Jetpack Compose & Material 3
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.coil.compose)

  // Core AndroidX & Architecture
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.inspector)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Coroutines
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  // Testing
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
