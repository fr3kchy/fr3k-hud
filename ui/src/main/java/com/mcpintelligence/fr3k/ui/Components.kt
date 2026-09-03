package com.mcpintelligence.fr3k.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.protocol.DeviceStatus

/** Status dot — used everywhere we render an "online / offline / degraded" signal. */
@Composable
fun StatusDot(
    status: DeviceStatus,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val color = when (status) {
        DeviceStatus.ONLINE -> Fr3kPalette.Ok
        DeviceStatus.DEGRADED -> Fr3kPalette.Warn
        DeviceStatus.OFFLINE -> Fr3kPalette.Err
        DeviceStatus.UNKNOWN -> Fr3kPalette.TextDim
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}

@Composable
fun Fr3kPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .border(1.dp, Fr3kPalette.Border, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)),
        color = Fr3kPalette.Surface,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = Fr3kPalette.Accent,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fr3kPalette.TextDim)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = Fr3kPalette.Text)
    }
}

@Composable
fun Fr3kBadge(text: String, color: Color = Fr3kPalette.Accent, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .border(1.dp, color, RoundedCornerShape(4.dp)),
        color = Color.Transparent,
    ) {
        Text(
            text = text.uppercase(),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}