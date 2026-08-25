package io.github.cyf112233.musicmc.room

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * 音乐房间跨平台协议(零 Minecraft / loader 依赖,纯 JDK 字节编解码)。
 *
 * 设计目标(对齐 AllMusic 思路,但针对"同服音乐房间"结构化):
 * - 同一份协议可被 Paper / Fabric / NeoForge / 任意 MC 版本复用 —— 编解码
 *   只用 java.io 与 java.nio.charset,不引用任何 net.minecraft / netty / loader API,
 *   因此"不会因玩家 MC 版本不同而出错"(传输层版本差异由各平台桥接层隔离)。
 * - 按服务器实例隔离:房间 id 在同一服务器内唯一;跨服需各自独立(本协议不含
 *   跨服路由,服务器侧中继仅在本服广播)。
 * - 房主权威:房主控制队列/选歌/切歌/进度/播放状态,成员只读跟随。
 *
 * 传输:负载经 MC 自定义 payload 通道承载,通道名 [CHANNEL](musicmc:room)。
 * 字节布局:首字节 = 协议版本,次字节 = 消息类型,其后为按类型编解码的字段。
 * 服务端中继收到客户端消息后,按消息类型维护房间状态并广播给同房其他成员。
 */

/** 消息类型(字节序即 ordinal;新增追加在末尾,禁止重排/删除历史值以保持前向兼容) */
enum class RoomMessageType {
    /** C→S:创建房间;载荷=房间名 */
    CREATE_ROOM,

    /** C→S:加入房间;载荷=房间 id */
    JOIN_ROOM,

    /** C→S:离开当前房间 */
    LEAVE_ROOM,

    /** C→S:请求房间列表;无载荷 */
    LIST_ROOMS,

    /** C→S:房主广播当前播放状态(队列/当前曲/进度/播放态);仅房主可发 */
    SYNC_STATE,

    /** C→S:房主请求切歌/选歌;载荷=目标歌曲 id(在队列中的位置) */
    SELECT_SONG,

    /** S→C:房间列表应答 */
    ROOM_LIST,

    /** S→C:加入房间成功(含当前房间状态快照) */
    JOINED_ROOM,

    /** S→C:房间已关闭(房主离开/服务端关闭) */
    ROOM_CLOSED,

    /** S→C:有成员加入本房间(通知,含新成员名) */
    MEMBER_JOINED,

    /** S→C:有成员离开本房间 */
    MEMBER_LEFT,

    /** S→C:房主广播的同步状态转发给房间成员 */
    SYNC_STATE_PUSH,

    /** S→C:房主选歌/切歌指令广播 */
    SELECT_SONG_PUSH,

    /** S→C:房主变更(原房主离开,权限移交) */
    HOST_CHANGED,

    /** C→S / S→C:错误应答(如房间不存在/重名/权限不足) */
    ERROR,
    ;

    companion object {
        /** 按 ordinal 安全解析(越界返回 null,避免陈旧客户端拿到非法值崩溃) */
        fun fromOrdinal(v: Int): RoomMessageType? =
            values().getOrNull(v)
    }
}

/** 房间内单个成员信息 */
data class RoomMember(
    val uuid: String,
    val name: String,
)

/**
 * 房间公开信息(列表展示用)。
 * 注意:成员列表不随 [ROOM_LIST] 下发(避免体积),只含统计。
 */
data class RoomInfo(
    val id: String,
    val name: String,
    val host: String,
    val memberCount: Int,
    val maxMembers: Int,
)

/**
 * 播放同步快照(房主权威下发;成员据此跟随)。
 * [songId]/[songTitle]/[songArtist]/[songDurationMs] 冗余下发歌曲标识,
 * 成员凭 songId 经本机音源解析播放地址;进度为播放器当前毫秒位置。
 */
data class RoomPlayState(
    val songId: String,
    val songTitle: String,
    val songArtist: String,
    val songDurationMs: Int,
    val positionMs: Int,
    val playing: Boolean,
)

/** 通用错误(供错误应答) */
data class RoomError(
    val code: Int,
    val message: String,
)

/**
 * 房间协议编解码器。所有方法无状态、线程安全。
 *
 * 字符串采用 UTF-8 + 前导 int 长度(与 AllMusic MusicPacketCodec 同约定);
 * 数字用 big-endian(java.io 默认),保证跨平台一致。
 */
