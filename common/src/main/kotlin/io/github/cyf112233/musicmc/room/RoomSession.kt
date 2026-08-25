package io.github.cyf112233.musicmc.room

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.player.PlayerListener
import io.github.cyf112233.musicmc.player.PlayerState

/**
 * 客户端房间会话核心(房主权威)。
 *
 * 职责:
 * - 房间生命周期:创建 / 加入 / 离开 / 请求列表;
 * - 房主端:把本机播放状态(队列当前曲 + 进度 + 播放态)周期性广播给服务端,
 *   由服务端转发给房间成员;房主选歌/切歌时下发 [SELECT_SONG];
 * - 成员端:接收服务端转发的 [SYNC_STATE_PUSH] / [SELECT_SONG_PUSH],驱动本机播放器跟随;
 * - 全部网络收发经 [RoomNetwork] 注入的 [RoomTransport],本类不依赖 MC/loader API。
 *
 * 状态字段由 UI 线程读写(房间 UI 均渲染线程单线程访问),加 @Volatile 保证跨线程可见。
 */
object RoomSession {

    /** 当前所在房间(未加入 / 已离开为 null) */
    @Volatile
    var currentRoom: RoomInfo? = null
        private set

    /** 当前是否房主(决定是否广播状态 / 是否有选歌权限) */
    @Volatile
    var isHost: Boolean = false
        private set

    /** 当前是否已连接可用通道 */
    @Volatile
    var available: Boolean = false
        private set

    /** 是否已在房间内 */
    val inRoom: Boolean get() = currentRoom != null

    /** 服务器下发的房间列表缓存(UI 展示用) */
    @Volatile
    var roomList: List<RoomInfo> = emptyList()
        private set

    /** 最新同步的播放状态(成员端展示用) */
    @Volatile
    var lastSyncState: RoomPlayState? = null
        private set

    /** 服务器返回的错误(UI 提示用) */
    @Volatile
    var lastError: String? = null
        private set

    /** 房主身份:本机玩家 UUID(由平台桥接在 setTransport 时注入) */
    @Volatile
    var localUuid: String = ""
        private set

    /** 播放器监听器是否已挂(幂等:只在首次可用时挂一次) */
    private var listenerAttached = false

    /** 房间显示名 → 实际排队中待播歌曲(房主端;选歌跳转用) */
    private val pendingQueue = ArrayList<Song>()

    /** 上一次进度广播的毫秒时间戳(节流,避免每 tick 刷服务端) */
    private var lastBroadcastMs = 0L

    private val listener = object : PlayerListener {
        override fun onProgress(posMs: Int, durationMs: Int) {
            // 房主:进度变化时按节流广播(500ms 一次),保持成员进度跟随
            if (isHost && inRoom) maybeBroadcastState()
        }

        override fun onSongChanged(song: Song?) {
            // 房主切歌(自动/手动):立即广播,让成员跟上
            if (isHost && inRoom) broadcastState()
        }
    }

    /** 由平台桥接在初始化时注入传输 + 本机 UUID;之后检测可用性(幂等,可多次调用) */
    fun initTransport(transport: RoomTransport, uuid: String) {
        RoomNetwork.transport = transport
        localUuid = uuid
        available = transport.isAvailable()
        if (available && !listenerAttached) {
            listenerAttached = true
            NetMusic.player.addListener(listener)
        }
    }

    /** 传入一条来自服务器的负载,解码并处理 */
    fun onServerPayload(payload: ByteArray) {
        val msg = RoomProtocol.decode(payload) ?: return
        when (msg) {
            is RoomMessage.RoomList -> roomList = msg.rooms
            is RoomMessage.JoinedRoom -> onJoined(msg)
            is RoomMessage.RoomClosed -> onClosed(msg.reason)
            is RoomMessage.MemberJoined -> { /* UI 可展示;此处无需动作 */ }
            is RoomMessage.MemberLeft -> { /* 同上 */ }
            is RoomMessage.SyncStatePush -> onSyncStatePush(msg.state)
            is RoomMessage.SelectSongPush -> onSelectSongPush(msg.songId)
            is RoomMessage.HostChanged -> isHost = (msg.newHostUuid == localUuid)
            is RoomMessage.Error -> lastError = msg.error.message
            else -> Unit // 客户端不应收到其余 C→S 消息,忽略
        }
    }

    // ---------------- 客户端动作 ----------------

