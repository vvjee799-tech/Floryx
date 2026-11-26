package com.flowchat.app.di

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppModuleContractTest {
    @Test
    fun sharedHttpClientInstallsHttpTimeoutPluginForPerRequestTimeouts() {
        val source = File("src/main/java/com/flowchat/app/di/AppModule.kt").readText()

        assertTrue(source.contains("import io.ktor.client.plugins.HttpTimeout"))
        assertTrue(source.contains("install(HttpTimeout)"))
    }
}
