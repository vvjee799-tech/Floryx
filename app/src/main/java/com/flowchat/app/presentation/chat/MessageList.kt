package com.flowchat.app.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import kotlinx.coroutines.launch

private const val BottomMessageAnchorKey = "bottom-message-anchor"

@Composable
internal fun MessageList(
    messages: List<Message>, assistantAvatarPath: String?, userAvatarPath: String?, showAvatars: Boolean,
    modifier: Modifier = Modifier, hasOlderMessages: Boolean = false, onLoadOlder: () -> Unit = {}
) {
    key(messages.firstOrNull()?.conversationId) {
        ConversationMessageList(messages, assistantAvatarPath, userAvatarPath, showAvatars, modifier,
            hasOlderMessages, onLoadOlder)
    }
}

@Composable
private fun ConversationMessageList(
    messages: List<Message>, assistantAvatarPath: String?, userAvatarPath: String?, showAvatars: Boolean,
    modifier: Modifier, hasOlderMessages: Boolean, onLoadOlder: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val displayMessages = remember(messages) { groupChatMessages(messages) }
    val lastMessage = displayMessages.lastOrNull()?.message
    val activeReasoning = displayMessages.lastOrNull { it.reasoning?.status == MessageStatus.Streaming }?.reasoning
    var followLatest by remember { mutableStateOf(true) }
    var loadingAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val latestUserId = messages.lastOrNull { it.role == MessageRole.User }?.id
    var previousUserId by remember { mutableStateOf(latestUserId) }
    var tailAtUserChange by remember { mutableStateOf(messages.lastOrNull()?.id) }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) followLatest = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { !listState.canScrollForward && !listState.isScrollInProgress }.collect { atBottom ->
            if (atBottom) followLatest = true
        }
    }
    LaunchedEffect(latestUserId) {
        if (latestUserId != previousUserId && latestUserId != null &&
            messages.indexOfFirst { it.id == latestUserId } > messages.indexOfFirst { it.id == tailAtUserChange }) {
            followLatest = true
        }
        previousUserId = latestUserId
        tailAtUserChange = messages.lastOrNull()?.id
    }
    LaunchedEffect(messages.firstOrNull()?.id, hasOlderMessages) {
        loadingAnchor?.let { (id, offset) ->
            val index = displayMessages.indexOfFirst { it.message.id == id }
            if (index >= 0) listState.scrollToItem(index + if (hasOlderMessages) 1 else 0, -offset)
            loadingAnchor = null
        }
    }
    LaunchedEffect(lastMessage?.id, lastMessage?.content, activeReasoning?.reasoningContent, followLatest) {
        if (followLatest && displayMessages.isNotEmpty()) {
            withFrameNanos { }
            listState.scrollToItem(displayMessages.size + if (hasOlderMessages) 1 else 0)
        }
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState, modifier = Modifier.fillMaxSize().nestedScroll(connection).testTag("chat-message-list"),
            contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            if (hasOlderMessages) item(key = "load-older-messages") {
                TextButton(onClick = {
                    followLatest = false
                    val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                        displayMessages.any { it.message.id == item.key }
                    }
                    loadingAnchor = anchor?.let { it.key.toString() to it.offset }
                    onLoadOlder()
                }, enabled = loadingAnchor == null, modifier = Modifier.fillMaxWidth().testTag("load-older-messages")) {
                    Text(stringResource(R.string.load_older_messages))
                }
            }
            items(displayMessages, key = { it.message.id }) { entry ->
                if (entry.activity.isNotEmpty()) ReplyActivity(entry)
                else MessageBubble(entry.message, assistantAvatarPath, userAvatarPath, showAvatars, entry.reasoning)
            }
            item(key = BottomMessageAnchorKey) { Spacer(Modifier.height(1.dp)) }
        }
        if (!followLatest && listState.canScrollForward) {
            FilledTonalButton(onClick = { followLatest = true; scope.launch {
                listState.animateScrollToItem(displayMessages.size + if (hasOlderMessages) 1 else 0)
            } }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).testTag("jump-to-latest")) {
                Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.jump_to_latest))
            }
        }
    }
}

@Composable
private fun ReplyActivity(entry: DisplayChatMessage) {
    var expanded by remember(entry.message.id) { mutableStateOf(false) }
    Column {
        entry.reasoning?.let { ReasoningBubble(it, com.flowchat.app.presentation.common.FlowLayout.ContentWidth -
            com.flowchat.app.presentation.common.FlowLayout.Gutter * 2) }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.reply_activity), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                stringResource(if (expanded) R.string.thinking_collapse else R.string.thinking_expand),
                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) entry.activity.forEach { message ->
            if (message.role == MessageRole.Tool) ToolCallBubble(message)
            else if (message.content.isNotBlank()) Text(message.content,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}
