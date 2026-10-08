package com.inmc.discord.status

import com.inmc.discord.Discord
import com.inmc.discord.relay.Texts
import kr.inmc.core.util.AtomicFiles
import kr.inmc.core.util.Text
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.RateLimitedException
import net.dv8tion.jda.api.requests.ErrorResponse
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * 채널 주제 — DiscordSRV 의 ChannelTopicUpdater 와 같은 토큰. 봇 일꾼에서 돈다(메인 아님).
 * 총 접속자 수는 `playerdata` 의 파일 수다(오프라인 플레이어 목록을 만들지 않는다).
 *
 * 디스코드 한도(채널마다 10분에 2번)는 [TopicGate] 가 먼저 지킨다 — 최근에 바꾼 시각을 `topics.yml` 에 둬 재시작을 넘긴다.
 * 한도에 걸려 미룬 것은 다음 주기에 다시 본다. 꺼질 때의 "오프라인" 주제도 같은 문이다 — 켠 지 얼마 안 돼 꺼지면 그대로 남는다.
 */
class Topics(private val discord: Discord) {

    /** 보낸 요청들이 다 끝나면 끝나는 것. 꺼질 때 이것을 기다린다. */
    fun update(shutdown: Boolean): CompletableFuture<*> {
        val topic = discord.settings.topic
        val values = values()
        val pending = ArrayList<CompletableFuture<*>>()
        val now = System.currentTimeMillis()
        fun set(channel: TextChannel?, format: String) {
            if (channel == null || format.isBlank()) return
            val text = Texts.plainOf(Text.substituteOnly(Texts.tokens(format, values))).take(MAX_TOPIC)
            if (text == channel.topic) return
            if (!gate(discord).allow(channel.idLong, now)) {
                discord.logger.info("채널 주제 갱신을 미룹니다(#${channel.name}) — 디스코드 한도(채널마다 ${TopicGate.WINDOW_MINUTES}분에 ${TopicGate.LIMIT}번)")
                return
            }
            // 보내는 순간 센다 — 완료될 때 세면, 429 로 JDA 가 들고 있다가 꺼질 때 취소된 요청(디스코드는 이미 받은 것)을 놓쳐
            // 다음 켤 때 또 보낸다(2026-10-08 콘솔 채널이 그랬다). 많이 세는 쪽이 안전하다 — 미뤄진 것은 다음 주기에 다시 온다.
            remember(channel.idLong)
            pending += channel.manager.setTopic(text).submit()
                .exceptionally {
                    explain(channel.name, it)
                    null
                }
        }
        set(discord.bot.chatChannel(), if (shutdown) topic.chatShutdown else topic.chat)
        set(discord.bot.consoleChannel(), if (shutdown) topic.consoleShutdown else topic.console)
        return CompletableFuture.allOf(*pending.toTypedArray())
    }

    private fun remember(channel: Long) {
        val gate = gate(discord)
        gate.record(channel, System.currentTimeMillis())
        val yaml = YamlConfiguration()
        for ((id, times) in gate.export()) yaml.set("channels.$id.set-at", times)
        // JDA 스레드에서 바로 쓴다 — 꺼지는 중이면 io 워커가 이미 닫혔을 수 있다. 파일이 작다.
        runCatching { AtomicFiles.write(file(discord), yaml.saveToString()) }
            .onFailure { discord.logger.warning("topics.yml 저장 실패: ${it.message}") }
    }

    /** 왜 못 바꿨는지 — 한도·종료는 정보, 권한만 경고. 전에는 전부 "권한을 확인하세요" 였다. */
    private fun explain(name: String, error: Throwable) {
        val cause = (error as? CompletionException)?.cause ?: error
        when {
            cause is ErrorResponseException && cause.errorResponse == ErrorResponse.MISSING_PERMISSIONS ->
                discord.logger.warning("채널 주제를 바꾸지 못했습니다(#$name) — 봇에게 채널 관리 권한이 없습니다")
            cause is RateLimitedException ->
                discord.logger.info("채널 주제 갱신이 디스코드 한도에 걸렸습니다(#$name) — 다음 주기에 다시 봅니다")
            cause is CancellationException ->
                discord.logger.info("채널 주제 갱신을 취소했습니다(#$name) — 꺼지는 중")
            else -> discord.logger.warning("채널 주제를 바꾸지 못했습니다(#$name): ${cause.message}")
        }
    }

    private fun values(): Map<String, String> {
        val runtime = Runtime.getRuntime()
        val gb = 1024.0 * 1024.0 * 1024.0
        fun gb(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / gb)
        return mapOf(
            "playercount" to Bukkit.getOnlinePlayers().size.toString(),
            "playermax" to Bukkit.getMaxPlayers().toString(),
            "totalplayers" to totalPlayers().toString(),
            "uptimemins" to (ManagementFactory.getRuntimeMXBean().uptime / 60_000).toString(),
            "uptimehours" to (ManagementFactory.getRuntimeMXBean().uptime / 3_600_000).toString(),
            "date" to discord.date(),
            "tps" to String.format(Locale.ROOT, "%.2f", Bukkit.getTPS().firstOrNull() ?: 20.0),
            "usedmemorygb" to gb(runtime.totalMemory() - runtime.freeMemory()),
            "freememorygb" to gb(runtime.freeMemory()),
            "totalmemorygb" to gb(runtime.totalMemory()),
            "maxmemorygb" to gb(runtime.maxMemory()),
            "serverversion" to Bukkit.getVersion(),
        )
    }

    private fun totalPlayers(): Int {
        val world = Bukkit.getWorlds().firstOrNull() ?: return 0
        return File(world.worldFolder, "playerdata").listFiles { f -> f.name.endsWith(".dat") }?.size ?: 0
    }

    companion object {
        private const val MAX_TOPIC = 1024

        /** 플러그인마다 하나 — `Topics` 는 갱신마다 새로 만들어진다. 처음 쓸 때 `topics.yml` 을 읽는다. */
        @Volatile
        private var shared: TopicGate? = null

        private fun file(discord: Discord): File = discord.io.file("topics.yml")

        @Synchronized
        private fun gate(discord: Discord): TopicGate {
            shared?.let { return it }
            val gate = TopicGate()
            val file = file(discord)
            if (file.exists()) {
                val saved = HashMap<Long, List<Long>>()
                val section = YamlConfiguration.loadConfiguration(file).getConfigurationSection("channels")
                for (id in section?.getKeys(false).orEmpty()) {
                    val channel = id.toLongOrNull() ?: continue
                    saved[channel] = section!!.getLongList("$id.set-at")
                }
                gate.import(saved)
            }
            shared = gate
            return gate
        }
    }
}
