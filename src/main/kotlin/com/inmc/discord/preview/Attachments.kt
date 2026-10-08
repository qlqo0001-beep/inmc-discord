package com.inmc.discord.preview

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 디스코드 첨부 파일 기록 — 게임 채팅의 `[파일이름]` 을 누르면 `/디스코드 그림 <id>` 로 찾아온다. [hours] 가 지나면 없어진다.
 */
class Attachments(private val now: () -> Long = System::currentTimeMillis) {

    class Entry(val url: String, val name: String, val size: Int, val image: Boolean, val created: Long)

    private val all = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    fun put(url: String, name: String, size: Int, image: Boolean, hours: Int): String {
        val limit = now() - hours * 3_600_000L
        all.entries.removeIf { it.value.created < limit }
        while (true) {
            val id = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            if (all.putIfAbsent(id, Entry(url, name, size, image, now())) == null) return id
        }
    }

    fun get(id: String, hours: Int): Entry? {
        val entry = all[id] ?: return null
        if (now() - entry.created > hours * 3_600_000L) {
            all.remove(id)
            return null
        }
        return entry
    }

    companion object {
        private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

        private val IMAGE = Regex("(?i)\\.(png|jpe?g|gif|bmp)(\\?.*)?$")

        /** 미리 볼 수 있는 그림인지(ImageIO 가 읽는 것). */
        fun isImage(fileName: String, contentType: String?): Boolean =
            contentType?.startsWith("image/") == true && contentType != "image/webp" || IMAGE.containsMatchIn(fileName)
    }
}
