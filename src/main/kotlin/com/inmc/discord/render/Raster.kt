package com.inmc.discord.render

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * ARGB 픽셀 판. java.awt 의 Graphics2D 를 쓰지 않는 것은 **최근접 확대·알파 섞기·곱하기 물들이기**를 마크와 똑같이 하려고다
 * (Graphics2D 는 보간·감마가 끼어 픽셀 그림이 번진다).
 */
class Raster(val width: Int, val height: Int) {

    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels: IntArray = (image.raster.dataBuffer as DataBufferInt).data

    operator fun get(x: Int, y: Int): Int = if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0

    fun fill(color: Int) = pixels.fill(color)

    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        for (yy in maxOf(0, y) until minOf(height, y + h)) for (xx in maxOf(0, x) until minOf(width, x + w)) blend(xx, yy, color)
    }

    /** 알파 섞기(src over). */
    fun blend(x: Int, y: Int, color: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val a = color ushr 24
        if (a == 0) return
        val i = y * width + x
        if (a == 255) {
            pixels[i] = color
            return
        }
        pixels[i] = over(color, pixels[i])
    }

    /** 더하기 섞기(인첸트 반짝임). 알파는 그대로. */
    fun add(x: Int, y: Int, color: Int, strength: Float) {
        if (x !in 0 until width || y !in 0 until height) return
        val i = y * width + x
        val d = pixels[i]
        if (d ushr 24 == 0) return
        val r = minOf(255, ((d shr 16) and 255) + (((color shr 16) and 255) * strength).toInt())
        val g = minOf(255, ((d shr 8) and 255) + (((color shr 8) and 255) * strength).toInt())
        val b = minOf(255, (d and 255) + ((color and 255) * strength).toInt())
        pixels[i] = (d and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    /** [src] 를 (x, y) 에 [scale] 배 최근접 확대로 얹는다. [tint] 는 곱하기(ARGB, 흰색이면 그대로). */
    fun draw(src: BufferedImage, x: Int, y: Int, scale: Int = 1, tint: Int = WHITE, sx: Int = 0, sy: Int = 0, sw: Int = src.width, sh: Int = src.height) {
        for (py in 0 until sh) for (px in 0 until sw) {
            val c = src.getRGB(sx + px, sy + py)
            if (c ushr 24 == 0) continue
            val colored = multiply(c, tint)
            for (dy in 0 until scale) for (dx in 0 until scale) blend(x + px * scale + dx, y + py * scale + dy, colored)
        }
    }

    /** 늘려 얹기(9조각 그림의 가운데·변). 최근접. */
    fun drawStretched(src: BufferedImage, sx: Int, sy: Int, sw: Int, sh: Int, dx: Int, dy: Int, dw: Int, dh: Int) {
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return
        for (y in 0 until dh) for (x in 0 until dw) {
            val c = src.getRGB(sx + x * sw / dw, sy + y * sh / dh)
            blend(dx + x, dy + y, c)
        }
    }

    /** 바둑판 늘이기 없이 반복(9조각의 tile 방식). */
    fun drawTiled(src: BufferedImage, sx: Int, sy: Int, sw: Int, sh: Int, dx: Int, dy: Int, dw: Int, dh: Int) {
        if (sw <= 0 || sh <= 0) return
        for (y in 0 until dh) for (x in 0 until dw) blend(dx + x, dy + y, src.getRGB(sx + x % sw, sy + y % sh))
    }

    fun draw(other: Raster, x: Int, y: Int) {
        for (py in 0 until other.height) for (px in 0 until other.width) blend(x + px, y + py, other.pixels[py * other.width + px])
    }

    /** 잘라 내기(투명한 가장자리 빼고). 다 투명하면 1×1. */
    fun trimmed(): Raster {
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (y in 0 until height) for (x in 0 until width) if (pixels[y * width + x] ushr 24 != 0) {
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < 0) return Raster(1, 1)
        val out = Raster(maxX - minX + 1, maxY - minY + 1)
        for (y in minY..maxY) System.arraycopy(pixels, y * width + minX, out.pixels, (y - minY) * out.width, out.width)
        return out
    }

    fun png(): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(image, "png", out)
        out.toByteArray()
    }

    companion object {
        const val WHITE = -1

        /** 곱하기 물들이기(마크의 틴트·글자색). */
        fun multiply(c: Int, tint: Int): Int {
            if (tint == WHITE) return c
            val a = ((c ushr 24) * (tint ushr 24)) / 255
            val r = (((c shr 16) and 255) * ((tint shr 16) and 255)) / 255
            val g = (((c shr 8) and 255) * ((tint shr 8) and 255)) / 255
            val b = ((c and 255) * (tint and 255)) / 255
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** 밝기 곱하기(면 그늘). 알파는 그대로. */
        fun shade(c: Int, f: Float): Int {
            if (f >= 0.999f) return c
            val r = (((c shr 16) and 255) * f).toInt()
            val g = (((c shr 8) and 255) * f).toInt()
            val b = ((c and 255) * f).toInt()
            return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }

        fun over(src: Int, dst: Int): Int {
            val sa = (src ushr 24) / 255f
            val da = (dst ushr 24) / 255f
            val oa = sa + da * (1 - sa)
            if (oa <= 0f) return 0
            fun ch(shift: Int): Int {
                val s = ((src shr shift) and 255) / 255f
                val d = ((dst shr shift) and 255) / 255f
                return (((s * sa + d * da * (1 - sa)) / oa) * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            return ((oa * 255f + 0.5f).toInt() shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
