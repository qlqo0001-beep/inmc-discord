package com.inmc.discord.render.images

import com.inmc.discord.render.Raster
import com.inmc.discord.render.assets.AssetPack
import com.inmc.discord.render.font.TextRenderer
import net.kyori.adventure.text.Component
import java.awt.image.BufferedImage
import kotlin.math.ceil

/**
 * 탭리스트 그림 — 마크 `PlayerTabOverlay` 배치: 한 줄 9px(칸 8 + 1), 한 열 최대 20명, 열 사이 5, 얼굴 8×8(+모자 층), 오른쪽 끝 핑 막대 10×8.
 * 바탕은 반투명 검정(0x80000000), 칸은 0x20FFFFFF, 머리말·꼬리말은 가운데 정렬.
 */
class Tablist(private val assets: AssetPack, private val text: TextRenderer) {

    class Entry(val name: Component, val skin: BufferedImage?, val ping: Int)

    fun render(entries: List<Entry>, header: List<Component>, footer: List<Component>, s: Int): Raster {
        val columns = maxOf(1, ceil(entries.size / 20.0).toInt())
        val rows = if (entries.isEmpty()) 0 else ceil(entries.size / columns.toDouble()).toInt()
        val nameWidth = entries.maxOfOrNull { text.width(it.name) }?.let { ceil(it).toInt() } ?: 0
        val cell = 9 + nameWidth + 13
        val body = columns * cell + (columns - 1) * 5
        val textWidth = (header + footer).maxOfOrNull { ceil(text.width(it)).toInt() } ?: 0
        val width = maxOf(body, textWidth) + 2
        val headerHeight = if (header.isEmpty()) 0 else header.size * 9 + 1
        val footerHeight = if (footer.isEmpty()) 0 else footer.size * 9 + 1
        val height = headerHeight + rows * 9 + footerHeight + 2
        val raster = Raster(width * s, height * s)
        raster.fillRect(0, 0, width * s, height * s, BACKGROUND)

        var y = 1
        for (line in header) {
            text.draw(raster, line, (width - text.width(line)) / 2f, y.toFloat(), s)
            y += 9
        }
        if (header.isNotEmpty()) y += 1
        val left = (width - body) / 2
        entries.forEachIndexed { i, entry ->
            val col = i / rows
            val row = i % rows
            val x = left + col * (cell + 5)
            val ey = y + row * 9
            raster.fillRect(x * s, ey * s, cell * s, 8 * s, CELL)
            entry.skin?.let { face(raster, it, x, ey, s) }
            text.draw(raster, entry.name, (x + 9).toFloat(), ey.toFloat(), s)
            ping(entry.ping)?.let { raster.draw(it, (x + cell - 11) * s, ey * s, s) }
        }
        y += rows * 9 + 1
        for (line in footer) {
            text.draw(raster, line, (width - text.width(line)) / 2f, y.toFloat(), s)
            y += 9
        }
        return raster
    }

    /** 마크 `PlayerFaceRenderer` — 얼굴(8,8) 위에 모자 층(40,8). 64 픽셀 기준 칸(고해상도 스킨은 비율대로). */
    private fun face(raster: Raster, skin: BufferedImage, x: Int, y: Int, s: Int) {
        val k = skin.width / 64
        val face = skin.getSubimage(8 * k, 8 * k, 8 * k, 8 * k)
        val hat = skin.getSubimage(40 * k, 8 * k, 8 * k, 8 * k)
        raster.drawStretched(face, 0, 0, face.width, face.height, x * s, y * s, 8 * s, 8 * s)
        raster.drawStretched(hat, 0, 0, hat.width, hat.height, x * s, y * s, 8 * s, 8 * s)
    }

    private fun ping(ping: Int): BufferedImage? {
        val level = when {
            ping < 0 -> "unknown"
            ping < 150 -> "5"
            ping < 300 -> "4"
            ping < 600 -> "3"
            ping < 1000 -> "2"
            else -> "1"
        }
        return assets.image("assets/minecraft/textures/gui/sprites/icon/ping_$level.png")
    }

    private companion object {
        const val BACKGROUND = 0x80000000.toInt()
        const val CELL = 0x20FFFFFF
    }
}
