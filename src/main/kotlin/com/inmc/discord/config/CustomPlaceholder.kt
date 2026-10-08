package com.inmc.discord.config

import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.configuration.file.YamlConfiguration
import java.util.logging.Logger

/**
 * `placeholders.yml` 의 한 줄 — 채팅에 [money] 같은 글을 치면 바뀌는 것(InteractiveChat 의 CustomPlaceholders).
 *
 * [viewer] 가 참이면 `%...%` 를 **보는 사람마다** 채운다. 그 경우만 보는 사람 수만큼 PAPI 를 부르므로
 * 기본 자리표시들은 거의 다 보낸 사람 기준이다.
 */
data class CustomPlaceholder(
    val id: String,
    val pattern: Regex,
    val name: String,
    val description: String,
    val viewer: Boolean,
    /** 비면 원래 글을 그대로 두고 호버·클릭만 붙인다. */
    val text: String,
    val hover: List<String>,
    /** `SUGGEST_COMMAND` 같은 이름(대문자). 빈칸이면 없음. */
    val clickAction: String,
    val clickValue: String,
    val permission: String,
) {

    companion object {

        fun load(config: YamlConfiguration, logger: Logger): List<CustomPlaceholder> =
            config.getKeys(false).mapNotNull { id ->
                val s = config.getConfigurationSection(id) ?: return@mapNotNull null
                val source = s.getString("pattern").orEmpty()
                val pattern = try {
                    Regex(source)
                } catch (e: Exception) {
                    logger.warning("placeholders.yml '$id' 의 정규식이 틀려 건너뜁니다: $source (${e.message})")
                    return@mapNotNull null
                }
                if (source.isEmpty()) return@mapNotNull null
                CustomPlaceholder(
                    id = id,
                    pattern = pattern,
                    name = s.getString("name") ?: id,
                    description = s.getString("description").orEmpty(),
                    viewer = s.getString("parse-player").equals("viewer", ignoreCase = true),
                    text = s.getString("text").orEmpty(),
                    hover = s.getStringList("hover"),
                    clickAction = s.getString("click-action").orEmpty().trim().uppercase().takeIf { it in ACTIONS }.orEmpty(),
                    clickValue = s.getString("click-value").orEmpty(),
                    permission = s.getString("permission").orEmpty(),
                )
            }

        val ACTIONS = setOf("SUGGEST_COMMAND", "RUN_COMMAND", "OPEN_URL", "COPY_TO_CLIPBOARD")

        /** 동작 이름 + 값 → 클릭. 비었거나 모르는 이름이면 없음. */
        fun click(action: String?, value: String): ClickEvent<*>? {
            if (value.isBlank()) return null
            return when (action?.trim()?.uppercase()) {
                "SUGGEST_COMMAND" -> ClickEvent.suggestCommand(value)
                "RUN_COMMAND" -> ClickEvent.runCommand(value)
                "OPEN_URL" -> ClickEvent.openUrl(value)
                "COPY_TO_CLIPBOARD" -> ClickEvent.copyToClipboard(value)
                else -> null
            }
        }
    }
}
