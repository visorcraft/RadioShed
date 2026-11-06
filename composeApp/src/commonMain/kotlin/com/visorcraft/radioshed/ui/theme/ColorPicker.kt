package com.visorcraft.radioshed.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HSV color picker — preset swatch row, hue/saturation wheel, value
 * slider, and a hex text field. KMP-safe: no `android.graphics.Color`
 * calls, all HSV math is in this file.
 *
 * Design:
 *  - Hue/saturation are picked together on the wheel (angle = hue,
 *    radius = saturation). Tap or drag anywhere inside the circle.
 *    Outside-circle pointers clamp to the rim — feels less twitchy
 *    than ignoring them outright.
 *  - Value (brightness) lives on its own slider beneath because
 *    encoding it onto a 2-D wheel forces a third axis we don't have.
 *  - Hex field is two-way: typing a valid `RRGGBB` (with or without
 *    leading `#`) snaps the wheel to that color. Invalid input is
 *    ignored; the field stays in the user's typed string until they
 *    hit a valid value (so you can be mid-edit without the wheel
 *    jumping around).
 *  - Preset swatches at the top are quick-select shortcuts; tapping
 *    one snaps every control simultaneously.
 *
 * The picker emits [onColorChange] continuously as the user
 * manipulates any control. The parent decides when to commit
 * (typically on AlertDialog confirm).
 */
@Composable
fun ColorPicker(
    initial: Color,
    onColorChange: (Color) -> Unit,
    presets: List<Color> = DefaultColorPresets,
    modifier: Modifier = Modifier,
) {
    var hsv by remember(initial) { mutableStateOf(initial.toHsv()) }
    val current = hsv.toColor()

    // Hex field state is kept separate from the wheel so the user can
    // type partial values without the wheel jumping per-keystroke.
    var hexField by remember(initial) { mutableStateOf(current.toHexString()) }
    // Re-sync the hex field when the wheel / slider / preset moves
    // the colour, but only when the field doesn't already represent
    // the same colour. The guard avoids overwriting a user's typed
    // text on the round-trip "field → parses to color → updates hsv
    // → updates field" path.
    LaunchedEffect(hsv) {
        val canonical = current.toHexString()
        val typedNorm = hexField.removePrefix("#").trim().uppercase()
        if (typedNorm != canonical) {
            hexField = canonical
        }
    }

    // Skip the first fire — that emission would otherwise propagate
    // a slightly-drifted version of `initial` (round-tripped through
    // HSV math) back to the parent and trigger a settings write
    // before the user has touched anything. After the first
    // composition, fire on every subsequent change so the parent's
    // live theme preview keeps up with wheel / slider / hex edits.
    var hasInitialized by remember(initial) { mutableStateOf(false) }
    LaunchedEffect(current) {
        if (hasInitialized) {
            onColorChange(current)
        } else {
            hasInitialized = true
        }
    }

    Column(modifier = modifier) {
        // ---- Presets row -----------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            presets.forEach { swatch ->
                val selected = colorsClose(swatch, current)
                Surface(
                    shape = CircleShape,
                    color = swatch,
                    modifier =
                        Modifier
                            .size(28.dp)
                            .clickable {
                                hsv = swatch.toHsv().copy(value = max(0.55f, swatch.toHsv().value))
                            }
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                    },
                                shape = CircleShape,
                            ),
                    content = {},
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- Hue / saturation wheel ------------------------------
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            val sizePx = with(density) { maxWidth.toPx() }
            val radiusPx = sizePx / 2f
            val centerPx = Offset(sizePx / 2f, sizePx / 2f)

            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .pointerInput(centerPx, radiusPx) {
                            // Both tap and drag funnel through the same
                            // hit-test path so a quick tap behaves
                            // identically to the start of a drag. Keying
                            // pointerInput on the geometry restarts the
                            // handler if the dialog is ever resized so
                            // the closure captures of `centerPx`/`radiusPx`
                            // never go stale.
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pos =
                                        event.changes.firstOrNull()?.position
                                            ?: continue
                                    if (event.changes.any { it.pressed }) {
                                        hsv = hsvFromWheel(pos, centerPx, radiusPx, hsv.value)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
            ) {
                // Layer 1: hue sweep gradient — 13 stops give a
                // visually smooth wheel without obvious banding at
                // typical screen sizes (~240-300dp diameter).
                val hueColors =
                    (0..12).map { i ->
                        colorFromHsv(i * 30f, saturation = 1f, value = 1f)
                    }
                drawCircle(
                    brush = Brush.sweepGradient(hueColors, center = centerPx),
                    radius = radiusPx,
                    center = centerPx,
                )
                // Layer 2: saturation falloff — white at the centre,
                // transparent at the rim. Gives the inner-circle pastel
                // look characteristic of Material colour wheels.
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                            center = centerPx,
                            radius = radiusPx,
                        ),
                    radius = radiusPx,
                    center = centerPx,
                )
                // Layer 3: value tint — pure black at full strength
                // when value = 0, transparent at value = 1. Simple
                // multiplicative darken keeps colour relationships
                // intact across the brightness axis.
                if (hsv.value < 1f) {
                    drawCircle(
                        color = Color.Black.copy(alpha = 1f - hsv.value),
                        radius = radiusPx,
                        center = centerPx,
                    )
                }
                // Layer 4: indicator. Two concentric strokes so the
                // dot stays visible against any underlying hue.
                val angle = (hsv.hue * PI / 180.0).toFloat()
                val r = hsv.saturation * radiusPx
                // Compose canvas y grows downward; subtract sin so
                // 0° = right, 90° = top (matches the conventional
                // "red at 3 o'clock" hue layout).
                val pickerPos =
                    Offset(
                        centerPx.x + r * cos(angle),
                        centerPx.y + r * sin(angle),
                    )
                val ringR = with(density) { 8.dp.toPx() }
                drawCircle(color = Color.Black, radius = ringR + 1.5f, center = pickerPos, style = Stroke(2.5f))
                drawCircle(color = Color.White, radius = ringR, center = pickerPos, style = Stroke(2f))
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---- Value (brightness) slider ---------------------------
        Column {
            Text(
                "Brightness",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            // Track tint: gradient from black to the current
            // hue+saturation at full value so the slider visually
            // previews what each position looks like.
            val trackColor = colorFromHsv(hsv.hue, hsv.saturation.coerceAtLeast(0.001f), 1f)
            Slider(
                value = hsv.value,
                onValueChange = { hsv = hsv.copy(value = it.coerceIn(0f, 1f)) },
                colors =
                    SliderDefaults.colors(
                        activeTrackColor = trackColor,
                        inactiveTrackColor = trackColor.copy(alpha = 0.25f),
                        thumbColor = trackColor,
                    ),
                valueRange = 0f..1f,
            )
        }

        // ---- Hex + preview --------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = current,
                shape = RoundedCornerShape(8.dp),
                modifier =
                    Modifier
                        .size(40.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            RoundedCornerShape(8.dp),
                        ),
                content = {},
            )
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value = hexField,
                onValueChange = { raw ->
                    hexField = raw
                    val normalized = raw.removePrefix("#").trim()
                    if (normalized.length == 6 && normalized.all { it.isHexDigit() }) {
                        // Parse → Color → HSV. Use the parsed value
                        // directly; an explicit hex tells us value =
                        // whatever the colour reports, including 0 if
                        // the user typed `000000`.
                        val parsed = parseHex6(normalized)
                        if (parsed != null) {
                            hsv = parsed.toHsv()
                        }
                    }
                },
                singleLine = true,
                label = { Text("Hex") },
                placeholder = { Text("RRGGBB") },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Curated quick-pick swatches. Spans the hue wheel with the brand
 *  blue at the front so taps in order land at the default. */
val DefaultColorPresets: List<Color> =
    listOf(
        Color(0xFF0061A4), // RadioShed brand blue (Material 3 Blue)
        Color(0xFF6750A4), // Material You purple
        Color(0xFFD32F2F), // red
        Color(0xFFE91E63), // pink
        Color(0xFF7B1FA2), // deep purple
        Color(0xFFB04E00), // amber (legacy brand)
        Color(0xFF00897B), // teal
        Color(0xFF388E3C), // green
    )

// ---- HSV math (KMP-safe; no android.graphics.Color) ------------------

/** HSV with explicit alpha. Hue 0–360, saturation/value/alpha 0–1. */
data class Hsv(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Float = 1f,
) {
    fun toColor(): Color = colorFromHsv(hue, saturation, value, alpha)
}

internal fun Color.toHsv(): Hsv {
    val r = red
    val g = green
    val b = blue
    val cMax = max(r, max(g, b))
    val cMin = min(r, min(g, b))
    val delta = cMax - cMin
    val h =
        when {
            delta == 0f -> 0f
            cMax == r -> 60f * (((g - b) / delta) % 6f)
            cMax == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
    val hue = if (h < 0f) h + 360f else h
    val saturation = if (cMax == 0f) 0f else delta / cMax
    return Hsv(hue, saturation, cMax, alpha)
}

internal fun colorFromHsv(
    hue: Float,
    saturation: Float,
    value: Float,
    alpha: Float = 1f,
): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - kotlin.math.abs(((h / 60f) % 2f) - 1f))
    val m = v - c
    val (r1, g1, b1) =
        when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
    return Color(red = r1 + m, green = g1 + m, blue = b1 + m, alpha = alpha)
}

