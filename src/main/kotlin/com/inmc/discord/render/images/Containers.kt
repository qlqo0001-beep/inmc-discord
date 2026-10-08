package com.inmc.discord.render.images

import com.inmc.discord.render.Lang
import com.inmc.discord.render.Raster
import com.inmc.discord.render.assets.AssetPack
import com.inmc.discord.render.font.TextRenderer
import com.inmc.discord.render.model.ItemView
import com.inmc.discord.render.raster.Canvas3D
import com.inmc.discord.render.raster.ItemIcons
import com.inmc.discord.render.raster.Lights
import com.inmc.discord.render.special.PlayerModel
import net.kyori.adventure.text.Component
import org.joml.Matrix4f
import org.joml.Vector3f
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * 화면 그림 — 가방(`gui/container/inventory.png` + 플레이어 모델 + 레벨)과 3줄 상자(엔더 상자).
 * 칸 자리·글자 자리는 마크 `InventoryMenu`·`InventoryScreen`·`ContainerScreen` 그대로. [s] 는 GUI 한 픽셀이 그림 몇 픽셀인지.
 */
class Containers(
    private val assets: AssetPack,
    private val text: TextRenderer,
    private val icons: ItemIcons,
    private val players: PlayerModel,
    private val lang: () -> Lang,
) {

    /** 칸 하나 — 아이콘 + 개수 + 내구도 막대. (x, y) 는 GUI 픽셀. */
    fun slot(raster: Raster, view: ItemView?, x: Int, y: Int, s: Int) {
        if (view == null || view.empty) return
        raster.draw(icons.render(view, 16 * s), x * s, y * s)
        decorate(raster, view, x, y, s)
    }

    /** 마크 `GuiGraphics.renderItemDecorations` — 내구도 막대(13×2)와 개수(오른쪽 아래, 그림자). */
    fun decorate(raster: Raster, view: ItemView, x: Int, y: Int, s: Int) {
        if (view.maxDamage > 0 && view.damage > 0) {
            val width = (13f - view.damage * 13f / view.maxDamage).roundToInt().coerceIn(0, 13)
            val hue = maxOf(0f, (view.maxDamage - view.damage).toFloat() / view.maxDamage) / 3f
            raster.fillRect((x + 2) * s, (y + 13) * s, 13 * s, 2 * s, 0xFF000000.toInt())
            raster.fillRect((x + 2) * s, (y + 13) * s, width * s, s, java.awt.Color.HSBtoRGB(hue, 1f, 1f) or 0xFF000000.toInt())
        }
        if (view.count > 1) {
            val label = Component.text(view.count.toString())
            val w = text.width(label)
            text.draw(raster, label, x + 19 - 2 - w, (y + 6 + 3).toFloat(), s)
        }
    }

    /**
     * 가방 화면. [items] 는 `PlayerInventory.contents` 순서(가방 0~35, 신발·바지·갑옷·투구 36~39, 왼손 40).
     * [mainHand] 는 손에 든 단축바 칸 번호.
     */
    fun inventory(items: List<ItemView?>, mainHand: Int, level: Int, skin: BufferedImage?, slim: Boolean, s: Int): Raster {
        val raster = Raster(WIDTH * s, INVENTORY_HEIGHT * s)
        assets.texture("minecraft:gui/container/inventory")?.let { raster.draw(it, 0, 0, s, sx = 0, sy = 0, sw = WIDTH, sh = INVENTORY_HEIGHT) }
        // 제목 "제작"
        text.draw(raster, lang().translate(Component.translatable("container.crafting")), 97f, 8f, s, LABEL, shadow = false)

        // 플레이어 — 마크 `renderEntityInInventoryFollowsMouse`(26..75 × 8..78, 크기 30) 를 정면으로.
        val skinImage = skin ?: assets.texture(if (slim) "minecraft:entity/player/slim/steve" else "minecraft:entity/player/wide/steve")
        if (skinImage != null) {
            val canvas = Canvas3D(raster)
            val k = 30f * 0.9375f
            val feet = 43f + (0.9f + 0.0625f) * 30f
            val pose = Matrix4f().translate(50.5f * s, feet * s, 0f).scale(k * s, k * s, -k * s)
                .rotateY(Math.toRadians(-20.0).toFloat()).translate(0f, -1.501f, 0f)
            val gear = PlayerModel.Equipment(
                head = items.getOrNull(39), chest = items.getOrNull(38), legs = items.getOrNull(37), feet = items.getOrNull(36),
                mainHand = items.getOrNull(mainHand), offHand = items.getOrNull(40),
            )
            players.draw(canvas, pose, skinImage, slim, gear, ENTITY_LIGHT)
        }
        if (level > 0) outlined(raster, level.toString(), 50.5f, 69f, s)

        for (i in 0..3) slot(raster, items.getOrNull(39 - i), 8, 8 + i * 18, s)
        slot(raster, items.getOrNull(40), 77, 62, s)
        for (row in 0..2) for (col in 0..8) slot(raster, items.getOrNull(9 + row * 9 + col), 8 + col * 18, 84 + row * 18, s)
        for (col in 0..8) slot(raster, items.getOrNull(col), 8 + col * 18, 142, s)
        return raster
    }

    /** 3줄 상자 화면(엔더 상자). [title] 은 번역 전 컴포넌트여도 된다. */
    fun chest(items: List<ItemView?>, title: Component, s: Int, rows: Int = 3): Raster {
        val top = rows * 18 + 17
        val raster = Raster(WIDTH * s, (top + 7) * s)
        assets.texture("minecraft:gui/container/generic_54")?.let {
            raster.draw(it, 0, 0, s, sx = 0, sy = 0, sw = WIDTH, sh = top)
            raster.draw(it, 0, top * s, s, sx = 0, sy = 215, sw = WIDTH, sh = 7)
        }
        text.draw(raster, lang().translate(title), 8f, 6f, s, LABEL, shadow = false)
        for (row in 0 until rows) for (col in 0..8) slot(raster, items.getOrNull(row * 9 + col), 8 + col * 18, 18 + row * 18, s)
        return raster
    }

    /** 경험치 레벨처럼 — 검은 테두리 + 초록 글자, 가운데 맞춤. */
    private fun outlined(raster: Raster, label: String, centerX: Float, y: Float, s: Int) {
        val c = Component.text(label)
        val x = centerX - text.width(c) / 2f
        for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) text.draw(raster, c, x + dx, y + dy, s, 0xFF000000.toInt(), shadow = false)
        text.draw(raster, c, x, y, s, LEVEL, shadow = false)
    }

    companion object {
        const val WIDTH = 176
        const val INVENTORY_HEIGHT = 166
        const val LABEL = 0xFF404040.toInt()
        const val LEVEL = 0xFF80FF20.toInt()

        /** 화면 속 엔티티 조명 — 위·앞에서(화면 공간: y 아래, z 앞). */
        val ENTITY_LIGHT = Lights(Vector3f(0.3f, -0.6f, 1.0f).normalize(), Vector3f(-0.3f, -0.6f, 1.0f).normalize())
    }
}