object RoomProtocol {

    const val CHANNEL: String = "musicmc:room"

    /** 协议版本号:双方不匹配时视为不兼容,客户端应拒绝建立房间通信 */
    const val VERSION: Int = 1

    /** 序列化一条消息(版本 + 类型 + 载荷) */
    fun encode(message: RoomMessage): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            dos.writeByte(VERSION)
            dos.writeByte(message.type.ordinal)
            writeMessage(dos, message)
        }
        return out.toByteArray()
    }

    /**
     * 反序列化一条消息。版本不符 / 类型非法 / 载荷损坏时返回 [null]
     * (调用方应静默忽略,保证陈旧或不兼容对端不会导致崩溃)。
     */
    fun decode(data: ByteArray): RoomMessage? {
        return try {
            val inp = DataInputStream(ByteArrayInputStream(data))
            val version = inp.readUnsignedByte()
            if (version != VERSION) return null
            val type = RoomMessageType.fromOrdinal(inp.readUnsignedByte()) ?: return null
            val msg = readMessage(inp, type) ?: return null
            inp.close()
            msg
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- 写 ----------------

    private fun writeMessage(dos: DataOutputStream, msg: RoomMessage) {
        when (msg) {
            is RoomMessage.CreateRoom -> writeString(dos, msg.name)
            is RoomMessage.JoinRoom -> writeString(dos, msg.roomId)
            is RoomMessage.LeaveRoom -> Unit
            is RoomMessage.ListRooms -> Unit
            is RoomMessage.SyncState -> writePlayState(dos, msg.state)
            is RoomMessage.SelectSong -> writeString(dos, msg.songId)
            is RoomMessage.RoomList -> {
                dos.writeInt(msg.rooms.size)
                for (r in msg.rooms) writeRoomInfo(dos, r)
            }
            is RoomMessage.JoinedRoom -> {
                writeRoomInfo(dos, msg.room)
                writePlayState(dos, msg.state)
            }
            is RoomMessage.RoomClosed -> writeString(dos, msg.reason)
            is RoomMessage.MemberJoined -> writeMember(dos, msg.member)
            is RoomMessage.MemberLeft -> writeMember(dos, msg.member)
            is RoomMessage.SyncStatePush -> writePlayState(dos, msg.state)
            is RoomMessage.SelectSongPush -> writeString(dos, msg.songId)
            is RoomMessage.HostChanged -> writeString(dos, msg.newHostUuid)
            is RoomMessage.Error -> {
                dos.writeInt(msg.error.code)
                writeString(dos, msg.error.message)
            }
        }
    }

    private fun writeRoomInfo(dos: DataOutputStream, r: RoomInfo) {
        writeString(dos, r.id)
        writeString(dos, r.name)
        writeString(dos, r.host)
        dos.writeInt(r.memberCount)
        dos.writeInt(r.maxMembers)
    }

    private fun writePlayState(dos: DataOutputStream, s: RoomPlayState) {
        writeString(dos, s.songId)
        writeString(dos, s.songTitle)
        writeString(dos, s.songArtist)
        dos.writeInt(s.songDurationMs)
        dos.writeInt(s.positionMs)
        dos.writeBoolean(s.playing)
    }

    private fun writeMember(dos: DataOutputStream, m: RoomMember) {
        writeString(dos, m.uuid)
        writeString(dos, m.name)
    }

    private fun writeString(dos: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    // ---------------- 读 ----------------

    private fun readMessage(inp: DataInputStream, type: RoomMessageType): RoomMessage? {
        return try {
            when (type) {
                RoomMessageType.CREATE_ROOM -> RoomMessage.CreateRoom(readString(inp))
                RoomMessageType.JOIN_ROOM -> RoomMessage.JoinRoom(readString(inp))
                RoomMessageType.LEAVE_ROOM -> RoomMessage.LeaveRoom
                RoomMessageType.LIST_ROOMS -> RoomMessage.ListRooms
                RoomMessageType.SYNC_STATE -> RoomMessage.SyncState(readPlayState(inp))
                RoomMessageType.SELECT_SONG -> RoomMessage.SelectSong(readString(inp))
                RoomMessageType.ROOM_LIST -> {
                    val n = inp.readInt()
                    val list = ArrayList<RoomInfo>(n.coerceAtLeast(0).coerceAtMost(1024))
                    repeat(n.coerceAtLeast(0).coerceAtMost(1024)) { list.add(readRoomInfo(inp)) }
                    RoomMessage.RoomList(list)
                }
                RoomMessageType.JOINED_ROOM -> RoomMessage.JoinedRoom(readRoomInfo(inp), readPlayState(inp))
                RoomMessageType.ROOM_CLOSED -> RoomMessage.RoomClosed(readString(inp))
                RoomMessageType.MEMBER_JOINED -> RoomMessage.MemberJoined(readMember(inp))
                RoomMessageType.MEMBER_LEFT -> RoomMessage.MemberLeft(readMember(inp))
                RoomMessageType.SYNC_STATE_PUSH -> RoomMessage.SyncStatePush(readPlayState(inp))
                RoomMessageType.SELECT_SONG_PUSH -> RoomMessage.SelectSongPush(readString(inp))
                RoomMessageType.HOST_CHANGED -> RoomMessage.HostChanged(readString(inp))
                RoomMessageType.ERROR -> RoomMessage.Error(RoomError(inp.readInt(), readString(inp)))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readRoomInfo(inp: DataInputStream): RoomInfo =
        RoomInfo(readString(inp), readString(inp), readString(inp), inp.readInt(), inp.readInt())

    private fun readPlayState(inp: DataInputStream): RoomPlayState =
        RoomPlayState(readString(inp), readString(inp), readString(inp), inp.readInt(), inp.readInt(), inp.readBoolean())

    private fun readMember(inp: DataInputStream): RoomMember =
        RoomMember(readString(inp), readString(inp))

    private fun readString(inp: DataInputStream): String {
        val n = inp.readInt()
        if (n < 0 || n > 65536) throw IOException("bad string length $n")
        val bytes = ByteArray(n)
        inp.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}

/** 一条房间消息(不可变)。工厂/编解码在 [RoomProtocol] */
sealed class RoomMessage {
    val type: RoomMessageType get() = when (this) {
        is CreateRoom -> RoomMessageType.CREATE_ROOM
        is JoinRoom -> RoomMessageType.JOIN_ROOM
        is LeaveRoom -> RoomMessageType.LEAVE_ROOM
        is ListRooms -> RoomMessageType.LIST_ROOMS
        is SyncState -> RoomMessageType.SYNC_STATE
        is SelectSong -> RoomMessageType.SELECT_SONG
        is RoomList -> RoomMessageType.ROOM_LIST
        is JoinedRoom -> RoomMessageType.JOINED_ROOM
        is RoomClosed -> RoomMessageType.ROOM_CLOSED
        is MemberJoined -> RoomMessageType.MEMBER_JOINED
        is MemberLeft -> RoomMessageType.MEMBER_LEFT
        is SyncStatePush -> RoomMessageType.SYNC_STATE_PUSH
        is SelectSongPush -> RoomMessageType.SELECT_SONG_PUSH
        is HostChanged -> RoomMessageType.HOST_CHANGED
        is Error -> RoomMessageType.ERROR
    }

    /** 客户端 → 服务端 */
    data class CreateRoom(val name: String) : RoomMessage()
    data class JoinRoom(val roomId: String) : RoomMessage()
    data object LeaveRoom : RoomMessage()
    data object ListRooms : RoomMessage()
    data class SyncState(val state: RoomPlayState) : RoomMessage()
    data class SelectSong(val songId: String) : RoomMessage()

    /** 服务端 → 客户端 */
    data class RoomList(val rooms: List<RoomInfo>) : RoomMessage()
    data class JoinedRoom(val room: RoomInfo, val state: RoomPlayState) : RoomMessage()
    data class RoomClosed(val reason: String) : RoomMessage()
    data class MemberJoined(val member: RoomMember) : RoomMessage()
    data class MemberLeft(val member: RoomMember) : RoomMessage()
    data class SyncStatePush(val state: RoomPlayState) : RoomMessage()
    data class SelectSongPush(val songId: String) : RoomMessage()
    data class HostChanged(val newHostUuid: String) : RoomMessage()
    data class Error(val error: RoomError) : RoomMessage()
}
