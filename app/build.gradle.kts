import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
val ks = Properties().apply { val f = file("/workspace/player-inteligente-keystore/keystore.properties"); if (f.exists()) f.inputStream().use { load(it) } }
android {
  namespace = "br.geracaoai.player"
  compileSdk = 35
  defaultConfig {
    applicationId = "br.geracaoai.player"; minSdk = 29; targetSdk = 35; versionCode = 21; versionName = "1.3.13"
    ndk { abiFilters += (project.findProperty("abis") as String? ?: "arm64-v8a").split(",") }
  }
  signingConfigs { create("release") { if (ks.isNotEmpty()) { storeFile = file(ks["storeFile"] as String); storePassword = ks["storePassword"] as String; keyAlias = ks["keyAlias"] as String; keyPassword = ks["keyPassword"] as String } } }
  buildTypes { release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"); signingConfig = signingConfigs.getByName("release") } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true; buildConfig = true }
  androidResources { noCompress += listOf("onnx", "tflite") }
  lint { checkReleaseBuilds = false; abortOnError = false }
  packaging {
    jniLibs { pickFirsts += listOf("**/libonnxruntime.so", "**/libc++_shared.so") }
  }
  testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
  val bom = platform("androidx.compose:compose-bom:2024.09.00")
  implementation(bom)
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
  implementation("androidx.media3:media3-exoplayer:1.5.0")
  implementation("androidx.media3:media3-ui:1.5.0")
  implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
  implementation("androidx.work:work-runtime-ktx:2.10.0")
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
  implementation("org.tensorflow:tensorflow-lite:2.14.0")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.13")
  testImplementation("androidx.test:core:1.6.1")
}
