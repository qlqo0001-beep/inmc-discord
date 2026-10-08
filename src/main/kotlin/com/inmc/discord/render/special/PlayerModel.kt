package com.inmc.discord.render.special

import com.inmc.discord.render.assets.AssetPack
import com.inmc.discord.render.model.ItemView
import com.inmc.discord.render.raster.Canvas3D
import com.inmc.discord.render.raster.ItemIcons
import com.inmc.discord.render.raster.Lights
import org.joml.Matrix4f
import org.joml.Vector3f
import java.awt.image.BufferedImage

/**
 * 플레이어 모델(마크 `PlayerModel`) — 스킨(넓은/슬림 팔, 바깥 층), 갑옷 층(`equipment/<id>.json`), 양손 아이템(`ItemInHandLayer`).
 * [pose] 는 엔티티 좌표(블록 단위, y 아래, 앞이 -Z)에서 화면까지. 발이 y = 1.5 에 오도록 부르는 쪽이 맞춘다.
 */
class PlayerModel(private val assets: AssetPack, private val icons: ItemIcons) {

    class Equipment(val head: ItemView?, val chest: ItemView?, val legs: ItemView?, val feet: ItemView?, val mainHand: ItemView?, val offHand: ItemView?)

    fun draw(canvas: Canvas3D, pose: Matrix4f, skin: BufferedImage, slim: Boolean, gear: Equipment, light: Lights) {
        val legacy = skin.height < 64
        val armW = if (slim) 3f else 4f
        val armY = if (slim) 2.5f else 2f
        // 아이템을 든 팔은 살짝 든다(마크 `HumanoidModel` ITEM 자세: xRot = -π/10).
        val rightRot = if (gear.mainHand?.empty == false) HOLD else 0f
        val leftRot = if (gear.offHand?.empty == false) HOLD else 0f
        val parts = listOf(
            Part(listOf(Box(0, 0, -4f, -8f, -4f, 8f, 8f, 8f), Box(32, 0, -4f, -8f, -4f, 8f, 8f, 8f, grow = 0.5f))),
            Part(listOf(Box(16, 16, -4f, 0f, -2f, 8f, 12f, 4f)) + if (legacy) emptyList() else listOf(Box(16, 32, -4f, 0f, -2f, 8f, 12f, 4f, grow = 0.25f))),
            Part(
                listOf(Box(40, 16, if (slim) -2f else -3f, -2f, -2f, armW, 12f, 4f)) +
                    if (legacy) emptyList() else listOf(Box(40, 32, if (slim) -2f else -3f, -2f, -2f, armW, 12f, 4f, grow = 0.25f)),
                offset = Vector3f(-5f, armY, 0f), xRot = rightRot,
            ),
            Part(
                if (legacy) listOf(Box(40, 16, -1f, -2f, -2f, armW, 12f, 4f, mirror = true))
                else listOf(Box(32, 48, -1f, -2f, -2f, armW, 12f, 4f), Box(48, 48, -1f, -2f, -2f, armW, 12f, 4f, grow = 0.25f)),
                offset = Vector3f(5f, armY, 0f), xRot = leftRot,
            ),
            Part(listOf(Box(0, 16, -2f, 0f, -2f, 4f, 12f, 4f)) + if (legacy) emptyList() else listOf(Box(0, 32, -2f, 0f, -2f, 4f, 12f, 4f, grow = 0.25f)), offset = Vector3f(-1.9f, 12f, 0f)),
            Part(
                if (legacy) listOf(Box(0, 16, -2f, 0f, -2f, 4f, 12f, 4f, mirror = true))
                else listOf(Box(16, 48, -2f, 0f, -2f, 4f, 12f, 4f), Box(0, 48, -2f, 0f, -2f, 4f, 12f, 4f, grow = 0.25f)),
                offset = Vector3f(1.9f, 12f, 0f),
            ),
        )
        EntityModel.draw(canvas, pose, parts, skin, 64, if (legacy) 32 else 64, -1, light)

        armor(canvas, pose, gear.feet, "humanoid", FEET, 1.0f, light, rightRot, leftRot)
        armor(canvas, pose, gear.legs, "humanoid_leggings", LEGS, 0.5f, light, rightRot, leftRot)
        armor(canvas, pose, gear.chest, "humanoid", CHEST, 1.0f, light, rightRot, leftRot)
        armor(canvas, pose, gear.head, "humanoid", HEAD, 1.0f, light, rightRot, leftRot)

        hand(canvas, pose, gear.mainHand, right = true, slim = slim, armY = armY, xRot = rightRot, light = light)
        hand(canvas, pose, gear.offHand, right = false, slim = slim, armY = armY, xRot = leftRot, light = light)
    }

