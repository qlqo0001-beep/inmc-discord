package com.inmc.discord.status

import kr.inmc.core.config.ConfigService
import kr.inmc.core.store.YamlFileStore
import org.bukkit.configuration.file.YamlConfiguration
import java.time.LocalDate
import java.util.UUID

/**
 * `panel-data.yml` — 패널 메시지가 어디 있는지, 점검 표시, 오늘 접속 수(2026-10-09). 모양은 `panel.yml`([com.inmc.discord.config.PanelDesign]).
 *
 * 값은 아무 스레드에서 바꿔도 된다(봇·JDA 스레드가 메시지 id 를 적는다). 파일로 옮기는 것([flush])은 core [YamlFileStore] 의
 * 계약대로 메인에서 — 패널이 주기마다 메인에서 값을 뜰 때와 꺼질 때.
 */
class PanelStore(io: ConfigService) : YamlFileStore(io, listOf(FILE), HEADER, "디스코드 패널") {

    data class Message(val channel: Long, val id: Long)

    @Volatile
    var message: Message? = null
        private set

    @Volatile
    var maintenance: String? = null
        private set

    /** 파일을 읽었나. 읽기 전에 패널을 올리면 저장된 메시지를 몰라 패널이 둘이 된다(2026-10-09 실제로). */
    @Volatile
    var loaded = false
        private set

    /** 켤 때(메인) 바로 읽는다 — 봇이 붙기 전에 메시지 위치를 알아야 한다. 몇 줄짜리 파일이다. */
    fun loadNow() {
        val file = io.file(FILE)
        read(if (file.exists()) io.load(file) else YamlConfiguration())
    }

    private val lock = Any()
    private var day: String = ""
    private val joined = HashSet<UUID>()
    private var peak = 0

    fun setMessage(value: Message?) {
        message = value
        markDirty()
    }

    fun setMaintenance(reason: String?) {
        maintenance = reason?.takeIf { it.isNotBlank() }
        markDirty()
    }

    /** 접속할 때(메인). [online] 은 들어온 사람을 센 지금 접속자 수. */
    fun recordJoin(player: UUID, online: Int, today: LocalDate) = synchronized(lock) {
        roll(today)
        if (joined.add(player) or (online > peak)) markDirty()
        peak = maxOf(peak, online)
    }

    /** 날짜가 바뀌었으면 지금 접속자로 다시 센다. */
    fun today(today: LocalDate, online: Int): PanelLayout.Today = synchronized(lock) {
        roll(today)
        if (online > peak) {
            peak = online
            markDirty()
        }
        PanelLayout.Today(joined.size, peak)
    }

    private fun roll(today: LocalDate) {
        val key = today.toString()
        if (key == day) return
        day = key
        joined.clear()
        peak = 0
        markDirty()
    }

    override fun read(config: YamlConfiguration) {
        val channel = config.getString("message.channel")?.toLongOrNull()
        val id = config.getString("message.id")?.toLongOrNull()
        message = if (channel != null && id != null) Message(channel, id) else null
        maintenance = config.getString("maintenance")?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            day = config.getString("today.date").orEmpty()
            joined.clear()
            config.getStringList("today.joined").mapNotNullTo(joined) { runCatching { UUID.fromString(it) }.getOrNull() }
            peak = config.getInt("today.peak", 0)
        }
        loaded = true
    }

    override fun write(config: YamlConfiguration) {
        // id 는 글자로 — 큰 수를 YAML 숫자로 두면 다른 도구가 double 로 읽어 끝자리가 바뀐다.
        message?.let {
            config.set("message.channel", it.channel.toString())
            config.set("message.id", it.id.toString())
        }
        maintenance?.let { config.set("maintenance", it) }
        synchronized(lock) {
            config.set("today.date", day)
            config.set("today.joined", joined.map(UUID::toString))
            config.set("today.peak", peak)
        }
    }

    companion object {
        const val FILE = "panel-data.yml"
        private const val HEADER = "디스코드 서버 현황 패널 — 플러그인이 쓰는 파일입니다(모양은 panel.yml). 패널을 다시 올리려면 /디스코드 관리 패널."

        /** 자료가 `panel.yml` 에 있던 판(2026-10-09 하루)에서 옮긴다 — 모양 파일과 자료 파일이 같은 이름이면 안 된다. */
        fun migrateOldFile(folder: java.io.File, logger: java.util.logging.Logger) {
            val old = java.io.File(folder, "panel.yml")
            val target = java.io.File(folder, FILE)
            if (!old.isFile || target.exists()) return
            val yaml = YamlConfiguration.loadConfiguration(old)
            if (yaml.isSet("fields") || !(yaml.isSet("message") || yaml.isSet("today"))) return
            if (old.renameTo(target)) logger.info("패널 자료를 panel.yml → $FILE 로 옮겼습니다(panel.yml 은 이제 패널 모양 파일)")
        }
    }
}
