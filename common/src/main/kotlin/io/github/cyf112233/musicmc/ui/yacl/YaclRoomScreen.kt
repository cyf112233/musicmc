package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.platform.McScreens
import io.github.cyf112233.musicmc.room.RoomInfo
import io.github.cyf112233.musicmc.room.RoomSession
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * 音乐房间页(YACL 风格)。
 *
 * 两个视图:
 * - 未在房间:房间列表(服务端 RoomManager 下发)+「新建房间」「刷新」;
 *   输入框填房名后创建 / 点击列表行加入。
 * - 已在房间:显示房名 / 房主 / 成员数 / 当前曲;房主可「选歌」(从本机队列选)
 *   与「离开」;成员只读跟随并显示房主同步的曲目信息。
 *
 * 视觉走 YaclTheme,交互语义与其它 Yacl*Screen 一致。
 * 网络层经 RoomSession(common),不直接依赖 MC/loader 网络 API。
 */
class YaclRoomScreen(private val back: Screen) : Screen(Component.literal(UiText.t("音乐房间", "Music Room"))) {

    private var scroll = 0
    private var createMode = false
    private var editingQueueIndex = false
    private var queueScroll = 0
    private var lastRoomId: String? = null

    private var editBox: EditBox? = null

    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectRefreshBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectCreateBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectCreateOkBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectCreateCancelBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectLeaveBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectPickSongBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectPickCancelBtn = YaclTheme.Rect(0, 0, 0, 0)

    override fun init() {
        super.init()
        val box = EditBox(font, width / 2 - 120, 42, 200, 16, Component.literal(UiText.t("房间名", "Room name")))
        box.setMaxLength(24)
        editBox = box
        addWidget(box)
        RoomSession.requestRoomList()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        YaclTheme.drawBackground(g, w, h)

        rectBackBtn.x1 = 12; rectBackBtn.y1 = 10; rectBackBtn.x2 = 56; rectBackBtn.y2 = 26
        YaclTheme.drawBtn(g, rectBackBtn, UiText.t("< 返回", "< Back"), mouseX, mouseY)

        val room = RoomSession.currentRoom
        if (room == null) {
            drawLobby(g, graphics, w, h, mouseX, mouseY)
        } else {
            drawInRoom(g, w, h, mouseX, mouseY, room)
        }
    }

    // ---------------- 未在房间:房间列表 / 新建 ----------------

    private fun drawLobby(g: GuiGraphicsHudGui, graphics: GuiGraphicsExtractor, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        YaclTheme.drawCenteredTitle(g, UiText.t("音乐房间", "Music Room"), w / 2, 10)

        // 顶部:刷新 + 新建
        rectRefreshBtn.x1 = w - 150; rectRefreshBtn.y1 = 10; rectRefreshBtn.x2 = w - 94; rectRefreshBtn.y2 = 26
        YaclTheme.drawBtn(g, rectRefreshBtn, UiText.t("刷新", "Refresh"), mouseX, mouseY)
        rectCreateBtn.x1 = w - 86; rectCreateBtn.y1 = 10; rectCreateBtn.x2 = w - 12; rectCreateBtn.y2 = 26
        YaclTheme.drawBtn(g, rectCreateBtn, UiText.t("新建", "New"), mouseX, mouseY, accent = !createMode)

        if (!RoomSession.available) {
            YaclTheme.drawTextClipped(
                g,
                UiText.t("此服务器未安装房间中继组件,音乐房间不可用", "This server has no room relay installed; music rooms are unavailable"),
                w / 2 - 140, h / 2 - 10, 11f, 280, YaclTheme.colorTextDim,
            )
            return
        }

        // 新建输入面板(EditBox 渲染 + 确定/取消)
        if (createMode) {
            editBox?.extractWidgetRenderState(graphics, mouseX, mouseY, 0f)
            rectCreateOkBtn.x1 = width / 2 + 88; rectCreateOkBtn.y1 = 40; rectCreateOkBtn.x2 = width / 2 + 128; rectCreateOkBtn.y2 = 58
            rectCreateCancelBtn.x1 = width / 2 + 134; rectCreateCancelBtn.y1 = 40; rectCreateCancelBtn.x2 = width / 2 + 174; rectCreateCancelBtn.y2 = 58
            YaclTheme.drawBtn(g, rectCreateOkBtn, UiText.t("确定", "OK"), mouseX, mouseY, accent = true)
            YaclTheme.drawBtn(g, rectCreateCancelBtn, UiText.t("取消", "Cancel"), mouseX, mouseY)
        }

        // 房间列表
        val list = RoomSession.roomList
        val rowH = 24
        val listX = 12
        val listW = w - 24
        val listTop = if (createMode) 70 else 48
        var idx = scroll
        var y = listTop
        while (idx < list.size && y + rowH < h - 8) {
            val r = list[idx]
            val hover = mouseY in y until y + rowH && mouseX in listX until listX + listW
            if (hover) g.fill(listX, y, listX + listW, y + rowH, YaclTheme.colorRowHover)
            YaclTheme.drawTextClipped(g, r.name.ifBlank { UiText.t("未命名房间", "Unnamed room") }, listX + 8, y + 2, 11f, listW - 60, YaclTheme.colorTextMain)
            YaclTheme.drawTextClipped(g, UiText.t("房主: ${r.host} · ${r.memberCount} 人", "Host: ${r.host} · ${r.memberCount}"), listX + 8, y + 14, 9f, listW - 60, YaclTheme.colorTextDim)
            y += rowH
            idx++
        }
        if (list.isEmpty()) {
            YaclTheme.drawTextClipped(g, UiText.t("暂无房间,点「新建」创建一个", "No rooms yet. Tap \"New\" to create one"), w / 2 - 120, h / 2 - 8, 11f, 240, YaclTheme.colorTextDim)
        }
    }

