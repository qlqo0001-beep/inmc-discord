package com.inmc.discord.render.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.inmc.discord.render.assets.AssetPack
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.concurrent.ConcurrentHashMap

enum class Direction(val nx: Float, val ny: Float, val nz: Float) {
    DOWN(0f, -1f, 0f), UP(0f, 1f, 0f), NORTH(0f, 0f, -1f), SOUTH(0f, 0f, 1f), WEST(-1f, 0f, 0f), EAST(1f, 0f, 0f);

    companion object {
        fun of(name: String): Direction? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** 면 하나. uv 는 텍스처의 0~16 단위(u1, v1, u2, v2). */
class Face(val uv: FloatArray?, val texture: String, val rotation: Int, val tintIndex: Int)

class ElementRotation(val origin: Vector3f, val axis: Char, val angle: Float, val rescale: Boolean)

class Element(val from: Vector3f, val to: Vector3f, val rotation: ElementRotation?, val faces: Map<Direction, Face>, val shade: Boolean)

/** 표시 변환(`display.gui` 등). translation 은 이미 1/16 로 줄인 블록 단위. */
class Transform(val rotation: Vector3f, val translation: Vector3f, val scale: Vector3f) {
    fun matrix(): Matrix4f = Matrix4f()
        .translate(translation)
        .rotate(Quaternionf().rotationXYZ(Math.toRadians(rotation.x.toDouble()).toFloat(), Math.toRadians(rotation.y.toDouble()).toFloat(), Math.toRadians(rotation.z.toDouble()).toFloat()))
        .scale(scale)

    companion object {
        val IDENTITY = Transform(Vector3f(), Vector3f(), Vector3f(1f, 1f, 1f))
    }
}

/**
 * 부모를 다 따라간 모델. [generated] 면 `builtin/generated`(텍스처 층을 그대로 그린다), 아니면 [elements].
 */
class BlockModel(
    val id: String,
    val textures: Map<String, String>,
    val elements: List<Element>,
    val display: Map<String, Transform>,
    val guiLight: String,
    val generated: Boolean,
) {
    fun gui(): Transform = display["gui"] ?: Transform.IDENTITY

    /** `#layer0` 같은 참조를 끝까지 풀어 자원 위치로. 못 풀면 null. */
    fun resolve(ref: String): String? {
        var r = ref
        repeat(16) {
            if (!r.startsWith("#")) return AssetPack.normalize(r)
            r = textures[r.substring(1)] ?: return null
        }
        return null
    }

    fun layers(): List<String> = generateSequence(0) { it + 1 }.map { textures["layer$it"] }.takeWhile { it != null }
        .mapNotNull { resolve(it!!) }.toList()
}

/** `assets/<ns>/models/<…>.json` 읽기와 캐시. */
class Models(private val assets: AssetPack) {

    private val cache = ConcurrentHashMap<String, Any>()

    fun get(id: String): BlockModel? {
        val key = AssetPack.normalize(id)
        val hit = cache[key]
        if (hit != null) return hit as? BlockModel
        val built: Any = runCatching { build(key) }.getOrNull() ?: MISSING
        cache[key] = built
        return built as? BlockModel
    }

    private fun build(id: String): BlockModel? {
        val chain = ArrayList<JsonObject>()
        var generated = false
        var current: String? = id
        val seen = HashSet<String>()
        while (current != null && seen.add(current)) {
            val bare = current.substringAfter(':')
            if (bare == "builtin/generated") {
                generated = true
                break
            }
            if (bare == "builtin/entity") break
            val json = assets.json(AssetPack.path(current, "models", ".json")) ?: break
            chain += json
            current = json.get("parent")?.asString?.let(AssetPack::normalize)
        }
        if (chain.isEmpty() && !generated) return null

        val textures = LinkedHashMap<String, String>()
        var elements: JsonArray? = null
        val display = HashMap<String, Transform>()
        var guiLight: String? = null
        // 부모부터 — 자식이 덮는다.
        for (json in chain.asReversed()) {
            json.getAsJsonObject("textures")?.entrySet()?.forEach { (k, v) -> if (v.isJsonPrimitive) textures[k] = v.asString }
            json.getAsJsonArray("elements")?.let { elements = it }
            json.getAsJsonObject("display")?.entrySet()?.forEach { (k, v) -> display[k] = transform(v.asJsonObject) }
            json.get("gui_light")?.asString?.let { guiLight = it }
        }
        return BlockModel(
            id = id,
            textures = textures,
            elements = elements?.map { element(it.asJsonObject) }.orEmpty(),
            display = display,
            guiLight = guiLight ?: "side",
            generated = generated,
        )
    }

    private fun element(json: JsonObject): Element {
        val faces = HashMap<Direction, Face>()
        json.getAsJsonObject("faces")?.entrySet()?.forEach { (k, v) ->
            val f = v.asJsonObject
            val dir = Direction.of(k) ?: return@forEach
            faces[dir] = Face(
                uv = f.getAsJsonArray("uv")?.let { a -> FloatArray(4) { a[it].asFloat } },
                texture = f.get("texture")?.asString ?: "#missing",
                rotation = f.get("rotation")?.asInt ?: 0,
                tintIndex = f.get("tintindex")?.asInt ?: -1,
            )
        }
        val rotation = json.getAsJsonObject("rotation")?.let { r ->
            ElementRotation(
                origin = vec(r.getAsJsonArray("origin")) ?: Vector3f(8f, 8f, 8f),
                axis = r.get("axis")?.asString?.firstOrNull() ?: 'y',
                angle = r.get("angle")?.asFloat ?: 0f,
                rescale = r.get("rescale")?.asBoolean ?: false,
            )
        }
        return Element(vec(json.getAsJsonArray("from"))!!, vec(json.getAsJsonArray("to"))!!, rotation, faces, json.get("shade")?.asBoolean ?: true)
    }

    companion object {
        private val MISSING = Any()

        fun vec(a: JsonArray?): Vector3f? = a?.let { Vector3f(it[0].asFloat, it[1].asFloat, it[2].asFloat) }

        /** 마크 `ItemTransform` 읽기 — translation 은 1/16, -80~80 으로 자른다. scale 은 -4~4. */
        fun transform(json: JsonObject): Transform {
            val rotation = vec(json.getAsJsonArray("rotation")) ?: Vector3f()
            val translation = (vec(json.getAsJsonArray("translation")) ?: Vector3f())
                .let { Vector3f(it.x.coerceIn(-80f, 80f), it.y.coerceIn(-80f, 80f), it.z.coerceIn(-80f, 80f)).mul(0.0625f) }
            val scale = (vec(json.getAsJsonArray("scale")) ?: Vector3f(1f, 1f, 1f))
                .let { Vector3f(it.x.coerceIn(-4f, 4f), it.y.coerceIn(-4f, 4f), it.z.coerceIn(-4f, 4f)) }
            return Transform(rotation, translation, scale)
        }
    }
}
