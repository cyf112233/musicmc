package io.github.cyf112233.musicmc.lyrics

import com.google.gson.Gson
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.bilibili.TitleLyricLookup
import io.github.cyf112233.musicmc.bilibili.optLong
import io.github.cyf112233.musicmc.bilibili.optObject
import io.github.cyf112233.musicmc.bilibili.optString
import io.github.cyf112233.musicmc.model.LyricLine
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.net.Http
import io.github.cyf112233.musicmc.util.Async
import io.github.cyf112233.musicmc.util.Lrc
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 歌词加载结果:歌词行 + 当前偏移 + 来源展示名("本地缓存"/"Hub"/"CC字幕"/来源名)。
 */
data class LyricResult(
    val lines: List<LyricLine>,
    val offsetSec: Float,
    val from: String,
)

/**
 * Hub 歌词数据结构(与 hub/server.js 约定的 JSON schema 一致):
 * {"id","updateTime","lrc","userOffsetSec","source","updatedAt"}
 */
data class HubData(
    val id: String,
    val updateTime: Long,
    val lrc: String,
    val userOffsetSec: Float,
    val source: String?,
    val updatedAt: Long,
)

/**
 * 歌词总控(BBPlayer 式完整歌词逻辑):
 * - [load]:本地缓存 →(配置了 hubUrl 时)Hub 同步 → CC 字幕 → 标题自动匹配
 *   (网易云 → QQ(duration±3s)→ 酷狗,命中即落缓存);
 * - [manualSearch]:三源并行搜索,按完成顺序逐源追加去重结果;
 * - [bind]:手动绑定指定来源的候选歌词(manual=true,保留旧偏移);
 * - [adjustOffset]:±0.5s 步进偏移,即点即存,推送 Hub(fire-and-forget);
 * - hub 客户端:[hubPull]/[hubPush]。
 *
 * 回调线程约定:[load]/[hubPull] 在后台线程;手动路径([manualSearch]/[bind]/
 * [adjustOffset])统一切到 UI 线程后回调。
 */
object LyricManager {

    private val gson = Gson()

    // ---------------- 主加载 ----------------

    /**
     * 加载歌词,优先级:
     * 1) 本地缓存(manual 或任何来源都直接用,偏移即点即存);
     * 2) Hub 同步(配置了 hubUrl 且缓存未命中时拉一次,命中落缓存;失败静默);
     * 3) CC 字幕(BilibiliSource.lyric):命中则落缓存(manual=false,保留已有偏移,
     *    不覆盖 manual 缓存)并返回;
     * 4) 标题自动匹配(仅 config.lyricTitleFallback 开启):网易云取第一首 →
     *    QQ / 酷狗 duration±3s 匹配;
     * 5) 全失败 → (empty, err)。
     */
    fun load(song: Song, cb: (LyricResult, String?) -> Unit) {
        Async.executor.execute {
            try {
                val key = LyricCache.keyFor(song)

                // 1) 本地缓存
                LyricCache.load(key)?.let { cached ->
                    cb(LyricResult(cached.lines(), cached.userOffsetSec, "Local cache"), null)
                    return@execute
                }

                // 2) Hub 同步(未配置 hubUrl 时直接跳过;拉取失败静默)
                if (hubEnabled()) {
                    hubPullSync(key)?.let { hub ->
                        val lines = Lrc.parseLrc(hub.lrc)
                        if (lines.isNotEmpty()) {
                            LyricCache.save(key, lines, hub.userOffsetSec, manual = false, source = hub.source ?: "hub")
                            cb(LyricResult(lines, hub.userOffsetSec, "Hub"), null)
                            return@execute
                        }
                    }
                }

                // 3) CC 字幕
                NetMusic.source.lyric(song.id) { lines, err ->
                    if (lines.isNotEmpty()) {
                        val existing = LyricCache.load(key)
                        val offset = existing?.userOffsetSec ?: 0f
                        if (existing == null || !existing.manual) {
                            LyricCache.save(key, lines, offset, manual = false, source = "cc")
                        }
                        cb(LyricResult(lines, offset, "CC subtitles"), null)
                    } else if (NetMusic.config.lyricTitleFallback) {
                        autoMatch(song, key, cb)
                    } else {
                        cb(LyricResult(emptyList(), 0f, ""), err ?: "No CC subtitles for this video")
                    }
                }
            } catch (e: Exception) {
                cb(LyricResult(emptyList(), 0f, ""), e.message ?: "Failed to load lyrics")
            }
        }
    }