    // ---------------- 已在房间 ----------------

    private fun drawInRoom(g: GuiGraphicsHudGui, w: Int, h: Int, mouseX: Int, mouseY: Int, room: RoomInfo) {
        val titleMaxW = (w - 192).coerceAtLeast(40)
        YaclTheme.drawCenteredClipped(g, UiText.t("房间: ${room.name}", "Room: ${room.name}"), w / 2, 10, 14f, titleMaxW, YaclTheme.colorTextMain)
        rectLeaveBtn.x1 = w - 96; rectLeaveBtn.y1 = 10; rectLeaveBtn.x2 = w - 12; rectLeaveBtn.y2 = 26
        YaclTheme.drawBtn(g, rectLeaveBtn, UiText.t("离开", "Leave"), mouseX, mouseY)

        val hostLabel = UiText.t("房主: ${room.host}", "Host: ${room.host}")
        val memberLabel = UiText.t("成员: ${room.memberCount}", "Members: ${room.memberCount}")
        YaclTheme.drawTextClipped(g, hostLabel, 16, 40, 11f, w - 32, YaclTheme.colorAccentBright)
        YaclTheme.drawTextClipped(g, memberLabel, 16, 56, 11f, w - 32, if (RoomSession.isHost) YaclTheme.colorAccent else YaclTheme.colorTextSub)

        // 当前曲(房主取本机播放,成员取同步状态)
        val cur = NetMusic.player.current
        val state = RoomSession.lastSyncState
        val songText = when {
            RoomSession.isHost && cur != null -> "${cur.title} - ${cur.artist}"
            !RoomSession.isHost && state != null && state.songId.isNotBlank() -> "${state.songTitle} - ${state.songArtist}"
            else -> UiText.t("未在播放", "Not Playing")
        }
        YaclTheme.drawTextClipped(g, UiText.t("当前: $songText", "Now: $songText"), 16, 76, 11f, w - 32, YaclTheme.colorTextMain)

        if (RoomSession.isHost) {
            rectPickSongBtn.x1 = 16; rectPickSongBtn.y1 = h - 90; rectPickSongBtn.x2 = 150; rectPickSongBtn.y2 = h - 66
            YaclTheme.drawBtn(g, rectPickSongBtn, UiText.t("选歌", "Pick Song"), mouseX, mouseY, accent = true)
            if (editingQueueIndex) drawQueuePicker(g, w, h, mouseX, mouseY)
        } else {
            YaclTheme.drawTextClipped(g, UiText.t("成员模式:跟随房主播放", "Member mode: following the host"), 16, h - 46, 11f, w - 32, YaclTheme.colorTextDim)
        }
    }

