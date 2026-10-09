package com.inmc.discord.status

import com.inmc.discord.config.PanelDesign
import java.util.Locale

/**
 * 서버 현황 패널의 **모양**(2026-10-09). 순수 — 상태·이름·숫자와 `panel.yml`([PanelDesign])을 받아 필드 목록을 낸다. JDA·서버 없이 `PanelTest` 가 돌린다.
 *
 * 칸은 `panel.yml` 의 틀에 토큰을 끼워 만든다. **값이 없는 토큰이 든 줄은 통째로 뺀다** — 주소를 비우면 주소 줄이, 꺼지면 TPS 줄이 빠진다.
 * 시각은 디스코드 타임스탬프(`<t:초:f>`·`<t:초:R>`) — 보는 사람의 시간대로 보이고 "2시간 전"은 봇이 고치지 않아도 흐른다.
 * 글자색은 디스코드가 `ansi` 코드 블록 안에서만 그린다 — `ansi: true` 칸의 `&a` 같은 마인크래프트 색 코드를 ANSI 로 바꾼다([ansi]).
 */
object PanelLayout {

    enum class State { STARTING, ONLINE, HANG, OFFLINE }

    data class Today(val joins: Int, val peak: Int)

    /** 메인에서 뜬 값. 멈춤·꺼짐 때는 마지막으로 뜬 것을 다시 쓴다. */
    data class Gathered(
        val names: List<String>,
        val online: Int,
        val max: Int,
        val tps: Double,
        val version: String,
        val today: Today,
    )

    data class Data(
        val state: State,
        val gathered: Gathered?,
        val startedAt: Long? = null,
        val stoppedAt: Long? = null,
        val hangSince: Long? = null,
        val maintenance: String? = null,
        val updatedAt: Long,
    )

    data class Field(val name: String, val value: String, val inline: Boolean = false)

    data class View(val title: String, val color: Int, val fields: List<Field>, val footer: String, val buttonsEnabled: Boolean)

    fun view(data: Data, design: PanelDesign, refreshSeconds: Int): View {
        val g = data.gathered
        val live = (data.state == State.ONLINE || data.state == State.HANG) && g != null
        val level = g?.let { tpsLevel(design.tps, it.tps) }

        // null = 값이 없음(그 줄을 뺀다) · "" = 있지만 비어 있음.
        val base = HashMap<String, String?>()
        base["since"] = data.hangSince?.let { stamp(it, 'R') }
        base["reason"] = data.maintenance?.takeIf { it.isNotBlank() }
        var state = fill(design.states[data.state] ?: data.state.name, base).orEmpty()
        if (base["reason"] != null) fill(design.maintenance, base)?.let { state += "\n" + it }
        base["state"] = state
        base["started"] = if (data.state == State.STARTING || data.startedAt == null) design.starting else moment(data.startedAt)
        base["stopped"] = data.stoppedAt?.takeIf { data.state == State.OFFLINE }?.let(::moment)
        base["updated"] = stamp(data.updatedAt, 'R')
        base["refresh"] = every(refreshSeconds)
        base["address"] = design.address.takeIf { it.isNotBlank() }
        base["version"] = g?.version?.takeIf { it.isNotBlank() }
        base["online"] = if (live) g!!.online.toString() else null
        base["max"] = if (live) g!!.max.toString() else null
        base["tps"] = if (live) String.format(Locale.ROOT, "%.1f", g!!.tps.coerceAtMost(20.0)) else null
        base["tps-label"] = if (live) level!!.label else null
        base["tps-dot"] = if (live) level!!.dot else null
        base["today"] = if (live) g!!.today.joins.toString() else null
        base["peak"] = if (live) g!!.today.peak.toString() else null
        fun more(n: Int) = fill(design.playersMore, mapOf("count" to n.toString())).orEmpty()

        val fields = ArrayList<Field>()
        for (def in design.fields) {
            if (def.whenStates.isNotEmpty() && data.state !in def.whenStates) continue
            val tokens = HashMap(base)
            if (def.ansi) {
                // 코드 블록 안 — 마크다운을 무력화하지 않고, 코드 블록을 닫는 ``` 만 막는다.
                for ((k, v) in tokens) tokens[k] = v?.replace("```", "`​``")
                tokens["tps-color"] = if (live) ansi(level!!.color) else null
                tokens["players"] = if (live) names(g!!.names, design.maxNames, design.playersEmpty, ::more) { it.replace("```", "") } else null
            } else {
                tokens["tps-color"] = if (live) "" else null
                tokens["players"] = if (live) names(g!!.names, design.maxNames, design.playersEmpty, ::more, ::escape) else null
            }
            val name = fill(stripColors(def.name), tokens)?.takeIf { it.isNotBlank() } ?: continue
            val raw = if (def.lines.isNotEmpty()) {
                def.lines.mapNotNull { line -> fill(if (def.ansi) ansi(line) else stripColors(line), tokens) }.joinToString("\n")
            } else fill(if (def.ansi) ansi(def.value) else stripColors(def.value), tokens).orEmpty()
            if (raw.isBlank()) continue
            val value = if (def.ansi) "```ansi\n" + raw.take(FIELD_VALUE_MAX - ANSI_FENCE) + "\n```" else raw.take(FIELD_VALUE_MAX)
            fields += Field(name.take(FIELD_NAME_MAX), value, def.inline)
        }

        val color = when {
            data.state != State.ONLINE -> design.colors[data.state] ?: 0
            data.maintenance?.isNotBlank() == true -> design.maintenanceColor
            else -> design.colors[State.ONLINE] ?: 0
        }
        return View(
            title = stripColors(design.title).take(TITLE_MAX).ifBlank { "-" },
            color = color,
            fields = fields.take(MAX_FIELDS),
            footer = fill(stripColors(design.footer), base).orEmpty(),
            // 꺼진 패널의 단추는 눌러도 "상호작용 실패"다(봇이 없다) — 끈 모양으로 그린다.
            buttonsEnabled = data.state != State.OFFLINE,
        )
    }

