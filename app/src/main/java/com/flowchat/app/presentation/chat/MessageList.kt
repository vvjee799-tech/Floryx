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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import kotlinx.coroutines.launch

private const val BottomMessageAnchorKey = "bottom-message-anchor"

private class ViewportHeightTracker {
    var lastMaxHeight: Int = -1
    var previousReadingHeight: Int = 0
}

@Composable
internal fun MessageList(
    messages: List<Message>, assistantAvatarPath: String?, userAvatarPath: String?, showAvatars: Boolean,
    modifier: Modifier = Modifier, hasOlderMessages: Boolean = false, onLoadOlder: () -> Unit = {},
    statusKey: Any? = null, statusContent: (@Composable () -> Unit)? = null
) {
    key(messages.firstOrNull()?.conversationId) {
        ConversationMessageList(messages, assistantAvatarPath, userAvatarPath, showAvatars, modifier,
            hasOlderMessages, onLoadOlder, statusKey, statusContent)
    }
}

@Composable
private fun ConversationMessageList(
    messages: List<Message>, assistantAvatarPath: String?, userAvatarPath: String?, showAvatars: Boolean,
    modifier: Modifier, hasOlderMessages: Boolean, onLoadOlder: () -> Unit,
    statusKey: Any?, statusContent: (@Composable () -> Unit)?
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isCompactHeight = configuration.screenHeightDp < 480
    val useSideJumpRail = isCompactHeight && configuration.screenWidthDp >= 480
    val displayMessages = remember(messages) { groupChatMessages(messages) }
    val lastMessage = displayMessages.lastOrNull()?.message
    val activeReasoning = displayMessages.lastOrNull { it.reasoning?.status == MessageStatus.Streaming }?.reasoning
    var followLatest by remember { mutableStateOf(true) }
    val viewportTracker = remember { ViewportHeightTracker() }
    var readingViewportHeight by remember { mutableIntStateOf(0) }
    val bottomAnchorIndex = displayMessages.size + (if (hasOlderMessages) 1 else 0) + (if (statusContent != null) 1 else 0)
    val currentBottomAnchorIndex by rememberUpdatedState(bottomAnchorIndex)
    val currentHasMessages by rememberUpdatedState(displayMessages.isNotEmpty())
    var loadingAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val latestUserId = messages.lastOrNull { it.role == MessageRole.User }?.id
    var previousUserId by remember { mutableStateOf(latestUserId) }
    var tailAtUserChange by remember { mutableStateOf(messages.lastOrNull()?.id) }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f && listState.canScrollBackward) {
                    followLatest = false
                }
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
            if (index >= 0) listState.requestScrollToItem(index + if (hasOlderMessages) 1 else 0, -offset)
            loadingAnchor = null
        }
    }
    LaunchedEffect(readingViewportHeight) {
        val newHeight = readingViewportHeight
        if (newHeight <= 0) return@LaunchedEffect
        val prevHeight = viewportTracker.previousReadingHeight
        viewportTracker.previousReadingHeight = newHeight
        // When a soft keyboard shrinks a short viewport, snap past a top-clipped bubble if no full message remains visible.
        if (!followLatest && prevHeight > 0 && newHeight in 1 until prevHeight) {
            val messageItems = listState.layoutInfo.visibleItemsInfo.filter { item ->
                displayMessages.any { it.message.id == item.key }
            }
            val firstMessage = messageItems.firstOrNull()
            val anyItemCleanlyVisible = messageItems.any { item ->
                item.offset >= 0 && item.offset + item.size <= newHeight
            }
            if (firstMessage != null && firstMessage.offset < 0 && !anyItemCleanlyVisible) {
                val target = messageItems.firstOrNull { it.offset >= 0 } ?: firstMessage
                listState.requestScrollToItem(target.index, 0)
            }
        }
    }
    LaunchedEffect(lastMessage?.id, lastMessage?.content, activeReasoning?.reasoningContent, followLatest, statusKey) {
        if (followLatest && displayMessages.isNotEmpty()) {
            listState.requestScrollToItem(bottomAnchorIndex)
        }
    }
    val jumpLabel = stringResource(R.string.jump_to_latest)
    val jumpToLatestAction = {
        followLatest = true
        scope.launch {
            listState.animateScrollToItem(bottomAnchorIndex)
        }
        Unit
    }
    val showJumpButton = !followLatest && listState.canScrollForward
    val messageLazyColumn: @Composable (Modifier) -> Unit = { listModifier ->
        LazyColumn(
            state = listState,
            modifier = listModifier
                .layout { measurable, constraints ->
                    val maxHeight = constraints.maxHeight
                    val previousMaxHeight = viewportTracker.lastMaxHeight
                    if (maxHeight != Constraints.Infinity && maxHeight > 0) {
                        viewportTracker.lastMaxHeight = maxHeight
                        if (followLatest && currentHasMessages && !listState.isScrollInProgress && maxHeight != previousMaxHeight) {
                            listState.requestScrollToItem(currentBottomAnchorIndex)
                        }
                    }
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(0, 0)
                    }
                }
                .onSizeChanged { size ->
                    if (!followLatest) {
                        if (size.height != readingViewportHeight) readingViewportHeight = size.height
                    } else {
                        viewportTracker.previousReadingHeight = size.height
                    }
                }
                .nestedScroll(connection)
                .testTag("chat-message-list"),
            contentPadding = PaddingValues(
                top = if (isCompactHeight) 4.dp else 28.dp,
                bottom = if (isCompactHeight) 4.dp else 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 8.dp else 26.dp)
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
            if (statusContent != null) item(key = "task-status") { statusContent() }
            item(key = BottomMessageAnchorKey) { Spacer(Modifier.height(1.dp)) }
        }
    }
    if (useSideJumpRail) {
        Row(modifier.fillMaxWidth()) {
            messageLazyColumn(Modifier.weight(1f).fillMaxHeight())
            if (showJumpButton) {
                FilledTonalIconButton(
                    onClick = jumpToLatestAction,
                    modifier = Modifier.align(Alignment.Bottom).padding(start = 8.dp, bottom = 4.dp)
                        .size(48.dp).semantics { contentDescription = jumpLabel }.testTag("jump-to-latest")
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
            }
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            messageLazyColumn(Modifier.weight(1f).fillMaxWidth())
            if (showJumpButton) {
                FilledTonalButton(
                    onClick = jumpToLatestAction,
                    modifier = Modifier.align(Alignment.End).padding(end = 8.dp).testTag("jump-to-latest")
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(jumpLabel)
                }
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
