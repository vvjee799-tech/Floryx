package com.flowchat.app.presentation.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.data.db.UsageRecord
import com.flowchat.app.data.usage.*
import java.text.DateFormat
import java.util.Date
import java.text.NumberFormat

@Composable
internal fun UsageDisplay(records: List<UsageRecord>, supported: Boolean, modifier: Modifier = Modifier,
    balance: BalanceState = BalanceState(), onRefresh: () -> Unit = {}) {
    var details by remember { mutableStateOf(false) }
    val reported = records.filter { it.inputTokens != null && it.outputTokens != null }
    val incomplete = reported.size != records.size
    val input = reported.sumOf { it.inputTokens ?: 0 }
    val output = reported.sumOf { it.outputTokens ?: 0 }
    val formatter = NumberFormat.getIntegerInstance()
    val balanceText = when (balance.status) {
        BalanceStatus.Ready -> stringResource(R.string.balance_amount, balance.balances.joinToString(" / ") { "${it.currency} ${it.total}" })
        BalanceStatus.Loading -> stringResource(R.string.balance_loading)
        BalanceStatus.Waiting -> stringResource(R.string.balance_waiting)
        BalanceStatus.Unsupported -> stringResource(R.string.balance_unsupported)
        BalanceStatus.MissingKey -> stringResource(R.string.balance_missing_key)
        BalanceStatus.Failed -> stringResource(R.string.balance_failed)
        BalanceStatus.Disabled -> stringResource(R.string.balance_unavailable)
    }
    val total = when {
        records.isEmpty() -> stringResource(if (supported) R.string.usage_empty else R.string.usage_unsupported)
        reported.isEmpty() -> stringResource(R.string.usage_missing)
        else -> "${formatter.format(input + output)} Token${if (incomplete) " *" else ""}"
    }
    Column(modifier.heightIn(min = 48.dp).testTag("usage_summary").clickable { details = true }
        .padding(vertical = 4.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (balance.status == BalanceStatus.Ready) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.balance_label), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(balance.balances.joinToString(" / ") { entry ->
                    (if (entry.currency == "CNY") "¥" else "US$") + entry.total
                }, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        } else {
            Text(balanceText, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(total, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (details) AlertDialog(onDismissRequest = { details = false },
        title = { Text(stringResource(R.string.usage_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.usage_details, formatter.format(input), formatter.format(output),
                formatter.format(reported.sumOf { it.cacheHitTokens ?: 0 }), reported.size, records.size))
            Text(balanceText)
            balance.balances.forEach { entry ->
                Text(stringResource(R.string.balance_breakdown, entry.currency, entry.granted, entry.toppedUp))
            }
            balance.updatedAt?.let { Text(stringResource(R.string.balance_updated, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it)))) }
            TextButton(onClick = onRefresh, enabled = balance.status !in listOf(BalanceStatus.Loading, BalanceStatus.Waiting, BalanceStatus.Unsupported, BalanceStatus.Disabled)) {
                Text(stringResource(R.string.balance_refresh))
            }
            if (incomplete) Text(stringResource(R.string.usage_incomplete), color = MaterialTheme.colorScheme.error)
            if (reported.any { it.cacheHitTokens == null }) Text(stringResource(R.string.usage_cache_unknown))
            Text(stringResource(R.string.usage_explanation))
        } },
        confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.close)) } })
}
