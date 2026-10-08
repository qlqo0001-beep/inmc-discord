package com.inmc.discord.chat

import com.inmc.discord.config.Settings
import com.inmc.discord.relay.Texts
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent

/**
 * 채팅에 보이는 [item]·[inv]·[ender] 글 — 마우스를 올리면 아이템(또는 안내), 누르면 보기 화면(`/디스코드 보기 <id>`).
 * 채팅([ChatListener])과 디스코드 공유 명령어([com.inmc.discord.bot.SlashCommands])가 같이 쓴다.
 */
object KeywordText {

    fun component(kind: SnapshotKind, keyword: Settings.Keyword, snapshot: Snapshot, id: String): Component {
        val click = ClickEvent.runCommand("/디스코드 보기 $id")
        if (kind == SnapshotKind.ITEM) {
            val stack = snapshot.items.first()
            val empty = stack == null || stack.isEmpty
            val name = if (empty) Component.translatable("block.minecraft.air") else stack!!.effectiveName()
            val amount = if (empty) 0 else stack!!.amount
            val template = if (amount == 1) keyword.singularText else keyword.text
            val shown = Texts.mini(template, "item" to name, "amount" to amount, "player" to snapshot.ownerName)
            return if (empty) shown.clickEvent(click) else shown.hoverEvent(stack!!.asHoverEvent()).clickEvent(click)
        }
        val shown = Texts.mini(keyword.text, "player" to snapshot.ownerName)
        val hover = keyword.hover.map { Texts.mini(it, "player" to snapshot.ownerName) }
        return shown.clickEvent(click).let { if (hover.isEmpty()) it else it.hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), hover))) }
    }
}
