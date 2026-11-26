package com.flowchat.app.locale

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun decodesStoredLanguageValues() {
        assertEquals(AppLanguage.System, AppLanguage.fromStorageValue(null))
        assertEquals(AppLanguage.System, AppLanguage.fromStorageValue("system"))
        assertEquals(AppLanguage.ChineseSimplified, AppLanguage.fromStorageValue("zh-CN"))
        assertEquals(AppLanguage.English, AppLanguage.fromStorageValue("en"))
    }

    @Test
    fun fallsBackToSystemForUnknownStoredValue() {
        assertEquals(AppLanguage.System, AppLanguage.fromStorageValue("fr"))
    }
}
