package com.inmc.discord.render.special

import com.google.gson.JsonObject
import com.inmc.discord.render.Skins
import com.inmc.discord.render.assets.AssetPack
import com.inmc.discord.render.model.ItemView
import com.inmc.discord.render.raster.Canvas3D
import com.inmc.discord.render.raster.Lights
import org.joml.Matrix4f
import org.joml.Vector3f
import java.awt.image.BufferedImage

/**
 * 아이템 정의의 `minecraft:special` — 엔티티 모델로 그리는 아이템. 모델 상자는 마크 각 렌더러의 `createBodyLayer` 를 옮긴 것.
 * 그릴 줄 모르는 종류(용 머리·꾸며진 항아리 등)는 false — 부르는 쪽이 대신 그림 한 장을 놓는다.
 */
class SpecialModels(private val assets: AssetPack, private val skins: Skins?) {

    fun render(canvas: Canvas3D, spec: JsonObject, view: ItemView, pose: Matrix4f, light: Lights): Boolean {
        val type = spec.get("type")?.asString?.let(AssetPack::normalize) ?: return false
        return when (type) {
            "minecraft:chest" -> chest(canvas, spec, pose, light)
            "minecraft:shulker_box" -> shulker(canvas, spec, pose, light)
            "minecraft:head" -> head(canvas, spec, pose, light)
            "minecraft:player_head" -> playerHead(canvas, view, pose, light)
            "minecraft:shield" -> shield(canvas, view, pose, light)
            "minecraft:banner" -> banner(canvas, spec, view, pose, light)
            "minecraft:conduit" -> conduit(canvas, pose, light)
            "minecraft:decorated_pot" -> decoratedPot(canvas, pose, light)
            else -> false
        }
    }

    private fun texture(location: String): BufferedImage? = assets.texture(location)

    // --- 상자 -------------------------------------------------------------------

    private val chestParts = listOf(
        Part(listOf(Box(0, 19, 1f, 0f, 1f, 14f, 10f, 14f))),
        Part(listOf(Box(0, 0, 1f, 0f, 0f, 14f, 5f, 14f)), offset = Vector3f(0f, 9f, 1f)),
        Part(listOf(Box(0, 0, 7f, -2f, 14f, 2f, 4f, 1f)), offset = Vector3f(0f, 9f, 1f)),
    )

    private fun chest(canvas: Canvas3D, spec: JsonObject, pose: Matrix4f, light: Lights): Boolean {
        val name = spec.get("texture")?.asString ?: "minecraft:normal"
        val (ns, path) = AssetPack.split(name)
        val tex = texture("$ns:entity/chest/$path") ?: return false
        EntityModel.draw(canvas, pose, chestParts, tex, 64, 64, -1, light)
        return true
    }

    // --- 셜커 상자 ---------------------------------------------------------------

    private val shulkerParts = listOf(
        Part(listOf(Box(0, 0, -8f, -16f, -8f, 16f, 12f, 16f)), offset = Vector3f(0f, 24f, 0f)),
        Part(listOf(Box(0, 28, -8f, -8f, -8f, 16f, 8f, 16f)), offset = Vector3f(0f, 24f, 0f)),
    )

    private fun shulker(canvas: Canvas3D, spec: JsonObject, pose: Matrix4f, light: Lights): Boolean {
        val name = spec.get("texture")?.asString ?: "minecraft:shulker"
        val (ns, path) = AssetPack.split(name)
        val tex = texture("$ns:entity/shulker/$path") ?: return false
        EntityModel.draw(canvas, pose, shulkerParts, tex, 64, 64, -1, light)
        return true
    }

    // --- 머리 -------------------------------------------------------------------

    private val skull = listOf(Part(listOf(Box(0, 0, -4f, -8f, -4f, 8f, 8f, 8f))))
    private val humanoidHead = listOf(Part(listOf(Box(0, 0, -4f, -8f, -4f, 8f, 8f, 8f), Box(32, 0, -4f, -8f, -4f, 8f, 8f, 8f, grow = 0.25f))))
    private val piglinHead = listOf(
        Part(
            listOf(
                Box(0, 0, -5f, -8f, -4f, 10f, 8f, 8f),
                Box(31, 1, -2f, -4f, -5f, 4f, 4f, 1f),
                Box(2, 4, 2f, -2f, -5f, 1f, 2f, 1f),
                Box(2, 0, -3f, -2f, -5f, 1f, 2f, 1f),
            ),
        ),
        Part(listOf(Box(51, 6, 0f, 0f, -2f, 1f, 5f, 4f)), offset = Vector3f(4.5f, -6f, 0f), zRot = -0.5235988f),
        Part(listOf(Box(39, 6, -1f, 0f, -2f, 1f, 5f, 4f)), offset = Vector3f(-4.5f, -6f, 0f), zRot = 0.5235988f),
    )

