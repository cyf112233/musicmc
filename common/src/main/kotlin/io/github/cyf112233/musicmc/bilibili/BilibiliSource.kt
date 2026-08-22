package io.github.cyf112233.musicmc.bilibili

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.cyf112233.musicmc.api.MusicSource
import io.github.cyf112233.musicmc.model.LyricLine
import io.github.cyf112233.musicmc.model.Playlist
import io.github.cyf112233.musicmc.model.Song
import io.github.cyf112233.musicmc.model.SongUrl
import io.github.cyf112233.musicmc.util.Async
import io.github.cyf112233.musicmc.client.UiText
import java.io.IOException
import java.util.concurrent.ExecutorService

/**
 * Bilibili 音源(匿名,无需登录)。把视频当作"歌曲"播放:搜索视频 / 排行榜列表 → bvid 即歌曲 id,
 * songUrl 经 view(取第一 P cid)+ playurl(取 DASH 音频流)得到 fMP4 直链,
 * 由 FfmpegAudioEngine(avformat+avcodec)解码 AAC-LC。
 * 歌词来自视频的 CC 字幕(UP 主上传的字幕;当前 B 站 UGC 绝大多数无 CC,此时返回空列表)。
 *
 * 2026-08 本机 curl 实测:
 * - 搜索:data.result[]{ bvid, title(含 <em class="keyword">,需 strip+unescape),
 *   author, duration("M:SS"/"H:MM:SS"), pic("//..." 需补 https:) };
 * - 排行榜:data.list[]{ bvid, title(纯文本), owner.name, duration(秒,number), pic(http:// 完整) };
 * - playurl:dash.audio[] 匿名可用,dolby/flac 匿名为 null;流选择 dolby[0] → flac → audio.find{id==30280} → audio[0]。
 * - 字幕:player/v2 的 data.subtitle.subtitles[] 恒为数组;无 CC 字幕时为空(实测 350+ 视频全空)。
 */
