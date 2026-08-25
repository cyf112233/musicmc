package io.github.cyf112233.musicmc.room

/**
 * 房间网络传输抽象 —— 由各平台桥接层实现。
 *
 * 把 [RoomProtocol] 编码出的字节负载经 MC 自定义 payload 通道([RoomProtocol.CHANNEL])
 * 发送到服务器;并把收到的服务器负载回调给 [RoomSession]。
 * 这样房间核心(common)完全不依赖 MC / loader 网络 API —— 版本差异全部隔离在
 * 各平台(fabric / neoforge / paper 服务端)的桥接实现里。
 */
interface RoomTransport {

    /** 把编码后的负载发往服务器(房间请求 / 房主状态广播) */
    fun sendToServer(payload: ByteArray)

    /**
     * 该通道当前是否可用(服务器侧已注册并注册了本通道的中继)。
     * 不可用(如 vanilla 服务器无本 mod / 无中继组件)时房间功能应静默关闭。
     */
    fun isAvailable(): Boolean
}

/** 全局传输注入点:由各平台桥接在初始化时 set;未注入视为不可用(静默降级) */
object RoomNetwork {
    @Volatile
    var transport: RoomTransport? = null

    /** 是否可用(有传输且通道注册成功) */
    val isAvailable: Boolean get() = transport?.isAvailable() == true
}
