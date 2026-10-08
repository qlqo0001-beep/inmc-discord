package com.inmc.discord.render.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.inmc.discord.render.assets.AssetPack
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 26.x 아이템 정의(`assets/<ns>/items/<id>.json`) 평가 — 무엇을 어떤 색·변환으로 그릴지.
 * GUI(인벤토리 칸) 기준이다: 손에 쓰는 중·당기는 중·낚시 중 같은 상태는 전부 "아님".
 */
class ItemModels(private val assets: AssetPack, private val models: Models) {

    sealed interface Layer {
        val transform: Matrix4f?
    }

    /** 보통 모델 — [tints] 는 tintindex 번째 색(ARGB). */
    class ModelLayer(val model: BlockModel, val tints: List<Int>, override val transform: Matrix4f?) : Layer

    /** 특수 모델(머리·상자·방패·깃발 …). [base] 의 표시 변환 안에서 그린다. */
    class SpecialLayer(val spec: JsonObject, val base: BlockModel?, override val transform: Matrix4f?) : Layer

    /** @param context 표시 상황(`gui` · `thirdperson_righthand` …) — `display_context` 고르기가 본다. */
    fun layers(view: ItemView, context: String = "gui"): List<Layer> {
        val definition = assets.json(AssetPack.path(view.model, "items", ".json"))?.getAsJsonObject("model")
            ?: return fallback(view)
        val out = ArrayList<Layer>()
        node(definition, view, context, null, out)
        return out
    }

    /** 정의가 없으면(오래된 팩) `item/<id>` 모델을 바로. */
    private fun fallback(view: ItemView): List<Layer> {
        val (ns, path) = AssetPack.split(view.model)
        val model = models.get("$ns:item/$path") ?: models.get("$ns:block/$path") ?: return emptyList()
        return listOf(ModelLayer(model, emptyList(), null))
    }

    private fun node(json: JsonObject, view: ItemView, context: String, parent: Matrix4f?, out: MutableList<Layer>) {
        val transform = json.get("transformation")?.let { mul(parent, transformation(it)) } ?: parent
        when (json.get("type")?.asString?.let(AssetPack::normalize)) {
            "minecraft:model" -> {
                val model = models.get(json.get("model").asString) ?: return
                val tints = json.getAsJsonArray("tints")?.map { tint(it.asJsonObject, view) }.orEmpty()
                out += ModelLayer(model, tints, transform)
            }
            "minecraft:composite" -> json.getAsJsonArray("models")?.forEach { node(it.asJsonObject, view, context, transform, out) }
            "minecraft:condition" -> {
                val branch = if (condition(json, view)) "on_true" else "on_false"
                json.getAsJsonObject(branch)?.let { node(it, view, context, transform, out) }
            }
            "minecraft:select" -> {
                val value = select(json, view, context)
                val case = json.getAsJsonArray("cases")?.firstOrNull { c ->
                    val whenValue = c.asJsonObject.get("when")
                    val options = if (whenValue.isJsonArray) whenValue.asJsonArray.map { it.asString } else listOf(whenValue.asString)
                    value != null && options.any { same(it, value) }
                }
                (case?.asJsonObject?.getAsJsonObject("model") ?: json.getAsJsonObject("fallback"))?.let { node(it, view, context, transform, out) }
            }
            "minecraft:range_dispatch" -> {
                val value = range(json, view) * (json.get("scale")?.asFloat ?: 1f)
                val entry = json.getAsJsonArray("entries")?.map { it.asJsonObject }
                    ?.filter { it.get("threshold").asFloat <= value }
                    ?.maxByOrNull { it.get("threshold").asFloat }
                (entry?.getAsJsonObject("model") ?: json.getAsJsonObject("fallback"))?.let { node(it, view, context, transform, out) }
            }
            "minecraft:special" -> {
                val base = json.get("base")?.asString?.let(models::get)
                out += SpecialLayer(json.getAsJsonObject("model"), base, transform)
            }
            else -> Unit // empty · bundle/selected_item
        }
    }

    private fun same(a: String, b: String): Boolean =
        a == b || (a.contains(':') || b.contains(':')) && AssetPack.normalize(a) == AssetPack.normalize(b)

    private fun condition(json: JsonObject, view: ItemView): Boolean = when (AssetPack.normalize(json.get("property").asString)) {
        "minecraft:broken" -> view.maxDamage > 0 && view.damage >= view.maxDamage - 1
        "minecraft:damaged" -> view.maxDamage > 0 && view.damage > 0
        "minecraft:has_component" -> json.get("component")?.asString?.let { AssetPack.normalize(it) in view.components } ?: false
        "minecraft:custom_model_data" -> view.flags.getOrNull(json.get("index")?.asInt ?: 0) ?: false
        else -> false // using_item · fishing_rod/cast · selected · carried · keybind_down · extended_view · view_entity …
    }

