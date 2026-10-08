package com.inmc.discord.render.font

import com.inmc.discord.render.Raster
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.KeybindComponent
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 서식 있는 글을 마크처럼 그린다 — 그림자·굵게·기울임·밑줄·취소선·난독화·글꼴 키. 번역 키는 미리 풀어서 넘긴다(`Lang.translate`).
 *
 * 좌표는 GUI 픽셀, [s] 는 GUI 한 픽셀이 그림 몇 픽셀인지(2 이상 — unihex 한 점이 GUI 0.5 픽셀이다).
 */
class TextRenderer(private val fonts: Fonts) {

    data class Run(
        val text: String,
        val color: Int,
        val bold: Boolean,
        val italic: Boolean,
        val underlined: Boolean,
        val strikethrough: Boolean,
        val obfuscated: Boolean,
        val font: String,
        val shadow: Int?,
    )

    /** 컴포넌트 나무를 글 조각 목록으로. 자식은 부모의 서식을 물려받는다. */
    fun runs(component: Component, defaultColor: Int): List<Run> {
        val out = ArrayList<Run>()
        collect(component, Style.empty(), defaultColor, out)
        return out
    }

    private fun collect(component: Component, parent: Style, defaultColor: Int, out: MutableList<Run>) {
        val style = component.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
        val text = when (component) {
            is TextComponent -> component.content()
            is KeybindComponent -> component.keybind()
            else -> ""
        }
        if (text.isNotEmpty()) {
            fun on(d: TextDecoration) = style.decoration(d) == TextDecoration.State.TRUE
            out += Run(
                text = text,
                color = style.color()?.value()?.or(0xFF000000.toInt()) ?: defaultColor,
                bold = on(TextDecoration.BOLD),
                italic = on(TextDecoration.ITALIC),
                underlined = on(TextDecoration.UNDERLINED),
                strikethrough = on(TextDecoration.STRIKETHROUGH),
                obfuscated = on(TextDecoration.OBFUSCATED),
                font = style.font()?.asString() ?: Fonts.DEFAULT,
                shadow = style.shadowColor()?.value(),
            )
        }
        for (child in component.children()) collect(child, style, defaultColor, out)
    }

    fun width(component: Component): Float = width(runs(component, Raster.WHITE))

    fun width(runs: List<Run>): Float {
        var w = 0f
        for (run in runs) {
            val font = fonts.font(run.font)
            run.text.codePoints().forEach { cp ->
                val g = font.glyph(cp) ?: return@forEach
                w += g.advance + if (run.bold) g.boldOffset else 0f
            }
        }
        return w
    }

    /**
     * [maxWidth] 에 맞춰 줄을 나눈다(책 쪽). 줄바꿈 문자에서 끊고, 넘치면 마지막 띄어쓰기에서(없으면 그 글자에서).
     * 서식은 조각마다 그대로 따라간다.
     */
    fun wrap(component: Component, maxWidth: Float, defaultColor: Int): List<List<Run>> {
        class Ch(val cp: Int, val run: Run)
        val chars = ArrayList<Ch>()
        for (run in runs(component, defaultColor)) run.text.codePoints().forEach { chars += Ch(it, run) }
        val lines = ArrayList<List<Ch>>()
        var line = ArrayList<Ch>()
        var width = 0f
        var lastSpace = -1
        for (ch in chars) {
            if (ch.cp == '\n'.code) {
                lines += line; line = ArrayList(); width = 0f; lastSpace = -1
                continue
            }
            val g = fonts.font(ch.run.font).glyph(ch.cp)
            val w = (g?.advance ?: 0f) + if (ch.run.bold) g?.boldOffset ?: 0f else 0f
            if (width + w > maxWidth && line.isNotEmpty()) {
                if (lastSpace >= 0) {
                    lines += line.subList(0, lastSpace).toList()
                    line = ArrayList(line.subList(lastSpace + 1, line.size))
                } else {
                    lines += line
                    line = ArrayList()
                }
                width = line.sumOf { c -> (fonts.font(c.run.font).glyph(c.cp)?.advance ?: 0f).toDouble() }.toFloat()
                lastSpace = -1
            }
            if (ch.cp == ' '.code) lastSpace = line.size
            line += ch
            width += w
        }
        lines += line
        // 같은 서식이 이어지면 한 조각으로.
        return lines.map { chs ->
            val out = ArrayList<Run>()
            for (c in chs) {
                val text = String(Character.toChars(c.cp))
                val last = out.lastOrNull()
                if (last != null && last.copy(text = "") == c.run.copy(text = "")) out[out.size - 1] = last.copy(text = last.text + text)
                else out += c.run.copy(text = text)
            }
            out
        }
    }

