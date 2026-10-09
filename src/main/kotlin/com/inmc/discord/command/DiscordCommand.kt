package com.inmc.discord.command

import com.inmc.discord.Discord
import com.inmc.discord.DiscordPlugin
import com.inmc.discord.chat.ViewMenu
import com.inmc.discord.config.PanelDesign
import com.inmc.discord.status.PanelLayout
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
                    // 서버 현황 패널(2026-10-09) — 다시 올리기 · 점검 표시 · 링크 단추 더하기/빼기.
                    .then(
                        Commands.literal("패널")
                            .executes { ctx -> panelRepost(sender(ctx)); 1 }
                            .then(Commands.literal("올리기").executes { ctx -> panelRepost(sender(ctx)); 1 })
                            .then(
                                Commands.literal("점검").then(
                                    Commands.argument("사유", StringArgumentType.greedyString()).executes { ctx ->
                                        val reason = StringArgumentType.getString(ctx, "사유").trim()
                                        discord.panel.maintenance(reason)
                                        discord.messages.send(sender(ctx), "panel-maintenance-on", Ph.of().value(reason))
                                        if (discord.settings.panel.channel == null) discord.messages.send(sender(ctx), "panel-no-channel")
                                        1
                                    },
                                ),
                            )
                            .then(Commands.literal("점검해제").executes { ctx ->
                                discord.panel.maintenance(null)
                                discord.messages.send(sender(ctx), "panel-maintenance-off")
                                1
                            })
                            .then(
                                Commands.literal("링크")
                                    .executes { ctx -> linkList(sender(ctx)); 1 }
                                    .then(Commands.literal("목록").executes { ctx -> linkList(sender(ctx)); 1 })
                                    .then(
                                        Commands.literal("추가").then(
                                            Commands.argument("내용", StringArgumentType.greedyString()).executes { ctx ->
                                                linkAdd(sender(ctx), StringArgumentType.getString(ctx, "내용"))
                                                1
                                            },
                                        ),
                                    )
                                    .then(
                                        Commands.literal("제거").then(
                                            Commands.argument("이름", StringArgumentType.greedyString())
                                                .suggests { _, builder -> discord.panelDesign.links.forEach { builder.suggest(it.label) }; builder.buildFuture() }
                                                .executes { ctx ->
                                                    linkRemove(sender(ctx), StringArgumentType.getString(ctx, "이름"))
                                                    1
                                                },
                                        ),
                                    ),
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

    private fun panelRepost(sender: CommandSender) {
        if (discord.settings.panel.channel == null) return discord.messages.send(sender, "panel-no-channel")
        discord.messages.send(sender, "panel-posting")
        discord.panel.repost()
    }

    private fun linkList(sender: CommandSender) {
        val links = discord.panelDesign.links
        if (links.isEmpty()) return discord.messages.send(sender, "panel-link-empty")
        discord.messages.send(sender, "panel-link-header", Ph.of().count(links.size).value(PanelDesign.MAX_LINKS.toString()))
        links.forEachIndexed { i, link ->
            discord.messages.send(sender, "panel-link-line", Ph.of().count(i + 1).name((link.emoji + " " + link.label).trim()).value(link.url))
        }
    }

    /** `[이모지] <이름> <주소>` — 한 줄로 받는다(한글 이름은 Brigadier 낱말 인자에 못 넣는다). */
    private fun linkAdd(sender: CommandSender, text: String) {
        val links = discord.panelDesign.links
        val link = PanelLayout.parseLink(text)
            ?: return discord.messages.send(sender, "panel-link-invalid", Ph.of().value("[이모지] <이름> <주소> 로 적어 주세요"))
        PanelDesign.linkProblem(link)?.let { return discord.messages.send(sender, "panel-link-invalid", Ph.of().value(it)) }
        if (links.size >= PanelDesign.MAX_LINKS) return discord.messages.send(sender, "panel-link-full", Ph.of().count(PanelDesign.MAX_LINKS))
        writeLinks(links + link) { discord.messages.send(sender, "panel-link-added", Ph.of().name(link.label).value(link.url)) }
    }

    /** 이름(대소문자 무시) 또는 `링크` 목록의 번호. */
    private fun linkRemove(sender: CommandSender, key: String) {
        val links = discord.panelDesign.links
        val wanted = key.trim()
        val link = wanted.toIntOrNull()?.let { links.getOrNull(it - 1) } ?: links.firstOrNull { it.label.equals(wanted, ignoreCase = true) }
            ?: return discord.messages.send(sender, "panel-link-missing", Ph.of().name(wanted))
        writeLinks(links - link) { discord.messages.send(sender, "panel-link-removed", Ph.of().name(link.label)) }
    }

    /**
     * `panel.yml` 의 `links` 만 바꿔 쓰고(워커, 원자적) 다시 읽는다 — 리로드가 패널을 다시 그린다.
     * Bukkit 이 주석을 지키며 다시 쓴다(`PanelTest` 가 배포 파일로 확인).
     */
    private fun writeLinks(links: List<PanelDesign.Link>, done: () -> Unit) {
        discord.io.async({
            val file = discord.io.file("panel.yml")
            val config = discord.io.load(file)
            config.set("links", PanelDesign.linksYaml(links))
            discord.io.save(file, config)
        }) { plugin.reload(done) }
    }

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