    private fun head(canvas: Canvas3D, spec: JsonObject, pose: Matrix4f, light: Lights): Boolean {
        val kind = spec.get("kind")?.asString ?: return false
        val override = spec.get("texture")?.asString
        val (parts, location, w, h) = when (kind) {
            "skeleton" -> Quad(skull, "minecraft:entity/skeleton/skeleton", 64, 32)
            "wither_skeleton" -> Quad(skull, "minecraft:entity/skeleton/wither_skeleton", 64, 32)
            "zombie" -> Quad(humanoidHead, "minecraft:entity/zombie/zombie", 64, 64)
            "creeper" -> Quad(skull, "minecraft:entity/creeper/creeper", 64, 32)
            "piglin" -> Quad(piglinHead, "minecraft:entity/piglin/piglin", 64, 64)
            "dragon" -> return dragonHead(canvas, pose, light)
            else -> return false
        }
        val tex = texture(override ?: location) ?: return false
        EntityModel.draw(canvas, pose, parts, tex, w, h, -1, light)
        return true
    }

    private data class Quad(val parts: List<Part>, val texture: String, val w: Int, val h: Int)

    private fun playerHead(canvas: Canvas3D, view: ItemView, pose: Matrix4f, light: Lights): Boolean {
        val skin = view.skinTexture?.let(::texture)
            ?: view.skinUrl?.let { skins?.get(it) }
            ?: texture(if (view.skinSlim) "minecraft:entity/player/slim/steve" else "minecraft:entity/player/wide/steve")
            ?: return false
        // 옛 64×32 스킨도 머리·모자 자리는 같다.
        EntityModel.draw(canvas, pose, humanoidHead, skin, 64, if (skin.height >= 64) 64 else 32, -1, light)
        return true
    }

    // --- 용 머리 (마크 DragonHeadModel — 주둥이·머리·비늘 둘·콧구멍 둘, 아래턱은 조금 벌린 채, 2026-10-08) -------------

    private val dragonHead = listOf(
        Part(
            listOf(
                Box(176, 44, -6f, -1f, -24f, 12f, 5f, 16f),
                Box(112, 30, -8f, -8f, -10f, 16f, 16f, 16f),
                Box(0, 0, -5f, -12f, -4f, 2f, 4f, 6f, mirror = true),
                Box(112, 0, -5f, -3f, -22f, 2f, 2f, 4f, mirror = true),
                Box(0, 0, 3f, -12f, -4f, 2f, 4f, 6f),
                Box(112, 0, 3f, -3f, -22f, 2f, 2f, 4f),
            ),
        ),
        Part(listOf(Box(176, 65, -6f, 0f, -16f, 12f, 4f, 16f)), offset = Vector3f(0f, 4f, -8f), xRot = 0.2f),
    )

    private fun dragonHead(canvas: Canvas3D, pose: Matrix4f, light: Lights): Boolean {
        val tex = texture("minecraft:entity/enderdragon/dragon") ?: return false
        // 마크 DragonHeadModel.renderToBuffer — 0.374375 내리고 0.75 배(머리가 한 칸보다 커서).
        EntityModel.draw(canvas, Matrix4f(pose).translate(0f, -0.374375f, 0f).scale(0.75f), dragonHead, tex, 256, 256, -1, light)
        return true
    }

    // --- 꾸며진 항아리 (마크 DecoratedPotRenderer — 목·입술·위·아래는 base, 네 면은 side(무늬 없는 항아리), 2026-10-08) -----

    private val potBase = listOf(
        Part(listOf(Box(0, 0, 4f, 17f, 4f, 8f, 3f, 8f), Box(0, 5, 5f, 20f, 5f, 6f, 1f, 6f)), offset = Vector3f(0f, 37f, 16f), xRot = Math.PI.toFloat()),
        Part(listOf(Box(-14, 13, 0f, 0f, 0f, 14f, 0f, 14f)), offset = Vector3f(1f, 16f, 1f)),
        Part(listOf(Box(-14, 13, 0f, 0f, 0f, 14f, 0f, 14f)), offset = Vector3f(1f, 0f, 1f)),
    )
    private val potSide = Box(1, 0, 0f, 0f, 0f, 14f, 16f, 0f)
    private val potSides = listOf(
        Part(listOf(potSide), offset = Vector3f(1f, 0f, 1f)),
        Part(listOf(potSide), offset = Vector3f(15f, 0f, 15f), yRot = Math.PI.toFloat()),
        Part(listOf(potSide), offset = Vector3f(1f, 0f, 15f), yRot = -Math.PI.toFloat() / 2f),
        Part(listOf(potSide), offset = Vector3f(15f, 0f, 1f), yRot = Math.PI.toFloat() / 2f),
    )

