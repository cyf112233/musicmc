// MusicMC NeoForge 房间桥接:客户端传输 + 服务端中继共用同一 payload 类型。
package io.github.cyf112233.musicmc.neoforge

import io.github.cyf112233.musicmc.room.RoomManager
import io.github.cyf112233.musicmc.room.RoomProtocol
import io.github.cyf112233.musicmc.room.RoomServerIO
import io.github.cyf112233.musicmc.room.RoomSession
import io.github.cyf112233.musicmc.room.RoomTransport
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.network.handling.IPayloadHandler
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import java.util.concurrent.ConcurrentHashMap

/** 房间 payload:承载 RoomProtocol 编码出的字节数组(客户端/服务端共用同一类型) */
class NeoRoomPayload(val data: ByteArray) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val IDENTIFIER: Identifier = Identifier.fromNamespaceAndPath("musicmc", "room")

        val TYPE: CustomPacketPayload.Type<NeoRoomPayload> = CustomPacketPayload.Type(IDENTIFIER)

        val CODEC: StreamCodec<RegistryFriendlyByteBuf, NeoRoomPayload> =
            StreamCodec.of(
                { buf, value -> buf.writeByteArray(value.data) },
                { buf -> NeoRoomPayload(buf.readByteArray()) },
            )
    }
}

/**
 * NeoForge 房间传输与中继。
 * - 客户端:实现 RoomTransport,经 Connection.send(ServerboundCustomPayloadPacket) 发送;
 *   收到 playToClient 负载喂给 RoomSession;
 * - 服务端:维护 uuid→ServerPlayer 映射,收到 playToServer 负载喂给 RoomManager 并广播。
 */
class NeoRoomHandler : IPayloadHandler<NeoRoomPayload> {

    private val io = object : RoomServerIO {
        private val players = ConcurrentHashMap<String, ServerPlayer>()
        fun onJoin(p: ServerPlayer) { players[p.getUUID().toString()] = p }
        fun onQuit(p: ServerPlayer) { players.remove(p.getUUID().toString()) }
        override fun sendTo(playerUuid: String, payload: ByteArray) {
            val player = players[playerUuid] ?: return
            runCatching { PacketDistributor.sendToPlayer(player, NeoRoomPayload(payload)) }
        }
    }

    private val manager = RoomManager(io)

    /** 客户端侧传输实现(仅客户端调用) */
    private val clientTransport = object : RoomTransport {
        override fun sendToServer(payload: ByteArray) {
            val conn = net.minecraft.client.Minecraft.getInstance().connection?.connection ?: return
            runCatching { conn.send(ServerboundCustomPayloadPacket(NeoRoomPayload(payload))) }
        }

        override fun isAvailable(): Boolean =
            net.minecraft.client.Minecraft.getInstance().connection != null
    }

    fun register(registrar: PayloadRegistrar) {
        registrar.optional()
            .playToServer(NeoRoomPayload.TYPE, NeoRoomPayload.CODEC, this)  // 客户端→服务端
            .playToClient(NeoRoomPayload.TYPE, NeoRoomPayload.CODEC, this)  // 服务端→客户端
    }

    /** 客户端初始化:注入传输 */
    fun initClient() {
        RoomSession.initTransport(clientTransport, net.minecraft.client.Minecraft.getInstance().player?.getUUID()?.toString().orEmpty())
    }

    override fun handle(payload: NeoRoomPayload, context: IPayloadContext) {
        // flow() == SERVERBOUND → 客户端发来的包,服务端接收处理;
        // flow() == CLIENTBOUND → 服务端发来的包,客户端接收处理
        val isServerSide = context.flow() == PacketFlow.SERVERBOUND
        context.enqueueWork {
            if (isServerSide) {
                val player = context.player()
                if (player is ServerPlayer) {
                    io.onJoin(player)
                    manager.handleClient(player.getUUID().toString(), player.gameProfile.name, payload.data)
                }
            } else {
                RoomSession.onServerPayload(payload.data)
            }
        }
    }

    /** 服务端玩家退出时调用:清理房间归属 */
    fun onPlayerQuit(player: ServerPlayer) {
        io.onQuit(player)
        manager.onPlayerQuit(player.getUUID().toString())
    }
}
