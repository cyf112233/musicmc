package io.github.cyf112233.musicmc.lyrics

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.cyf112233.musicmc.bilibili.optArray
import io.github.cyf112233.musicmc.bilibili.optInt
import io.github.cyf112233.musicmc.bilibili.optLong
import io.github.cyf112233.musicmc.bilibili.optObject
import io.github.cyf112233.musicmc.bilibili.optString
import io.github.cyf112233.musicmc.model.LyricLine
import io.github.cyf112233.musicmc.net.Http
import io.github.cyf112233.musicmc.util.Async
import io.github.cyf112233.musicmc.util.Lrc
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 第三方歌词库提供方(全部 plain 免登录接口,2026-08 本机 curl 实测):
 * - netease 网易云:POST music.163.com/api/search/get/web(form s/type=1/limit=10,Referer music.163.com)
 *   → result.songs[]{id,name,artists[]{name},duration(毫秒)};取词 GET music.163.com/api/song/lyric
 *   ?id=&lv=1&kv=1&tv=-1 → lrc.lyric;
 * - qq QQ音乐:POST u.y.qq.com/cgi-bin/musicu.fcg(UA Firefox/115、Content-Type application/json
 *   、Referer y.qq.com)→ 实测响应路径 data.req.data.body.song.list[]{mid,name,singer[]{name},
 *   interval(秒)};取词 GET i.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=<mid>
 *   &g_tk=5381&format=json&inCharset=utf8&outCharset=utf8&nobase64=1 → lyric 文本(HTML 实体
 *   反转义后 parseLrc);
 * - kugou 酷狗:GET mobilecdn.kugou.com/api/v3/search/song(UA IPhone-8990-searchSong +
 *   UNI-UserAgent)→ data.info[]{hash,songname,singername,duration(秒)};两步取词:
 *   krcs.kugou.com/search(ver=1&hash=)→ candidates[0]{id,accesskey} →
 *   lyrics.kugou.com/download → content 为 Base64 编码的 LRC → decode → parseLrc。
 *
 * [search] / [fetch] 均在后台线程执行,回调同样在后台线程(调用方自行切 UI);
 * 失败回调带中文错误文案。
 */
object LyricProviders {

    const val NETEASE = "netease"
    const val QQ = "qq"
    const val KUGOU = "kugou"

    /** 来源常量 → 中文展示名(UI 来源标签 / 候选行后缀用) */
    private val SOURCE_LABELS = mapOf(NETEASE to "网易云", QQ to "QQ音乐", KUGOU to "酷狗")

    fun sourceLabel(source: String): String = SOURCE_LABELS[source] ?: source

    // ---------------- 公开异步接口(均后台线程) ----------------

    /** 按关键词搜索候选(来源见 [searchNetease]/[searchQq]/[searchKugou]) */
    fun search(source: String, keyword: String, cb: (List<LyricCandidate>, String?) -> Unit) {
        Async.executor.execute {
            try {
                val list = when (source) {
                    NETEASE -> searchNetease(keyword)
                    QQ -> searchQq(keyword)
                    KUGOU -> searchKugou(keyword)
                    else -> throw IOException("未知歌词来源: $source")
                }
                cb(list, null)
            } catch (e: Exception) {
                cb(emptyList(), "搜索失败: ${e.message ?: "网络错误"}")
            }
        }
    }

    /** 按候选取歌词(解析后的行;该来源无歌词时回调空列表与"该来源暂无歌词") */
    fun fetch(candidate: LyricCandidate, cb: (List<LyricLine>, String?) -> Unit) {
        Async.executor.execute {
            try {
                val lines = when (candidate.source) {
                    NETEASE -> fetchNetease(candidate.remoteId)
                    QQ -> fetchQq(candidate.remoteId)
                    KUGOU -> fetchKugou(candidate.remoteId)
                    else -> throw IOException("未知歌词来源: ${candidate.source}")
                }
                if (lines.isEmpty()) cb(emptyList(), "该来源暂无歌词") else cb(lines, null)
            } catch (e: Exception) {
                cb(emptyList(), "取歌词失败: ${e.message ?: "网络错误"}")
            }
        }
    }

    // ---------------- 网易云 ----------------

    private const val NETEASE_SEARCH = "https://music.163.com/api/search/get/web"
    private const val NETEASE_LYRIC = "https://music.163.com/api/song/lyric"
    private const val NETEASE_REFERER = "https://music.163.com/"

    private fun searchNetease(keyword: String): List<LyricCandidate> {
        val json = Http.postForm(
            NETEASE_SEARCH,
            mapOf(
                "s" to keyword,
                "type" to "1",
                "limit" to "10",
                "offset" to "0",
                "total" to "true",
            ),
            referer = NETEASE_REFERER,
        )
        val songs = json.optObject("result")?.optArray("songs") ?: JsonArray()
        return songs.mapNotNull { e ->
            if (!e.isJsonObject) return@mapNotNull null
            val o = e.asJsonObject
            val id = o.get("id").optLong()
            if (id <= 0) return@mapNotNull null
            LyricCandidate(
                source = NETEASE,
                remoteId = id.toString(),
                title = o.optString("name") ?: "",
                artist = (o.optArray("artists") ?: JsonArray())
                    .mapNotNull { if (it.isJsonObject) it.asJsonObject.optString("name") else null }
                    .joinToString(" / "),
                durationMs = o.get("duration").optInt(),
            )
        }
    }

