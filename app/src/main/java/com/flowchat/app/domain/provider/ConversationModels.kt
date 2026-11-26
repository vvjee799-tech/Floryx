package com.flowchat.app.domain.provider

import com.flowchat.app.domain.model.ProviderConfig

fun cleanModelSelection(names: List<String>): List<String> = names.map(String::trim).filter(String::isNotBlank).distinct()

/** An explicit selection is authoritative, even when an older conversation uses another model. */
fun ProviderConfig.conversationModelNames(discovered: List<String>, activeModel: String? = null): List<String> =
    cleanModelSelection(conversationModels ?: (listOfNotNull(activeModel) + discovered))