    /** 请求服务器房间列表 */
    fun requestRoomList() {
        if (!available) return
        send(RoomMessage.ListRooms)
    }

    /** 创建房间 */
    fun createRoom(name: String) {
        if (!available || !inRoom) return
        send(RoomMessage.CreateRoom(name))
    }

    /** 加入房间 */
    fun joinRoom(roomId: String) {
        if (!available) return
        send(RoomMessage.JoinRoom(roomId))
    }

    /** 离开当前房间 */
    fun leaveRoom() {
        if (!available || !inRoom) return
        send(RoomMessage.LeaveRoom)
        currentRoom = null
        isHost = false
        lastSyncState = null
    }

    // ---------------- 房主权限 ----------------

    /** 房主选歌(把队列中 [index] 的歌曲设为当前播放) */
    fun hostSelectSong(index: Int) {
        if (!isHost || !inRoom) return
        val song = pendingQueue.getOrNull(index) ?: return
        NetMusic.player.play(song, pendingQueue.toList(), index)
        // play 已切歌 → onSongChanged 会广播;这里再补一次确保带正确 songId
        broadcastState()
    }

    /** 房主请求播放/暂停 */
    fun hostTogglePlay() {
        if (!isHost || !inRoom) return
        NetMusic.player.toggle()
        broadcastState()
    }

    /** 房主请求跳转进度 */
    fun hostSeek(positionMs: Int) {
        if (!isHost || !inRoom) return
        NetMusic.player.seekTo(positionMs)
        broadcastState()
    }

    // ---------------- 内部 ----------------

    private fun onJoined(msg: RoomMessage.JoinedRoom) {
        currentRoom = msg.room
        isHost = (msg.room.host == localUuid)
        lastSyncState = msg.state
        lastError = null
        // 作为成员加入时,立即跟随房主当前状态(若有歌)
        if (!isHost) applyRemoteState(msg.state)
    }

    private fun onClosed(reason: String) {
        currentRoom = null
        isHost = false
        lastSyncState = null
        lastError = reason.ifBlank { null }
    }

    private fun onSyncStatePush(state: RoomPlayState) {
        lastSyncState = state
        if (!isHost) applyRemoteState(state)
    }

    private fun onSelectSongPush(songId: String) {
        if (isHost) return // 房主自己触发的切歌,无需再次响应
        // 成员:按 songId 在待播队列中定位并播放(若已缓存该曲)
        val idx = pendingQueue.indexOfFirst { it.id == songId }
        if (idx >= 0) {
            NetMusic.player.play(pendingQueue[idx], pendingQueue.toList(), idx)
        }
    }

    /**
     * 成员跟随:用服务端下发的状态驱动本机播放。
     * 已有同曲则 seek + 播放态同步;无则按 songId 构造 Song 播放(本机解析地址)。
     */
    private fun applyRemoteState(state: RoomPlayState) {
        val current = NetMusic.player.current
        if (current != null && current.id == state.songId) {
            // 同曲:同步播放态与进度
            if (state.playing && NetMusic.player.state != PlayerState.PLAYING) NetMusic.player.toggle()
            if (state.positionMs >= 0) NetMusic.player.seekTo(state.positionMs)
        } else if (state.songId.isNotBlank()) {
            val song = Song(
                id = state.songId,
                title = state.songTitle.ifBlank { state.songId },
                artist = state.songArtist,
                album = "",
                picUrl = null,
                durationMs = state.songDurationMs.coerceAtLeast(0),
            )
            NetMusic.player.play(song)
            if (!state.playing) NetMusic.player.toggle()
        }
    }

    /** 房主广播当前状态 */
    private fun broadcastState() {
        if (!available || !inRoom || !isHost) return
        val song = NetMusic.player.current ?: return
        val state = RoomPlayState(
            songId = song.id,
            songTitle = song.title,
            songArtist = song.artist,
            songDurationMs = song.durationMs,
            positionMs = NetMusic.player.engine.positionMs(),
            playing = NetMusic.player.state == PlayerState.PLAYING,
        )
        send(RoomMessage.SyncState(state))
    }

    /** 进度节流广播 */
    private fun maybeBroadcastState() {
        val now = System.currentTimeMillis()
        if (now - lastBroadcastMs >= 500) {
            lastBroadcastMs = now
            broadcastState()
        }
    }

    private fun send(msg: RoomMessage) {
        RoomNetwork.transport?.sendToServer(RoomProtocol.encode(msg))
    }
}