    private fun decoratedPot(canvas: Canvas3D, pose: Matrix4f, light: Lights): Boolean {
        val base = texture("minecraft:entity/decorated_pot/decorated_pot_base") ?: return false
        val side = texture("minecraft:entity/decorated_pot/decorated_pot_side") ?: return false
        EntityModel.draw(canvas, pose, potBase, base, 32, 32, -1, light)
        EntityModel.draw(canvas, pose, potSides, side, 16, 16, -1, light)
        return true
    }

    // --- 방패 ------------------------------------------------------------------

    private val shieldPlate = listOf(Part(listOf(Box(0, 0, -6f, -11f, -2f, 12f, 22f, 1f))))
    private val shieldHandle = listOf(Part(listOf(Box(26, 0, -1f, -3f, -1f, 2f, 6f, 6f))))

    private fun shield(canvas: Canvas3D, view: ItemView, pose: Matrix4f, light: Lights): Boolean {
        val plain = view.baseColor == null && view.patterns.isEmpty()
        val base = texture(if (plain) "minecraft:entity/shield/shield_base_nopattern" else "minecraft:entity/shield/shield_base") ?: return false
        EntityModel.draw(canvas, pose, shieldHandle, base, 64, 64, -1, light)
        EntityModel.draw(canvas, pose, shieldPlate, base, 64, 64, -1, light)
        if (!plain) patterns(canvas, pose, shieldPlate, "shield", view.baseColor ?: "white", view.patterns, light)
        return true
    }

    // --- 깃발 ------------------------------------------------------------------

    // 1.21.4 부터의 서 있는 깃발(`BannerModel`·`BannerFlagModel` standing): 봉 -42..0, 가로대 -44..-42, 천은 -44 에서 40 내려온다.
    private val bannerFlag = listOf(Part(listOf(Box(0, 0, -10f, 0f, -2f, 20f, 40f, 1f)), offset = Vector3f(0f, -44f, 0f)))
    private val bannerPole = listOf(
        Part(listOf(Box(44, 0, -1f, -42f, -1f, 2f, 42f, 2f))),
        Part(listOf(Box(0, 42, -10f, -44f, -1f, 20f, 2f, 2f))),
    )

    private fun banner(canvas: Canvas3D, spec: JsonObject, view: ItemView, pose: Matrix4f, light: Lights): Boolean {
        val pole = texture("minecraft:entity/banner/banner_base") ?: return false
        EntityModel.draw(canvas, pose, bannerPole, pole, 64, 64, -1, light)
        patterns(canvas, pose, bannerFlag, "banner", spec.get("color")?.asString ?: view.baseColor ?: "white", view.patterns, light)
        return true
    }

    /** 바탕 층(바탕 색) + 무늬 층들(각 염료 색)을 같은 면에 차례로. 앞 층 위에 덮이도록 조금씩 앞으로 낸다. */
    private fun patterns(canvas: Canvas3D, pose: Matrix4f, parts: List<Part>, folder: String, base: String, layers: List<Pair<String, String>>, light: Lights) {
        val all = listOf("minecraft:base" to base) + layers
        all.forEachIndexed { i, (pattern, dye) ->
            val (ns, path) = AssetPack.split(pattern)
            val tex = texture("$ns:entity/$folder/$path") ?: return@forEachIndexed
            val color = ItemView.DYES[dye] ?: -1
            val nudged = Matrix4f(pose).translate(0f, 0f, -0.0005f * (i + 1))
            EntityModel.draw(canvas, nudged, parts, tex, 64, 64, color, light)
        }
    }

    // --- 콘듀잇 -----------------------------------------------------------------

    private val conduitShell = listOf(Part(listOf(Box(0, 0, -3f, -3f, -3f, 6f, 6f, 6f))))

    private fun conduit(canvas: Canvas3D, pose: Matrix4f, light: Lights): Boolean {
        val tex = texture("minecraft:entity/conduit/base") ?: return false
        EntityModel.draw(canvas, pose, conduitShell, tex, 32, 16, -1, light)
        return true
    }
}
