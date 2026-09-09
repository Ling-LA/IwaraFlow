plugins {
id("com.android.application")
id("org.jetbrains.kotlin.android")
}

android {
namespace = "com.ling.iwaraflow"
compileSdk = 35

defaultConfig {
applicationId = "com.ling.iwaraflow"
minSdk = 28
targetSdk = 35
versionCode = 1
versionName = "0.1.0"
}

buildTypes {
release {
isMinifyEnabled = false
proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
signingConfig = signingConfigs.getByName("debug")
}
}

compileOptions {
sourceCompatibility = JavaVersion.VERSION_17
targetCompatibility = JavaVersion.VERSION_17
}

kotlinOptions {
jvmTarget = "17"
freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
}

lint {
abortOnError = false
}
}

dependencies {
implementation("androidx.core:core-ktx:1.15.0")
implementation("androidx.appcompat:appcompat:1.7.0")
implementation("androidx.activity:activity-ktx:1.9.3")
implementation("androidx.recyclerview:recyclerview:1.3.2")
implementation("androidx.viewpager2:viewpager2:1.1.0")
implementation("androidx.media3:media3-exoplayer:1.4.1")
implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
implementation("androidx.media3:media3-ui:1.4.1")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
