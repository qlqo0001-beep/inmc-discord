package com.inmc.discord.render.assets

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.logging.Logger

/**
 * 그림 자원 준비 — `plugins/inmc-discord/assets/`.
 *
 * - `client.jar` : 바닐라 마인크래프트 클라이언트(텍스처·모델·아이템 정의·기본 글꼴 그림)
 * - `objects/assets/minecraft/…` : 클라이언트 jar 에 없는 자산 객체 — 한글 글꼴(`font/unifont.zip`·`font/include/unifont.json`)과 번역(`lang/<언어>.json`)
 *
 * 없으면 Mojang 공식에서 한 번 받는다(버전 목록 → 버전 정보 → client.jar, 자산 목록 → 객체). 받은 것은 sha1 로 확인한다.
 * 관리자가 직접 넣어도 된다 — 마인크래프트 설치 폴더의 `versions/<버전>/<버전>.jar` 를 `client.jar` 로,
 * `assets/indexes/<번호>.json` 이 가리키는 객체를 위 경로로(GUIDE 참조).
 */
class AssetSetup(dataFolder: File, private val logger: Logger) {

    val folder = File(dataFolder, "assets")
    val clientJar = File(folder, "client.jar")
    val objects = File(folder, "objects")

    fun objectFile(key: String): File = File(objects, "assets/$key")

    fun ready(language: String): Boolean = clientJar.isFile && needed(language).all { objectFile(it).isFile }

    fun missing(language: String): List<String> =
        (if (clientJar.isFile) emptyList() else listOf("client.jar")) + needed(language).filterNot { objectFile(it).isFile }

    /** 블로킹 — 그림 일꾼·백그라운드 스레드에서. */
    fun download(version: String, language: String) {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
        fun json(url: String): JsonObject =
            JsonParser.parseString(client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).asJsonObject
        fun fetch(url: String, sha1: String?, target: File) {
            val bytes = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body()
            if (sha1 != null && sha1(bytes) != sha1) error("${target.name} 의 sha1 이 맞지 않습니다")
            target.parentFile.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.writeBytes(bytes)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) error("${target.name} 을 쓰지 못했습니다")
        }

        val manifest = json(MANIFEST)
        val entry = manifest.getAsJsonArray("versions").map { it.asJsonObject }.firstOrNull { it.get("id").asString == version }
            ?: error("버전 목록에 $version 이 없습니다")
        val info = json(entry.get("url").asString)
        if (!clientJar.isFile) {
            val download = info.getAsJsonObject("downloads").getAsJsonObject("client")
            logger.info("마인크래프트 $version 클라이언트를 받습니다(${download.get("size").asLong / 1_000_000}MB, Mojang) — 아이템 그림용")
            fetch(download.get("url").asString, download.get("sha1").asString, clientJar)
        }
        val wanted = needed(language).filterNot { objectFile(it).isFile }
        if (wanted.isNotEmpty()) {
            val index = json(info.getAsJsonObject("assetIndex").get("url").asString).getAsJsonObject("objects")
            for (key in wanted) {
                val obj = index.getAsJsonObject(key) ?: continue
                val hash = obj.get("hash").asString
                fetch("$RESOURCES/${hash.take(2)}/$hash", hash, objectFile(key))
            }
        }
        logger.info("아이템 그림 자원을 준비했습니다")
    }

    companion object {
        const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val RESOURCES = "https://resources.download.minecraft.net"

        fun needed(language: String): List<String> = listOf(
            "minecraft/font/include/unifont.json",
            "minecraft/font/unifont.zip",
            "minecraft/lang/$language.json",
        )

        fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
