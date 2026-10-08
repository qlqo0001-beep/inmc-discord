package com.inmc.discord.bot

import com.inmc.discord.Discord
import com.inmc.discord.chat.ItemViews
import com.inmc.discord.chat.MapPixels
import com.inmc.discord.chat.Snapshot
import com.inmc.discord.chat.SnapshotKind
import com.inmc.discord.util.Ph
import io.papermc.paper.datacomponent.DataComponentTypes
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.utils.FileUpload
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CompletableFuture

/**
 * 디스코드에서 자세히 보기(InteractiveChat 디스코드 애드온의 DiscordItemDetailsAndInteractions).
 * - [inv]·[ender] 그림 밑 "칸 고르기" → 그 아이템의 툴팁 그림(본인에게만)
 * - 아이템 그림 밑 단추 — 셜커·꾸러미 "내용 보기", 책 "책 보기"(쪽 넘김), 지도 "지도 보기"
 *
 * 아이템은 채팅에 친 순간의 사본(스냅샷)에서 꺼낸다 — [Settings.Keywords.timeoutMinutes] 가 지나면 만료.
 * 툴팁·지도 색·상자 내용은 메인에서 뽑고, 그림은 그림 일꾼에서. JDA 는 3초 안에 답해야 하므로 먼저 "생각 중" 으로 미룬다.
 */
class Interactions(private val discord: Discord) {

    /** 채팅 메시지에 붙일 줄들. */
    fun rows(snapshot: Snapshot): List<ActionRow> = when (snapshot.kind) {
        SnapshotKind.ITEM -> listOfNotNull(extras(snapshot, 0))
        else -> {
            val options = snapshot.items.withIndex().mapNotNull { (i, stack) ->
                if (stack == null || stack.isEmpty) null
                else SelectOption.of(label(stack), i.toString()).withDescription(slotName(snapshot.kind, i))
            }
            options.chunked(MENU_OPTIONS).take(2).mapIndexed { n, chunk ->
                ActionRow.of(
                    StringSelectMenu.create("$PREFIX$SLOT:${snapshot.id}:$n")
                        .setPlaceholder(discord.messages.discord("discord-select-slot"))
                        .addOptions(chunk)
                        .build(),
                )
            }
        }
    }

    /** @return 우리 것이었으면 true */
    fun onSelect(event: StringSelectInteractionEvent): Boolean {
        val parts = event.componentId.split(':')
        if (parts.size < 3 || parts[0] != "inmcd" || parts[1] != SLOT) return false
        val snapshot = find(parts[2]) ?: run {
            event.reply(discord.messages.discord("discord-expired")).setEphemeral(true).queue(null) { }
            return true
        }
        val slot = event.values.firstOrNull()?.toIntOrNull() ?: return true
        event.deferReply(true).queue({ hook -> itemReply(hook, snapshot, slot) }) { }
        return true
    }

    fun onButton(event: ButtonInteractionEvent): Boolean {
        val parts = event.componentId.split(':')
        if (parts.size < 4 || parts[0] != "inmcd") return false
        val snapshot = find(parts[2]) ?: run {
            event.reply(discord.messages.discord("discord-expired")).setEphemeral(true).queue(null) { }
            return true
        }
        val slot = parts[3].toIntOrNull() ?: return true
        val stack = snapshot.items.getOrNull(slot)?.takeUnless { it.isEmpty } ?: return true
        when (parts[1]) {
            BOX -> event.deferReply(true).queue({ hook -> contents(hook, stack) }) { }
            MAP -> event.deferReply(true).queue({ hook -> map(hook, stack) }) { }
            BOOK -> {
                val page = parts.getOrNull(4)?.toIntOrNull() ?: 0
                // 처음 열 때는 새 답, 쪽을 넘길 때는 그 답을 고친다.
                if (parts.getOrNull(5) == "edit") event.deferEdit().queue({ hook -> book(hook, snapshot, slot, stack, page, edit = true) }) { }
                else event.deferReply(true).queue({ hook -> book(hook, snapshot, slot, stack, page, edit = false) }) { }
            }
        }
        return true
    }

    // --- 답 ---------------------------------------------------------------------------

    private fun itemReply(hook: InteractionHook, snapshot: Snapshot, slot: Int) {
        val view = snapshot.views.getOrNull(slot)
        val lines = snapshot.tooltips.getOrNull(slot).orEmpty()
        discord.renderer.itemPng(view, lines).thenAccept { png ->
            if (png == null) {
                hook.sendMessage(lines.drop(1).joinToString("\n") { discord.lang.plain(it) }.ifBlank { "-" }).queue(null) { }
                return@thenAccept
            }
            val action = hook.sendFiles(FileUpload.fromData(png, "item.png"))
            extras(snapshot, slot)?.let { action.setComponents(it) }
            action.queue(null) { }
        }
    }

    private fun contents(hook: InteractionHook, stack: ItemStack) {
        onMain {
            val items = runCatching { stack.getData(DataComponentTypes.CONTAINER)?.contents() }.getOrNull()
                ?: runCatching { stack.getData(DataComponentTypes.BUNDLE_CONTENTS)?.contents() }.getOrNull()
                ?: emptyList()
            items.map(ItemViews::of) to stack.effectiveName()
        }.thenCompose { (views, title) ->
            discord.renderer.chestPng(views, title, ((views.size + 8) / 9).coerceIn(1, 6))
        }.thenAccept { png -> send(hook, png, "contents.png") }
    }

