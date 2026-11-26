package com.flowchat.app.domain.model

enum class ThinkingEffort(val wireValue: String) {
    Auto("auto"), Minimal("minimal"), Low("low"), Medium("medium"), High("high"), XHigh("xhigh"), Max("max");

    companion object {
        fun fromStored(value: String): ThinkingEffort = entries.firstOrNull { it.wireValue == value } ?: Auto
    }
}
