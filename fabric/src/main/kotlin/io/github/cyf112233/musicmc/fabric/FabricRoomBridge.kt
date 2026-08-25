// MusicMC Fabric 房间桥接:RoomPayload(自定义 payload)+ 客户端传输 + 引导。
package io.github.cyf112233.musicmc.fabric

import io.github.cyf112233.musicmc.room.RoomSession
import io.github.cyf112233.musicmc.room.RoomTransport
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/** 房间 payload:承载 RoomProtocol 编码出的字节数组 */
class RoomPayload(val data: ByteArray) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val IDENTIFIER: Identifier = Identifier.fromNamespaceAndPath("musicmc", "room")

        val TYPE: CustomPacketPayload.Type<RoomPayload> = CustomPacketPayload.Type(IDENTIFIER)

        val CODEC: StreamCodec<FriendlyByteBuf, RoomPayload> =
            StreamCodec.of(
                { buf, value -> buf.writeByteArray(value.data) },
                { buf -> RoomPayload(buf.readByteArray()) },
            )
    }
}

/** Fabric 客户端房间传输实现:把 RoomSession 的请求发往服务器,并把收到的负载喂回 RoomSession */
class FabricRoomTransport : RoomTransport {

    private val registered = ClientPlayNetworking.registerGlobalReceiver(
        RoomPayload.TYPE,
        ClientPlayNetworking.PlayPayloadHandler { payload, _ ->
            RoomSession.onServerPayload(payload.data)
        },
    )

    override fun sendToServer(payload: ByteArray) {
        ClientPlayNetworking.send(RoomPayload(payload))
    }

    override fun isAvailable(): Boolean =
        // 通道已注册,且已连接服务器且服务器声明了本通道(可发送)
        ClientPlayNetworking.canSend(RoomPayload.TYPE)
}

/** Fabric 客户端房间引导 */
object FabricRoomBootstrap {

    @Volatile
    private var transport: FabricRoomTransport? = null

    @Volatile
    private var initialized = false

    /** 由客户端入口调用:注册通道 + 注入传输;玩家进服后刷新本机 UUID */
    fun init() {
        if (initialized) return
        initialized = true
        val t = FabricRoomTransport()
        transport = t

        // 玩家进入世界:刷新本机 UUID(RoomSession 需用它判断房主身份)
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            val uuid = runCatching { Minecraft.getInstance().player?.getUUID()?.toString() }.getOrDefault("").orEmpty()
            RoomSession.initTransport(t, uuid)
        }
    }
}
