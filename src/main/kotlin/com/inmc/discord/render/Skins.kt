package com.inmc.discord.render

import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import javax.imageio.ImageIO

/**
 * 플레이어 스킨 그림 — 프로필의 텍스처 주소(textures.minecraft.net)에서 받아 `cache/skins/` 에 둔다.
 * 그림 일꾼에서만 부른다(받는 동안 기다린다, 최대 5초). 그 밖의 주소는 받지 않는다.
 * 받지 못한 주소는 [RETRY_MS] 동안 다시 묻지 않는다(그동안은 기본 스킨).
 *
 * 그림은 마크 `SkinTextureDownloader` 처럼 다듬는다 — [process].
 */
class Skins(private val folder: File, private val logger: Logger) {

    private val memory = ConcurrentHashMap<String, BufferedImage>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun get(url: String): BufferedImage? {
        val key = url.replace("http://", "https://")
        if (!ALLOWED.matches(key)) return null
        memory[key]?.let { return it }
        failed[key]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return null }
        val file = File(folder, sha1(key) + ".png")
        val image = runCatching {
            if (file.isFile) ImageIO.read(file) else download(key, file)
        }.getOrNull()?.let(::process)
        if (image == null) {
            failed[key] = System.currentTimeMillis()
            return null
        }
        failed.remove(key)
        memory[key] = image
        return image
    }

    private fun download(url: String, file: File): BufferedImage? {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) {
            logger.fine("스킨을 받지 못했습니다(${response.statusCode()}): $url")
            return null
        }
        val bytes = response.body()
        val image = ImageIO.read(bytes.inputStream()) ?: return null
        folder.mkdirs()
        file.writeBytes(bytes)
        return image
    }

    companion object {
        private val ALLOWED = Regex("^https://textures\\.minecraft\\.net/texture/[0-9a-fA-F]+$")
        private const val RETRY_MS = 10 * 60_000L

        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /**
         * 마크 `SkinTextureDownloader.processLegacySkin` 과 같게:
         * - 64×32(옛 스킨): 모자 층이 전부 불투명이면 지운다(Notch hack — 옛 스킨은 모자 자리를 검게 칠해 두곤 했다).
         * - 64×64: 몸 기본 층 세 구역은 알파를 무시하고 불투명으로(구멍 뚫린 스킨이 검게 보이는 것처럼).
         * 64 의 정수배(고해상도)는 비율대로. 그 밖의 크기는 그대로.
         */
        fun process(source: BufferedImage): BufferedImage {
            val w = source.width
            if (w % 64 != 0 || (source.height != w && source.height * 2 != w)) return source
            val k = w / 64
            // drawImage 로 옮기면 완전 투명한 픽셀의 색이 사라진다 — 마크는 PNG 에 남은 색을 살려 불투명으로 만든다.
            val image = BufferedImage(w, source.height, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, w, source.height, source.getRGB(0, 0, w, source.height, null, 0, w), 0, w)
            fun region(x0: Int, y0: Int, x1: Int, y1: Int, action: (Int, Int) -> Unit) {
                for (y in y0 * k until y1 * k) for (x in x0 * k until x1 * k) action(x, y)
            }
            if (source.height * 2 == w) {
                var opaque = true
                region(32, 0, 64, 16) { x, y -> if (image.getRGB(x, y) ushr 24 < 128) opaque = false }
                if (opaque) region(32, 0, 64, 16) { x, y -> image.setRGB(x, y, 0) }
                region(0, 0, 32, 16) { x, y -> image.setRGB(x, y, image.getRGB(x, y) or 0xFF000000.toInt()) }
                region(0, 16, 64, 32) { x, y -> image.setRGB(x, y, image.getRGB(x, y) or 0xFF000000.toInt()) }
            } else {
                region(0, 0, 32, 16) { x, y -> image.setRGB(x, y, image.getRGB(x, y) or 0xFF000000.toInt()) }
                region(0, 16, 64, 32) { x, y -> image.setRGB(x, y, image.getRGB(x, y) or 0xFF000000.toInt()) }
                region(16, 48, 48, 64) { x, y -> image.setRGB(x, y, image.getRGB(x, y) or 0xFF000000.toInt()) }
            }
            return image
        }
    }
}
