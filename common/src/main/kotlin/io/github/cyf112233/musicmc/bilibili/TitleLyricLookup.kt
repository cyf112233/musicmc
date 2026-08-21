package io.github.cyf112233.musicmc.bilibili

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.cyf112233.musicmc.model.LyricLine
import io.github.cyf112233.musicmc.net.Http
import io.github.cyf112233.musicmc.util.Async
import io.github.cyf112233.musicmc.util.Lrc
import io.github.cyf112233.musicmc.client.UiText

/**
 * 标题匹配歌词:把歌曲标题经网易云无登录 plain 接口(与 BBPlayer 歌词来源同思路)
 * 匹配到网易云歌词库中的一首歌并取歌词。仅是歌词提供方,不提供任何音源
 * (用户已弃用网易云音源,仅保留其公开歌词库作为可开关的标题匹配源)。
 *
 * 注:自 lyrics 包接入后,歌词加载统一走 LyricManager(CC 字幕 → 自动三源匹配 →
 * 手动绑定),本对象的 [lookup] 不再被调用,仅保留 [cleanTitle] 作为
 * 自动匹配时的标题清洗工具(网易云 provider 复用)。
 *
 * 2026-08 本机 curl 实测:
 * - POST https://music.163.com/api/search/get/web(form: s/type=1/limit=5/offset=0/total=true,
 *   带 Referer https://music.163.com/ 与浏览器 UA)匿名可用,无加密无登录;
 *   response.result.songs[]{ id(可能超出 Int,须按 Long 读), name, artists[] }。
 * - GET https://music.163.com/api/song/lyric?id=<id>&lv=1&kv=1&tv=-1 匿名可用;
 *   lrc.lyric 为空字符串表示无歌词;仅含"作词/作曲"等元数据(无时间戳)时
 *   Lrc.parseLrc 也会解析为空,两者都按无歌词处理、跳过该候选。
 * - 网易云搜索对新上传(翻唱/AI 翻唱)排序靠前,经典原版可能不在前 5;但热门翻唱通常
 *   同样携带原版歌词(实测"晴天(深情版)"含原版歌词),故匹配结果仍可用于 karaoke。
 */
object TitleLyricLookup {

    /** 网易云搜索接口(web 页 plain,无登录) */
    private const val SEARCH_URL = "https://music.163.com/api/search/get/web"

    /** 网易云歌词接口(plain,无登录) */
    private const val LYRIC_URL = "https://music.163.com/api/song/lyric"

    /** 网易云页面 Referer(与 BBPlayer 同思路;实测缺失也能通,带上更稳) */
    private const val NET_EASE_REFERER = "https://music.163.com/"

    /** 搜索返回候选数(实测 5 条已够,再多易混入无关曲目) */
    private const val LIMIT = 5

    /**
     * 按 [title] 查找歌词。全程在共享后台线程池执行,回调同样在后台线程(调用方自行切 UI)。
     * - 命中:callback(lines, null);
     * - 无歌词:callback(emptyList, "未找到匹配歌词");
     * - 搜索阶段失败(网络等):callback(emptyList, "标题匹配歌词失败")。
     */
    fun lookup(title: String, callback: (List<LyricLine>, String?) -> Unit) {
        Async.executor.execute {
            try {
                val keyword = cleanTitle(title)
                if (keyword.isBlank()) {
                    callback(emptyList(), UiText.t("未找到匹配歌词", "No matching lyrics found"))
                    return@execute
                }
                val songs = search(keyword)
                if (songs.isEmpty()) {
                    callback(emptyList(), UiText.t("未找到匹配歌词", "No matching lyrics found"))
                    return@execute
                }
                // 相似度降序(稳定排序保持网易云排序),逐个候选取歌词:跳过无歌词的候选
                for (song in songs.sortedByDescending { score(it, keyword) }) {
                    val lines = fetchLyric(song.get("id").optLong())
                    if (lines.isNotEmpty()) {
                        callback(lines, null)
                        return@execute
                    }
                }
                callback(emptyList(), UiText.t("未找到匹配歌词", "No matching lyrics found"))
            } catch (e: Exception) {
                callback(emptyList(), UiText.t("标题匹配歌词失败", "Failed to match lyrics by title"))
            }
        }
    }

    // ---------------- 标题清洗 ----------------

    /**
     * 标题清洗(2026-08 按 curl 实测调优;internal 供 lyrics 包的网易云 provider 复用):
     * 1) 去 <em> 等 HTML 标签(防御;Song.title 已由 [BilibiliSource] 的 stripHtml 处理过);
     * 2) 含《书名号》时优先取第一个《》内文本——中文视频标题常用书名号框出歌名,实测最稳
     *    (如 "循环歌曲《晴天》|..."、"【Hi-Res】｜《晴天》- 周杰伦" → "晴天");
     * 3) 否则:去【】[]()及其内容 → 去《》「」『』｜ 等字符(实测残留这些符号会污染网易云搜索)
     *    → 按 "-"、"|"、"/" 取第一段;
     * 4) trim;空则回退原标题。
     */
    internal fun cleanTitle(title: String): String {
        var t = title.replace(Regex("<[^>]+>"), "")
        Regex("《([^》]+)》").find(t)?.let { return it.groupValues[1].trim() }
        t = t.replace(Regex("【[^】]*】"), "")
            .replace(Regex("\\[[^]]*\\]"), "")
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("[《》「」『』｜|]"), "")
        val seg = t.split(Regex("[-|/]")).first().trim()
        return if (seg.isEmpty()) title.trim() else seg
    }

    // ---------------- 搜索 / 匹配 / 取歌词 ----------------

    /** 网易云搜索:返回 result.songs[];无结果返回空列表;网络/解析失败抛异常由外层兜底 */
    private fun search(keyword: String): List<JsonObject> {
        val json = Http.postForm(
            SEARCH_URL,
            mapOf(
                "s" to keyword,
                "type" to "1",
                "limit" to LIMIT.toString(),
                "offset" to "0",
                "total" to "true",
            ),
            referer = NET_EASE_REFERER,
        )
        val songs = json.optObject("result")?.optArray("songs") ?: JsonArray()
        return songs.mapNotNull { e -> if (e.isJsonObject) e.asJsonObject else null }
    }

    /**
     * 简单相似度(候选名 vs 关键词,尽量可解释):
     * - 关键词完全包含于候选名 → 2(最高分,如 "晴天" ⊂ "晴天(深情版)");
     * - 候选名完全包含于关键词 → 1(如 "稻香" ⊂ "稻香 完整版",搜索词比歌名长);
     * - 否则 0(都不命中时退回"选第 1 条"的语义,由稳定排序保持网易云相关度顺序)。
     */
    private fun score(song: JsonObject, keyword: String): Int {
        val name = song.optString("name") ?: ""
        return when {
            name.contains(keyword) -> 2
            name.isNotEmpty() && keyword.contains(name) -> 1
            else -> 0
        }
    }

    /** 取歌词并解析;失败 / 无歌词(含仅元数据无时间戳)返回空列表(调用方跳过该候选) */
    private fun fetchLyric(id: Long): List<LyricLine> {
        return try {
            val json = Http.getJson("$LYRIC_URL?id=$id&lv=1&kv=1&tv=-1", referer = NET_EASE_REFERER)
            val raw = json.optObject("lrc")?.optString("lyric") ?: ""
            if (raw.isBlank()) emptyList() else Lrc.parseLrc(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
