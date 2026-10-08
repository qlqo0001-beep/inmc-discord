package com.inmc.discord.relay

import com.inmc.discord.config.Settings
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * 게임 ↔ 디스코드 글 다듬기. 전부 순수 함수 — `TextsTest` 가 지킨다.
 */
object Texts {

    private val MM = MiniMessage.miniMessage()
    private val PLAIN = PlainTextComponentSerializer.plainText()

    /** `§x§a§b§c§d§e§f`(번지 hex) → `&#abcdef`. CMI·Vault 접두사가 이 모양으로 온다. */
    private val BUNGEE_HEX = Regex("[§&][xX]((?:[§&][0-9a-fA-F]){6})")
    private val ANSI = Regex("\u001B\\[[;\\d]*[A-Za-z]")
    private val LEGACY = Regex("[§&](?:#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])")

    /**
     * 관리자 글 + PAPI 결과처럼 **색이 섞인 글**을 색 없는 글로. 마크업을 해석해서 걷어 낸다 — `&a` · `§x§…` · `<green>` 전부.
     * 플레이어가 친 글에는 쓰지 않는다(그건 [stripLegacy]).
     */
    fun plainOf(colored: String): String {
        if (colored.isEmpty()) return ""
        val normalized = BUNGEE_HEX.replace(colored) { m -> "&#" + m.groupValues[1].filter { it != '§' && it != '&' } }
        return runCatching { PLAIN.serialize(MM.deserialize(Text.legacyToMiniMessage(normalized))) }
            .getOrElse { stripLegacy(normalized) }
    }

    /** 색 코드만 걷어 낸다 — 마크업은 해석하지 않는다(플레이어 글·콘솔 줄). */
    fun stripLegacy(text: String): String = LEGACY.replace(ANSI.replace(text, ""), "")

    fun plain(component: Component): String = PLAIN.serialize(component)

    /** 정규식 거르기. 결과가 비면 null(버린다). DiscordSRV 와 같이 차례대로 바꾼다. */
    fun filter(text: String, filters: List<Settings.Filter>): String? {
        var out = text
        for (f in filters) {
            out = runCatching { f.pattern.replace(out, f.replacement) }.getOrDefault(out)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** 글자 수로 자른다(대리 쌍을 끊지 않게). 0 이면 자르지 않는다. */
    fun truncate(text: String, max: Int): String {
        if (max <= 0 || text.codePointCount(0, text.length) <= max) return text
        val end = text.offsetByCodePoints(0, max)
        return text.substring(0, end)
    }

    /**
     * `%a%` 토큰 바꾸기. `%message%` 처럼 **사람이 친 글**은 여기서 넣지 않는다 — PAPI 를 돌린 **뒤에** 넣어야
     * 플레이어가 친 `%player_name%` 이 풀리지 않는다.
     */
    fun tokens(format: String, values: Map<String, String>): String {
        if (format.indexOf('%') < 0) return format
        var out = format
        for ((k, v) in values) out = out.replace("%$k%", v)
        return out
    }

    /**
     * 게임에서 친 `@이름` → 디스코드 멘션. [targets] 는 (이름, 멘션 글) — 긴 이름부터 맞춘다(`@abc` 가 `@abcd` 를 먹지 않게).
     * 이름 뒤가 영문·숫자·밑줄이면 다른 이름의 앞부분이므로 바꾸지 않는다. 대소문자는 가리지 않는다.
     */
    fun mentionsToDiscord(text: String, targets: List<Pair<String, String>>, sigil: Char = '@'): String {
        if (text.indexOf(sigil) < 0 || targets.isEmpty()) return text
        val sorted = targets.filter { it.first.isNotBlank() }.sortedByDescending { it.first.length }
        val out = StringBuilder(text.length + 16)
        var i = 0
        outer@ while (i < text.length) {
            if (text[i] == sigil) {
                for ((name, mention) in sorted) {
                    val end = i + 1 + name.length
                    if (end <= text.length && text.regionMatches(i + 1, name, 0, name.length, ignoreCase = true) &&
                        (end == text.length || !isNameChar(text[end]))
                    ) {
                        out.append(mention)
                        i = end
                        continue@outer
                    }
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    /** 마인크래프트 이름 글자(영문·숫자·밑줄). 한글 조사(`@Steve님`)는 이름 밖이다 — 한글 닉네임은 긴 것부터 맞춰서 가린다. */
    private fun isNameChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    /** 디스코드 메시지 하나의 한도(2000자)에 맞게 줄 단위로 나눈다. 한 줄이 넘치면 그 줄을 자른다. */
    fun chunks(lines: List<String>, limit: Int): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (raw in lines) {
            val line = if (raw.length > limit) raw.substring(0, limit) else raw
            if (current.isNotEmpty() && current.length + 1 + line.length > limit) {
                out += current.toString()
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /**
     * 관리자 글(`&색`·MiniMessage)에 **남이 친 값**을 끼워 넣는다. 값은 마크업으로도 PAPI 로도 해석되지 않는다
     * — 디스코드 이름이나 파일 이름에 `<red>`·`%server_name%` 이 들어 있어도 글자 그대로.
     * 템플릿의 `{이름}` 자리에 들어간다.
     */
    fun mini(template: String, vararg values: Pair<String, Any>): Component {
        var t = template
        val resolvers = values.map { (key, value) ->
            val tag = "v_" + key.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
            t = t.replace("{$key}", "<$tag>")
            when (value) {
                is Component -> net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(tag, value)
                else -> net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(tag, value.toString())
            }
        }
        return MM.deserialize(Text.legacyToMiniMessage(t), *resolvers.toTypedArray())
    }

    /** 코드 블록 안에서 블록을 닫아 버리지 않게. */
    fun escapeCodeBlock(text: String): String = text.replace("```", "`​``")
}