    private fun map(hook: InteractionHook, stack: ItemStack) {
        onMain {
            runCatching { stack.getData(DataComponentTypes.MAP_ID)?.id() }.getOrNull()?.let(MapPixels::read)
        }.thenCompose { colors ->
            if (colors == null) CompletableFuture.completedFuture(null) else discord.renderer.mapPng(colors)
        }.thenAccept { png -> send(hook, png, "map.png") }
    }

    private fun book(hook: InteractionHook, snapshot: Snapshot, slot: Int, stack: ItemStack, page: Int, edit: Boolean) {
        val pages: List<Component> = runCatching { stack.getData(DataComponentTypes.WRITTEN_BOOK_CONTENT)?.pages()?.map { it.raw() } }.getOrNull()
            ?: runCatching { stack.getData(DataComponentTypes.WRITABLE_BOOK_CONTENT)?.pages()?.map { Component.text(it.raw()) } }.getOrNull()
            ?: emptyList()
        if (pages.isEmpty()) return send(hook, null, "book.png")
        val index = page.coerceIn(0, pages.size - 1)
        discord.renderer.bookPng(pages, index).thenAccept { png ->
            if (png == null) return@thenAccept send(hook, null, "book.png")
            val nav = ActionRow.of(
                Button.secondary("$PREFIX$BOOK:${snapshot.id}:$slot:${index - 1}:edit", discord.messages.discord("discord-button-prev")).withDisabled(index == 0),
                Button.secondary("$PREFIX$BOOK:${snapshot.id}:$slot:${index + 1}:edit", discord.messages.discord("discord-button-next")).withDisabled(index >= pages.size - 1),
            )
            val file = FileUpload.fromData(png, "book.png")
            if (edit) hook.editOriginalAttachments(file).setComponents(nav).queue(null) { }
            else hook.sendFiles(file).setComponents(nav).queue(null) { }
        }
    }

    // --- 도움 --------------------------------------------------------------------------

    /** 아이템에 따라 붙는 단추들 — 없으면 null. 아이템 사본을 읽기만 한다. */
    private fun extras(snapshot: Snapshot, slot: Int): ActionRow? {
        val stack = snapshot.items.getOrNull(slot)?.takeUnless { it.isEmpty } ?: return null
        val buttons = ArrayList<Button>()
        fun has(block: () -> Boolean) = runCatching(block).getOrDefault(false)
        if (has { stack.getData(DataComponentTypes.CONTAINER)?.contents()?.any { !it.isEmpty } == true } ||
            has { stack.getData(DataComponentTypes.BUNDLE_CONTENTS)?.contents()?.isNotEmpty() == true }
        ) buttons += Button.secondary("$PREFIX$BOX:${snapshot.id}:$slot", discord.messages.discord("discord-button-contents"))
        if (has { stack.hasData(DataComponentTypes.WRITTEN_BOOK_CONTENT) || stack.hasData(DataComponentTypes.WRITABLE_BOOK_CONTENT) }) {
            buttons += Button.secondary("$PREFIX$BOOK:${snapshot.id}:$slot:0", discord.messages.discord("discord-button-book"))
        }
        if (has { stack.hasData(DataComponentTypes.MAP_ID) }) buttons += Button.secondary("$PREFIX$MAP:${snapshot.id}:$slot", discord.messages.discord("discord-button-map"))
        return if (buttons.isEmpty()) null else ActionRow.of(buttons)
    }

    private fun send(hook: InteractionHook, png: ByteArray?, name: String) {
        if (png == null) hook.sendMessage(discord.messages.discord("discord-render-failed")).queue(null) { }
        else hook.sendFiles(FileUpload.fromData(png, name)).queue(null) { }
    }

    private fun <T> onMain(work: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        discord.runMain { runCatching(work).fold(future::complete, future::completeExceptionally) }
        return future
    }

    private fun find(id: String): Snapshot? = discord.snapshots.get(id, discord.settings.keywords.timeoutMinutes)

    private fun label(stack: ItemStack): String =
        (discord.lang.plain(stack.effectiveName()) + if (stack.amount > 1) " x${stack.amount}" else "").take(SelectOption.LABEL_MAX_LENGTH)

    /** 칸 이름 — 가방은 단축바·가방·갑옷·왼손, 엔더는 칸 번호. */
    private fun slotName(kind: SnapshotKind, i: Int): String {
        val m = discord.messages
        if (kind == SnapshotKind.ENDER) return m.discord("discord-slot-ender", Ph.of().count(i + 1))
        return when (i) {
            in 0..8 -> m.discord("discord-slot-hotbar", Ph.of().count(i + 1))
            in 9..35 -> m.discord("discord-slot-bag", Ph.of().count(i - 8))
            36 -> m.discord("discord-slot-feet")
            37 -> m.discord("discord-slot-legs")
            38 -> m.discord("discord-slot-chest")
            39 -> m.discord("discord-slot-head")
            else -> m.discord("discord-slot-offhand")
        }
    }

    private companion object {
        const val PREFIX = "inmcd:"
        const val SLOT = "slot"
        const val BOX = "box"
        const val MAP = "map"
        const val BOOK = "book"
        const val MENU_OPTIONS = 25
    }
}