    /** 이미 나눈 조각들을 한 줄로 그린다. */
    fun drawLine(raster: Raster, runs: List<Run>, x: Float, y: Float, s: Int, shadow: Boolean): Float {
        if (shadow) drawRuns(raster, runs, x, y, s, shadowPass = true)
        return drawRuns(raster, runs, x, y, s, shadowPass = false)
    }

    /** @return 그린 너비(GUI 픽셀) */
    fun draw(raster: Raster, component: Component, x: Float, y: Float, s: Int, defaultColor: Int = Raster.WHITE, shadow: Boolean = true): Float {
        val runs = runs(component, defaultColor)
        if (shadow) drawRuns(raster, runs, x, y, s, shadowPass = true)
        return drawRuns(raster, runs, x, y, s, shadowPass = false)
    }

    private fun drawRuns(raster: Raster, runs: List<Run>, x0: Float, y: Float, s: Int, shadowPass: Boolean): Float {
        var x = x0
        for (run in runs) {
            val font = fonts.font(run.font)
            val color = if (shadowPass) run.shadow ?: shadowOf(run.color) else run.color
            if (shadowPass && run.shadow != null && run.shadow ushr 24 == 0) {
                // 그림자를 끈 글(투명 그림자) — 자리만 차지한다.
                run.text.codePoints().forEach { cp -> font.glyph(cp)?.let { x += it.advance + if (run.bold) it.boldOffset else 0f } }
                continue
            }
            val start = x
            run.text.codePoints().forEach { cp ->
                var g = font.glyph(cp) ?: return@forEach
                if (run.obfuscated) g = obfuscate(font, g)
                val off = if (shadowPass) g.shadowOffset else 0f
                drawGlyph(raster, g, x + off, y + off, s, color, run.italic)
                if (run.bold) drawGlyph(raster, g, x + off + g.boldOffset, y + off, s, color, run.italic)
                x += g.advance + if (run.bold) g.boldOffset else 0f
            }
            val off = if (shadowPass) 1f else 0f
            if (run.strikethrough) rect(raster, start - 1 + off, y + 3.5f + off, x + off, y + 4.5f + off, s, color)
            if (run.underlined) rect(raster, start - 1 + off, y + 8f + off, x + off, y + 9f + off, s, color)
        }
        return x - x0
    }

    private fun obfuscate(font: FontSet, g: Glyph): Glyph {
        repeat(16) {
            val candidate = font.glyph(Random.nextInt(0x21, 0x7F)) ?: return@repeat
            if (candidate.advance == g.advance) return candidate
        }
        return g
    }

    private fun rect(raster: Raster, x0: Float, y0: Float, x1: Float, y1: Float, s: Int, color: Int) {
        val ax = (x0 * s).roundToInt(); val ay = (y0 * s).roundToInt()
        raster.fillRect(ax, ay, (x1 * s).roundToInt() - ax, maxOf(1, (y1 * s).roundToInt() - ay), color)
    }

    fun drawGlyph(raster: Raster, g: Glyph, x: Float, y: Float, s: Int, color: Int, italic: Boolean) {
        val image = g.image ?: return
        val px = g.scale * s
        val destW = ceil(g.srcW * px).toInt()
        val destH = ceil(g.srcH * px).toInt()
        val left = x * s
        val top = (y + g.top) * s
        for (dy in 0 until destH) {
            val sy = (dy / px).toInt().coerceAtMost(g.srcH - 1)
            // 마크의 기울임: 글자 위는 오른쪽으로 1, 아래로 갈수록 왼쪽으로(GUI 한 픽셀에 0.25).
            val shift = if (italic) (1f - 0.25f * (g.top + (dy + 0.5f) / s)) * s else 0f
            for (dx in 0 until destW) {
                val sx = (dx / px).toInt().coerceAtMost(g.srcW - 1)
                val c = image.getRGB(g.srcX + sx, g.srcY + sy)
                if (c ushr 24 == 0) continue
                raster.blend((left + dx + shift).roundToInt(), (top + dy).roundToInt(), Raster.multiply(c, color))
            }
        }
    }

    companion object {
        /** 마크 그림자 = 글자색의 1/4 밝기. */
        fun shadowOf(color: Int): Int {
            val r = ((color shr 16) and 255) / 4
            val g = ((color shr 8) and 255) / 4
            val b = (color and 255) / 4
            return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }
}