    /** 갑옷 한 칸 — 그 칸이 덮는 부위만, 층마다(가죽은 염색 색). */
    private fun armor(canvas: Canvas3D, pose: Matrix4f, view: ItemView?, layerType: String, slot: Int, grow: Float, light: Lights, rightRot: Float, leftRot: Float) {
        val asset = view?.equipment ?: return
        val json = assets.json(AssetPack.path(asset, "equipment", ".json")) ?: return
        val layers = json.getAsJsonObject("layers")?.getAsJsonArray(layerType) ?: return
        val parts = ArrayList<Part>()
        if (slot == HEAD) parts += Part(listOf(Box(0, 0, -4f, -8f, -4f, 8f, 8f, 8f, grow = grow), Box(32, 0, -4f, -8f, -4f, 8f, 8f, 8f, grow = grow + 0.5f)))
        if (slot == CHEST || slot == LEGS) parts += Part(listOf(Box(16, 16, -4f, 0f, -2f, 8f, 12f, 4f, grow = grow)))
        if (slot == CHEST) {
            parts += Part(listOf(Box(40, 16, -3f, -2f, -2f, 4f, 12f, 4f, grow = grow)), offset = Vector3f(-5f, 2f, 0f), xRot = rightRot)
            parts += Part(listOf(Box(40, 16, -1f, -2f, -2f, 4f, 12f, 4f, grow = grow, mirror = true)), offset = Vector3f(5f, 2f, 0f), xRot = leftRot)
        }
        if (slot == LEGS || slot == FEET) {
            parts += Part(listOf(Box(0, 16, -2f, 0f, -2f, 4f, 12f, 4f, grow = grow)), offset = Vector3f(-1.9f, 12f, 0f))
            parts += Part(listOf(Box(0, 16, -2f, 0f, -2f, 4f, 12f, 4f, grow = grow, mirror = true)), offset = Vector3f(1.9f, 12f, 0f))
        }
        for (element in layers) {
            val layer = element.asJsonObject
            val (ns, path) = AssetPack.split(layer.get("texture").asString)
            val texture = assets.texture("$ns:entity/equipment/$layerType/$path") ?: continue
            val dyeable = layer.getAsJsonObject("dyeable")
            val tint = when {
                dyeable == null -> -1
                view.dyedColor != null -> view.dyedColor
                dyeable.has("color_when_undyed") -> dyeable.get("color_when_undyed").asInt or 0xFF000000.toInt()
                else -> continue
            }
            EntityModel.draw(canvas, pose, parts, texture, 64, 32, tint, light)
        }
    }

    /** 마크 `ItemInHandLayer` — 팔 끝으로 옮기고 X -90°, Y 180°, (±1/16, 0.125, -0.625). */
    private fun hand(canvas: Canvas3D, pose: Matrix4f, view: ItemView?, right: Boolean, slim: Boolean, armY: Float, xRot: Float, light: Lights) {
        if (view == null || view.empty) return
        val armX = (if (right) -5f else 5f) + if (slim) (if (right) 0.5f else -0.5f) else 0f
        val arm = Part(emptyList(), offset = Vector3f(armX, armY, 0f), xRot = xRot)
        val m = Matrix4f(pose).mul(arm.pose())
            .rotateX((-Math.PI / 2).toFloat())
            .rotateY(Math.PI.toFloat())
            .translate((if (right) 1f else -1f) / 16f, 0.125f, -0.625f)
        icons.drawInHand(canvas, view, m, if (right) "thirdperson_righthand" else "thirdperson_lefthand", light)
    }

    private companion object {
        const val HEAD = 0
        const val CHEST = 1
        const val LEGS = 2
        const val FEET = 3
        val HOLD = (-Math.PI / 10).toFloat()
    }
}
