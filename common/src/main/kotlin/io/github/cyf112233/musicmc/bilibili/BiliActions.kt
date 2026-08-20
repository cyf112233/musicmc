package io.github.cyf112233.musicmc.bilibili

import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.util.Async
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 点赞 / 收藏业务层。负责:
 * - bvid→aid 缓存(经 [BiliHttp.view] 获取,失败报"获取视频信息失败");
 * - 收藏状态缓存:bvid → 所在收藏夹 id 集合([folderFavs]),由 [refreshFavState] 全量刷新、
 *   [toggleFavInFolder] 增量维护;单视频"是否已收藏"取集合非空;
 * - 未登录统一提示"请先在设置中登录 B 站"(判定用 [NetMusic.bilibiliLoggedIn])。
 *
 * [BiliHttp] 的接口为阻塞式(回调同步返回),本层统一用 [Async.run] 放到后台线程执行,
 * 并把最终回调经 [Async.onUi] 切回 UI 线程,可直接更新视图。
 */
object BiliActions {

    /** bvid → aid 缓存(经 view 获取后缓存;同一视频 aid 恒定,不主动清理) */
    private val aidCache = ConcurrentHashMap<String, Long>()

    /** bvid → 所在收藏夹 id 集合(null = 未知;refreshFavState/toggleFavInFolder 后为真实态,可为空) */
    private val folderFavs = ConcurrentHashMap<String, MutableSet<Long>>()

    /** bvid → 内存点赞状态(null 表示未知,需要向服务端查询) */
    private class State {
        @Volatile var liked: Boolean? = null
    }

    private val states = ConcurrentHashMap<String, State>()

    private fun stateOf(bvid: String): State = states.computeIfAbsent(bvid) { State() }

    /**
     * 阻塞式获取视频 aid(内存缓存优先;否则经 view 获取并缓存)。
     * 失败抛 IOException("获取视频信息失败" 或具体错误)。仅限后台线程调用。
     */
    private fun blockingAid(bvid: String): Long {
        aidCache[bvid]?.let { return it }
        val view = BiliHttp.view(bvid)
        val a = view.optObject("data")?.get("aid")?.optLong() ?: 0L
        if (a <= 0L) throw IOException("Failed to fetch video info")
        aidCache[bvid] = a
        return a
    }

    /**
     * 获取视频 aid(内存缓存优先;否则经 view 获取并缓存)。
     * 失败 cb(0L, "获取视频信息失败" 或具体错误)。回调在 UI 线程。
     */
    fun ensureAid(song: Song, cb: (Long, String?) -> Unit) {
        val bvid = song.id
        aidCache[bvid]?.let {
            Async.onUi { cb(it, null) }
            return
        }
        Async.run {
            val (aid, err) = try {
                blockingAid(bvid).let { it to null }
            } catch (e: Exception) {
                0L to (e.message ?: "Failed to fetch video info")
            }
            Async.onUi { cb(aid, err) }
        }
    }

    /**
     * 点赞 / 取消点赞切换。
     * 流程:内存缓存优先取当前态,否则 [BiliHttp.hasLiked] → 反向调 [BiliHttp.like] → 更新缓存。
     * 未登录 → cb(false, "请先在设置中登录 B 站")。回调 (liked: Boolean 切换后的新状态, err) 在 UI 线程。
     */
    fun toggleLike(song: Song, cb: (Boolean, String?) -> Unit) {
        if (!NetMusic.bilibiliLoggedIn()) {
            cb(false, "Please log in to Bilibili in Settings first")
            return
        }
        val bvid = song.id
        val state = stateOf(bvid)
        Async.run {
            var liked = false
            var err: String? = null
            try {
                // 当前态:内存缓存优先,否则 hasLiked 查询
                if (state.liked == null) {
                    var current = false
                    var qErr: String? = null
                    BiliHttp.hasLiked(bvid) { v, e -> current = v; qErr = e }
                    if (qErr != null) throw IOException(qErr)
                    state.liked = current
                }
                // 反向切换
                val target = state.liked != true
                var likeErr: String? = null
                BiliHttp.like(bvid, target) { e -> likeErr = e }
                if (likeErr != null) throw IOException(likeErr)
                state.liked = target
                liked = target
            } catch (e: Exception) {
                err = e.message ?: "Failed to like"
            }
            Async.onUi { cb(liked, err) }
        }
    }

