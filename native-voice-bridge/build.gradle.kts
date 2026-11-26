plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.flowchat.nativevoicebridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.floryx.nativevoicebridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.9"
        testInstrumentationRunner = "com.flowchat.nativevoicebridge.NativeVoiceRoundOneTest"
    }

    sourceSets.named("main") { java.srcDir("contract") }
    buildFeatures { buildConfig = true }
    buildTypes.configureEach {
        val stopProbe = name == "debug" && providers.gradleProperty("nativeVoiceStopProbe").orNull == "true"
        buildConfigField("boolean", "NATIVE_VOICE_STOP_PROBE", stopProbe.toString())
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
}
