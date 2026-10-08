package com.inmc.discord.render.special

import com.inmc.discord.render.raster.Canvas3D
import com.inmc.discord.render.raster.Lights
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.awt.image.BufferedImage

/**
 * 마크 엔티티 모델(`ModelPart`)의 상자 — 텍스처 자리(texOffs)와 크기로 여섯 면의 UV 가 정해지는 그 배치를 그대로 옮겼다.
 * 좌표는 엔티티 픽셀(1/16 블록), y 는 아래로(엔티티 모델의 관례 — 놓는 쪽 변환이 뒤집는다).
 */
class Box(
    val u: Int, val v: Int,
    val x: Float, val y: Float, val z: Float,
    val w: Float, val h: Float, val d: Float,
    val grow: Float = 0f,
    /** 마크 `mirror()` — 좌우를 뒤집어 붙인다(왼팔·왼다리가 오른쪽 텍스처를 쓸 때). */
    val mirror: Boolean = false,
)

class Part(val boxes: List<Box>, val offset: Vector3f = Vector3f(), val xRot: Float = 0f, val yRot: Float = 0f, val zRot: Float = 0f) {
    /** 마크 `ModelPart.translateAndRotate` — 옮기고(픽셀/16) ZYX 순 회전. */
    fun pose(): Matrix4f = Matrix4f().translate(offset.x / 16f, offset.y / 16f, offset.z / 16f)
        .rotate(Quaternionf().rotationZYX(zRot, yRot, xRot))
}

object EntityModel {

    private class Poly(val vertices: Array<Vector3f>, val u1: Float, val v1: Float, val u2: Float, val v2: Float, val normal: Vector3f)

    /** 상자 하나의 여섯 면(마크 `ModelPart.Cube` 와 같은 꼭짓점·UV). */
    private fun polygons(b: Box): List<Poly> {
        var x0 = b.x - b.grow; val y0 = b.y - b.grow; val z0 = b.z - b.grow
        var x1 = b.x + b.w + b.grow; val y1 = b.y + b.h + b.grow; val z1 = b.z + b.d + b.grow
        if (b.mirror) x0 = x1.also { x1 = x0 }
        val flip = if (b.mirror) -1f else 1f
        val v7 = Vector3f(x0, y0, z0); val v0 = Vector3f(x1, y0, z0); val v1 = Vector3f(x1, y1, z0); val v2 = Vector3f(x0, y1, z0)
        val v3 = Vector3f(x0, y0, z1); val v4 = Vector3f(x1, y0, z1); val v5 = Vector3f(x1, y1, z1); val v6 = Vector3f(x0, y1, z1)
        val u = b.u.toFloat(); val v = b.v.toFloat()
        val a = u + b.d; val bb = u + b.d + b.w; val c = u + b.d + b.w + b.w; val e = u + b.d + b.w + b.d; val f = u + b.d + b.w + b.d + b.w
        val p = v; val q = v + b.d; val r = v + b.d + b.h
        return listOf(
            Poly(arrayOf(v4, v3, v7, v0), a, p, bb, q, Vector3f(0f, -1f, 0f)),
            Poly(arrayOf(v1, v2, v6, v5), bb, q, c, p, Vector3f(0f, 1f, 0f)),
            Poly(arrayOf(v7, v3, v6, v2), u, q, a, r, Vector3f(-1f * flip, 0f, 0f)),
            Poly(arrayOf(v0, v7, v2, v1), a, q, bb, r, Vector3f(0f, 0f, -1f)),
            Poly(arrayOf(v4, v0, v1, v5), bb, q, e, r, Vector3f(1f * flip, 0f, 0f)),
            Poly(arrayOf(v3, v4, v5, v6), e, q, f, r, Vector3f(0f, 0f, 1f)),
        )
    }

    /**
     * [parts] 를 [pose](화면까지의 변환, 블록 단위 입력)로 그린다. [texW]/[texH] 는 모델이 정한 텍스처 크기
     * — 실제 그림이 더 크면(고해상도 팩) 같은 비율로 읽는다.
     */
    fun draw(canvas: Canvas3D, pose: Matrix4f, parts: List<Part>, texture: BufferedImage, texW: Int, texH: Int, tint: Int, light: Lights) {
        for (part in parts) {
            val m = Matrix4f(pose).mul(part.pose())
            val normalMatrix = Matrix3f(m).invert().transpose()
            for (box in part.boxes) for (poly in polygons(box)) {
                val uvs = arrayOf(floatArrayOf(poly.u2, poly.v1), floatArrayOf(poly.u1, poly.v1), floatArrayOf(poly.u1, poly.v2), floatArrayOf(poly.u2, poly.v2))
                val verts = Array(4) { i ->
                    val p = Vector3f(poly.vertices[i]).div(16f)
                    m.transformPosition(p)
                    Canvas3D.Vertex(p.x, p.y, p.z, uvs[i][0] / texW, uvs[i][1] / texH)
                }
                val n = normalMatrix.transform(Vector3f(poly.normal)).normalize()
                canvas.quad(verts, texture, tint, light.at(n))
            }
        }
    }
}
