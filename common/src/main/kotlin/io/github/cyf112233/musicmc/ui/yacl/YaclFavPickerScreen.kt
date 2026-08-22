package io.github.cyf112233.musicmc.ui.yacl

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.bilibili.BiliActions
import io.github.cyf112233.musicmc.bilibili.FavFolder
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.UiText
import io.github.cyf112233.musicmc.platform.McScreens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * YACL 版收藏夹选择器(MUI FavPickerFragment 的对应物):把**当前播放歌曲**
 * 加入 / 移出收藏夹,可新建收藏夹。
 *
 * - 打开时加载全部收藏夹(BiliActions.folders)+ 刷新当前歌曲收藏状态缓存
 *   (BiliActions.refreshFavState,填充 folderFavs 供每行 ✓ 标记);
 * - 每行:夹名 + "N 个内容" + 右侧 ✓(当前歌曲在该夹,主题色);
 * - 点击行:BiliActions.toggleFavInFolder 切换该夹收藏态,即时刷新该行;
 * - 底部"新建收藏夹":MC EditBox 输入 + 确定/取消,createFolder 成功后刷新列表;
 * - 未登录显示提示(列表 / 新建入口隐藏)。
 */
class YaclFavPickerScreen(private val back: Screen) : Screen(Component.literal(UiText.t("选择收藏夹", "Select Folder"))) {

    private var folders: List<FavFolder>? = null
    private var error: String? = null
    private var scroll = 0

    /** 当前歌曲所在收藏夹 id 集合(✓ 标记;refreshFavState 填充) */
    private var favedFids: Set<Long> = emptySet()

    private var createMode = false
    private var creating = false

    private var editBox: EditBox? = null

    private val rectBackBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectNewBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectCreateOkBtn = YaclTheme.Rect(0, 0, 0, 0)
    private val rectCreateCancelBtn = YaclTheme.Rect(0, 0, 0, 0)

    override fun init() {
        super.init()
        val box = EditBox(font, width / 2 - 120, 40, 200, 16, Component.literal(UiText.t("收藏夹名称", "Folder name")))
        box.setMaxLength(30)
        editBox = box
        addWidget(box)
        load()
    }

