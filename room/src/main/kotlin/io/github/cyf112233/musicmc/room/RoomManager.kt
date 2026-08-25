package io.github.cyf112233.musicmc.room

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务器侧房间中继核心(房主权威,按服务器实例隔离)。
 *
 * 纯逻辑,零 Minecraft / loader 依赖:由各平台(Paper 插件 / Fabric 服务端 mod /
 * NeoForge 服务端 mod)桥接,把收到的 [RoomProtocol] 负载喂给 [handleClient],
 * 需要发送时经注入的 [RoomServerIO] 把编码负载发给指定玩家。
 *
 * 权限模型:
 * - 房主:创建者可全权 —— 选歌 / 切歌 / 广播状态 / 解散;
 * - 成员:只读跟随(收房主广播),无切歌权限;
 * - 房主离开 → 把房主移交给最先进房的成员(若房间无人则销毁)。
 */
class RoomManager(private val io: RoomServerIO) {

    private val rooms = ConcurrentHashMap<String, Room>()

    /** 记录某玩家当前所在房间(离开服务器/换服时清理) */
    private val playerRoom = ConcurrentHashMap<String, String>()

    /**
     * 处理一名玩家发来的一条负载。
     * [playerUuid]/[playerName] 由平台桥接提供(Paper 的 Player / Fabric、NeoForge 的 ServerPlayer)。
     */
    fun handleClient(playerUuid: String, playerName: String, payload: ByteArray) {
        val msg = RoomProtocol.decode(payload) ?: return
        when (msg) {
            is RoomMessage.CreateRoom -> createRoom(playerUuid, playerName, msg.name)
            is RoomMessage.JoinRoom -> joinRoom(playerUuid, playerName, msg.roomId)
            is RoomMessage.LeaveRoom -> leaveRoom(playerUuid)
            is RoomMessage.ListRooms -> sendRoomList(playerUuid)
            is RoomMessage.SyncState -> onHostState(playerUuid, msg.state)
            is RoomMessage.SelectSong -> onHostSelect(playerUuid, msg.songId)
            else -> Unit // 服务端不应收到 S→C 消息,忽略
        }
    }

    /** 玩家离开服务器时调用,清理其房间归属 */
    fun onPlayerQuit(playerUuid: String) {
        leaveRoom(playerUuid)
        playerRoom.remove(playerUuid)
    }

    // ---------------- 房间操作 ----------------

    private fun createRoom(hostUuid: String, hostName: String, name: String) {
        val roomId = UUID.randomUUID().toString().take(8)
        val room = Room(roomId, name.ifBlank { "${hostName}'s room" }, hostUuid, hostName)
        rooms[roomId] = room
        room.members[hostUuid] = hostName
        playerRoom[hostUuid] = roomId
        // 通知房主加入成功(空状态)
        io.sendTo(hostUuid, RoomProtocol.encode(RoomMessage.JoinedRoom(
            RoomInfo(roomId, room.name, hostName, 1, room.maxMembers),
            emptyPlayState(),
        )))
    }

    private fun joinRoom(playerUuid: String, playerName: String, roomId: String) {
        val room = rooms[roomId] ?: run {
            io.sendTo(playerUuid, RoomProtocol.encode(RoomMessage.Error(RoomError(1, "room not found: $roomId"))))
            return
        }
        // 已在别的房间 → 先离开
        leaveRoom(playerUuid)
        room.members[playerUuid] = playerName
        playerRoom[playerUuid] = roomId
        // 通知新成员加入成功 + 当前房主状态(若有)
        val state = room.lastState ?: emptyPlayState()
        io.sendTo(playerUuid, RoomProtocol.encode(RoomMessage.JoinedRoom(
            RoomInfo(roomId, room.name, room.hostName, room.members.size, room.maxMembers),
            state,
        )))
        // 广播房间人数变化给房主(轻量:仅房主知道新成员名即可)
        io.sendTo(room.hostUuid, RoomProtocol.encode(RoomMessage.MemberJoined(RoomMember(playerUuid, playerName))))
    }

    private fun leaveRoom(playerUuid: String) {
        val roomId = playerRoom.remove(playerUuid) ?: return
        val room = rooms[roomId] ?: return
        val name = room.members.remove(playerUuid)
        if (room.members.isEmpty()) {
            rooms.remove(roomId)
            return
        }
        if (room.hostUuid == playerUuid) {
            // 房主离开 → 移交(取最早加入者)
            val newHostUuid = room.members.keys.first()
            val newHostName = room.members[newHostUuid] ?: newHostUuid
            room.hostUuid = newHostUuid
            room.hostName = newHostName
            io.sendTo(newHostUuid, RoomProtocol.encode(RoomMessage.HostChanged(newHostUuid)))
        }
        // 通知其余成员有人离开
        for (m in room.members.keys) {
            io.sendTo(m, RoomProtocol.encode(RoomMessage.MemberLeft(RoomMember(playerUuid, name ?: ""))))
        }
    }

    private fun sendRoomList(playerUuid: String) {
        val list = rooms.values.map { room ->
            RoomInfo(room.id, room.name, room.hostName, room.members.size, room.maxMembers)
        }
        io.sendTo(playerUuid, RoomProtocol.encode(RoomMessage.RoomList(list)))
    }

    private fun onHostState(hostUuid: String, state: RoomPlayState) {
        val roomId = playerRoom[hostUuid] ?: return
        val room = rooms[roomId] ?: return
        if (room.hostUuid != hostUuid) return // 非房主无权广播状态
        room.lastState = state
        // 广播给所有成员(含房主,回显确认)
        for (m in room.members.keys) {
            io.sendTo(m, RoomProtocol.encode(RoomMessage.SyncStatePush(state)))
        }
    }

    private fun onHostSelect(hostUuid: String, songId: String) {
        val roomId = playerRoom[hostUuid] ?: return
        val room = rooms[roomId] ?: return
        if (room.hostUuid != hostUuid) return
        // 广播选歌指令给成员(成员据此播放对应曲)
        for (m in room.members.keys) {
            if (m != hostUuid) io.sendTo(m, RoomProtocol.encode(RoomMessage.SelectSongPush(songId)))
        }
    }

    private fun emptyPlayState(): RoomPlayState = RoomPlayState("", "", "", 0, 0, false)

    private class Room(
        val id: String,
        val name: String,
        var hostUuid: String,
        var hostName: String,
        val maxMembers: Int = 32,
    ) {
        val members = LinkedHashMap<String, String>()
        var lastState: RoomPlayState? = null
    }
}

/** 服务器侧发送抽象:由平台桥接实现(把编码负载发给指定玩家) */
interface RoomServerIO {
    fun sendTo(playerUuid: String, payload: ByteArray)
}
