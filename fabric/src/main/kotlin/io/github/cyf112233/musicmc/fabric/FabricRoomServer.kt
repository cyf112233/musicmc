// MusicMC Fabric 服务端房间中继。
// 在专用服务器 / 集成服务器(玩家自建、他人联机)上注册 musicmc:room 通道,
// 用 common 的 RoomManager 维护房间状态,并在同服玩家间广播。
package io.github.cyf112233.musicmc.fabric

import io.github.cyf112233.musicmc.room.RoomManager
import io.github.cyf112233.musicmc.room.RoomServerIO
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fabric 服务端房间中继:注册全局 receiver + 转发给同服玩家。
 * 由 fabric.mod.json 的 server entrypoint 启动(服务端专用,不触碰客户端类)。
 */
object FabricRoomServer {

    private val io = object : RoomServerIO {
        private val players = ConcurrentHashMap<String, ServerPlayer>()

        fun onJoin(p: ServerPlayer) { players[p.getUUID().toString()] = p }

        fun onQuit(p: ServerPlayer) { players.remove(p.getUUID().toString()) }

        override fun sendTo(playerUuid: String, payload: ByteArray) {
            val player = players[playerUuid] ?: return
            runCatching {
                ServerPlayNetworking.send(player, RoomPayload(payload))
            }
        }
    }

    private val manager = RoomManager(io)

    fun init(server: MinecraftServer) {
        // 注册接收:全局 receiver(任意玩家发来的房间负载)
        ServerPlayNetworking.registerGlobalReceiver(
            RoomPayload.TYPE,
            ServerPlayNetworking.PlayPayloadHandler { payload, context ->
                val player = context.player()
                io.onJoin(player)
                manager.handleClient(player.getUUID().toString(), player.gameProfile.name, payload.data)
            },
        )

        // 玩家退出:清理其房间归属
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val player = handler.player
            io.onQuit(player)
            manager.onPlayerQuit(player.getUUID().toString())
        }
    }

    // 供 entrypoint 调用;ServerLifecycleEvents 已在入口注册
    fun instance() = manager
}