class BilibiliSource(
    private val executor: ExecutorService = Async.executor,
) : MusicSource {

    override val id: String = "bilibili"
    override val displayName: String = UiText.t("哔哩哔哩", "Bilibili")

    /** 首页固定排行榜(rid 已实测可用:/x/web-interface/ranking/v2?rid=1/3/5 均返回 code=0) */
    private val homeRanks = listOf(
        Playlist("1", UiText.t("全站排行榜", "Overall Rankings"), null, emptyList()),
        Playlist("3", UiText.t("音乐排行榜", "Music Rankings"), null, emptyList()),
        Playlist("5", UiText.t("娱乐排行榜", "Entertainment Rankings"), null, emptyList()),
    )

    private val rankNames = mapOf(
        "1" to UiText.t("全站排行榜", "Overall Rankings"),
        "3" to UiText.t("音乐排行榜", "Music Rankings"),
        "5" to UiText.t("娱乐排行榜", "Entertainment Rankings"),
        "129" to UiText.t("舞蹈排行榜", "Dance Rankings"),
        "4" to UiText.t("游戏排行榜", "Game Rankings"),
    )

    // ---------------- search ----------------

    override fun search(keyword: String, limit: Int, offset: Int, callback: (List<Song>, String?) -> Unit) {
        executor.execute {
            try {
                // B 站搜索按页分页:offset/limit 换算页码
                val page = (offset / maxOf(limit, 1)) + 1
                val json = BiliHttp.search(keyword, page, limit)
                val result = json.optObject("data")?.optArray("result") ?: JsonArray()
                // 搜索 data.result 里可能混有非视频对象(如 banner),bvid 缺失的丢弃
                val songs = result.mapNotNull { e ->
                    if (e.isJsonObject) parseSong(e.asJsonObject) else null
                }
                callback(songs, null)
            } catch (e: Exception) {
                callback(emptyList(), e.message ?: UiText.t("网络错误", "Network error"))
            }
        }
    }

    // ---------------- song url ----------------

    override fun songUrl(song: Song, bitrate: Int, callback: (SongUrl?, String?) -> Unit) {
        executor.execute {
            try {
                val bvid = song.id
                // 1) view 取第一 P cid
                val view = BiliHttp.view(bvid)
                val data = view.optObject("data") ?: throw IOException(UiText.t("视频信息为空", "Video info is empty"))
                val cid = data.get("cid").optLong()
                if (cid <= 0) throw IOException(UiText.t("视频信息缺少 cid", "Video info missing cid"))
                // 2) playurl 取 DASH 音频流
                val pl = BiliHttp.playurl(bvid, cid.toString())
                val dash = pl.optObject("data")?.optObject("dash")
                    ?: throw IOException(UiText.t("该视频无独立音频流", "No standalone audio stream for this video"))
                val audios = dash.optArray("audio") ?: JsonArray()
                if (audios.size() == 0) throw IOException(UiText.t("该视频无独立音频流", "No standalone audio stream for this video"))
                val selected = pickAudio(dash, audios)
                val baseUrl = selected.optString("baseUrl")
                if (baseUrl.isNullOrBlank()) throw IOException(UiText.t("播放地址为空", "Play URL is empty"))
                val backups = selected.optArray("backupUrl")?.mapNotNull {
                    // 防御:backupUrl 数组若混入非字符串元素,asString 会抛
                    // IllegalStateException 使整条 songUrl 失败 —— 明明 baseUrl 可用
                    // 却被丢弃;逐项判 isJsonPrimitive 再取
                    if (it.isJsonPrimitive) it.asString else null
                } ?: emptyList()
                callback(
                    SongUrl(
                        url = baseUrl,
                        referer = "https://www.bilibili.com/",
                        backupUrls = backups,
                    ),
                    null,
                )
            } catch (e: Exception) {
                callback(null, e.message ?: UiText.t("播放失败", "Playback failed"))
            }
        }
    }

    /**
     * 音频流选择优先级(登录后生效):dolby.audio[0] > flac.audio > audio.find{id==30280} > audio[0]。
     * 2026-08 实测匿名 dolby/flac 均为 null(登录+大会员后才可能非空),故实际落到
     * audio 数组按 id==30280 优先(约 177kbps AAC-LC 48kHz 双声道),否则 audio[0]。
     */
    private fun pickAudio(dash: JsonObject, audios: JsonArray): JsonObject {
        dash.optObject("dolby")?.optArray("audio")?.let { if (it.size() > 0) return it.get(0).asJsonObject }
        dash.optObject("flac")?.optArray("audio")?.let { if (it.size() > 0) return it.get(0).asJsonObject }
        audios.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("id").optInt() == 30280
        }?.let { return it.asJsonObject }
        return audios.get(0).asJsonObject
    }

    // ---------------- lyric(CC 字幕) ----------------

    /**
     * 歌词 = 视频 CC 字幕:
     * 1) view(bvid) 拿第一 P cid;
     * 2) player/v2(bvid, cid) 拿 data.subtitle.subtitles[](防御:null/空数组);
     * 3) 中文优先(lan 含 "zh" 或 lan_doc 含 "中"),否则取第一项;
     * 4) subtitle_url(可能相对路径)补全 → GET(带 Referer/UA);
     * 5) body[]{from,to,content} 映射为 LyricLine(timeMs=秒×1000,text=content 去空白)。
     *
     * 无 CC 字幕:callback(emptyList, "该视频无 CC 字幕");任何失败 callback(emptyList, err)。
     */
    override fun lyric(songId: String, callback: (List<LyricLine>, String?) -> Unit) {
        executor.execute {
            try {
                val bvid = songId
                // 1) view 取第一 P cid
                val view = BiliHttp.view(bvid)
                val data = view.optObject("data") ?: throw IOException(UiText.t("视频信息为空", "Video info is empty"))
                val cid = data.get("cid").optLong()
                if (cid <= 0) throw IOException(UiText.t("视频信息缺少 cid", "Video info missing cid"))
                // 2) 字幕列表(防御:data.subtitle 恒为对象,subtitles 可能为 null/空数组)
                val subtitles = BiliHttp.subtitle(bvid, cid.toString())
                    .optObject("data")?.optObject("subtitle")?.optArray("subtitles")
                    ?: JsonArray()
                if (subtitles.size() == 0) {
                    callback(emptyList(), UiText.t("该视频无 CC 字幕", "No CC subtitles for this video"))
                    return@execute
                }
                // 3) 中文优先,否则第一项(防御:subtitles 元素非对象时丢弃,不崩溃)
                val selected = (subtitles.firstOrNull { e ->
                    if (e.isJsonObject) {
                        val o = e.asJsonObject
                        val lan = o.optString("lan") ?: ""
                        val doc = o.optString("lan_doc") ?: ""
                        lan.lowercase().contains("zh") || doc.contains("中")
                    } else false
                } ?: subtitles.firstOrNull { it.isJsonObject })?.asJsonObject
                    ?: throw IOException(UiText.t("字幕数据异常", "Invalid subtitle data"))
                val rawUrl = selected.optString("subtitle_url")
                    ?: throw IOException(UiText.t("字幕地址为空", "Subtitle URL is empty"))
                // 4) 拉取字幕 JSON(subtitle_url 可能为协议相对 // 或绝对路径 /x/...)
                val subJson = BiliHttp.subtitleContent(normalizeSubtitleUrl(rawUrl))
                // 5) body[] → LyricLine。B 站 CC 字幕接口文档约定 from 以秒为单位
                //    (aisubtitle JSON 规范,实测一致);按秒 ×1000 转毫秒。
                //    注:不用"from>1000 视为毫秒"的启发式 —— 长视频(>16.7 分钟)的
                //    秒数会超过 1000,启发式会把整首歌词时间轴标错(确定性数据错误)。
                val body = subJson.optArray("body") ?: JsonArray()
                val lines = body.mapNotNull { e ->
                    if (!e.isJsonObject) return@mapNotNull null
                    val o = e.asJsonObject
                    val from = o.get("from").takeIf { it.isJsonPrimitive }?.asDouble
                        ?: return@mapNotNull null
                    val content = o.optString("content") ?: return@mapNotNull null
                    LyricLine(
                        timeMs = (from * 1000).toInt().coerceAtLeast(0),
                        text = content.trim(),
                    )
                }
                callback(lines, null)
            } catch (e: Exception) {
                callback(emptyList(), e.message ?: UiText.t("歌词获取失败", "Failed to fetch lyrics"))
            }
        }
    }

    /** 字幕地址补全:"//..." → "https://...";"/..." → "https://api.bilibili.com/..." */
    private fun normalizeSubtitleUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://api.bilibili.com$url"
        else -> url
    }

    // ---------------- playlist ----------------

    override fun playlistDetail(playlistId: String, callback: (Playlist, String?) -> Unit) {
        executor.execute {
            try {
                val json = BiliHttp.ranking(playlistId)
                val list = json.optObject("data")?.optArray("list") ?: JsonArray()
                val songs = list.mapNotNull { e ->
                    if (e.isJsonObject) parseSong(e.asJsonObject) else null
                }
                callback(
                    Playlist(
                        id = playlistId,
                        name = rankNames[playlistId] ?: UiText.t("哔哩哔哩排行榜", "Bilibili Rankings"),
                        coverUrl = null,
                        songs = songs,
                    ),
                    null,
                )
            } catch (e: Exception) {
                callback(Playlist(playlistId, rankNames[playlistId] ?: UiText.t("哔哩哔哩排行榜", "Bilibili Rankings"), null, emptyList()), e.message ?: UiText.t("网络错误", "Network error"))
            }
        }
    }

    override fun homePlaylists(callback: (List<Playlist>, String?) -> Unit) {
        executor.execute { callback(homeRanks, null) }
    }

    // ---------------- 解析 ----------------

    /** 解析搜索 / 排行榜条目为 Song(id=bvid)。字段对搜索与排行榜都兼容。 */
    private fun parseSong(o: JsonObject): Song? {
        val bvid = o.optString("bvid") ?: return null
        return Song(
            id = bvid,
            title = stripHtml(o.optString("title") ?: UiText.t("未知视频", "Unknown video")),
            // 搜索是 author 字符串,排行榜是 owner.name;
            // 注意:author 存在但为空串时也要回退 owner.name(空串非 null,?: 不会触发)
            artist = (o.optString("author")?.takeIf { it.isNotBlank() })
                ?: o.optObject("owner")?.optString("name")
                ?: "",
            album = UiText.t("哔哩哔哩", "Bilibili"),
            picUrl = normalizePic(o.optString("pic")),
            durationMs = parseDurationMs(o),
        )
    }

    /**
     * 时长解析:搜索 "M:SS"(M 可 >60)或 "H:MM:SS";排行榜为 number(秒)。
     * 统一转毫秒。
     */
    private fun parseDurationMs(o: JsonObject): Int {
        val d = o.get("duration")
        if (d != null && d.isJsonPrimitive) {
            val p = d.asJsonPrimitive
            if (p.isNumber) {
                return p.asInt.coerceAtLeast(0) * 1000
            }
            val parts = p.asString.split(':').mapNotNull { it.toIntOrNull() }
            return when (parts.size) {
                2 -> (parts[0] * 60 + parts[1]).coerceAtLeast(0) * 1000
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).coerceAtLeast(0) * 1000
                else -> 0
            }
        }
        return 0
    }

    /** 去掉 <em class="keyword"> 等 HTML 标签并反转义 &amp; &lt; &gt; &quot; &#39; &nbsp; */
    private fun stripHtml(s: String): String {
        var t = s.replace(TAG_REGEX, "")
        t = t.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        return t.trim()
    }

    private companion object {
        /** 标签正则:每次调用复用一个实例(避免每首歌现场编译) */
        val TAG_REGEX = Regex("<[^>]*>")
    }

    /** 封面:""//..." 补 https:;hdslb 存档图附缩略参数(可选,减小流量) */
    private fun normalizePic(pic: String?): String? {
        if (pic.isNullOrBlank()) return null
        val u = if (pic.startsWith("//")) "https:$pic" else pic
        return if (u.contains("hdslb.com/bfs/archive/") && !u.contains("@")) {
            "$u@672w_378h_1c"
        } else {
            u
        }
    }
}