    private fun fetchNetease(id: String): List<LyricLine> {
        val json = Http.getJson("$NETEASE_LYRIC?id=$id&lv=1&kv=1&tv=-1", referer = NETEASE_REFERER)
        val raw = json.optObject("lrc")?.optString("lyric") ?: ""
        if (raw.isBlank()) return emptyList()
        return Lrc.parseLrc(raw)
    }

    // ---------------- QQ 音乐 ----------------

    private const val QQ_SEARCH = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val QQ_REFERER = "https://y.qq.com/"
    /** QQ 取词需要浏览器 UA(实测 Firefox/115 可用) */
    private const val QQ_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Firefox/115.0"

    private fun searchQq(keyword: String): List<LyricCandidate> {
        val body = JsonObject().apply {
            add("comm", JsonObject().apply {
                addProperty("ct", "19")
                addProperty("cv", "1859")
                addProperty("uin", "0")
            })
            add("req", JsonObject().apply {
                addProperty("method", "DoSearchForQQMusicDesktop")
                addProperty("module", "music.search.SearchCgiService")
                add("param", JsonObject().apply {
                    addProperty("grp", 1)
                    addProperty("num_per_page", 10)
                    addProperty("page_num", 1)
                    addProperty("query", keyword)
                    addProperty("search_type", 0)
                })
            })
        }
        val json = Http.postJson(
            QQ_SEARCH,
            body.toString(),
            referer = QQ_REFERER,
            extraHeaders = mapOf("User-Agent" to QQ_UA),
        )
        // 实测响应路径(对象式 req):data.req.data.body.song.list[](数组式请求则为 req_0)
        val songs = json.optObject("req")
            ?.optObject("data")
            ?.optObject("body")
            ?.optObject("song")
            ?.optArray("list") ?: JsonArray()
        return songs.mapNotNull { e ->
            if (!e.isJsonObject) return@mapNotNull null
            val o = e.asJsonObject
            val mid = o.optString("mid") ?: return@mapNotNull null
            LyricCandidate(
                source = QQ,
                remoteId = mid,
                title = o.optString("name") ?: "",
                artist = (o.optArray("singer") ?: JsonArray())
                    .mapNotNull { if (it.isJsonObject) it.asJsonObject.optString("name") else null }
                    .joinToString(" / "),
                durationMs = o.get("interval").optInt() * 1000,
            )
        }
    }

    private fun fetchQq(mid: String): List<LyricLine> {
        val url = "https://i.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=$mid&g_tk=5381&format=json&inCharset=utf8&outCharset=utf8&nobase64=1"
        val json = Http.getJson(url, referer = QQ_REFERER, extraHeaders = mapOf("User-Agent" to QQ_UA))
        val raw = json.optString("lyric") ?: ""
        if (raw.isBlank()) return emptyList()
        return Lrc.parseLrc(htmlUnescape(raw))
    }

    // ---------------- 酷狗 ----------------

    /** 酷狗移动端搜索 UA(mobilecdn 需专属 UA,实测可用) */
    private val KUGOU_HEADERS = mapOf(
        "User-Agent" to "IPhone-8990-searchSong",
        "UNI-UserAgent" to "iOS11.4-Phone8990-1009-0-WiFi",
    )

    private fun searchKugou(keyword: String): List<LyricCandidate> {
        val enc = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name())
        val url = "http://mobilecdn.kugou.com/api/v3/search/song" +
            "?api_ver=1&area_code=1&correct=1&pagesize=10&plat=2&tag=1&sver=5&showtype=10&page=1&keyword=$enc&version=8990"
        val json = Http.getJson(url, extraHeaders = KUGOU_HEADERS)
        val info = json.optObject("data")?.optArray("info") ?: JsonArray()
        return info.mapNotNull { e ->
            if (!e.isJsonObject) return@mapNotNull null
            val o = e.asJsonObject
            val hash = o.optString("hash") ?: return@mapNotNull null
            LyricCandidate(
                source = KUGOU,
                remoteId = hash,
                title = o.optString("songname") ?: "",
                artist = o.optString("singername") ?: "",
                durationMs = o.get("duration").optInt() * 1000,
            )
        }
    }

    /** 酷狗两步取词:hash → candidates[0]{id,accesskey} → Base64 LRC */
    private fun fetchKugou(hash: String): List<LyricLine> {
        val metaUrl = "http://krcs.kugou.com/search?keyword=%20-%20&ver=1&hash=$hash&client=mobi&man=yes"
        val meta = Http.getJson(metaUrl, extraHeaders = KUGOU_HEADERS)
        val candidate = meta.optArray("candidates")?.firstOrNull { it.isJsonObject }?.asJsonObject
            ?: throw IOException("酷狗无歌词数据")
        val id = candidate.optString("id") ?: throw IOException("酷狗无歌词数据")
        val accessKey = candidate.optString("accesskey") ?: throw IOException("酷狗无歌词数据")
        val dlUrl = "http://lyrics.kugou.com/download?charset=utf8&accesskey=$accessKey&id=$id&client=mobi&fmt=lrc&ver=1"
        val dl = Http.getJson(dlUrl, extraHeaders = KUGOU_HEADERS)
        val content = dl.optString("content") ?: throw IOException("酷狗歌词内容为空")
        val lrc = String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8)
        return Lrc.parseLrc(lrc)
    }

    // ---------------- 工具 ----------------

    /** QQ 歌词文本里的 HTML 实体反转义(&amp; &#39; &quot; &lt; &gt;) */
    private fun htmlUnescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
