package com.inmc.discord.console

import com.inmc.discord.Discord
import com.inmc.discord.config.Settings
import com.inmc.discord.relay.Texts
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 콘솔 로그 → 디스코드 콘솔 채널.
 *
 * log4j 어펜더는 **로그를 찍은 그 스레드**(메인일 수도 있다)에서 불린다. 그래서 거기서는 날 값 넷을 대기열에 넣기만 하고
 * (꽉 차면 버린다), 거르기·모양 만들기·나누기·보내기는 봇 일꾼이 [Settings.Console.refreshSeconds] 마다 한다.
 * JDA 자신의 로그는 받지 않는다 — 보내다 실패한 경고가 또 보낼 줄이 되어 돌게 된다.
 */
class ConsoleRelay(private val discord: Discord) {

    data class Line(val time: Long, val level: String, val logger: String, val message: String)

    private val queue = ArrayBlockingQueue<Line>(QUEUE)

    @Volatile
    private var dropped = 0

    private var appender: Appender? = null
    private var task: ScheduledFuture<*>? = null

    fun pending(): Int = queue.size

    fun start() {
        val root = LogManager.getRootLogger() as? Logger ?: run {
            discord.logger.warning("서버 로거가 log4j-core 가 아니라 콘솔 채널을 켤 수 없습니다")
            return
        }
        val a = Appender()
        a.start()
        root.addAppender(a)
        appender = a
        restart()
    }

    /** 리로드 때 주기가 바뀌었을 수 있다. */
    fun restart() {
        task?.cancel(false)
        val seconds = discord.settings.console.refreshSeconds.toLong()
        task = discord.bot.scheduler.scheduleAtFixedRate({ runCatching { flush() } }, seconds, seconds, TimeUnit.SECONDS)
    }

    fun stop() {
        task?.cancel(false)
        appender?.let { a ->
            (LogManager.getRootLogger() as? Logger)?.removeAppender(a)
            a.stop()
        }
        appender = null
    }

    private fun offer(line: Line) {
        if (!queue.offer(line)) dropped++
    }

    private fun flush() {
        if (queue.isEmpty() && dropped == 0) return
        val settings = discord.settings
        val channel = discord.bot.consoleChannel()
        val lines = ArrayList<Line>(queue.size)
        queue.drainTo(lines)
        val skipped = dropped
        dropped = 0
        if (channel == null) return
        val texts = format(lines, settings.console, settings.time.zone)
        val messages = Texts.chunks(texts, if (settings.console.codeBlocks) LIMIT - CODE_FENCE else LIMIT)
        for ((index, chunk) in messages.withIndex()) {
            if (index >= MAX_MESSAGES_PER_FLUSH) {
                val rest = messages.size - index
                discord.bot.send(channel, discord.messages.discord("discord-console-dropped", com.inmc.discord.util.Ph.of().count(rest)))
                break
            }
            discord.bot.send(channel, if (settings.console.codeBlocks) "```\n$chunk\n```" else chunk, emptySet())
        }
        if (skipped > 0) {
            discord.bot.send(channel, discord.messages.discord("discord-console-dropped", com.inmc.discord.util.Ph.of().count(skipped)), emptySet())
        }
    }

    private inner class Appender : AbstractAppender("inmc-discord-console", null, null, false, Property.EMPTY_ARRAY) {
        override fun append(event: LogEvent) {
            val name = event.loggerName.orEmpty()
            if (name.startsWith(LIB_PREFIX)) return
            val message = event.message?.formattedMessage ?: return
            val thrown = event.thrown?.let { t ->
                buildString {
                    append('\n').append(t.toString())
                    for (frame in t.stackTrace.take(STACK_LINES)) append("\n    at ").append(frame)
                }
            }.orEmpty()
            offer(Line(event.timeMillis, event.level.name(), name, message + thrown))
        }
    }

    companion object {

        private const val QUEUE = 5_000
        private const val LIMIT = 2000
        private const val CODE_FENCE = 8
        private const val MAX_MESSAGES_PER_FLUSH = 10
        private const val STACK_LINES = 15
        /** relocate 된 JDA 의 로거 이름. */
        private const val LIB_PREFIX = "com.inmc.discord.lib"

        /** 거르기 + 모양. 순수 — `ConsoleFormatTest`. */
        fun format(lines: List<Line>, console: Settings.Console, zone: java.time.ZoneId): List<String> {
            val stamp = runCatching { DateTimeFormatter.ofPattern(console.timestamp, Locale.KOREA) }.getOrNull()
            val out = ArrayList<String>(lines.size)
            for (line in lines) {
                val level = when (line.level.uppercase()) {
                    "WARN" -> "warn"
                    "ERROR", "FATAL" -> "error"
                    "DEBUG", "TRACE" -> "debug"
                    else -> "info"
                }
                if (level !in console.levels) continue
                val body = Texts.filter(Texts.stripLegacy(line.message), console.filters) ?: continue
                val date = stamp?.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(line.time), zone)).orEmpty()
                val name = if (line.logger.isBlank() || line.logger == "Minecraft" || line.logger.startsWith("net.minecraft")) "" else " ${line.logger}"
                val prefix = console.prefix.replace("{date}", date).replace("{level}", line.level.uppercase()).replace("{name}", name)
                for (part in body.split('\n')) out += Texts.escapeCodeBlock(prefix + part)
            }
            return out
        }

        /** 디스코드에서 친 명령어 기록 한 줄을 파일에 붙인다. 봇 일꾼에서 부른다. */
        fun usage(folder: File, pattern: String, zone: java.time.ZoneId, who: String, command: String) {
            if (pattern.isBlank()) return
            val now = ZonedDateTime.now(zone)
            val file = File(File(folder, "logs"), pattern.replace("%date%", now.format(DateTimeFormatter.ISO_LOCAL_DATE)))
            runCatching {
                file.parentFile.mkdirs()
                file.appendText("[${now.format(DateTimeFormatter.ISO_LOCAL_TIME).take(8)}] $who: $command\n", Charsets.UTF_8)
            }
        }
    }
}