    /**
     * 切歌时刷新点赞态(onSongChanged 调用):内存缓存优先,否则 [BiliHttp.hasLiked] 查询并缓存。
     * 未登录 → cb(null, null)(界面保持空心)。回调 (liked: Boolean?, err) 在 UI 线程。
     */
    fun refreshLike(song: Song, cb: (Boolean?, String?) -> Unit) {
        if (!NetMusic.bilibiliLoggedIn()) {
            Async.onUi { cb(null, null) }
            return
        }
        val bvid = song.id
        val state = stateOf(bvid)
        state.liked?.let {
            Async.onUi { cb(it, null) }
            return
        }
        Async.run {
            var liked: Boolean? = null
            var err: String? = null
            try {
                var current = false
                var qErr: String? = null
                BiliHttp.hasLiked(bvid) { v, e -> current = v; qErr = e }
                if (qErr != null) throw IOException(qErr)
                liked = current
                state.liked = current
            } catch (e: Exception) {
                err = e.message ?: "Failed to check like status"
            }
            Async.onUi { cb(liked, err) }
        }
    }

    // ---------------- 收藏夹 ----------------

    /**
     * 全部收藏夹列表。未登录 → cb(emptyList, "请先在设置中登录 B 站")。
     * 回调 (List<FavFolder>, err) 在 UI 线程(favState 恒 false,单夹状态经
     * [refreshFavState] 的 [folderFavs] 缓存查询)。
     */
    fun folders(cb: (List<FavFolder>, String?) -> Unit) {
        if (!NetMusic.bilibiliLoggedIn()) {
            Async.onUi { cb(emptyList(), "Please log in to Bilibili in Settings first") }
            return
        }
        Async.run {
            var list = emptyList<FavFolder>()
            var err: String? = null
            try {
                val mid = BiliHttp.currentMid() ?: throw IOException("Failed to fetch account info")
                var listErr: String? = null
                BiliHttp.favFolderList(mid, null) { l, e -> list = l; listErr = e }
                if (listErr != null) throw IOException(listErr)
            } catch (e: Exception) {
                err = e.message ?: "Failed to fetch favorites"
            }
            Async.onUi { cb(list, err) }
        }
    }

    /**
     * 刷新当前视频的收藏状态缓存(收藏夹选择器打开时调用):
     * favFolderList(mid, aid) 带 rid 查询所有"含该视频"的收藏夹,把它们的 id 写入 [folderFavs],
     * 供单夹 ✓ 标记与 [favedState] 使用。回调 (err: String?) 在 UI 线程(err==null 表示已刷新)。
     */
    fun refreshFavState(song: Song, cb: (String?) -> Unit) {
        if (!NetMusic.bilibiliLoggedIn()) {
            Async.onUi { cb(null) }
            return
        }
        val bvid = song.id
        Async.run {
            var err: String? = null
            try {
                val aid = blockingAid(bvid)
                val mid = BiliHttp.currentMid() ?: throw IOException("Failed to fetch account info")
                var list = emptyList<FavFolder>()
                var listErr: String? = null
                BiliHttp.favFolderList(mid, aid) { l, e -> list = l; listErr = e }
                if (listErr != null) throw IOException(listErr)
                val set = ConcurrentHashMap.newKeySet<Long>()
                for (f in list) if (f.favState) set.add(f.id)
                folderFavs[bvid] = set
            } catch (e: Exception) {
                err = e.message ?: "Failed to check favorite status"
            }
            Async.onUi { cb(err) }
        }
    }

