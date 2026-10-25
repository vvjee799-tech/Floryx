package com.flowchat.app.presentation.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.flowchat.app.R
import com.flowchat.app.data.attachment.ImageAttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ImageAttachmentPreview(name: String, path: String, compact: Boolean = false, onRemove: (() -> Unit)? = null) {
    var expanded by remember(path) { mutableStateOf(false) }
    val previewDescription = stringResource(R.string.preview_image, name)
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = if (compact) Modifier.fillMaxWidth().padding(6.dp) else Modifier.padding(4.dp)) {
        if (compact) {
            StoredImage(path, previewDescription, Modifier.size(64.dp)
                .clip(RoundedCornerShape(12.dp)).clickable { expanded = true })
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.image_model_hint), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Column(modifier = Modifier.weight(1f, fill = false)) {
            StoredImage(path, previewDescription, Modifier.widthIn(max = 240.dp).height(156.dp)
                .clip(RoundedCornerShape(12.dp)).clickable { expanded = true })
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.widthIn(max = 240.dp).padding(top = 4.dp))
        }
        onRemove?.let { remove ->
            IconButton(onClick = remove, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.remove_attachment), modifier = Modifier.size(18.dp))
            }
        }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { expanded = false }) {
                        Icon(Icons.Default.Close, stringResource(R.string.close_image_preview))
                    }
                }
                StoredImage(path, name, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 480.dp), large = true)
            }
        }
    }
}

@Composable
private fun StoredImage(path: String, description: String, modifier: Modifier, large: Boolean = false) {
    val context = LocalContext.current.applicationContext
    var state by remember(path, large) { mutableStateOf<Pair<Boolean, ImageBitmap?>>(false to null) }
    LaunchedEffect(path, large) {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val file = ImageAttachmentStore.resolve(context, path)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                val maxEdge = if (large) 1600 else 512
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge * 2) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
        state = true to bitmap
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        state.second?.let { bitmap ->
            Image(bitmap, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } ?: if (state.first) Text(stringResource(R.string.image_unavailable), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(12.dp)) else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
