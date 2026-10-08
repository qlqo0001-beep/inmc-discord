package com.inmc.discord.chat

import org.bukkit.Bukkit

/**
 * 지도 그림의 색 번호 128×128 — Bukkit 에 읽는 API 가 없어서 리플렉션으로 읽는다(**메인 스레드**).
 * `CraftMapView.worldMap`(`MapItemSavedData`)의 `colors`. 판이 바뀌어 이름이 달라지면 null — 디스코드에 지도 그림만 빠진다.
 * ARCHITECTURE "리플렉션 진입점" 에 적혀 있다.
 */
object MapPixels {

    fun read(mapId: Int): ByteArray? = runCatching {
        @Suppress("DEPRECATION")
        val view = Bukkit.getMap(mapId) ?: return null
        val field = view.javaClass.getDeclaredField("worldMap").apply { isAccessible = true }
        val data = field.get(view) ?: return null
        val colors = data.javaClass.getField("colors").get(data) as ByteArray
        colors.copyOf()
    }.getOrNull()
}
