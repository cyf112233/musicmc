package io.github.cyf112233.musicmc.ui.hud

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.lyrics.LyricManager
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.util.Async
import java.util.concurrent.ConcurrentHashMap

/**
 * HUD 歌词缓存:换歌时异步加载(三源,CC 优先,复用 [LyricManager]),完成后回主线程。
 *
 * 由游戏内渲染层与 HUD 编辑器在 onSongChanged(换歌 / 换形状无关)时调用 [refresh];
 * 直接走 LyricManager.load 而非 NetMusic.getLyrics:需要 offsetSec 做时间同步
 * (getLyrics 回调不携带偏移);加载条件 = 歌词总开关 lyricsEnabled(HUD 歌词与
 * 聊天栏歌词共用此数据源;hudLyricEnabled / chatLyricEnabled 为显示层开关,各自判断)。
 *
 * 脏标记([dirty]/[invalidate]):歌词 GUI(播放页)调整偏移或手动绑定新歌词后,
 * 歌词内容 / 偏移已变化,但歌曲 id 未变 —— 原 refresh 的"同歌直接 return"会让 HUD
 * 一直显示旧歌词。GUI 侧落盘完成后调 [invalidate],HUD 渲染层每帧检测到 dirty
 * (节流 500ms)对同一首歌重载,实现"歌词 GUI 修改后 HUD 同步"。
 */
object HudLyricsCache {

    @Volatile
    var current: CachedHudLyric? = null
        private set

    /** 歌词内容(偏移 / 手动绑定)已被 GUI 侧修改、HUD 需重载(@Volatile 跨线程) */
    @Volatile
    var dirty = false
        private set

    @Volatile
    private var lastSongId: String? = null

    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** GUI 侧(播放页偏移调整 / 手动绑定成功)落盘完成后调用:标脏,HUD 下一轮重载 */
    fun invalidate() {
        dirty = true
    }

    /** 清空(总开关关闭 / 无歌曲):HUD 歌词块不画(布局层 current==null 时无歌词行) */
    fun clear() {
        current = null
        lastSongId = null
        dirty = false
    }

    fun refresh(song: Song?) {
        if (song == null) {
            clear()
            return
        }
        // 同歌但被 invalidate 标脏时允许重载(GUI 侧改了偏移 / 手动绑定了新歌词)
        if (song.id == lastSongId && !dirty) return
        lastSongId = song.id
        current = null
        // 已有同歌加载在途:放弃本次,保留 dirty 由下一轮 500ms 节流重试自愈
        if (!inFlight.add(song.id)) return

        // 歌词总开关优先:HUD 歌词与聊天栏歌词共用此数据源,总开关关闭时不加载
        // (hudLyricEnabled / chatLyricEnabled 只是显示层开关,由各自渲染方判断)
        if (!NetMusic.config.lyricsEnabled) {
            inFlight.remove(song.id)
            return
        }
        dirty = false // 实际发起加载前清除标脏(防止本轮加载尚未完成时重复触发)
        LyricManager.load(song) { result, _ ->
            inFlight.remove(song.id)
            if (result.lines.isEmpty()) return@load
            val snapshot = CachedHudLyric(result.lines, result.offsetSec, result.from)
            Async.onUi {
                // 仅当仍停留在同一首歌时更新(防旧回调覆盖新歌)
                if (lastSongId == song.id) current = snapshot
            }
        }
    }
}