private fun hsvFromWheel(
    pos: Offset,
    center: Offset,
    radius: Float,
    valuePreserved: Float,
): Hsv {
    val dx = pos.x - center.x
    val dy = pos.y - center.y
    val dist = sqrt(dx * dx + dy * dy)
    val saturation = (dist / radius).coerceIn(0f, 1f)
    // atan2 returns -π..π; map to 0..360 with 0° at "3 o'clock"
    // (positive x axis) and angles increasing clockwise so the wheel
    // matches the hue order rendered by the sweep gradient.
    val theta = atan2(dy.toDouble(), dx.toDouble())
    val degrees = (theta * 180.0 / PI).toFloat()
    val hue = if (degrees < 0f) degrees + 360f else degrees
    return Hsv(hue = hue, saturation = saturation, value = valuePreserved)
}

private fun Color.toHexString(): String {
    val r = (red * 255f).roundToInt().coerceIn(0, 255)
    val g = (green * 255f).roundToInt().coerceIn(0, 255)
    val b = (blue * 255f).roundToInt().coerceIn(0, 255)

    fun byte(v: Int): String {
        val s = v.toString(16).uppercase()
        return if (s.length == 1) "0$s" else s
    }
    return byte(r) + byte(g) + byte(b)
}

private fun parseHex6(hex: String): Color? {
    if (hex.length != 6 || !hex.all { it.isHexDigit() }) return null
    val r = hex.substring(0, 2).toInt(16) / 255f
    val g = hex.substring(2, 4).toInt(16) / 255f
    val b = hex.substring(4, 6).toInt(16) / 255f
    return Color(red = r, green = g, blue = b, alpha = 1f)
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun colorsClose(
    a: Color,
    b: Color,
): Boolean {
    val tol = 0.02f
    return kotlin.math.abs(a.red - b.red) < tol &&
        kotlin.math.abs(a.green - b.green) < tol &&
        kotlin.math.abs(a.blue - b.blue) < tol
}
