package com.flowchat.app.locale

enum class AppLanguage(
    val storageValue: String,
    val languageTag: String?
) {
    System("system", null),
    ChineseSimplified("zh-CN", "zh-CN"),
    English("en", "en");

    companion object {
        fun fromStorageValue(value: String?): AppLanguage {
            return entries.firstOrNull { it.storageValue == value } ?: System
        }
    }
}
