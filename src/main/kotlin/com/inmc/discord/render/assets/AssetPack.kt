package com.inmc.discord.render.assets

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * 리소스팩처럼 겹친 자원. **뒤의 층이 앞을 덮는다** — 바닐라 클라이언트 jar < 자산 객체 폴더(한글 글꼴·번역) < 서버 팩(커스텀아이템).
 * zip 을 풀지 않고 바로 읽고, 읽은 것은 메모리에 둔다(그림 일꾼 둘이 같이 읽으니 동시성 맵).
 *
 * 경로는 `assets/<ns>/<…>` 그대로 쓴다. 자원 위치(`minecraft:item/stick`)는 [path] 로 바꾼다.
 */
class AssetPack(private val layers: List<Layer>) : Closeable {

    interface Layer : Closeable {
        val name: String
        fun bytes(path: String): ByteArray?
        fun list(prefix: String): List<String>
    }

    /**
     * zip 리소스팩. [overlays] 는 `pack.mcmeta` 의 overlay 폴더 가운데 이 판에서 켜지는 것(뒤의 것이 위) — [activeOverlays].
     */
    class ZipLayer(file: File, private val overlays: List<String> = emptyList()) : Layer {
        override val name: String = file.name + if (overlays.isEmpty()) "" else "(+${overlays.size})"
        private val zip = ZipFile(file)
        override fun bytes(path: String): ByteArray? {
            for (overlay in overlays.asReversed()) zip.getEntry("$overlay/$path")?.let { e -> return zip.getInputStream(e).use { it.readBytes() } }
            return zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        }
        override fun list(prefix: String): List<String> = zip.entries().asSequence().map { it.name }.filter { it.startsWith(prefix) && !it.endsWith("/") }.toList()
        override fun close() = zip.close()
    }

    class DirLayer(private val root: File) : Layer {
        override val name: String = root.name
        override fun bytes(path: String): ByteArray? = File(root, path).takeIf { it.isFile }?.readBytes()
        override fun list(prefix: String): List<String> {
            val base = File(root, prefix)
            if (!base.exists()) return emptyList()
            return base.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        }
        override fun close() = Unit
    }

    /** 테스트·그림 미리보기용 — 메모리 안의 파일들. */
    class MapLayer(override val name: String, private val files: Map<String, ByteArray>) : Layer {
        override fun bytes(path: String): ByteArray? = files[path]
        override fun list(prefix: String): List<String> = files.keys.filter { it.startsWith(prefix) }
        override fun close() = Unit
    }

    private val images = ConcurrentHashMap<String, Any>()
    private val jsons = ConcurrentHashMap<String, Any>()

    fun layerNames(): List<String> = layers.map { it.name }

    fun bytes(path: String): ByteArray? {
        for (layer in layers.asReversed()) layer.bytes(path)?.let { return it }
        return null
    }

    fun exists(path: String): Boolean = layers.any { it.bytes(path) != null }

    /**
     * 모든 층의 같은 파일 — 아래 층부터. 마크가 **합치는** 것(언어 파일·글꼴 정의)은 이것으로 읽는다.
     * [bytes] 처럼 위 층 하나만 보면, 자기 항목만 든 팩의 `ko_kr.json`·`font/default.json` 이 바닐라를 통째로 지운다.
     */
    fun stack(path: String): List<ByteArray> = layers.mapNotNull { it.bytes(path) }

    /** 모든 층에서 — 글꼴의 `include/unifont.json` 처럼 덮인 것이 아니라 합쳐야 하는 것은 없다(마크도 덮는다). */
    fun list(prefix: String): Set<String> = layers.flatMapTo(LinkedHashSet()) { it.list(prefix) }

    fun json(path: String): JsonObject? {
        val cached = jsons[path]
        if (cached != null) return cached as? JsonObject
        val parsed: Any = bytes(path)?.let { runCatching { JsonParser.parseString(String(it, Charsets.UTF_8)).asJsonObject }.getOrNull() } ?: MISSING
        jsons[path] = parsed
        return parsed as? JsonObject
    }

    /** 그림. 움직이는 그림(`.mcmeta` 의 animation)은 첫 장만. */
    fun image(path: String): BufferedImage? {
        val cached = images[path]
        if (cached != null) return cached as? BufferedImage
        val loaded: Any = bytes(path)?.let { data ->
            runCatching { ImageIO.read(data.inputStream()) }.getOrNull()?.let { img ->
                val argb = toArgb(img)
                if (json("$path.mcmeta")?.has("animation") == true && argb.height > argb.width) {
                    val frame = json("$path.mcmeta")!!.getAsJsonObject("animation")
                    val w = frame.get("width")?.asInt ?: argb.width
                    val h = frame.get("height")?.asInt ?: w
                    argb.getSubimage(0, 0, w.coerceAtMost(argb.width), h.coerceAtMost(argb.height))
                } else argb
            }
        } ?: MISSING
        images[path] = loaded
        return loaded as? BufferedImage
    }

