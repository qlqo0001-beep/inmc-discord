package com.inmc.discord

import com.inmc.discord.chat.ChatListener
import com.inmc.discord.command.DiscordCommand
import com.inmc.discord.config.Migration
import com.inmc.discord.render.Lang
import kr.inmc.core.listener.MenuListener
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/**
 * 켜는 순서: 기본 파일 → (처음이면) DiscordSRV·InteractiveChat 에서 옮기기 → 설정 읽기 → 리스너·명령어 →
 * 콘솔 어펜더 → 봇 연결(봇 일꾼에서, 메인은 기다리지 않음) → 첫 틱에 시작 알림·감시 시작.
 */
class DiscordPlugin : JavaPlugin() {

    private lateinit var discord: Discord
    private lateinit var chat: ChatListener

    override fun onEnable() {
        discord = Discord(this)
        // 패널 자료는 panel-data.yml — 같은 날 잠깐 panel.yml 이었다(지금 panel.yml 은 모양 파일). 기본 파일을 깔기 전에 옮긴다.
        com.inmc.discord.status.PanelStore.migrateOldFile(dataFolder, logger)
        for (name in RESOURCES) discord.io.copyDefault(name, discord.io.file(name))
        warnConflicts()
        runCatching { Migration(dataFolder, dataFolder.parentFile, logger).runIfNeeded() }
            .onFailure { logger.severe("DiscordSRV·InteractiveChat 설정을 옮기지 못했습니다: ${it.message}") }
        discord.apply(discord.readFiles())
        discord.lang = Lang.load(dataFolder, discord.settings.render.language, logger)
        discord.links.load { logger.info("연결 계정 ${discord.links.count()}명") }
        discord.panel.store.loadNow()

        chat = ChatListener(discord)
        server.pluginManager.registerEvents(chat, this)
        server.pluginManager.registerEvents(MenuListener(discord), this)
        server.pluginManager.registerEvents(discord.previews, this)
        server.pluginManager.registerEvents(discord.playerEvents, this)
        server.pluginManager.registerEvents(discord.nicknames, this)
        server.pluginManager.registerEvents(discord.panel, this)
        Signals.register()
        discord.previews.load()
        DiscordCommand(discord, this).register()
        discord.alerts.register()

        discord.console.start()
        discord.bot.start()
        discord.renderer.start()
        // 모든 플러그인이 켜지고 서버가 첫 틱을 돌 때.
        Bukkit.getScheduler().runTask(this, Runnable {
            discord.bot.markServerStarted()
            discord.panel.serverStarted()
            discord.watchdog.arm()
            discord.nicknames.bindTitleForge()
        })
    }

    override fun onDisable() {
        if (!::discord.isInitialized) return
        // 람다가 이 플러그인의 클래스로더를 붙들고 있다.
        Signals.unregister()
        discord.watchdog.stop()
        discord.alerts.unregister()
        discord.bot.stop()
        discord.renderer.stop()
        discord.console.stop()
        discord.previews.stop()
        discord.previews.flush()
        discord.links.flushBlocking()
        discord.panel.store.flushBlocking()
        discord.io.shutdown()
    }

    /** `/디스코드 관리 리로드`. 파일은 워커에서 읽고 반영은 메인에서. 봇 토큰을 바꿨으면 재시작해야 한다. */
    fun reload(then: () -> Unit) {
        val oldToken = discord.settings.bot.token
        closeMenus()
        discord.io.async({ discord.readFiles() }) { files ->
            discord.apply(files)
            if (files.settings.bot.token != oldToken) logger.warning("봇 토큰은 서버를 다시 켜야 바뀝니다")
            discord.bot.jda?.presence?.activity = files.settings.bot.status.takeIf { it.isNotBlank() }
                ?.let(net.dv8tion.jda.api.entities.Activity::playing)
            discord.bot.restartTopics()
            discord.panel.reloaded()
            discord.console.restart()
            for (player in server.onlinePlayers) chat.refreshCompletions(player)
            then()
        }
    }

    private fun closeMenus() {
        for (player in server.onlinePlayers) {
            val holder = player.openInventory.topInventory.holder
            if (holder is kr.inmc.core.gui.Menu && holder.owner === discord) player.closeInventory()
        }
    }

    /** 대신하는 플러그인이 같이 있으면 같은 봇이 두 번 말하고 채팅이 두 번 꾸며진다. */
    private fun warnConflicts() {
        val present = REPLACED.filter { server.pluginManager.getPlugin(it) != null }
        if (present.isNotEmpty()) {
            logger.warning("${present.joinToString(", ")} 이(가) 같이 켜져 있습니다 — inmc-discord 가 대신하므로 빼 주세요(메시지가 두 번 갑니다)")
        }
    }

    private companion object {
        val RESOURCES = listOf("config.yml", "messages.yml", "placeholders.yml", "panel.yml")
        val REPLACED = listOf("DiscordSRV", "InteractiveChat", "InteractiveChatDiscordSrvAddon")
    }
}
