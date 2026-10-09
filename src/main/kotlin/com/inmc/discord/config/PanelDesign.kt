package com.inmc.discord.config

import com.inmc.discord.status.PanelLayout.State
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.util.logging.Logger

/**
 * `panel.yml` — 서버 현황 패널의 **모양**(2026-10-09, 사용자 요청 "패널의 내부 내용을 수정할 수 있는 yml"). 불변 스냅샷.
 * 켜고 끄기·채널·주기·알림 역할은 `config.yml` 의 [Settings.Panel], 보이는 글·칸·색·단추·링크는 여기.
 */
data class PanelDesign(
    val title: String = "INMC 서버 현황",
    val thumbnail: String = "",
    val address: String = "",
    val maxNames: Int = 60,
    val footer: String = "{refresh}마다 새로 고칩니다",
    val states: Map<State, String> = DEFAULT_STATES,
    val maintenance: String = "🔧 점검 중 — {reason}",
    val colors: Map<State, Int> = DEFAULT_COLORS,
    val maintenanceColor: Int = 0x5865F2,
    val tps: List<TpsLevel> = DEFAULT_TPS,
    val starting: String = "아직 켜지는 중입니다",
    val playersEmpty: String = "아무도 없습니다",
    val playersMore: String = " 외 {count}명",
    val fields: List<FieldDef> = emptyList(),
    val buttons: Map<String, ButtonDef> = DEFAULT_BUTTONS,
    val links: List<Link> = emptyList(),
) {

    /** TPS 등급 — [min] 이상이면 이 등급(마지막은 나머지 전부). [color] 는 `&a` 같은 색 코드. */
    data class TpsLevel(val min: Double, val label: String, val color: String, val dot: String)

    /** 칸 하나. [lines] 가 있으면 [value] 대신 줄마다. [whenStates] 가 비면 언제나. */
    data class FieldDef(
        val name: String,
        val value: String = "",
        val lines: List<String> = emptyList(),
        val ansi: Boolean = false,
        val inline: Boolean = false,
        val whenStates: Set<State> = emptySet(),
    )

    data class ButtonDef(val label: String, val emoji: String = "")

    /** 패널 밑 링크 단추 하나 — 디스코드가 여는 링크라 봇이 꺼져 있어도 동작한다. */
    data class Link(val label: String, val url: String, val emoji: String = "")

    fun button(key: String): ButtonDef = buttons[key] ?: DEFAULT_BUTTONS.getValue(key)

    companion object {

        val DEFAULT_STATES = mapOf(
            State.ONLINE to "🟢 온라인",
            State.STARTING to "🟡 켜지는 중",
            State.HANG to "🟠 응답 없음 — {since}부터 멈춤",
            State.OFFLINE to "🔴 오프라인",
        )

        val DEFAULT_COLORS = mapOf(State.ONLINE to 0x57F287, State.STARTING to 0xFEE75C, State.HANG to 0xFAA61A, State.OFFLINE to 0xED4245)

        val DEFAULT_TPS = listOf(
            TpsLevel(18.0, "쾌적", "&a", "🟢"),
            TpsLevel(16.0, "보통", "&6", "🟠"),
            TpsLevel(0.0, "렉", "&c", "🔴"),
        )

        val DEFAULT_BUTTONS = mapOf(
            "me" to ButtonDef("내 정보", "👤"),
            "refresh" to ButtonDef("새로고침", "🔄"),
            "notify" to ButtonDef("켜지면 알림 받기", "🔔"),
        )

        /** 링크 단추는 한 줄(디스코드: 줄마다 단추 5개). */
        const val MAX_LINKS = 5

        /** 링크 단추로 쓸 수 없으면 그 까닭. 읽기와 `/디스코드 관리 패널 링크 추가` 가 같이 쓴다. */
        fun linkProblem(link: Link): String? = when {
            link.label.isBlank() -> "이름(label)이 비었습니다"
            link.label.length > 80 -> "이름이 80자를 넘습니다"
            !(link.url.startsWith("https://") || link.url.startsWith("http://")) -> "주소(url)는 https:// 로 시작해야 합니다"
            link.url.length > 512 -> "주소가 512자를 넘습니다"
            else -> null
        }

        /** `links` 의 YAML 모양 — `{label, url, emoji?}` 목록. 명령이 `panel.yml` 을 다시 쓸 때. */
        fun linksYaml(links: List<Link>): List<Map<String, String>> = links.map { link ->
            linkedMapOf("label" to link.label, "url" to link.url).apply { if (link.emoji.isNotBlank()) put("emoji", link.emoji) }
        }

        private fun stateOf(name: String): State? = when (name.trim().lowercase()) {
            "online", "온라인" -> State.ONLINE
            "starting", "켜지는중", "켜지는 중" -> State.STARTING
            "hang", "멈춤", "응답없음" -> State.HANG
            "offline", "오프라인" -> State.OFFLINE
            else -> null
        }

        /** 읽다 틀린 값은 경고하고 기본값으로 — 한 줄이 틀렸다고 패널이 서지 않게. */
        fun load(config: YamlConfiguration, logger: Logger): PanelDesign {
            val d = PanelDesign()
            fun color(section: ConfigurationSection?, key: String, fallback: Int): Int {
                val raw = section?.getString(key)?.trim() ?: return fallback
                return raw.removePrefix("#").toIntOrNull(16) ?: fallback.also { logger.warning("panel.yml 의 colors.$key 를 읽지 못했습니다: $raw") }
            }
            val states = config.getConfigurationSection("states")
            val colors = config.getConfigurationSection("colors")
            val tps = config.getMapList("tps").mapNotNull { entry ->
                val label = entry["label"]?.toString() ?: return@mapNotNull null
                TpsLevel((entry["min"] as? Number)?.toDouble() ?: 0.0, label, entry["color"]?.toString().orEmpty(), entry["dot"]?.toString().orEmpty())
            }.sortedByDescending { it.min }
            val fields = config.getMapList("fields").mapNotNull { entry ->
                val name = entry["name"]?.toString()
                if (name.isNullOrBlank()) {
                    logger.warning("panel.yml 의 fields 에 name 이 없는 칸을 건너뜁니다")
                    return@mapNotNull null
                }
                val whenStates = (entry["when"] as? List<*>).orEmpty().mapNotNull { raw ->
                    stateOf(raw.toString()) ?: null.also { logger.warning("panel.yml 의 '$name' when 에 모르는 상태: $raw (online · starting · hang · offline)") }
                }.toSet()
                FieldDef(
                    name = name,
                    value = entry["value"]?.toString().orEmpty(),
                    lines = (entry["lines"] as? List<*>).orEmpty().map { it.toString() },
                    ansi = entry["ansi"] == true,
                    inline = entry["inline"] == true,
                    whenStates = whenStates,
                )
            }
            val buttons = LinkedHashMap(DEFAULT_BUTTONS)
            config.getConfigurationSection("buttons")?.let { section ->
                for (key in DEFAULT_BUTTONS.keys) {
                    val b = section.getConfigurationSection(key) ?: continue
                    buttons[key] = ButtonDef(b.getString("label")?.takeIf { it.isNotBlank() }?.take(80) ?: DEFAULT_BUTTONS.getValue(key).label, b.getString("emoji")?.trim().orEmpty())
                }
            }
            val links = ArrayList<Link>()
            for (entry in config.getMapList("links")) {
                val link = Link(entry["label"]?.toString()?.trim().orEmpty(), entry["url"]?.toString()?.trim().orEmpty(), entry["emoji"]?.toString()?.trim().orEmpty())
                val why = linkProblem(link)
                when {
                    why != null -> logger.warning("panel.yml 의 링크 '${link.label}' 를 건너뜁니다 — $why")
                    links.size >= MAX_LINKS -> logger.warning("panel.yml 의 링크는 ${MAX_LINKS}개까지입니다 — '${link.label}' 를 건너뜁니다")
                    else -> links += link
                }
            }
            return PanelDesign(
                title = config.getString("title") ?: d.title,
                thumbnail = config.getString("thumbnail")?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty(),
                address = config.getString("address")?.trim().orEmpty(),
                maxNames = config.getInt("max-names", d.maxNames).coerceIn(1, 200),
                footer = config.getString("footer") ?: d.footer,
                states = State.entries.associateWith { states?.getString(it.name.lowercase()) ?: DEFAULT_STATES.getValue(it) },
                maintenance = states?.getString("maintenance") ?: d.maintenance,
                colors = State.entries.associateWith { color(colors, it.name.lowercase(), DEFAULT_COLORS.getValue(it)) },
                maintenanceColor = color(colors, "maintenance", d.maintenanceColor),
                tps = tps.ifEmpty { DEFAULT_TPS },
                starting = config.getString("texts.starting") ?: d.starting,
                playersEmpty = config.getString("texts.players-empty") ?: d.playersEmpty,
                playersMore = config.getString("texts.players-more") ?: d.playersMore,
                fields = fields,
                buttons = buttons,
                links = links,
            )
        }
    }
}
