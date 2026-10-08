package com.inmc.discord.render.images

import com.inmc.discord.render.Raster
import com.inmc.discord.render.assets.AssetPack
import com.inmc.discord.render.font.TextRenderer
import net.kyori.adventure.text.Component
import java.awt.image.BufferedImage
import kotlin.math.ceil

/**
 * 툴팁 그림 — 마크 `GuiGraphics.renderTooltip` + `TooltipRenderUtil` 과 같은 배치.
 * 줄 높이 10, 첫 줄 뒤 2 띄움, 글 둘레 여백 3, 배경·테두리 그림은 그 바깥 9 까지(9조각 GUI 스프라이트).
 * 아이템의 `tooltip_style` 이 있으면 `<style>_background`·`<style>_frame` 을 쓴다.
 */
class Tooltips(private val assets: AssetPack, private val text: TextRenderer) {

    fun render(lines: List<Component>, style: String?, s: Int): Raster {
        if (lines.isEmpty()) return Raster(1, 1)
        val w = ceil(lines.maxOf { text.width(it) }).toInt()
        val h = 10 * lines.size - 2 + if (lines.size > 1) 2 else 0
        val raster = Raster((w + MARGIN * 2) * s, (h + MARGIN * 2) * s)
        background(raster, style, w + MARGIN * 2, h + MARGIN * 2, s)
        var y = MARGIN.toFloat()
        lines.forEachIndexed { i, line ->
            text.draw(raster, line, MARGIN.toFloat(), y, s)
            y += 10f + if (i == 0) 2f else 0f
        }
        return raster.trimmed()
    }

    private fun background(raster: Raster, style: String?, w: Int, h: Int, s: Int) {
        val (ns, path) = style?.let(AssetPack::split) ?: ("minecraft" to "")
        val prefix = if (style == null) "minecraft:tooltip/" else "$ns:tooltip/${path}_"
        val bg = sprite("${prefix}background") ?: sprite("minecraft:tooltip/background")
        val frame = sprite("${prefix}frame") ?: sprite("minecraft:tooltip/frame")
        if (bg == null || frame == null) {
            legacy(raster, w, h, s)
            return
        }
        bg.draw(raster, 0, 0, w, h, s)
        frame.draw(raster, 0, 0, w, h, s)
    }

    /** 9조각 GUI 스프라이트(`.mcmeta` 의 `gui.scaling`). */
    private inner class Sprite(val image: BufferedImage, val width: Int, val height: Int, val border: Border, val stretchInner: Boolean) {
        fun draw(raster: Raster, x: Int, y: Int, w: Int, h: Int, s: Int) {
            // 그림 한 픽셀 = GUI 한 픽셀(스프라이트 단위 width 가 그림 너비와 다르면 비율).
            val kx = image.width / width
            val ky = image.height / height
            val l = border.left; val r = border.right; val t = border.top; val b = border.bottom
            fun part(sx: Int, sy: Int, sw: Int, sh: Int, dx: Int, dy: Int, dw: Int, dh: Int, stretch: Boolean) {
                if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return
                if (stretch) raster.drawStretched(image, sx * kx, sy * ky, sw * kx, sh * ky, (x + dx) * s, (y + dy) * s, dw * s, dh * s)
                else tile(raster, image, sx * kx, sy * ky, sw * kx, sh * ky, (x + dx) * s, (y + dy) * s, dw * s, dh * s, s / kx.coerceAtLeast(1))
            }
            val cw = width - l - r; val ch = height - t - b
            val iw = w - l - r; val ih = h - t - b
            part(0, 0, l, t, 0, 0, l, t, true)
            part(width - r, 0, r, t, w - r, 0, r, t, true)
            part(0, height - b, l, b, 0, h - b, l, b, true)
            part(width - r, height - b, r, b, w - r, h - b, r, b, true)
            part(l, 0, cw, t, l, 0, iw, t, stretchInner)
            part(l, height - b, cw, b, l, h - b, iw, b, stretchInner)
            part(0, t, l, ch, 0, t, l, ih, stretchInner)
            part(width - r, t, r, ch, w - r, t, r, ih, stretchInner)
            part(l, t, cw, ch, l, t, iw, ih, stretchInner)
        }
    }

    private fun tile(raster: Raster, image: BufferedImage, sx: Int, sy: Int, sw: Int, sh: Int, dx: Int, dy: Int, dw: Int, dh: Int, scale: Int) {
        val k = scale.coerceAtLeast(1)
        for (y in 0 until dh) for (x in 0 until dw) {
            raster.blend(dx + x, dy + y, image.getRGB(sx + (x / k) % sw, sy + (y / k) % sh))
        }
    }

    private class Border(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun sprite(location: String): Sprite? {
        val (ns, path) = AssetPack.split(location)
        val file = "assets/$ns/textures/gui/sprites/$path.png"
        val image = assets.image(file) ?: return null
        val scaling = assets.json("$file.mcmeta")?.getAsJsonObject("gui")?.getAsJsonObject("scaling")
        val width = scaling?.get("width")?.asInt ?: image.width
        val height = scaling?.get("height")?.asInt ?: image.height
        val borderJson = scaling?.get("border")
        val border = when {
            borderJson == null -> Border(0, 0, 0, 0)
            borderJson.isJsonPrimitive -> borderJson.asInt.let { Border(it, it, it, it) }
            else -> borderJson.asJsonObject.let { Border(it.get("left").asInt, it.get("top").asInt, it.get("right").asInt, it.get("bottom").asInt) }
        }
        return Sprite(image, width, height, border, scaling?.get("stretch_inner")?.asBoolean ?: false)
    }

    /** 스프라이트가 없는 옛 팩 — 1.20 의 그라데이션 테두리. */
    private fun legacy(raster: Raster, w: Int, h: Int, s: Int) {
        val bg = 0xF0100010.toInt()
        val x0 = (MARGIN - 3) * s; val y0 = (MARGIN - 3) * s
        val x1 = (w - MARGIN + 3) * s; val y1 = (h - MARGIN + 3) * s
        raster.fillRect(x0, y0 - s, x1 - x0, s, bg)
        raster.fillRect(x0, y1, x1 - x0, s, bg)
        raster.fillRect(x0 - s, y0, s, y1 - y0, bg)
        raster.fillRect(x1, y0, s, y1 - y0, bg)
        raster.fillRect(x0, y0, x1 - x0, y1 - y0, bg)
        val top = 0x505000FF; val bottom = 0x5028007F
        for (yy in y0 until y1) {
            val f = (yy - y0).toFloat() / (y1 - y0)
            val c = lerp(top, bottom, f)
            raster.fillRect(x0, yy, s, 1, c)
            raster.fillRect(x1 - s, yy, s, 1, c)
        }
        raster.fillRect(x0, y0, x1 - x0, s, top)
        raster.fillRect(x0, y1 - s, x1 - x0, s, bottom)
    }

    private fun lerp(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) = ((((a shr shift) and 255) * (1 - f)) + (((b shr shift) and 255) * f)).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        /** 글 둘레 여백 3 + 스프라이트 바깥 9. */
        const val MARGIN = 12
    }
}
