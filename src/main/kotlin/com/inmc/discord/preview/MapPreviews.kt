package com.inmc.discord.preview

import com.inmc.discord.Discord
import com.inmc.discord.relay.Texts
import kr.inmc.core.config.ConfigService
import kr.inmc.core.store.YamlFileStore
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 디스코드 첨부 그림을 게임에서 미리보기(InteractiveChat 애드온의 ShowImageUsingMaps).
 *
 * **패킷을 건드리지 않는다.** 지도 제작대 화면은 첫 칸의 지도를 크게 그려 보이므로, 지도 아이템 한 장을 넣은 제작대 화면을 연다.
 * 지도 그림은 `Player.sendMap` 으로 보낸다(움직이는 그림은 프레임마다 다시). 화면의 칸은 전부 막고 닫으면 비운다.
 * 지도는 사람마다 한 장을 만들어 `preview-maps.yml` 에 적어 두고 다시 쓴다(볼 때마다 새 지도 파일이 생기지 않게).
 */
class MapPreviews(private val discord: Discord) : Listener {

    private val ids = PreviewMapStore(discord.io)
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "inmc-discord-preview").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    private class Holder(val owner: UUID) : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    private class FrameRenderer : MapRenderer(false) {
        @Volatile
        var colors: ByteArray = ByteArray(ImageFrames.SIZE * ImageFrames.SIZE)

        @Suppress("DEPRECATION")
        override fun render(map: MapView, canvas: MapCanvas, player: Player) {
            val c = colors
            for (i in c.indices) canvas.setPixel(i % ImageFrames.SIZE, i / ImageFrames.SIZE, c[i])
        }
    }

    private class Session(val holder: Holder, val view: MapView, val renderer: FrameRenderer, val frames: List<ImageFrames.Frame>, val started: Long) {
        @Volatile
        var closed = false
    }

    fun load() = ids.load()

    fun flush() = ids.flushBlocking()

    /** 메인 — `/디스코드 그림 <id>`. 받기·풀기는 일꾼에서. */
    fun open(player: Player, id: String) {
        val settings = discord.settings.attachments
        val entry = discord.attachments.get(id, settings.keepHours) ?: return discord.messages.send(player, "preview-expired")
        if (!settings.preview || !entry.image) return discord.messages.send(player, "preview-not-image")
        discord.messages.send(player, "preview-loading")
        CompletableFuture.supplyAsync({
            val bytes = download(entry.url, settings.maxBytes) ?: return@supplyAsync emptyList()
            @Suppress("DEPRECATION")
            ImageFrames.toMap(ImageFrames.decode(bytes)) { rgb -> org.bukkit.map.MapPalette.matchColor((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255) }
        }, worker).whenComplete { frames, error ->
            discord.runFor(player) {
                if (error != null || frames.isNullOrEmpty()) discord.messages.send(player, "preview-failed")
                else show(player, entry.name, frames)
            }
        }
    }

    /** 디스코드 CDN 만, 크기 한도까지만 읽는다. */
    private fun download(url: String, maxBytes: Int): ByteArray? {
        val host = runCatching { URI.create(url).host }.getOrNull() ?: return null
        if (host != "cdn.discordapp.com" && host != "media.discordapp.net") return null
        val response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) return null
        return response.body().use { read(it, maxBytes) }
    }

    private fun read(input: InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > max) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun show(player: Player, name: String, frames: List<ImageFrames.Frame>) {
        if (!player.isOnline) return
        close(player.uniqueId)
        val view = mapFor(player)
        view.renderers.toList().forEach(view::removeRenderer)
        val renderer = FrameRenderer().apply { colors = frames.first().colors }
        view.addRenderer(renderer)
        view.isTrackingPosition = false
        view.isLocked = true

        val holder = Holder(player.uniqueId)
        val inv = Bukkit.createInventory(holder, InventoryType.CARTOGRAPHY, Texts.mini(discord.messages.raw("preview-title"), "name" to name))
        holder.inv = inv
        inv.setItem(0, ItemStack(Material.FILLED_MAP).apply { editMeta(MapMeta::class.java) { it.mapView = view } })
        player.openInventory(inv)
        player.sendMap(view)
        val session = Session(holder, view, renderer, frames, System.currentTimeMillis())
        sessions[player.uniqueId] = session
        if (frames.size > 1) animate(player, session, 1)
    }

    /** 다음 프레임을 그 프레임의 지연만큼 뒤에. 닫았거나 [MAX_PLAY_MS] 가 지나면 멈춘다. */
    private fun animate(player: Player, session: Session, index: Int) {
        val delayTicks = maxOf(1L, session.frames[(index - 1 + session.frames.size) % session.frames.size].delayMs / 50L)
        Bukkit.getScheduler().runTaskLater(discord.plugin, Runnable {
            if (session.closed || !player.isOnline || System.currentTimeMillis() - session.started > MAX_PLAY_MS) return@Runnable
            session.renderer.colors = session.frames[index % session.frames.size].colors
            player.sendMap(session.view)
            animate(player, session, index + 1)
        }, delayTicks)
    }

    @Suppress("DEPRECATION")
    private fun mapFor(player: Player): MapView {
        ids.get(player.uniqueId)?.let { id -> Bukkit.getMap(id)?.let { return it } }
        val view = Bukkit.createMap(player.world)
        ids.put(player.uniqueId, view.id)
        return view
    }

    private fun close(owner: UUID) {
        sessions.remove(owner)?.let { session ->
            session.closed = true
            session.holder.inv.clear()
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        close(holder.owner)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        close(event.player.uniqueId)
    }

    fun stop() {
        sessions.keys.toList().forEach(::close)
        worker.shutdownNow()
    }

    private companion object {
        const val MAX_PLAY_MS = 5 * 60_000L
    }
}

/** 사람마다의 미리보기 지도 번호. 바뀔 때 바로 쓴다(드물다). */
private class PreviewMapStore(io: ConfigService) : YamlFileStore(io, listOf("preview-maps.yml"), "디스코드 그림 미리보기에 쓰는 사람마다의 지도 번호", "미리보기 지도 번호") {

    private val maps = ConcurrentHashMap<UUID, Int>()

    fun get(player: UUID): Int? = maps[player]

    fun put(player: UUID, id: Int) {
        maps[player] = id
        markDirty()
        flush()
    }

    override fun read(config: YamlConfiguration) {
        maps.clear()
        for (key in config.getKeys(false)) {
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
            maps[uuid] = config.getInt(key)
        }
    }

    override fun write(config: YamlConfiguration) {
        for ((uuid, id) in maps) config.set(uuid.toString(), id)
    }
}
