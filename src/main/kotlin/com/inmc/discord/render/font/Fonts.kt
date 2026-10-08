package com.inmc.discord.render.font

import com.google.gson.JsonObject
import com.inmc.discord.render.assets.AssetPack
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * 글자 하나 — 그림의 한 칸과 놓는 법. 단위는 **GUI 픽셀**(마크 화면 배율 1 기준).
 *
 * @param scale 그림 한 픽셀이 GUI 픽셀 몇 개인지(비트맵 = height/칸 높이, unihex = 0.5)
 * @param top 줄 위에서부터 그림 윗변까지(비트맵 = 7 - ascent)
 */
class Glyph(
    val image: BufferedImage?,
    val srcX: Int,
    val srcY: Int,
    val srcW: Int,
    val srcH: Int,
    val scale: Float,
    val top: Float,
    val advance: Float,
    val shadowOffset: Float,
    val boldOffset: Float,
) {
    companion object {
        fun space(advance: Float) = Glyph(null, 0, 0, 0, 0, 1f, 0f, advance, 1f, 1f)
    }
}

/** 글꼴 하나(`minecraft:default` 등) — 공급자를 차례로 묻는다. */
class FontSet(private val providers: List<Provider>) {

    interface Provider {
        fun glyph(codepoint: Int): Glyph?
    }

    private val cache = ConcurrentHashMap<Int, Any>()

    fun glyph(codepoint: Int): Glyph? {
        val hit = cache[codepoint]
        if (hit != null) return hit as? Glyph
        val found = providers.firstNotNullOfOrNull { it.glyph(codepoint) }
        cache[codepoint] = found ?: NONE
        return found
    }

    private companion object {
        val NONE = Any()
    }
}

/**
 * 글꼴 정의(`assets/<ns>/font/<이름>.json`)를 읽는다. 공급자 `bitmap` · `space` · `reference` · `unihex`. `ttf` 는 건너뛴다
 * (unifont 가 받친다). 필터는 마크 기본 옵션(유니코드 강제 끔·일본어 변형 끔) 기준.
 */
class Fonts(private val assets: AssetPack) {

    private val fonts = ConcurrentHashMap<String, FontSet>()
    private val unihex = ConcurrentHashMap<String, Map<Int, UnihexGlyph>>()

    fun font(id: String): FontSet = fonts.computeIfAbsent(AssetPack.normalize(id)) { key ->
        FontSet(providers(key, HashSet()))
    }

    fun default(): FontSet = font(DEFAULT)

    /**
     * 글꼴 하나의 공급자 — **층마다의 정의를 합친다**(마크 `FontManager` 처럼, 위 팩의 것이 먼저). 서버 팩은 기본 글꼴에
     * 자기 글리프만 더하는 `default.json` 을 넣으므로, 위 층 하나만 읽으면 바닐라 글자(영문·한글)가 사라진다.
     */
    private fun providers(id: String, seen: MutableSet<String>): List<FontSet.Provider> {
        if (!seen.add(id)) return emptyList()
        val definitions = assets.stack(AssetPack.path(id, "font", ".json")).asReversed().mapNotNull { bytes ->
            runCatching { com.google.gson.JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject }.getOrNull()
        }
        val out = ArrayList<FontSet.Provider>()
        for (element in definitions.flatMap { it.getAsJsonArray("providers")?.toList().orEmpty() }) {
            val p = element.asJsonObject
            if (!passes(p)) continue
            when (p.get("type")?.asString?.removePrefix("minecraft:")) {
                "bitmap" -> bitmap(p)?.let(out::add)
                "space" -> out += space(p)
                "reference" -> out += providers(AssetPack.normalize(p.get("id").asString), seen)
                "unihex" -> unihex(p)?.let(out::add)
            }
        }
        return out
    }

    private fun passes(p: JsonObject): Boolean {
        val filter = p.getAsJsonObject("filter") ?: return true
        for ((option, value) in filter.entrySet()) {
            val current = when (option) {
                "uniform" -> false
                "jp" -> false
                else -> false
            }
            if (value.asBoolean != current) return false
        }
        return true
    }

    private fun space(p: JsonObject): FontSet.Provider {
        val advances = p.getAsJsonObject("advances")?.entrySet()?.associate { (k, v) -> k.codePointAt(0) to v.asFloat }.orEmpty()
        return object : FontSet.Provider {
            override fun glyph(codepoint: Int): Glyph? = advances[codepoint]?.let(Glyph::space)
        }
    }