    /**
     * 切换当前视频在指定收藏夹的收藏态(收藏夹选择器点行调用):
     * 在夹 → [BiliHttp.favDel] 移出;不在 → [BiliHttp.favAdd] 加入;成功后增量更新 [folderFavs]。
     * 90022("已在该收藏夹",缓存过期场景)按已加入成功处理。回调 (faved 切换后的新状态, err) 在 UI 线程。
     */
    fun toggleFavInFolder(song: Song, folder: FavFolder, cb: (Boolean, String?) -> Unit) {
        if (!NetMusic.bilibiliLoggedIn()) {
            Async.onUi { cb(false, "Please log in to Bilibili in Settings first") }
            return
        }
        val bvid = song.id
        Async.run {
            var faved = false
            var err: String? = null
            try {
                val aid = blockingAid(bvid)
                val set = folderFavs.computeIfAbsent(bvid) { ConcurrentHashMap.newKeySet() }
                val inFolder = folder.id in set
                var opErr: String? = null
                if (inFolder) BiliHttp.favDel(aid, folder.id) { e -> opErr = e }
                else BiliHttp.favAdd(aid, folder.id) { e -> opErr = e }
                if (opErr != null) {
                    if (opErr == "Already in this folder") {
                        // 缓存过期:实际已在夹,按加入成功处理
                        set.add(folder.id)
                        faved = true
                    } else {
                        throw IOException(opErr)
                    }
                } else {
                    if (inFolder) set.remove(folder.id) else set.add(folder.id)
                    faved = !inFolder
                }
            } catch (e: Exception) {
                err = e.message ?: "Failed to add favorite"
            }
            Async.onUi { cb(faved, err) }
        }
    }

    /**
     * 新建收藏夹。回调 (fid 新建成功后的收藏夹 id, err) 在 UI 线程。
     * 未登录 → cb(null, "请先在设置中登录 B 站")。
     */
    fun createFolder(title: String, cb: (Long?, String?) -> Unit) {
        if (!NetMusic.bilibiliLoggedIn()) {
            Async.onUi { cb(null, "Please log in to Bilibili in Settings first") }
            return
        }
        Async.run {
            var fid: Long? = null
            var err: String? = null
            try {
                var opErr: String? = null
                BiliHttp.favCreateFolder(title) { f, e -> fid = f; opErr = e }
                if (opErr != null) throw IOException(opErr)
            } catch (e: Exception) {
                err = e.message ?: "Failed to create folder"
                fid = null
            }
            Async.onUi { cb(fid, err) }
        }
    }

    /**
     * 收藏夹内容(一页 ps=20)。未登录 → cb(emptyList, "请先在设置中登录 B 站")。
     * 回调 (List<Song>: id=bvid, err) 在 UI 线程;分页由调用方递增 [pn] 拼接。
     */
    fun folderSongs(fid: Long, pn: Int, cb: (List<Song>, String?) -> Unit) {
        if (!NetMusic.bilibiliLoggedIn()) {
            Async.onUi { cb(emptyList(), "Please log in to Bilibili in Settings first") }
            return
        }
        Async.run {
            var list = emptyList<Song>()
            var err: String? = null
            try {
                var listErr: String? = null
                BiliHttp.favResourceList(fid, pn) { l, e -> list = l; listErr = e }
                if (listErr != null) throw IOException(listErr)
            } catch (e: Exception) {
                err = e.message ?: "Failed to fetch folder contents"
            }
            Async.onUi { cb(list, err) }
        }
    }

    /** 内存中的收藏态:缓存的收藏夹集合非空即已收藏;未刷新过返回 null(UI 显示空心,切歌时 ☆) */
    fun favedState(bvid: String): Boolean? {
        val set = folderFavs[bvid] ?: return null
        return set.isNotEmpty()
    }

    /** 当前视频所在的收藏夹 id 集合(未刷新过返回空集;供收藏夹选择器 ✓ 标记) */
    fun favFoldersOf(bvid: String): Set<Long> = folderFavs[bvid] ?: emptySet()
}