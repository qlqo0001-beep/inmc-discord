package com.inmc.discord.command

import com.inmc.discord.Discord
import com.inmc.discord.DiscordPlugin
import com.inmc.discord.chat.ViewMenu
import com.inmc.discord.relay.Texts
import com.inmc.discord.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import org.bukkit.Bukkit
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/디스코드` 한 트리(별칭 `discord`·`inmcdiscord` — DiscordSRV 의 `/discord` 자리를 잇는다).
 */
class DiscordCommand(private val discord: Discord, private val plugin: DiscordPlugin) {

    fun register() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(tree().build(), "INMC 디스코드", listOf("discord", "inmcdiscord"))
        }
    }

    /** 도움말 — 관리자 줄은 권한이 있을 때만(2026-10-08). */
    private fun help(sender: org.bukkit.command.CommandSender) {
        discord.messages.send(sender, "help")
        if (sender.hasPermission(Discord.ADMIN)) discord.messages.send(sender, "help-admin")
    }

    private fun sender(ctx: CommandContext<CommandSourceStack>): CommandSender = ctx.source.sender

    private fun player(ctx: CommandContext<CommandSourceStack>): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { discord.messages.send(sender(ctx), "player-only") }

    private fun has(permission: String) = { source: CommandSourceStack -> source.sender.hasPermission(permission) }

    private fun tree(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("디스코드")
            .executes { ctx ->
                val link = discord.settings.inviteLink
                if (link.isBlank()) discord.messages.send(sender(ctx), "invite-none")
                else discord.messages.send(sender(ctx), "invite", Ph.of().value(link))
                1
            }
            .then(Commands.literal("도움말").executes { ctx -> help(sender(ctx)); 1 })
            .then(Commands.literal("연결").requires(has(Discord.LINK)).executes { ctx -> player(ctx)?.let(discord.linking::start); 1 })
            .then(Commands.literal("연결해제").requires(has(Discord.LINK)).executes { ctx -> player(ctx)?.let(discord.linking::unlink); 1 })
            .then(Commands.literal("연결상태").requires(has(Discord.LINK)).executes { ctx -> player(ctx)?.let(discord.linking::status); 1 })
            .then(Commands.literal("자리표시").executes { ctx -> placeholders(sender(ctx)); 1 })
            .then(
                Commands.literal("보기").requires(has(Discord.VIEW))
                    .then(Commands.argument("id", StringArgumentType.word()).executes { ctx ->
                        player(ctx)?.let { view(it, StringArgumentType.getString(ctx, "id")) }
                        1
                    }),
            )
            .then(
                Commands.literal("그림")
                    .then(Commands.argument("id", StringArgumentType.word()).executes { ctx ->
                        player(ctx)?.let { discord.previews.open(it, StringArgumentType.getString(ctx, "id")) }
                        1
                    }),
            )
            .then(
                Commands.literal("관리").requires(has(Discord.ADMIN))
                    .executes { ctx -> help(sender(ctx)); 1 }
                    .then(Commands.literal("리로드").executes { ctx ->
                        val sender = sender(ctx)
                        plugin.reload { discord.messages.send(sender, "reloaded") }
                        1
                    })
                    .then(
                        Commands.literal("검증")
                            .executes { ctx -> discord.verifier.run(sender(ctx), send = false); 1 }
                            .then(Commands.literal("보내기").executes { ctx -> discord.verifier.run(sender(ctx), send = true); 1 }),
                    )
                    // 관리자 시험 도구(2026-10-08) — 접속·퇴장·사망 알림을 사건 없이, 디스코드 별명 맞추기를 지금 해 보고 결과를.
                    .then(
                        // 종류는 명령 단어로 — 한글은 Brigadier word 인자에 못 넣는다.
                        listOf("접속", "처음접속", "퇴장", "사망").fold(Commands.literal("시험")) { node, kind ->
                            node.then(Commands.literal(kind).executes { ctx ->
                                val player = player(ctx) ?: return@executes 0
                                val why = discord.playerEvents.test(player, kind)
                                discord.messages.send(player, if (why == null) "verify-ok" else "verify-fail",
                                    Ph.of().name("디스코드 $kind 알림").value(why ?: "채팅 채널로 보냄"))
                                1
                            })
                        },
                    )
                    .then(
                        Commands.literal("별명")
                            .executes { ctx -> player(ctx)?.let { nicknameTest(sender(ctx), it.uniqueId, it.name) }; 1 }
                            .then(
                                Commands.argument("플레이어", StringArgumentType.word())
                                    .suggests { _, builder -> Bukkit.getOnlinePlayers().forEach { builder.suggest(it.name) }; builder.buildFuture() }
                                    .executes { ctx ->
                                        val raw = StringArgumentType.getString(ctx, "플레이어")
                                        val target = Bukkit.getPlayerExact(raw) ?: Bukkit.getOfflinePlayerIfCached(raw)
                                        if (target == null) {
                                            discord.messages.send(sender(ctx), "verify-fail", Ph.of().name("디스코드 별명").value("'$raw' 을(를) 찾지 못함"))
                                        } else nicknameTest(sender(ctx), target.uniqueId, target.name ?: raw)
                                        1
                                    },
                            ),
                    )
                    .then(Commands.literal("상태").executes { ctx ->
                        discord.messages.send(
                            sender(ctx), "status",
                            Ph.of().value(discord.bot.state).count(discord.links.count()).name(discord.console.pending().toString()).bot(discord.renderer.state),
                        )
                        1
                    }),
            )

    /** 별명 맞추기를 지금 해 보고 결과를 [sender] 에게(JDA 스레드에서 오므로 메인으로 넘겨 알린다). */
    private fun nicknameTest(sender: org.bukkit.command.CommandSender, uuid: java.util.UUID, name: String) {
        discord.nicknames.sync(uuid, name) { ok, text ->
            discord.runMain {
                discord.messages.send(sender, if (ok == false) "verify-fail" else "verify-ok", Ph.of().name("디스코드 별명 ($name)").value(text))
            }
        }
    }

    private fun view(player: Player, id: String) {
        val snapshot = discord.snapshots.get(id, discord.settings.keywords.timeoutMinutes)
            ?: return discord.messages.send(player, "view-expired")
        val keywords = discord.settings.keywords
        val keyword = when (snapshot.kind) {
            com.inmc.discord.chat.SnapshotKind.ITEM -> keywords.item
            com.inmc.discord.chat.SnapshotKind.INVENTORY -> keywords.inventory
            com.inmc.discord.chat.SnapshotKind.ENDER -> keywords.ender
        }
        ViewMenu(discord, snapshot, Texts.mini(keyword.title, "player" to snapshot.ownerName)).open(player)
    }

    /** `/디스코드 자리표시` — 채팅에 쓸 수 있는 것 목록(InteractiveChat 의 `/ic list`). */
    private fun placeholders(sender: CommandSender) {
        val messages = discord.messages
        val keywords = discord.settings.keywords
        val rows = listOf(keywords.item, keywords.inventory, keywords.ender).filter { it.enabled }.map { it.name to it.description } +
            discord.placeholders.filter { it.permission.isBlank() || sender.hasPermission(it.permission) }.map { it.name to it.description }
        messages.send(sender, "placeholder-list-header")
        rows.forEachIndexed { i, (name, description) ->
            sender.sendMessage(Texts.mini(messages.raw("placeholder-list-line").replace("{value}", description), "count" to (i + 1), "name" to name))
        }
    }
}
