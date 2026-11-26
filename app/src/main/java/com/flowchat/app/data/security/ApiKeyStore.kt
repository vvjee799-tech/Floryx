package com.flowchat.app.data.security

interface ApiKeyStore {
    suspend fun save(alias: String, value: String)
    suspend fun read(alias: String): String?
    suspend fun delete(alias: String)
}
