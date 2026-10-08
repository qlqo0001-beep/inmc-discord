package com.inmc.discord.link

import kr.inmc.core.config.ConfigService
import kr.inmc.core.store.YamlFileStore
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 마인크래프트 uuid ↔ 디스코드 id. `links.yml` 하나.
 *
 * 바꾸기(연결·해제)는 메인 스레드에서만, 읽기는 아무 스레드에서나 — JDA 스레드가 멘션을 바꿀 때 묻는다.
 * 그래서 양쪽 맵을 동시성 맵으로 두고, 파일 쓰기는 core [YamlFileStore] 의 계약(메인에서 YAML 을 만들고 워커가 쓴다)을 따른다.
 * 연결은 드물고 잃으면 다시 연결해야 하므로 바뀔 때마다 바로 쓴다.
 */
class LinkStore(io: ConfigService) : YamlFileStore(io, listOf("links.yml"), HEADER, "디스코드 연결 계정") {

    private val byPlayer = ConcurrentHashMap<UUID, Long>()
    private val byDiscord = ConcurrentHashMap<Long, UUID>()

    fun discordOf(player: UUID): Long? = byPlayer[player]

    fun playerOf(discord: Long): UUID? = byDiscord[discord]

    fun count(): Int = byPlayer.size

    fun all(): Map<UUID, Long> = HashMap(byPlayer)

    /** 메인 스레드. 같은 디스코드 계정·같은 플레이어의 옛 연결은 지운다. */
    fun link(player: UUID, discord: Long) {
        // ConcurrentHashMap.remove(null) 은 던진다 — 옛 연결이 있을 때만 지운다.
        byPlayer[player]?.let(byDiscord::remove)
        byDiscord[discord]?.let(byPlayer::remove)
        byPlayer[player] = discord
        byDiscord[discord] = player
        markDirty()
        flush()
    }

    /** 메인 스레드. 지운 디스코드 id, 없었으면 null. */
    fun unlink(player: UUID): Long? {
        val id = byPlayer.remove(player) ?: return null
        byDiscord.remove(id)
        markDirty()
        flush()
        return id
    }

    override fun read(config: YamlConfiguration) {
        byPlayer.clear()
        byDiscord.clear()
        val section = config.getConfigurationSection("links") ?: return
        for (key in section.getKeys(false)) {
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
            val id = section.getString(key)?.toLongOrNull() ?: continue
            byPlayer[uuid] = id
            byDiscord[id] = uuid
        }
    }

    override fun write(config: YamlConfiguration) {
        // id 는 글자로 — 큰 수를 YAML 숫자로 두면 다른 도구가 double 로 읽어 끝자리가 바뀐다.
        for ((uuid, id) in byPlayer) config.set("links.$uuid", id.toString())
    }

    private companion object {
        const val HEADER = "마인크래프트 uuid: 디스코드 id. /디스코드 연결 로 생기고, 처음 켤 때 DiscordSRV accounts.aof 에서 옮겨 온다."
    }
}
