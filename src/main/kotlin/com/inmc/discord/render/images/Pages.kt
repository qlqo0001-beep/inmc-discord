package com.inmc.discord.render.images

import com.inmc.discord.render.Lang
import com.inmc.discord.render.Raster
import com.inmc.discord.render.assets.AssetPack
import com.inmc.discord.render.font.TextRenderer
import net.kyori.adventure.text.Component
import java.awt.Color

/**
 * 책 쪽과 지도 그림.
 * - 책: 마크 `BookViewScreen` — `gui/book.png` 192×192, 글은 (36, 32) 에서 너비 114·줄 높이 9·14줄, 쪽 표시는 오른쪽 위(검은 글, 그림자 없음).
 * - 지도: 128×128 색 번호(마크 지도 팔레트) — `map/map_background` 위에.
 */
class Pages(private val assets: AssetPack, private val text: TextRenderer, private val lang: () -> Lang) {

    fun book(pages: List<Component>, page: Int, s: Int): Raster {
        val raster = Raster(BOOK * s, BOOK * s)
        assets.texture("minecraft:gui/book")?.let { raster.draw(it, 0, 0, s, sx = 0, sy = 0, sw = BOOK, sh = BOOK) }
        val total = pages.size.coerceAtLeast(1)
        val index = page.coerceIn(0, total - 1)
        val indicator = lang().translate(Component.translatable("book.pageIndicator", Component.text(index + 1), Component.text(total)))
        text.draw(raster, indicator, BOOK - 44f - text.width(indicator), 18f, s, BLACK, shadow = false)
        val content = pages.getOrNull(index)?.let(lang()::translate) ?: Component.empty()
        text.wrap(content, 114f, BLACK).take(14).forEachIndexed { i, runs ->
            text.drawLine(raster, runs, 36f, 32f + i * 9f, s, shadow = false)
        }
        return raster
    }

    fun map(colors: ByteArray, s: Int): Raster {
        val size = (MAP + 2 * MAP_BORDER) * s
        val raster = Raster(size, size)
        assets.texture("minecraft:map/map_background")?.let { bg ->
            raster.drawStretched(bg, 0, 0, bg.width, bg.height, 0, 0, size, size)
        } ?: raster.fill(0xFFD6BE96.toInt())
        @Suppress("DEPRECATION")
        for (y in 0 until MAP) for (x in 0 until MAP) {
            val b = colors.getOrNull(y * MAP + x) ?: continue
            val c: Color = runCatching { org.bukkit.map.MapPalette.getColor(b) }.getOrNull() ?: continue
            if (c.alpha == 0) continue
            raster.fillRect((MAP_BORDER + x) * s, (MAP_BORDER + y) * s, s, s, c.rgb or 0xFF000000.toInt())
        }
        return raster
    }

    companion object {
        const val BOOK = 192
        const val MAP = 128
        const val MAP_BORDER = 8
        const val BLACK = 0xFF000000.toInt()
    }
}
