package uk.ccez.clock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pure black (AMOLED), white, grays; one soft accent for what's on. */
object Palette {
    val bg = Color.Black
    val fg = Color.White
    val dim = Color(0xFF9A9A9A)
    val faint = Color(0xFF5A5A5A)
    val cell = Color(0xFF121212)
    val line = Color(0xFF262626)
    val accent = Color(0xFFF2B36B)
}

/**
 * One line that shrinks to fit rather than wrap ("Stopw/atch" at the
 * largest font size), down to half its size.
 */
@Composable fun FitText(
    text: String,
    color: Color,
    fontSize: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight? = null,
    style: TextStyle = TextStyle.Default,
    align: TextAlign = TextAlign.Center,
) {
    androidx.compose.foundation.text.BasicText(
        text,
        modifier,
        style = style.merge(TextStyle(color = color, fontSize = fontSize.sp, fontWeight = weight, textAlign = align)),
        maxLines = 1,
        softWrap = false,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = (fontSize / 2).sp, maxFontSize = fontSize.sp, stepSize = 1.sp),
    )
}

/** Digits that don't shift as they change. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable fun ClockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color.Black,
            primaryContainer = Palette.accent,
            onPrimaryContainer = Color.Black,
            secondary = Palette.fg,
            secondaryContainer = Palette.line,
            onSecondaryContainer = Palette.fg,
            tertiaryContainer = Palette.accent,
            onTertiaryContainer = Color.Black,
            background = Palette.bg,
            onBackground = Palette.fg,
            surface = Palette.bg,
            onSurface = Palette.fg,
            surfaceVariant = Palette.cell,
            onSurfaceVariant = Palette.dim,
            surfaceContainerHighest = Palette.cell,
            surfaceContainerHigh = Palette.cell,
            surfaceContainer = Palette.cell,
            outline = Palette.faint,
        ),
        content = content,
    )
}

enum class Glyph { PLUS, MORE, BACK }

/** A 48 dp+ icon button with a hand-drawn glyph (no icon library: a small APK). */
@Composable fun GlyphButton(glyph: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .defaultMinSize(56.dp, 56.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val w = size.width
            val stroke = 2.2.dp.toPx()
            val c = Palette.fg
            when (glyph) {
                Glyph.PLUS -> {
                    drawLine(c, Offset(w / 2, w * 0.12f), Offset(w / 2, w * 0.88f), stroke, StrokeCap.Round)
                    drawLine(c, Offset(w * 0.12f, w / 2), Offset(w * 0.88f, w / 2), stroke, StrokeCap.Round)
                }
                Glyph.MORE -> for (y in listOf(0.2f, 0.5f, 0.8f)) drawCircle(c, 2.dp.toPx(), Offset(w / 2, w * y))
                Glyph.BACK -> {
                    drawLine(c, Offset(w * 0.15f, w / 2), Offset(w * 0.85f, w / 2), stroke, StrokeCap.Round)
                    drawLine(c, Offset(w * 0.15f, w / 2), Offset(w * 0.45f, w * 0.2f), stroke, StrokeCap.Round)
                    drawLine(c, Offset(w * 0.15f, w / 2), Offset(w * 0.45f, w * 0.8f), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

/** A big pill button: filled for the main action, outlined for the other. */
@Composable fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 64.dp,
    fontSize: Int = 18,
) {
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier
            .defaultMinSize(minHeight = height, minWidth = 96.dp)
            .clip(shape)
            .then(if (filled) Modifier.background(if (enabled) Palette.fg else Palette.line) else Modifier.border(1.5.dp, if (enabled) Palette.faint else Palette.line, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        FitText(
            text,
            color = when {
                !enabled -> Palette.faint
                filled -> Color.Black
                else -> Palette.fg
            },
            fontSize = fontSize,
            weight = FontWeight.Medium,
        )
    }
}

/** A small choice chip (presets, snooze lengths). */
@Composable fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, spoken: String? = null) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp, minWidth = 56.dp)
            .clip(shape)
            .background(if (selected) Palette.fg else Palette.cell)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                if (spoken != null) contentDescription = spoken
                role = Role.Button
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        FitText(text, color = if (selected) Color.Black else Palette.fg, fontSize = 16)
    }
}