    // ---------------- 手动搜索 / 绑定 / 偏移 ----------------

    /**
     * 三源并行手动搜索(keyword 为用户输入,不做标题清洗):
     * 每个来源完成后按完成顺序把结果追加进累计列表(去重按 source+remoteId),
     * 并切 UI 线程回调一次;全部完成后仍为空则回调 "未找到相关歌词"。
     */
    fun manualSearch(keyword: String, cb: (List<LyricCandidate>, String?) -> Unit) {
        if (keyword.isBlank()) {
            cb(emptyList(), "Search keyword is empty")
            return
        }
        val accumulated = mutableListOf<LyricCandidate>()
        val seen = mutableSetOf<String>()
        val remaining = AtomicInteger(3)
        for (source in listOf(LyricProviders.NETEASE, LyricProviders.QQ, LyricProviders.KUGOU)) {
            LyricProviders.search(source, keyword) { candidates, err ->
                val snapshot: List<LyricCandidate>
                synchronized(accumulated) {
                    for (c in candidates) {
                        if (seen.add("${c.source}:${c.remoteId}")) accumulated.add(c)
                    }
                    snapshot = accumulated.toList()
                }
                val last = remaining.decrementAndGet() == 0
                Async.onUi {
                    when {
                        snapshot.isNotEmpty() -> cb(snapshot, null)
                        err != null -> cb(emptyList(), err)
                        last -> cb(emptyList(), "No related lyrics found")
                        else -> cb(emptyList(), null) // 其他来源可能还有结果,不报错
                    }
                }
            }
        }
    }

    /**
     * 手动绑定某来源的候选歌词:fetch → 落缓存(manual=true,offset 保留旧值)→
     * 回 UI 线程回调;成功后推送 Hub(如已配置,失败静默)。
     */
    fun bind(song: Song, candidate: LyricCandidate, cb: (LyricResult, String?) -> Unit) {
        LyricProviders.fetch(candidate) { lines, err ->
            if (lines.isEmpty()) {
                Async.onUi { cb(LyricResult(emptyList(), 0f, ""), err ?: "No lyrics from this source") }
                return@fetch
            }
            val key = LyricCache.keyFor(song)
            val existingOffset = LyricCache.load(key)?.userOffsetSec ?: 0f
            LyricCache.save(key, lines, existingOffset, manual = true, source = candidate.source)
            LyricCache.load(key)?.let { cached ->
                hubPush(
                    key,
                    HubData(cached.id, cached.updateTime, cached.lrc, existingOffset, cached.source, System.currentTimeMillis()),
                )
            }
            Async.onUi { cb(LyricResult(lines, existingOffset, LyricProviders.sourceLabel(candidate.source)), null) }
        }
    }

    /**
     * 调整偏移(±0.5s 步进):读缓存 → offset+delta → saveOffset 即点即存 →
     * hubUrl 配置了则 fire-and-forget 推送 → UI 线程回调新偏移。
     */
    fun adjustOffset(song: Song, deltaSec: Float, cb: (Float) -> Unit) {
        Async.executor.execute {
            val key = LyricCache.keyFor(song)
            val old = LyricCache.load(key)?.userOffsetSec ?: 0f
            val newOffset = old + deltaSec
            LyricCache.saveOffset(key, newOffset)
            if (hubEnabled()) {
                LyricCache.load(key)?.let { cached ->
                    hubPush(
                        key,
                        HubData(cached.id, cached.updateTime, cached.lrc, newOffset, cached.source, System.currentTimeMillis()),
                    )
                }
            }
            Async.onUi { cb(newOffset) }
        }
    }

    // ---------------- Hub 客户端 ----------------

    /** 从 Hub 拉取歌词(GET hubUrl/lyrics/:key),回调在后台线程;未配置 / 404 等返回 null */
    fun hubPull(key: String, cb: (HubData?, String?) -> Unit) {
        Async.executor.execute {
            if (!hubEnabled()) {
                cb(null, "No Hub URL configured")
                return@execute
            }
            val data = hubPullSync(key)
            cb(data, if (data == null) "Hub has no lyrics or fetch failed" else null)
        }
    }

    /** 推送歌词到 Hub(PUT JSON,fire-and-forget,失败静默) */
    fun hubPush(key: String, data: HubData) {
        Async.executor.execute {
            if (!hubEnabled()) return@execute
            try {
                val url = hubUrlBase() + "/lyrics/" + key
                Http.putJson(url, gson.toJson(data))
            } catch (e: Exception) {
                // 推送失败静默(不影响本地使用)
            }
        }
    }