    private fun select(json: JsonObject, view: ItemView, context: String): String? = when (AssetPack.normalize(json.get("property").asString)) {
        "minecraft:main_hand" -> "right"
        "minecraft:display_context" -> context
        "minecraft:charge_type" -> view.chargeType
        "minecraft:trim_material" -> view.trimMaterial
        "minecraft:block_state" -> view.blockState[json.get("block_state_property")?.asString]
        "minecraft:custom_model_data" -> view.strings.getOrNull(json.get("index")?.asInt ?: 0)
        "minecraft:local_time" -> runCatching {
            val zone = json.get("time_zone")?.asString?.let(ZoneId::of) ?: ZoneId.systemDefault()
            DateTimeFormatter.ofPattern(json.get("pattern").asString, Locale.ROOT).format(ZonedDateTime.now(zone))
        }.getOrNull()
        else -> null
    }

    private fun range(json: JsonObject, view: ItemView): Float = when (AssetPack.normalize(json.get("property").asString)) {
        "minecraft:custom_model_data" -> view.floats.getOrNull(json.get("index")?.asInt ?: 0) ?: 0f
        "minecraft:damage" -> if (json.get("normalize")?.asBoolean != false) {
            if (view.maxDamage > 0) (view.damage.toFloat() / view.maxDamage).coerceIn(0f, 1f) else 0f
        } else view.damage.toFloat()
        "minecraft:count" -> if (json.get("normalize")?.asBoolean != false) {
            (view.count.toFloat() / view.maxStack.coerceAtLeast(1)).coerceIn(0f, 1f)
        } else view.count.toFloat()
        else -> 0f // cooldown · time · compass · crossbow/pull · use_duration · use_cycle
    }

    private fun tint(json: JsonObject, view: ItemView): Int {
        val default = json.get("default")?.let(::color) ?: -1
        return when (AssetPack.normalize(json.get("type").asString)) {
            "minecraft:constant" -> json.get("value")?.let(::color) ?: -1
            "minecraft:dye" -> view.dyedColor ?: default
            "minecraft:potion" -> view.potionColor ?: default
            "minecraft:firework" -> view.fireworkColor ?: default
            "minecraft:map_color" -> view.mapColor ?: default
            "minecraft:custom_model_data" -> view.colors.getOrNull(json.get("index")?.asInt ?: 0) ?: default
            "minecraft:grass" -> colormap("minecraft:colormap/grass", json.get("temperature")?.asFloat ?: 0.5f, json.get("downfall")?.asFloat ?: 1f)
            else -> default // team …
        }
    }

    /** 마크의 기후 색표 — (1-온도, 1-습도×온도). */
    private fun colormap(texture: String, temperature: Float, downfall: Float): Int {
        val img = assets.texture(texture) ?: return 0xFF91BD59.toInt()
        val t = temperature.coerceIn(0f, 1f)
        val d = downfall.coerceIn(0f, 1f) * t
        val x = ((1f - t) * 255f).toInt().coerceIn(0, img.width - 1)
        val y = ((1f - d) * 255f).toInt().coerceIn(0, img.height - 1)
        return img.getRGB(x, y) or 0xFF000000.toInt()
    }

    companion object {

        /** 정수 ARGB 나 [r, g, b] 실수. */
        fun color(e: JsonElement): Int {
            if (e.isJsonArray) {
                val a = e.asJsonArray
                fun ch(i: Int) = (a[i].asFloat.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                return (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
            }
            val v = e.asLong.toInt()
            // 알파 없이 적은 RGB 는 불투명으로.
            return if (v ushr 24 == 0) v or 0xFF000000.toInt() else v
        }

        fun mul(a: Matrix4f?, b: Matrix4f): Matrix4f = if (a == null) b else Matrix4f(a).mul(b)

        /** `transformation` — 16칸 행렬(행 우선) 또는 {translation, left_rotation, scale, right_rotation}. */
        fun transformation(e: JsonElement): Matrix4f {
            if (e.isJsonArray) {
                val a = e.asJsonArray
                val m = FloatArray(16) { a[it].asFloat }
                return Matrix4f().setTransposed(m)
            }
            val o = e.asJsonObject
            val t = Models.vec(o.getAsJsonArray("translation")) ?: Vector3f()
            val s = Models.vec(o.getAsJsonArray("scale")) ?: Vector3f(1f, 1f, 1f)
            return Matrix4f().translate(t).rotate(quat(o.get("left_rotation"))).scale(s).rotate(quat(o.get("right_rotation")))
        }

        private fun quat(e: JsonElement?): Quaternionf {
            if (e == null) return Quaternionf()
            if (e.isJsonArray) {
                val a = e.asJsonArray
                return Quaternionf(a[0].asFloat, a[1].asFloat, a[2].asFloat, a[3].asFloat)
            }
            val o = e.asJsonObject
            val axis = Models.vec(o.getAsJsonArray("axis")) ?: Vector3f(0f, 1f, 0f)
            return Quaternionf().rotationAxis(Math.toRadians(o.get("angle").asDouble).toFloat(), axis.normalize())
        }
    }
}
