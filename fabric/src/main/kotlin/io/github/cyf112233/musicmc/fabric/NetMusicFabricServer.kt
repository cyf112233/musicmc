// MusicMC Fabric 服务端入口:仅加载房间中继(不触碰任何客户端类)。
package io.github.cyf112233.musicmc.fabric

import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

class NetMusicFabricServer : DedicatedServerModInitializer {
    override fun onInitializeServer() {
        // 房间中继在服务器启动完成后注册(需要服务器实例)
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            FabricRoomServer.init(server)
        }
    }
}
