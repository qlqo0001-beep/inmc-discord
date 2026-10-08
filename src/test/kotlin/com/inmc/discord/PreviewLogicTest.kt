package com.inmc.discord

import com.inmc.discord.preview.Attachments
import com.inmc.discord.preview.ImageFrames
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewLogicTest {

    private fun image(w: Int, h: Int, argb: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until h) for (x in 0 until w) setRGB(x, y, argb)
    }

    /** 빨강 → 1, 재생 막대 회색 → 2, 그 밖 → 3. */
    private val palette: (Int) -> Byte = { rgb ->
        when (rgb) {
            0xFF0000 -> 1
            0x938B86 -> 2
            else -> 3
        }.toByte()
    }

    @Test
    fun `PNG 는 한 장`() {
        val bytes = ByteArrayOutputStream().use { ImageIO.write(image(10, 10, 0xFF00FF00.toInt()), "png", it); it.toByteArray() }
        val frames = ImageFrames.decode(bytes)
        assertEquals(1, frames.size)
        val map = ImageFrames.toMap(frames, palette)
        assertEquals(128 * 128, map.single().colors.size)
        // 재생 막대 없음 — 맨 아래 줄도 그림 색
        assertEquals(3.toByte(), map.single().colors[127 * 128 + 64])
    }

    @Test
    fun `움직이는 그림은 아래 두 줄에 재생 막대`() {
        val frames = listOf(image(128, 128, 0xFF0000FF.toInt()) to 100, image(128, 128, 0xFF0000FF.toInt()) to 100)
        val map = ImageFrames.toMap(frames, palette)
        val first = map[0].colors
        // 첫 장은 절반까지 빨강
        assertEquals(1.toByte(), first[126 * 128 + 10])
        assertEquals(2.toByte(), first[126 * 128 + 100])
        assertEquals(1.toByte(), map[1].colors[127 * 128 + 127])
        assertEquals(3.toByte(), first[50 * 128 + 50])
    }

    @Test
    fun `반투명보다 옅으면 투명(색 0)`() {
        val map = ImageFrames.toMap(listOf(image(16, 16, 0x10FFFFFF) to 0), palette)
        assertTrue(map.single().colors.all { it == 0.toByte() })
    }

    @Test
    fun `그림 파일인지`() {
        assertTrue(Attachments.isImage("a.PNG", null))
        assertTrue(Attachments.isImage("photo", "image/jpeg"))
        assertFalse(Attachments.isImage("a.webp", "image/webp"))
        assertFalse(Attachments.isImage("notes.txt", "text/plain"))
    }

    @Test
    fun `첨부 기록은 시간이 지나면 없어진다`() {
        var now = 0L
        val attachments = Attachments { now }
        val id = attachments.put("https://cdn.discordapp.com/a.png", "a.png", 10, true, 24)
        assertEquals("a.png", attachments.get(id, 24)!!.name)
        now += 25 * 3_600_000L
        assertNull(attachments.get(id, 24))
    }
}
