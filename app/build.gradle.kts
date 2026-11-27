import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.exists()) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}
val hasReleaseSigning = releaseKeystorePropertiesFile.exists()
val nativeVoiceBridge = providers.gradleProperty("nativeVoiceBridge").orNull == "true"
val nativeVoiceRealRuntime = nativeVoiceBridge && providers.gradleProperty("nativeVoiceRealRuntime").orNull == "true"
val searchProxyUrl = providers.gradleProperty("floryxSearchProxyUrl")
    .orElse(providers.gradleProperty("flowchatSearchProxyUrl")) // Legacy build-property compatibility.
    .orElse("").get()
val xasrPrototypeDir = providers.gradleProperty("floryxXasrPrototypeDir").orNull?.let { rootProject.file(it) }
val xasrRuntimeAar = providers.gradleProperty("floryxXasrRuntimeAar").orNull?.let { path ->
    rootProject.file(path).also { aar ->
        val digest = MessageDigest.getInstance("SHA-256")
        aar.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        require(fingerprint == "6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e") {
            "floryxXasrRuntimeAar must match the verified sherpa-onnx 2.0.2 release AAR"
        }
    }
}

fun releaseSigningProperty(name: String): String =
    releaseKeystoreProperties.getProperty(name)
        ?: error("Missing $name in keystore.properties")

android {
    namespace = "com.flowchat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.floryx.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        buildConfigField("String", "SEARCH_PROXY_URL", "\"${searchProxyUrl.replace("\"", "\\\"")}\"")
        buildConfigField("boolean", "NATIVE_VOICE_REAL_RUNTIME", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
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

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "NATIVE_VOICE_REAL_RUNTIME", nativeVoiceRealRuntime.toString())
            manifestPlaceholders["nativeVoiceBridgeEnabled"] = nativeVoiceBridge.toString()
            manifestPlaceholders["nativeVoiceProviderProcess"] =
                if (nativeVoiceRealRuntime) "com.floryx.app.debug" else ":native_voice_local"
        }
        release {
            isMinifyEnabled = true
            buildConfigField("boolean", "NATIVE_VOICE_REAL_RUNTIME", "true")
            manifestPlaceholders["nativeVoiceBridgeEnabled"] = "true"
            manifestPlaceholders["nativeVoiceProviderProcess"] = "com.floryx.app"
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
        aidl = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets.named("main") {
        java.srcDir(rootProject.file("native-voice-bridge/contract"))
    }
    sourceSets.named("debug") {
        if (xasrRuntimeAar == null) {
            java.srcDir("src/withoutXasr/java")
        } else {
            java.srcDirs("src/xasrCommon/java", "src/withXasr/java")
            assets.srcDir("src/xasrCommon/assets")
        }
    }
    sourceSets.named("release") {
        java.srcDir("src/withoutXasr/java")
    }
    if (xasrPrototypeDir != null) {
        sourceSets.named("androidTest") {
            java.srcDir("src/xasrAndroidTest/java")
            if (xasrRuntimeAar == null) java.srcDir("src/xasrCommon/java")
            assets.srcDir("src/xasrCommon/assets")
            assets.srcDir(xasrPrototypeDir.resolve("assets"))
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libflowchat_*.so", "**/libandroid-shmem.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation(libs.ktor.client.mock)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation("androidx.test:runner:1.6.2")
    if (xasrRuntimeAar != null) {
        debugImplementation(files(xasrRuntimeAar))
    } else if (xasrPrototypeDir != null) {
        androidTestImplementation(files(xasrPrototypeDir.resolve("sherpa-onnx-2.0.2.aar")))
    }
}