    /** 同步拉取实现(供 [load] 在后台线程内直接调用) */
    private fun hubPullSync(key: String): HubData? {
        val hubUrl = NetMusic.config.hubUrl
        if (hubUrl.isBlank()) return null
        return try {
            val json = Http.getJson(hubUrl.trimEnd('/') + "/lyrics/" + key)
            val lrc = json.optString("lrc") ?: return null
            if (lrc.isBlank()) return null
            HubData(
                id = json.optString("id") ?: key,
                updateTime = json.get("updateTime").optLong(),
                lrc = lrc,
                userOffsetSec = json.get("userOffsetSec").takeIf { it.isJsonPrimitive }?.asFloat ?: 0f,
                source = json.optString("source"),
                updatedAt = json.get("updatedAt").optLong(),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun hubUrlBase(): String = NetMusic.config.hubUrl.trimEnd('/')

    private fun hubEnabled(): Boolean = NetMusic.config.hubUrl.isNotBlank()

    // ---------------- 标题自动匹配 ----------------

    /**
     * 自动匹配链路(均在后台线程回调):
     * 网易云 search(清洗标题)→ 取第一首 fetch → 空则 QQ(duration±3s 匹配)→ 空则酷狗(同样匹配)。
     */
    private fun autoMatch(song: Song, key: String, cb: (LyricResult, String?) -> Unit) {
        val keyword = TitleLyricLookup.cleanTitle(song.title)
        if (keyword.isBlank()) {
            cb(LyricResult(emptyList(), 0f, ""), "No matching lyrics found")
            return
        }
        LyricProviders.search(LyricProviders.NETEASE, keyword) { candidates, _ ->
            val first = candidates.firstOrNull()
            if (first == null) {
                tryQq(song, key, keyword, cb)
                return@search
            }
            LyricProviders.fetch(first) { lines, _ ->
                if (lines.isNotEmpty()) {
                    finishAuto(song, key, lines, first.source, cb)
                } else {
                    tryQq(song, key, keyword, cb)
                }
            }
        }
    }

    private fun tryQq(song: Song, key: String, keyword: String, cb: (LyricResult, String?) -> Unit) {
        LyricProviders.search(LyricProviders.QQ, keyword) { candidates, _ ->
            val matched = matchDuration(candidates, song.durationMs)
            if (matched == null) {
                tryKugou(song, key, keyword, cb)
                return@search
            }
            LyricProviders.fetch(matched) { lines, _ ->
                if (lines.isNotEmpty()) {
                    finishAuto(song, key, lines, matched.source, cb)
                } else {
                    tryKugou(song, key, keyword, cb)
                }
            }
        }
    }

    private fun tryKugou(song: Song, key: String, keyword: String, cb: (LyricResult, String?) -> Unit) {
        LyricProviders.search(LyricProviders.KUGOU, keyword) { candidates, _ ->
            val matched = matchDuration(candidates, song.durationMs)
            if (matched == null) {
                cb(LyricResult(emptyList(), 0f, ""), "No matching lyrics found")
                return@search
            }
            LyricProviders.fetch(matched) { lines, _ ->
                if (lines.isNotEmpty()) {
                    finishAuto(song, key, lines, matched.source, cb)
                } else {
                    cb(LyricResult(emptyList(), 0f, ""), "No matching lyrics found")
                }
            }
        }
    }

    /** duration±3s 匹配(歌曲时长未知时取第一首);无匹配返回 null */
    private fun matchDuration(candidates: List<LyricCandidate>, durationMs: Int): LyricCandidate? {
        if (candidates.isEmpty()) return null
        if (durationMs <= 0) return candidates.first()
        return candidates.firstOrNull { abs(it.durationMs - durationMs) <= 3000 }
    }

    /** 自动匹配命中:落缓存(manual=false,保留已有偏移;不覆盖 manual 缓存) */
    private fun finishAuto(song: Song, key: String, lines: List<LyricLine>, source: String, cb: (LyricResult, String?) -> Unit) {
        val existing = LyricCache.load(key)
        val offset = existing?.userOffsetSec ?: 0f
        if (existing == null || !existing.manual) {
            LyricCache.save(key, lines, offset, manual = false, source = source)
        }
        cb(LyricResult(lines, offset, LyricProviders.sourceLabel(source)), null)
    }
}