    /** 房主从本机队列选歌(点击行 → hostSelectSong) */
    private fun drawQueuePicker(g: GuiGraphicsHudGui, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        rectPickCancelBtn.x1 = w - 96; rectPickCancelBtn.y1 = h - 90; rectPickCancelBtn.x2 = w - 12; rectPickCancelBtn.y2 = h - 66
        YaclTheme.drawBtn(g, rectPickCancelBtn, UiText.t("取消", "Cancel"), mouseX, mouseY)

        val queue = NetMusic.player.queue
        val rowH = 22
        val listX = 16
        val listW = w - 32
        val listTop = 96
        val listBottom = h - 100
        var idx = queueScroll
        var y = listTop
        while (idx < queue.size && y + rowH < listBottom) {
            val song = queue[idx]
            val current = song.id == NetMusic.player.current?.id
            val hover = mouseY in y until y + rowH && mouseX in listX until listX + listW
            when {
                current -> { g.fill(listX, y, listX + 3, y + rowH, YaclTheme.colorAccent); g.fill(listX + 3, y, listX + listW, y + rowH, YaclTheme.colorRowCurrent) }
                hover -> g.fill(listX, y, listX + listW, y + rowH, YaclTheme.colorRowHover)
            }
            YaclTheme.drawTextClipped(g, song.title.ifBlank { UiText.t("未知标题", "Unknown") }, listX + 6, y + 2, 11f, listW - 12, if (current) YaclTheme.colorTextMain else 0xFFDDDDDD.toInt())
            YaclTheme.drawTextClipped(g, song.artist, listX + 6, y + 13, 9f, listW - 12, YaclTheme.colorTextDim)
            y += rowH
            idx++
        }
    }

    // ---------------- 交互 ----------------

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        if (rectBackBtn.hit(x, y)) { McScreens.open(back); return true }

        val room = RoomSession.currentRoom
        if (room == null) {
            // lobbyClick 内部不再调 super;未命中时由这里透传给 super
            return if (lobbyClick(x, y)) true else super.mouseClicked(event, doubleClick)
        }

        if (rectLeaveBtn.hit(x, y)) { RoomSession.leaveRoom(); return true }
        if (editingQueueIndex && rectPickCancelBtn.hit(x, y)) { editingQueueIndex = false; return true }
        if (rectPickSongBtn.hit(x, y) && RoomSession.isHost) { editingQueueIndex = !editingQueueIndex; return true }
        if (editingQueueIndex && RoomSession.isHost) {
            val rowH = 22
            val listX = 16
            val listW = width - 32
            val listTop = 96
            val listBottom = height - 100
            if (x >= listX && x < listX + listW && y >= listTop && y < listBottom) {
                val row = (y - listTop).toInt() / rowH + queueScroll
                val queue = NetMusic.player.queue
                if (row in queue.indices) {
                    RoomSession.hostSelectSong(row)
                    editingQueueIndex = false
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    private fun lobbyClick(x: Double, y: Double): Boolean {
        if (rectRefreshBtn.hit(x, y)) { RoomSession.requestRoomList(); return true }
        if (rectCreateBtn.hit(x, y)) {
            createMode = !createMode
            if (createMode) { editBox?.setValue(""); editBox?.setFocused(true) }
            return true
        }
        if (createMode) {
            if (rectCreateOkBtn.hit(x, y)) { submitCreate(); return true }
            if (rectCreateCancelBtn.hit(x, y)) { createMode = false; return true }
        }
        val list = RoomSession.roomList
        val rowH = 24
        val listX = 12
        val listW = width - 24
        val listTop = if (createMode) 70 else 48
        if (x >= listX && x < listX + listW && y >= listTop && y < height - 8) {
            val row = (y - listTop).toInt() / rowH + scroll
            if (row in list.indices) {
                RoomSession.joinRoom(list[row].id)
                return true
            }
        }
        return false
    }

    private fun submitCreate() {
        val name = editBox?.getValue()?.trim().orEmpty()
        if (name.isEmpty()) return
        RoomSession.createRoom(name)
        createMode = false
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (createMode) {
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                submitCreate()
                return true
            }
            val box = editBox
            if (box != null && box.isFocused) {
                if (box.keyPressed(event)) return true
            }
            return super.keyPressed(event)
        }
        return super.keyPressed(event)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        if (RoomSession.currentRoom == null) {
            val rowH = 24
            val maxScroll = (RoomSession.roomList.size - (height - 50) / rowH).coerceAtLeast(0)
            scroll = (scroll - dy.toInt()).coerceIn(0, maxScroll)
        } else if (editingQueueIndex) {
            val rowH = 22
            val maxScroll = (NetMusic.player.queue.size - (height - 196) / rowH).coerceAtLeast(0)
            queueScroll = (queueScroll - dy.toInt()).coerceIn(0, maxScroll)
        }
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