    private val TOKEN = Regex("""\{([a-z-]+)\}""")

    /** 틀의 `{토큰}` 을 바꾼다. 값이 null 인 토큰이 하나라도 있으면 null(그 줄을 뺀다). 모르는 토큰은 그대로 둔다. */
    fun fill(template: String, tokens: Map<String, String?>): String? {
        var missing = false
        val out = TOKEN.replace(template) { m ->
            val key = m.groupValues[1]
            if (!tokens.containsKey(key)) m.value
            else tokens[key] ?: run {
                missing = true
                ""
            }
        }
        return if (missing) null else out
    }

    /** 인게임 /tps 처럼 — 위에서부터 [PanelDesign.TpsLevel.min] 이상인 첫 등급, 없으면 마지막. */
    fun tpsLevel(levels: List<PanelDesign.TpsLevel>, tps: Double): PanelDesign.TpsLevel =
        levels.firstOrNull { tps >= it.min } ?: levels.last()

    /**
     * 마인크래프트 색 코드(`&a` · `§c` · `&l` · `&r`)를 디스코드 `ansi` 코드 블록의 색으로. 디스코드가 그리는 글자색은 여덟 가지라
     * 비슷한 것끼리 묶는다(주황·노랑 → 노랑 33, 회색·검정 → 회색 30). 모르는 코드는 그대로 둔다.
     */
    fun ansi(text: String): String {
        if (text.indexOf('&') < 0 && text.indexOf('§') < 0) return text
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if ((c == '&' || c == '§') && i + 1 < text.length) {
                val code = ANSI_CODES[text[i + 1].lowercaseChar()]
                if (code != null) {
                    out.append(ESC).append('[').append(code).append('m')
                    i += 2
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** ansi 가 아닌 칸의 색 코드는 지운다(디스코드 글자에 색이 없다). */
    fun stripColors(text: String): String = COLOR_CODE.replace(text, "")

    /** 채널에 남은 우리 패널 id(새것부터) 가운데 남길 것 — 저장된 것이 그 안에 있으면 그것, 아니면 가장 새것. 하나도 없으면 null. */
    fun keepPanel(stored: Long?, newestFirst: List<Long>): Long? = newestFirst.firstOrNull { it == stored } ?: newestFirst.firstOrNull()

    /** 디스코드 타임스탬프. f = 날짜와 시각, D = 날짜, R = "2시간 전". */
    fun stamp(millis: Long, style: Char): String = "<t:${millis / 1000}:$style>"

    /** "2026년 10월 9일 오후 5:33 (2시간 전)". */
    fun moment(millis: Long): String = stamp(millis, 'f') + " (" + stamp(millis, 'R') + ")"

    /**
     * 이름 목록 한 칸. 디스코드 필드는 1024자 — [max] 명 또는 글자 한도에서 끊고 "외 N명". 이름은 [escapeName] 을 거친다
     * (마크다운 칸은 `Nine_Sik` 의 밑줄이 기울임이 되지 않게).
     */
    fun names(names: List<String>, max: Int, empty: String, more: (Int) -> String, escapeName: (String) -> String = ::escape): String {
        if (names.isEmpty()) return empty
        val budget = FIELD_VALUE_MAX - ANSI_FENCE - 64
        val out = StringBuilder()
        var shown = 0
        for (name in names) {
            val piece = (if (shown == 0) "" else ", ") + escapeName(name)
            val rest = names.size - shown - 1
            val tail = if (rest > 0) more(rest).length else 0
            if (shown >= max || out.length + piece.length + tail > budget) break
            out.append(piece)
            shown++
        }
        if (shown < names.size) out.append(more(names.size - shown))
        return out.toString()
    }

    /** 디스코드 마크다운 글자를 `\` 로 무력화한다. */
    fun escape(text: String): String = MARKDOWN.replace(text) { "\\" + it.value }

    /** 60 → "1분", 90 → "1분 30초", 15 → "15초". */
    fun every(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m == 0 -> "${s}초"
            s == 0 -> "${m}분"
            else -> "${m}분 ${s}초"
        }
    }

    /** 틱 → "3일 4시간" · "41시간 12분" · "12분" · "1분 미만". */
    fun playtime(ticks: Long): String {
        val minutes = ticks / 20 / 60
        val days = minutes / (60 * 24)
        val hours = minutes / 60 % 24
        val mins = minutes % 60
        return when {
            days > 0 -> "${days}일 ${hours}시간"
            minutes >= 60 -> "${minutes / 60}시간 ${mins}분"
            minutes > 0 -> "${minutes}분"
            else -> "1분 미만"
        }
    }

    /**
     * `/디스코드 관리 패널 링크 추가` 의 글 — `[이모지] <이름> <주소>`. 주소는 마지막 낱말, 이모지는 첫 낱말이 글자·숫자가 하나도 없을 때.
     * 이름에는 띄어쓰기가 들어가도 된다(한글은 Brigadier 낱말 인자에 못 넣어 한 줄로 받는다).
     */
    fun parseLink(text: String): PanelDesign.Link? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val url = words.last()
        var rest = words.dropLast(1)
        var emoji = ""
        if (rest.size >= 2 && rest.first().codePoints().noneMatch(Character::isLetterOrDigit)) {
            emoji = rest.first()
            rest = rest.drop(1)
        }
        return PanelDesign.Link(rest.joinToString(" "), url, emoji)
    }

    private const val ESC = '\u001B'

    private val ANSI_CODES: Map<Char, String> = mapOf(
        '0' to "0;30", '8' to "0;30", '7' to "0;30",
        'f' to "0;37",
        'a' to "0;32", '2' to "0;32",
        'c' to "0;31", '4' to "0;31",
        'e' to "0;33", '6' to "0;33",
        'b' to "0;36", '3' to "0;36",
        '9' to "0;34", '1' to "0;34",
        'd' to "0;35", '5' to "0;35",
        'l' to "1", 'n' to "4", 'r' to "0",
    )

    private val COLOR_CODE = Regex("[&§][0-9a-fk-orA-FK-OR]")

    private val MARKDOWN = Regex("""[\\*_~`|>\[\]()]""")

    /** "```ansi\n" + "\n```" */
    private const val ANSI_FENCE = 12

    const val TITLE_MAX = 256
    const val FIELD_NAME_MAX = 256
    const val FIELD_VALUE_MAX = 1024
    const val MAX_FIELDS = 25
}
