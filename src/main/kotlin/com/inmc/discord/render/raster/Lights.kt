package com.inmc.discord.render.raster

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.min

/**
 * 마크 GUI 아이템 조명(`Lighting` 의 ITEMS_3D·ITEMS_FLAT + 셰이더 `minecraft_mix_light`): 빛 둘의 확산 × 0.6 + 바탕 0.4, 최대 1.
 * 법선은 화면 공간(y 아래로 뒤집힌 GUI 공간)에서 넘긴다 — 마크도 같은 공간에서 계산한다.
 */
class Lights(private val l0: Vector3f, private val l1: Vector3f) {

    fun at(n: Vector3f): Float = min(1f, (max(0f, l0.dot(n)) + max(0f, l1.dot(n))) * 0.6f + 0.4f)

    companion object {
        private val LIGHT0 = Vector3f(0.2f, 1.0f, -0.7f).normalize()
        private val LIGHT1 = Vector3f(-0.2f, 1.0f, 0.7f).normalize()

        private fun of(m: Matrix4f) = Lights(m.transformDirection(Vector3f(LIGHT0)), m.transformDirection(Vector3f(LIGHT1)))

        /** GUI 3D 아이템(블록·머리·상자 …). */
        val ITEMS_3D: Lights = of(
            Matrix4f().scaling(1f, -1f, 1f).rotateYXZ(1.0821041f, 3.2375858f, 0f)
                .rotateYXZ((-Math.PI / 8).toFloat(), (Math.PI * 3.0 / 4.0).toFloat(), 0f),
        )

        /** GUI 납작한 아이템(`gui_light: front`). */
        val ITEMS_FLAT: Lights = of(Matrix4f().rotationY((-Math.PI / 8).toFloat()).rotateX((Math.PI * 3.0 / 4.0).toFloat()))

        fun gui(guiLight: String): Lights = if (guiLight == "front") ITEMS_FLAT else ITEMS_3D
    }
}
