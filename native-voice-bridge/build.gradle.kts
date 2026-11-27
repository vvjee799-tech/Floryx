import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.exists()) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}
val hasReleaseSigning = releaseKeystorePropertiesFile.exists()

fun releaseSigningProperty(name: String): String =
    releaseKeystoreProperties.getProperty(name)
        ?: error("Missing $name in keystore.properties")

android {
    namespace = "com.flowchat.nativevoicebridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.floryx.nativevoicebridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "com.flowchat.nativevoicebridge.NativeVoiceRoundOneTest"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperty("storeFile"))
                storePassword = releaseSigningProperty("storePassword")
                keyAlias = releaseSigningProperty("keyAlias")
                keyPassword = releaseSigningProperty("keyPassword")
            }
        }
    }

    sourceSets.named("main") { java.srcDir("contract") }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
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