    private fun bitmap(p: JsonObject): FontSet.Provider? {
        // `file` 은 textures/ 아래 경로를 확장자까지 적는다(`minecraft:font/ascii.png`) — texture() 처럼 .png 를 또 붙이면 못 찾는다.
        val (ns, file) = AssetPack.split(p.get("file").asString)
        val image = assets.image("assets/$ns/textures/$file") ?: return null
        val rows = p.getAsJsonArray("chars").map { row -> row.asString.codePoints().toArray() }
        if (rows.isEmpty()) return null
        val columns = rows.maxOf { it.size }
        val cellW = image.width / columns
        val cellH = image.height / rows.size
        val height = p.get("height")?.asInt ?: 8
        val ascent = p.get("ascent").asInt
        val scale = height.toFloat() / cellH
        val glyphs = HashMap<Int, Glyph>()
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, cp ->
                if (cp == 0) return@forEachIndexed
                val sx = c * cellW
                val sy = r * cellH
                val actual = actualWidth(image, sx, sy, cellW, cellH)
                val advance = (0.5f + actual * scale).toInt() + 1f
                glyphs.putIfAbsent(cp, Glyph(image, sx, sy, cellW, cellH, scale, 7f - ascent, advance, 1f, 1f))
            }
        }
        return object : FontSet.Provider {
            override fun glyph(codepoint: Int): Glyph? = glyphs[codepoint]
        }
    }

    /** 오른쪽에서부터 처음 보이는 열 + 1. 빈 칸이면 0. */
    private fun actualWidth(image: BufferedImage, sx: Int, sy: Int, w: Int, h: Int): Int {
        for (x in w - 1 downTo 0) for (y in 0 until h) if (image.getRGB(sx + x, sy + y) ushr 24 != 0) return x + 1
        return 0
    }

    class UnihexGlyph(val image: BufferedImage, val left: Int, val right: Int)

    private class Override(val from: Int, val to: Int, val left: Int, val right: Int)

    private fun unihex(p: JsonObject): FontSet.Provider? {
        val file = p.get("hex_file").asString
        val overrides = p.getAsJsonArray("size_overrides")?.map { o ->
            val obj = o.asJsonObject
            Override(obj.get("from").asString.codePointAt(0), obj.get("to").asString.codePointAt(0), obj.get("left").asInt, obj.get("right").asInt)
        }.orEmpty()
        val (ns, rest) = AssetPack.split(file)
        val path = "assets/$ns/$rest"
        val data = assets.bytes(path) ?: return null
        val table = unihex.computeIfAbsent(path) { parseHexZip(data, overrides) }
        return object : FontSet.Provider {
            override fun glyph(codepoint: Int): Glyph? {
                val g = table[codepoint] ?: return null
                val width = g.right - g.left + 1
                val advance = (if (width > 0) width / 2 else 0) + 1f
                return Glyph(g.image, g.left.coerceAtLeast(0), 0, width.coerceAtLeast(0), 16, 0.5f, 0f, advance, 0.5f, 0.5f)
            }
        }
    }

    private fun parseHexZip(data: ByteArray, overrides: List<Override>): Map<Int, UnihexGlyph> {
        val out = HashMap<Int, UnihexGlyph>(120_000)
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.name.endsWith(".hex")) continue
                val text = zip.readBytes().toString(Charsets.US_ASCII)
                for (line in text.lineSequence()) {
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val cp = line.substring(0, colon).toIntOrNull(16) ?: continue
                    val hex = line.substring(colon + 1).trim()
                    val width = when (hex.length) {
                        32 -> 8; 48 -> 12; 64 -> 16; 96 -> 24; 128 -> 32
                        else -> continue
                    }
                    val img = BufferedImage(width, 16, BufferedImage.TYPE_INT_ARGB)
                    val digitsPerRow = width / 4
                    var minX = width
                    var maxX = -1
                    for (row in 0 until 16) {
                        val bits = hex.substring(row * digitsPerRow, (row + 1) * digitsPerRow).toLong(16)
                        for (x in 0 until width) {
                            if ((bits shr (width - 1 - x)) and 1L == 1L) {
                                img.setRGB(x, row, -1)
                                if (x < minX) minX = x
                                if (x > maxX) maxX = x
                            }
                        }
                    }
                    val o = overrides.firstOrNull { cp in it.from..it.to }
                    out[cp] = if (o != null) UnihexGlyph(img, o.left, o.right) else if (maxX < 0) UnihexGlyph(img, 0, -1) else UnihexGlyph(img, minX, maxX)
                }
            }
        }
        return out
    }

    companion object {
        const val DEFAULT = "minecraft:default"
    }
}
