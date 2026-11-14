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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** Action glyphs, drawn by hand (no icon library: a small APK). Each button names itself for TalkBack. */
enum class Glyph { PLUS, MORE, BACK, PLAY, PAUSE, RESET, LAP, CLOSE, CHECK, DELETE }

/** Draws [glyph] in [color], filling this square canvas. */
fun DrawScope.glyph(glyph: Glyph, color: Color) {
    val w = size.width
    val stroke = w * 0.092f
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(w * x1, w * y1), Offset(w * x2, w * y2), stroke, StrokeCap.Round)
    val thin = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (glyph) {
        Glyph.PLUS -> { line(0.5f, 0.12f, 0.5f, 0.88f); line(0.12f, 0.5f, 0.88f, 0.5f) }
        Glyph.MORE -> for (y in listOf(0.2f, 0.5f, 0.8f)) drawCircle(color, w * 0.083f, Offset(w / 2, w * y))
        Glyph.BACK -> { line(0.15f, 0.5f, 0.85f, 0.5f); line(0.15f, 0.5f, 0.45f, 0.2f); line(0.15f, 0.5f, 0.45f, 0.8f) }
        Glyph.PLAY -> drawPath(
            Path().apply { moveTo(w * 0.28f, w * 0.14f); lineTo(w * 0.86f, w * 0.5f); lineTo(w * 0.28f, w * 0.86f); close() },
            color,
            style = Fill,
        )
        Glyph.PAUSE -> {
            drawRoundRect(color, Offset(w * 0.22f, w * 0.14f), Size(w * 0.18f, w * 0.72f), CornerRadius(w * 0.05f))
            drawRoundRect(color, Offset(w * 0.6f, w * 0.14f), Size(w * 0.18f, w * 0.72f), CornerRadius(w * 0.05f))
        }
        Glyph.RESET -> {
            // An open circle with an arrowhead at its top end: start over.
            drawArc(color, -60f, 300f, false, Offset(w * 0.14f, w * 0.14f), Size(w * 0.72f, w * 0.72f), style = thin)
            line(0.68f, 0.19f, 0.78f, 0.05f)
            line(0.68f, 0.19f, 0.86f, 0.27f)
        }
        Glyph.LAP -> {
            // A flag on a pole.
            line(0.24f, 0.1f, 0.24f, 0.9f)
            drawPath(
                Path().apply { moveTo(w * 0.24f, w * 0.12f); lineTo(w * 0.84f, w * 0.3f); lineTo(w * 0.24f, w * 0.5f); close() },
                color,
                style = Fill,
            )
        }
        Glyph.CLOSE -> { line(0.18f, 0.18f, 0.82f, 0.82f); line(0.82f, 0.18f, 0.18f, 0.82f) }
        Glyph.CHECK -> { line(0.12f, 0.54f, 0.4f, 0.8f); line(0.4f, 0.8f, 0.88f, 0.22f) }
        Glyph.DELETE -> {
            // A bin: lid, handle, body with two ribs.
            line(0.12f, 0.24f, 0.88f, 0.24f)
            line(0.38f, 0.1f, 0.62f, 0.1f)
            drawPath(
                Path().apply { moveTo(w * 0.22f, w * 0.24f); lineTo(w * 0.28f, w * 0.9f); lineTo(w * 0.72f, w * 0.9f); lineTo(w * 0.78f, w * 0.24f) },
                color,
                style = thin,
            )
            line(0.42f, 0.4f, 0.42f, 0.74f)
            line(0.58f, 0.4f, 0.58f, 0.74f)
        }
    }
}

/** A 56 dp icon button with a hand-drawn glyph; [label] is its spoken name. */
@Composable fun GlyphButton(glyph: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.fg) {
    Box(
        modifier
            .defaultMinSize(56.dp, 56.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) { glyph(glyph, color) }
    }
}

/** A big pill button showing a glyph; [label] is its spoken name. */
@Composable fun Pill(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 64.dp,
) {
    val shape = RoundedCornerShape(height / 2)
    val color = when {
        !enabled -> Palette.faint
        filled -> Color.Black
        else -> Palette.fg
    }
    Box(
        modifier
            .defaultMinSize(minHeight = height, minWidth = 96.dp)
            .clip(shape)
            .then(if (filled) Modifier.background(if (enabled) Palette.fg else Palette.line) else Modifier.border(1.5.dp, if (enabled) Palette.faint else Palette.line, shape))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(28.dp)) { glyph(glyph, color) }
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