    /**
     * 텍스처. 파일이 없으면 아틀라스 별칭(`atlases/<이름>.json` 의 `single` 소스: sprite → resource)을 본다
     * — ItemsAdder 처럼 모델이 `ia:274` 같은 별칭을 쓰는 팩.
     */
    fun texture(location: String): BufferedImage? {
        image(path(location, "textures", ".png"))?.let { return it }
        val alias = aliases()[normalize(location)] ?: return null
        return image(path(alias, "textures", ".png"))
    }

    @Volatile
    private var aliasMap: Map<String, String>? = null

    /** 아틀라스 별칭 — 층마다의 아틀라스 정의를 합친다(마크도 팩마다의 sources 를 더한다). */
    private fun aliases(): Map<String, String> {
        aliasMap?.let { return it }
        val out = HashMap<String, String>()
        for (layer in layers) for (atlas in ATLASES) {
            val json = layer.bytes("assets/minecraft/atlases/$atlas.json")?.let { runCatching { JsonParser.parseString(String(it, Charsets.UTF_8)).asJsonObject }.getOrNull() } ?: continue
            for (element in json.getAsJsonArray("sources") ?: continue) {
                val source = element.asJsonObject
                if (source.get("type")?.asString?.removePrefix("minecraft:") != "single") continue
                val resource = source.get("resource")?.asString ?: continue
                val sprite = source.get("sprite")?.asString ?: continue
                if (normalize(sprite) != normalize(resource)) out[normalize(sprite)] = normalize(resource)
            }
        }
        aliasMap = out
        return out
    }

    override fun close() {
        for (layer in layers) runCatching { layer.close() }
    }

    companion object {

        private val MISSING = Any()

        private val ATLASES = listOf("blocks", "items", "armor_trims", "banner_patterns", "shield_patterns", "gui", "chests", "signs")

        /**
         * `pack.mcmeta` 의 overlay 가운데 [format] 에서 켜지는 폴더(적힌 순서 = 뒤가 위). 범위는 `min_format`/`max_format`
         * (수 또는 [major, minor]) 이 있으면 그것, 없으면 `formats`(수 · [min, max] · {min_inclusive, max_inclusive}).
         */
        fun activeOverlays(mcmeta: JsonObject?, format: Int): List<String> {
            val entries = mcmeta?.getAsJsonObject("overlays")?.getAsJsonArray("entries") ?: return emptyList()
            fun major(e: com.google.gson.JsonElement?): Int? = when {
                e == null || e.isJsonNull -> null
                e.isJsonArray -> e.asJsonArray.firstOrNull()?.asInt
                else -> e.asInt
            }
            return entries.map { it.asJsonObject }.filter { entry ->
                val min = major(entry.get("min_format"))
                val max = major(entry.get("max_format"))
                val range: IntRange = if (min != null || max != null) (min ?: 0)..(max ?: Int.MAX_VALUE) else {
                    val f = entry.get("formats") ?: return@filter false
                    when {
                        f.isJsonPrimitive -> f.asInt..f.asInt
                        f.isJsonArray -> f.asJsonArray.let { a -> a[0].asInt..a[a.size() - 1].asInt }
                        else -> f.asJsonObject.let { o -> o.get("min_inclusive").asInt..o.get("max_inclusive").asInt }
                    }
                }
                format in range
            }.mapNotNull { it.get("directory")?.asString }
        }

        /** 클라이언트 jar 의 `version.json` → 리소스팩 형식(26.2 = 88). */
        fun resourceFormat(versionJson: ByteArray?): Int? = runCatching {
            val pack = JsonParser.parseString(String(versionJson!!, Charsets.UTF_8)).asJsonObject.get("pack_version")
            if (pack.isJsonObject) pack.asJsonObject.let { (it.get("resource_major") ?: it.get("resource")).asInt } else pack.asInt
        }.getOrNull()

        /** zip 팩 하나를 overlay 까지 맞춰 연다. */
        fun zipPack(file: File, format: Int): ZipLayer {
            val mcmeta = ZipFile(file).use { zip -> zip.getEntry("pack.mcmeta")?.let { e -> zip.getInputStream(e).use { JsonParser.parseString(String(it.readBytes(), Charsets.UTF_8)).asJsonObject } } }
            return ZipLayer(file, activeOverlays(mcmeta, format))
        }

        /** `minecraft:item/stick` + (`textures`, `.png`) → `assets/minecraft/textures/item/stick.png`. 이름공간이 없으면 minecraft. */
        fun path(location: String, kind: String, extension: String): String {
            val (ns, p) = split(location)
            return "assets/$ns/$kind/$p$extension"
        }

        fun split(location: String): Pair<String, String> {
            val colon = location.indexOf(':')
            return if (colon < 0) "minecraft" to location else location.substring(0, colon) to location.substring(colon + 1)
        }

        fun normalize(location: String): String = split(location).let { "${it.first}:${it.second}" }

        fun toArgb(img: BufferedImage): BufferedImage {
            if (img.type == BufferedImage.TYPE_INT_ARGB) return img
            val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until img.height) for (x in 0 until img.width) out.setRGB(x, y, img.getRGB(x, y))
            return out
        }
    }
}
