package com.inmc.discord.render.raster

import com.inmc.discord.render.Raster
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 정사영 삼각형 래스터라이저. 화면 좌표(x 오른쪽, y 아래, z 는 클수록 앞)에 텍스처 삼각형을 찍는다.
 * 최근접 샘플링 · 알파 0.1 미만 버림(마크 cutout) · z-버퍼.
 */
class Canvas3D(val raster: Raster) {

    private val depth = FloatArray(raster.width * raster.height) { Float.NEGATIVE_INFINITY }

    class Vertex(val x: Float, val y: Float, val z: Float, val u: Float, val v: Float)

    /**
     * @param texture null 이면 [color] 로 칠한다.
     * @param u, v 는 0~1(텍스처 전체 기준).
     */
    fun triangle(a: Vertex, b: Vertex, c: Vertex, texture: BufferedImage?, tint: Int, light: Float, color: Int = -1) {
        val area = edge(a.x, a.y, b.x, b.y, c.x, c.y)
        if (kotlin.math.abs(area) < 1e-6f) return
        val minX = max(0, floor(min(a.x, min(b.x, c.x))).toInt())
        val maxX = min(raster.width - 1, ceil(max(a.x, max(b.x, c.x))).toInt())
        val minY = max(0, floor(min(a.y, min(b.y, c.y))).toInt())
        val maxY = min(raster.height - 1, ceil(max(a.y, max(b.y, c.y))).toInt())
        val tw = texture?.width ?: 1
        val th = texture?.height ?: 1
        for (py in minY..maxY) {
            val y = py + 0.5f
            for (px in minX..maxX) {
                val x = px + 0.5f
                var w0 = edge(b.x, b.y, c.x, c.y, x, y) / area
                var w1 = edge(c.x, c.y, a.x, a.y, x, y) / area
                var w2 = edge(a.x, a.y, b.x, b.y, x, y) / area
                if (w0 < -EPS || w1 < -EPS || w2 < -EPS) continue
                w0 = max(w0, 0f); w1 = max(w1, 0f); w2 = max(w2, 0f)
                val z = a.z * w0 + b.z * w1 + c.z * w2
                val i = py * raster.width + px
                if (z < depth[i] - DEPTH_EPS) continue
                var texel = if (texture == null) color else {
                    val u = a.u * w0 + b.u * w1 + c.u * w2
                    val v = a.v * w0 + b.v * w1 + c.v * w2
                    val tx = floor(u * tw).toInt().coerceIn(0, tw - 1)
                    val ty = floor(v * th).toInt().coerceIn(0, th - 1)
                    texture.getRGB(tx, ty)
                }
                val alpha = texel ushr 24
                if (alpha < ALPHA_CUTOFF) continue
                texel = Raster.shade(Raster.multiply(texel, tint), light)
                if (alpha >= 250) {
                    raster.pixels[i] = texel or 0xFF000000.toInt()
                    depth[i] = z
                } else {
                    raster.blend(px, py, texel)
                    if (alpha > 127) depth[i] = z
                }
            }
        }
    }

    fun quad(v: Array<Vertex>, texture: BufferedImage?, tint: Int, light: Float) {
        triangle(v[0], v[1], v[2], texture, tint, light)
        triangle(v[0], v[2], v[3], texture, tint, light)
    }

    private fun edge(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private companion object {
        const val EPS = 1e-4f
        const val DEPTH_EPS = 1e-4f
        /** 마크 cutout 은 알파 0.1 미만을 버린다. */
        const val ALPHA_CUTOFF = 26
    }
}
