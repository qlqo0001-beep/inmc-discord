package com.inmc.discord

import com.inmc.discord.render.Skins
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkinsTest {

    private fun skin(w: Int, h: Int, argb: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until h) for (x in 0 until w) setRGB(x, y, argb)
    }

    @Test
    fun `64x64 몸 기본 층은 불투명으로, 바깥 층은 그대로`() {
        val out = Skins.process(skin(64, 64, 0x00112233))
        assertEquals(0xFF112233.toInt(), out.getRGB(8, 8))   // 머리 앞
        assertEquals(0xFF112233.toInt(), out.getRGB(20, 20)) // 몸
        assertEquals(0xFF112233.toInt(), out.getRGB(20, 52)) // 왼다리·왼팔 기본
        assertEquals(0x00112233, out.getRGB(40, 8))          // 모자 층
        assertEquals(0x00112233, out.getRGB(20, 36))         // 겉옷 층
    }

    @Test
    fun `옛 64x32 스킨의 모자 층이 다 불투명이면 지운다`() {
        val out = Skins.process(skin(64, 32, 0xFF445566.toInt()))
        assertEquals(0, out.getRGB(40, 8))
        assertEquals(0xFF445566.toInt(), out.getRGB(8, 8))
        // 투명한 곳이 하나라도 있으면 그대로
        val partial = skin(64, 32, 0xFF445566.toInt()).apply { setRGB(33, 1, 0) }
        assertEquals(0xFF445566.toInt(), Skins.process(partial).getRGB(40, 8))
    }

    @Test
    fun `고해상도 스킨은 비율대로`() {
        val out = Skins.process(skin(128, 128, 0x00112233))
        assertEquals(0xFF112233.toInt(), out.getRGB(16, 16))
        assertEquals(0x00112233, out.getRGB(80, 16))
    }

    @Test
    fun `마크 텍스처 서버 주소만 받는다`() {
        val skins = Skins(Files.createTempDirectory("skins").toFile(), Logger.getAnonymousLogger())
        assertNull(skins.get("https://example.com/skin.png"))
        assertNull(skins.get("file:///C:/x.png"))
    }
}
