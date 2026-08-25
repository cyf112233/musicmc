// MusicMC Paper 服务端房间中继插件。
// 注册 musicmc:room 插件消息通道,用 common 的 RoomManager 维护房间状态,
// 并在同服玩家间广播。房间协议字节与客户端 mod 完全一致。
package io.github.cyf112233.musicmc.paper

import io.github.cyf112233.musicmc.room.RoomManager
import io.github.cyf112233.musicmc.room.RoomServerIO
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MusicRoomPaper : JavaPlugin(), PluginMessageListener {

    companion object {
        const val CHANNEL: String = "musicmc:room"
    }

    private val io = object : RoomServerIO {
        private val players = ConcurrentHashMap<String, Player>()

        fun onJoin(p: Player) { players[p.uniqueId.toString()] = p }

        fun onQuit(p: Player) { players.remove(p.uniqueId.toString()) }

        override fun sendTo(playerUuid: String, payload: ByteArray) {
            val player = players[playerUuid] ?: return
            try {
                player.sendPluginMessage(this@MusicRoomPaper, CHANNEL, payload)
            } catch (_: Exception) {
                // 静默:玩家可能已离线
            }
        }
    }

    private val manager = RoomManager(io)

    override fun onEnable() {
        server.messenger.registerOutgoingPluginChannel(this, CHANNEL)
        server.messenger.registerIncomingPluginChannel(this, CHANNEL, this)
        server.pluginManager.registerEvents(QuitListener(), this)
        logger.info("MusicMC 音乐房间中继已启用($CHANNEL)")
    }

    override fun onDisable() {
        server.messenger.unregisterOutgoingPluginChannel(this)
        server.messenger.unregisterIncomingPluginChannel(this)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != CHANNEL) return
        io.onJoin(player)
        manager.handleClient(player.uniqueId.toString(), player.name, message)
    }

    /** 玩家退出时清理其房间归属 */
    private inner class QuitListener : org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler
        fun onQuit(e: org.bukkit.event.player.PlayerQuitEvent) {
            val p = e.player
            io.onQuit(p)
            manager.onPlayerQuit(p.uniqueId.toString())
        }
    }
}
