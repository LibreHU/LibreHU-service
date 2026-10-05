package org.librehu.service.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Rounded block of a settings page, Android Auto style. */
@Composable
fun Card(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(CarColors.Surface)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) Text(title, color = CarColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
fun Hint(text: String) = Text(text, color = CarColors.TextDim, fontSize = 15.sp)

@Composable
fun BodyText(
    text: String,
    mono: Boolean = false,
) = Text(text, color = CarColors.Text, fontSize = if (mono) 14.sp else 17.sp, fontFamily = if (mono) FontFamily.Monospace else null)

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = enabled) { onChange(!checked) }
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) CarColors.Text else CarColors.TextDim, fontSize = 18.sp)
            if (subtitle != null) Text(subtitle, color = CarColors.TextDim, fontSize = 14.sp)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = CarColors.Accent, checkedThumbColor = CarColors.OnAccent),
        )
    }
}

/** Slider sending its value when the finger is lifted (each step is an I2C write or a Settings write). */
@Composable
fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    format: (Int) -> String = { it.toString() },
    onChange: (Int) -> Unit,
) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) value else dragging.roundToInt()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = CarColors.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text(format(shown), color = CarColors.TextDim, fontSize = 16.sp)
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                if (!dragging.isNaN()) onChange(dragging.roundToInt())
                dragging = Float.NaN
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceIn(0, 200),
            colors = SliderDefaults.colors(thumbColor = CarColors.Accent, activeTrackColor = CarColors.Accent),
        )
    }
}

/** Pill buttons, one selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Choices(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((value, label) in options) Pill(label, value == selected) { onSelect(value) }
    }
}

@Composable
fun Pill(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) CarColors.Accent else CarColors.SurfaceHigh)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color =
            if (selected) {
                CarColors.OnAccent
            } else if (enabled) {
                CarColors.Text
            } else {
                CarColors.TextDim
            }
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/** Row of action pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Actions(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/** Big value with its label (dashboard tiles). */
@Composable
fun Tile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    alert: Boolean = false,
) {
    Column(
        modifier =
            modifier
                .widthIn(min = 140.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(CarColors.SurfaceHigh)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            value,
            color = if (alert) Color(0xFFF44336) else CarColors.Text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(label, color = CarColors.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Status dot + text. */
@Composable
fun StatusLine(
    text: String,
    ok: Boolean?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    when (ok) {
                        true -> Color(0xFF4CAF50)
                        false -> Color(0xFFF44336)
                        null -> CarColors.TextDim
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = CarColors.Text, fontSize = 17.sp)
    }
}

/** Clickable list line: title, subtitle and trailing content. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(16.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = CarColors.Text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle !=
                null
            ) {
                Text(subtitle, color = CarColors.TextDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
fun PageTitle(text: String) {
    Text(text, color = CarColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
fun Gap() = Spacer(Modifier.height(4.dp))