    private fun load() {
        if (!NetMusic.bilibiliLoggedIn()) {
            error = UiText.t("请先在设置中登录 B 站", "Please log in to Bilibili in Settings first")
            folders = emptyList()
            return
        }
        val song = NetMusic.player.current
        if (song != null) {
            // BiliActions 回调已切 UI 线程
            BiliActions.refreshFavState(song) { _ ->
                favedFids = BiliActions.favFoldersOf(song.id)
            }
        }
        BiliActions.folders { list, err ->
            if (err != null) {
                error = err
                folders = emptyList()
            } else {
                folders = list
                error = null
                val songId = NetMusic.player.current?.id
                if (songId != null) favedFids = BiliActions.favFoldersOf(songId)
            }
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val g = GuiGraphicsHudGui(graphics)
        val w = g.guiWidth()
        val h = g.guiHeight()

        YaclTheme.drawBackground(g, w, h)

        rectBackBtn.x1 = 12; rectBackBtn.y1 = 10; rectBackBtn.x2 = 56; rectBackBtn.y2 = 26
        YaclTheme.drawBtn(g, rectBackBtn, UiText.t("< 返回", "< Back"), mouseX, mouseY)

        val song = NetMusic.player.current
        val title = UiText.t("选择收藏夹", "Select Folder")
        val titleMaxW = (w - 160).coerceAtLeast(40)
        YaclTheme.drawCenteredClipped(g, title, w / 2, 10, 14f, titleMaxW, YaclTheme.colorTextMain)

        rectNewBtn.x1 = w - 96; rectNewBtn.y1 = 10; rectNewBtn.x2 = w - 12; rectNewBtn.y2 = 26
        YaclTheme.drawBtn(g, rectNewBtn, UiText.t("新建", "New"), mouseX, mouseY, accent = !createMode)

        // 当前歌曲提示
        if (song != null) {
            YaclTheme.drawTextClipped(
                g,
                UiText.t("当前歌曲: ${song.title}", "Current song: ${song.title}"),
                16,
                32,
                10f,
                w - 32,
                YaclTheme.colorTextSub,
            )
        }

        // 新建收藏夹输入面板
        if (createMode) {
            editBox?.extractWidgetRenderState(graphics, mouseX, mouseY, 0f)
            rectCreateOkBtn.x1 = width / 2 + 88; rectCreateOkBtn.y1 = 40; rectCreateOkBtn.x2 = width / 2 + 128; rectCreateOkBtn.y2 = 56
            rectCreateCancelBtn.x1 = width / 2 + 134; rectCreateCancelBtn.y1 = 40; rectCreateCancelBtn.x2 = width / 2 + 174; rectCreateCancelBtn.y2 = 56
            YaclTheme.drawBtn(g, rectCreateOkBtn, UiText.t("确定", "OK"), mouseX, mouseY, accent = true)
            YaclTheme.drawBtn(g, rectCreateCancelBtn, UiText.t("取消", "Cancel"), mouseX, mouseY)
        }

        // 列表
        val list = folders
        if (error != null) {
            YaclTheme.drawCenteredClipped(g, error, w / 2, h / 2 - 30, 11f, (w - 48).coerceAtLeast(40), YaclTheme.colorError)
            return
        }
        if (list == null) {
            g.drawText(UiText.t("加载中…", "Loading…"), w / 2 - 40, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }
        if (list.isEmpty()) {
            g.drawText(UiText.t("暂无收藏夹,点「新建」创建一个", "No favorites yet. Tap \"New\" to create one"), w / 2 - 140, h / 2 - 8, 12f, 1f, YaclTheme.colorTextDim)
            return
        }

        val rowH = 24
        val listX = 16
        val listW = w - 32
        var idx = scroll
        var y = if (createMode) 70 else 48
        while (idx < list.size && y + rowH < h - 8) {
            val f = list[idx]
            val checked = f.id in favedFids
            val hover = mouseY in y until y + rowH && mouseX in listX until listX + listW
            if (hover) g.fill(listX, y, listX + listW, y + rowH, YaclTheme.colorRowHover)
            if (checked) g.fill(listX, y, listX + 3, y + rowH, YaclTheme.colorAccent)
            YaclTheme.drawTextClipped(g, f.title.ifBlank { UiText.t("未命名收藏夹", "Unnamed folder") }, listX + 8, y + 2, 11f, listW - 80, YaclTheme.colorTextMain)
            YaclTheme.drawTextClipped(g, UiText.t("${f.mediaCount} 个内容", "${f.mediaCount} items"), listX + 8, y + 14, 9f, listW - 80, YaclTheme.colorTextDim)
            // 右侧 ✓(当前歌曲在该夹)
            if (checked) {
                g.drawText("✓", listX + listW - 20, y + 4, 12f, 1f, YaclTheme.colorAccentBright)
            }
            y += rowH
            idx++
        }
        if (list.size > (h - 48) / rowH) {
            YaclTheme.drawScrollHint(g, w, h)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x()
        val y = event.y()
        if (rectBackBtn.hit(x, y)) { McScreens.open(back); return true }
        if (rectNewBtn.hit(x, y)) {
            createMode = !createMode
            if (createMode) {
                editBox?.setValue("")
                editBox?.setFocused(true)
            }
            return true
        }
        if (createMode) {
            if (rectCreateOkBtn.hit(x, y)) { submitCreate(); return true }
            if (rectCreateCancelBtn.hit(x, y)) { createMode = false; return true }
        }
        // 候选行点击:切换收藏态
        val list = folders
        if (list != null && list.isNotEmpty()) {
            val rowH = 24
            val listX = 16
            val listW = width - 32
            val listTop = if (createMode) 70 else 48
            if (x >= listX && x < listX + listW && y >= listTop) {
                val row = (y - listTop).toInt() / rowH + scroll
                if (row in list.indices) {
                    toggleFolder(list[row])
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
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
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseScrolled(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        val size = folders?.size ?: 0
        val rowH = 24
        val maxScroll = (size - (height - 48) / rowH).coerceAtLeast(0)
        scroll = (scroll - dy.toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun isPauseScreen(): Boolean = false

    /** 切换当前歌曲在指定夹的收藏态,刷新该行 ✓ */
    private fun toggleFolder(folder: FavFolder) {
        val song = NetMusic.player.current
        if (song == null) return
        // BiliActions 回调已切 UI 线程
        BiliActions.toggleFavInFolder(song, folder) { faved, err ->
            if (err != null) {
                // 轻提示:聊天栏输出错误
                Minecraft.getInstance().gui.getChat().addClientSystemMessage(
                    Component.literal(err).withColor(0xFFFF5C5C),
                )
                return@toggleFavInFolder
            }
            // 刷新本地 ✓ 缓存(folderFavs 已被 BiliActions 维护)
            favedFids = BiliActions.favFoldersOf(song.id)
        }
    }

    /** 新建收藏夹(成功后刷新列表) */
    private fun submitCreate() {
        val title = editBox?.getValue()?.trim().orEmpty()
        if (title.isEmpty()) return
        if (creating) return
        creating = true
        BiliActions.createFolder(title) { _, err ->
            creating = false
            if (err != null) {
                Minecraft.getInstance().gui.getChat().addClientSystemMessage(
                    Component.literal(err).withColor(0xFFFF5C5C),
                )
                return@createFolder
            }
            createMode = false
            // 新建成功:刷新列表(✓ 态经 refreshFavState 更新)
            load()
        }
    }
}
