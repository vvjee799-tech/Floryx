package com.flowchat.app.data.security

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRulesContractTest {
    @Test
    fun excludesEncryptedKeysAndMemoryFromAndroidBackups() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val backupRules = File("src/main/res/xml/backup_rules.xml").readText()
        val extractionRules = File("src/main/res/xml/data_extraction_rules.xml").readText()

        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(backupRules.contains("domain=\"sharedpref\" path=\"api_keys.xml\""))
        assertTrue(backupRules.contains("domain=\"file\" path=\"memory_store.json\""))
        assertTrue(extractionRules.contains("domain=\"sharedpref\" path=\"api_keys.xml\""))
        assertTrue(extractionRules.contains("domain=\"file\" path=\"memory_store.json\""))
    }
}
