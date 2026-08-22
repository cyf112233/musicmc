package io.github.cyf112233.musicmc.client

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.player.PlayerListener
import io.github.cyf112233.musicmc.ui.hud.HudLyricsCache
import io.github.cyf112233.musicmc.util.Lrc
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/**
 * 聊天栏歌词:播放进度每句歌词在玩家聊天栏输出一条(本地可见,不发给服务器)。
 *
 * 独立于 HUD 渲染:由 loader 在 NetMusic.init 后 addListener 注册;
 * 换歌时自行触发 [HudLyricsCache.refresh] 保证歌词数据加载(即使 HUD 歌词关闭)。
 * 歌词行去重键 = "歌曲id|行下标":正常逐句前进输出;seek 回退(下标倒退)也会
 * 重新输出,保证歌词与听到的进度一致。
 *
 * 线程:PlayerListener 回调在 MUI UI 线程(MusicPlayer 统一 postToUiThread),
 * ChatComponent 非 UI 线程安全,经 Minecraft.execute 切渲染线程执行 addClientSystemMessage。
 */
object ChatLyricSender : PlayerListener {

    /** 已输出过的行键("歌曲id|行下标"),行变化才输出,防重复刷屏 */
    @Volatile
    private var lastKey: String? = null

    override fun onSongChanged(song: Song?) {
        lastKey = null
        if (song != null) HudLyricsCache.refresh(song)
    }

    override fun onProgress(posMs: Int, durationMs: Int) {
        // 歌词总开关优先(设置页已联动禁用,此处兜底:总开关关 → 不输出,即使缓存残留)
        if (!NetMusic.config.lyricsEnabled || !NetMusic.config.chatLyricEnabled) return
        val cache = HudLyricsCache.current ?: return
        if (cache.lines.isEmpty()) return
        // 与 HUD 歌词块同一时间同步:播放位置减去用户手动偏移(offsetSec)
        val syncMs = posMs - (cache.offsetSec * 1000f).roundToInt()
        val idx = Lrc.findLineIndex(cache.lines, syncMs)
        if (idx < 0) return
        val line = cache.lines[idx].text
        if (line.isBlank()) return
        val songId = NetMusic.player.current?.id ?: return
        val key = "$songId|$idx"
        if (key == lastKey) return
        lastKey = key
        send(line)
    }

    private fun send(line: String) {
        runCatching {
            val mc = Minecraft.getInstance()
            mc.execute {
                runCatching {
                    mc.gui.getChat().addClientSystemMessage(
                        Component.literal("♪ $line").withColor(0x4FC3F7),
                    )
                }
            }
        }
    }
}
