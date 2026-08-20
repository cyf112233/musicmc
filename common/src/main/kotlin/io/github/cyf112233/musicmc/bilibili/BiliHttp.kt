package io.github.cyf112233.musicmc.bilibili

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.cyf112233.musicmc.model.Song
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate

/**
 * Bilibili 公开 web API 的 HTTP 客户端(支持可选登录态)。
 *
 * 2026-08 本机实测:
 * - 搜索 /x/web-interface/wbi/search/type 带 wbi 签名更稳(无签名可能触发 v_voucher 风控);
 * - 详情 /x/web-interface/view?bvid= 无需签名;
 * - 播放地址 /x/player/wbi/playurl 签名更稳,未签名也可用(实测 code=0);
 * - 字幕信息 /x/player/wbi/v2 未签名即可用(实测 code=0;data.subtitle.subtitles 为空数组
 *   表示无 CC 字幕——当前 B 站 UGC 内容几乎都不再有 CC 字幕);
 * - 排行榜 /x/web-interface/ranking/v2?rid= 无需签名;
 * - 匿名可用;不带 cookie 反而稳,遇风控(code=-403 / -412 / data.v_voucher)时刷
 *   buvid3/4(/x/frontend/finger/spi → data.b_3 / b_4)后重试一次。
 * - Web 扫码登录(passport 链路,BBPlayer 同款):
 *   generate 是 GET 且无参数(POST 会 405);poll 建议 2s 间隔,状态在 data.code
 *   (86101 未扫 / 86090 已扫未确认 / 0 成功 / 86038 过期,垃圾 key 也回 86038);
 *   成功(0)时登录 cookie 在响应 Set-Cookie 头(全量保存不挑);
 *   登录链路不需要 wbi/csrf/buvid3;passport 端点不带 Cookie,只带 UA+Referer。
 * - 登录后 SESSDATA 附加到所有 api.bilibili.com 请求(搜索个性化 + 防 -412 风控;
 *   playurl 的 dash.dolby.audio / dash.flac.audio 由登录 + 大会员决定是否非空)。
 * - 登录态检测:/x/web-interface/nav(带 cookie)→ isLogin + data.uname(昵称);
 *   匿名时 nav 返回 code=-101(不算错误,data.wbi_img 仍在,isLogin=false)。
 * - 收藏系列(需登录):
 *   · 收藏夹列表 GET /x/v3/fav/folder/created/list-all?up_mid=&rid=&type=2 —— 带 rid+type 时
 *     只返回"包含该视频"的夹且每项带 fav_state(1=存在);不带 rid 返回全部收藏夹,无 fav_state(防御取 0);
 *   · 收藏夹内容 GET /x/v3/fav/resource/list?media_id=&pn=&ps=20&platform=web → data.medias[];
 *   · 新建 GET /x/v3/fav/folder/add(form title+csrf,成功 data.fid)、
 *     添加/移除 POST /x/v3/fav/resource/deal(add_media_ids/del_media_ids + csrf)。
 *   本机实测:匿名时两个 GET 返回 code=0 且 data=null(一律按空列表防御解析);
 *   写操作未登录返回 code=-101("账号未登录")。
 *
 * 统一请求头:Chrome/120 桌面 UA + Referer/Origin https://www.bilibili.com/。
 * 业务 code != 0 抛 [IOException] 并带中文信息;网络失败同理。
 */
object BiliHttp {

    private const val BASE = "https://api.bilibili.com"

    /** passport 域名:扫码登录用,请求不带 Cookie(与 api.bilibili.com 行为区分) */
    private const val PASSPORT_BASE = "https://passport.bilibili.com"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val REFERER = "https://www.bilibili.com/"

    /** 独立客户端:无 cookie handler(默认不带 cookie),跟随重定向,连接超时 15s */
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    // ---- 登录 cookie(可选,setCookie 注入;全量保存不挑)----
    @Volatile
    private var loginCookies: Map<String, String> = emptyMap()

    // ---- 风控用 buvid(惰性获取)----
    @Volatile
    private var buvid3: String? = null
    @Volatile
    private var buvid4: String? = null

    // ---- wbi 密钥(按天缓存)----
    @Volatile
    private var wbiDay: String = ""
    @Volatile
    private var wbiImgKey: String? = null
    @Volatile
    private var wbiSubKey: String? = null

    // ---- nav 登录态缓存(5 分钟)----
    @Volatile
    private var navProfileCache: NavProfile? = null
    @Volatile
    private var navProfileCacheTime: Long = 0
    private const val NAV_PROFILE_CACHE_MS = 5 * 60_000L

    // ---------------- 登录 ----------------

    /**
     * 设置登录 cookie(如 "SESSDATA=..; bili_jct=..; DedeUserID=..; ...",全量保存不挑)。
     * 同时使 [navProfile] 的 5 分钟缓存失效,保证登录/退出后昵称立即刷新。
     */
    fun setCookie(cookieHeader: String) {
        val map = linkedMapOf<String, String>()
        for (part in cookieHeader.split(';')) {
            val idx = part.indexOf('=')
            if (idx > 0) {
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) map[name] = value
            }
        }
        loginCookies = map
        navProfileCache = null
    }

    /** 退出登录:仅清除本地 cookie(不调用服务端失效接口) */
    fun logout() {
        loginCookies = emptyMap()
        navProfileCache = null
    }

    /** 生成扫码登录二维码(GET 无参数;POST 会 405)。二维码内容 = [QrData.url] */
    fun qrGenerate(): QrData {
        val response = passportGet("/x/passport-login/web/qrcode/generate", emptyMap())
        if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()} for ${response.uri()}")
        val element = JsonParser.parseString(response.body())
        if (!element.isJsonObject) throw IOException("Failed to generate QR code: response error")
        val json = element.asJsonObject
        if (json.get("code").optInt() != 0) throw IOException("Failed to generate QR code (code=${json.get("code").optInt()})")
        val data = json.optObject("data") ?: throw IOException("Failed to generate QR code: missing data")
        val url = data.optString("url") ?: throw IOException("Failed to generate QR code: missing url")
        val key = data.optString("qrcode_key") ?: throw IOException("Failed to generate QR code: missing qrcode_key")
        return QrData(url, key)
    }

    /**
     * 轮询扫码状态。外层 code 恒 0,状态在 data.code:
     * 86101 未扫 / 86090 已扫未确认 / 0 成功(登录 cookie 在 Set-Cookie 头)/ 其余 过期(86038)。
     */
    fun qrPoll(key: String): QrPollResult {
        val response = passportGet("/x/passport-login/web/qrcode/poll", mapOf("qrcode_key" to key))
        if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()} for ${response.uri()}")
        val element = JsonParser.parseString(response.body())
        if (!element.isJsonObject) throw IOException("Polling response error")
        val json = element.asJsonObject
        val data = json.optObject("data")
        val code = data?.get("code")?.optInt() ?: -1
        return when (code) {
            0 -> QrPollResult(
                QrStatus.SUCCESS,
                parseSetCookies(response.headers().allValues("Set-Cookie")),
            )
            86101 -> QrPollResult(QrStatus.WAIT, null)
            86090 -> QrPollResult(QrStatus.SCANNED, null)
            else -> QrPollResult(QrStatus.EXPIRED, null)
        }
    }

    /**
     * 登录态检测(/x/web-interface/nav 带 cookie)→ [NavProfile.isLogin] + [NavProfile.uname] + [NavProfile.mid]。
     * 结果缓存 5 分钟(登录/退出经 [setCookie]/[logout] 失效缓存)。
     * 匿名时 nav 返回 code=-101 但 data 仍在,isLogin=false,不抛异常。
     */
    fun navProfile(): NavProfile {
        val now = System.currentTimeMillis()
        val cached = navProfileCache
        if (cached != null && now - navProfileCacheTime < NAV_PROFILE_CACHE_MS) return cached
        val json = get("/x/web-interface/nav", emptyMap())
        val data = json.optObject("data")
        val isLogin = data?.get("isLogin")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        val profile = NavProfile(isLogin, data?.optString("uname"), data?.get("mid")?.optLong()?.takeIf { it > 0 })
        navProfileCache = profile
        navProfileCacheTime = now
        return profile
    }

    // ---------------- 公开接口 ----------------

    /** 搜索视频(keyword/search_type=video/page/page_size) */
    fun search(keyword: String, page: Int, limit: Int): JsonObject {
        val json = wbiGet(
            "/x/web-interface/wbi/search/type",
            mapOf(
                "keyword" to keyword,
                "search_type" to "video",
                "page" to page.toString(),
                "page_size" to limit.toString(),
            ),
        )
        checkCode(json, "Search")
        return json
    }

    /** 视频详情(第一 P 的 cid / 时长等) */
    fun view(bvid: String): JsonObject {
        val json = get("/x/web-interface/view", mapOf("bvid" to bvid))
        checkCode(json, "Video info")
        return json
    }

    /**
     * 播放地址(带 wbi 签名;签名链路失败时回退未签名——实测未签名也可用)。
     * 参数与参考实现一致:fnval=4048(fMP4 DASH)、fnver=0、fourk=1、qlt=30280、voice_balance=1。
     */
    fun playurl(bvid: String, cid: String): JsonObject {
        val params = mapOf(
            "bvid" to bvid,
            "cid" to cid,
            "fnval" to "4048",
            "fnver" to "0",
            "fourk" to "1",
            "qlt" to "30280",
            "voice_balance" to "1",
        )
        val json = try {
            wbiGet("/x/player/wbi/playurl", params)
        } catch (e: Exception) {
            // wbi 获取密钥失败等场景:回退未签名请求
            get("/x/player/wbi/playurl", params)
        }
        checkCode(json, "Play URL")
        return json
    }

    /**
     * CC 字幕信息(带 wbi 签名;签名链路失败时回退未签名——实测未签名也可用)。
     * 响应 data.subtitle.subtitles[]{lan, lan_doc, subtitle_url};无字幕时为空数组。
     */
    fun subtitle(bvid: String, cid: String): JsonObject {
        val params = mapOf("bvid" to bvid, "cid" to cid)
        val json = try {
            wbiGet("/x/player/wbi/v2", params)
        } catch (e: Exception) {
            get("/x/player/wbi/v2", params)
        }
        checkCode(json, "Subtitle info")
        return json
    }

    /**
     * 拉取 CC 字幕内容 JSON(subtitle_url 指向的文件,通常位于 aisubtitle.hdslb.com 等 CDN,
     * 不能走 [get] 的 api.bilibili.com 前缀)。形状:
     * {"font_size":..., "body":[{"from":秒,"to":秒,"content":"..."}]}
     */
    fun subtitleContent(url: String): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()} for $url")
        }
        val element = JsonParser.parseString(response.body())
        if (!element.isJsonObject) throw IOException("Subtitle content is not a JSON object: $url")
        return element.asJsonObject
    }

    /** 排行榜(rid:1=全站 3=音乐 5=娱乐 129=舞蹈 4=游戏 等,2026-08 实测均可用) */
    fun ranking(rid: String): JsonObject {
        val json = get("/x/web-interface/ranking/v2", mapOf("rid" to rid))
        checkCode(json, "Rankings")
        return json
    }

    // ---------------- 点赞 / 收藏(需登录) ----------------

    /**
     * csrf 值:登录 cookie 中的 bili_jct(未登录 / 缺失返回 null)。
     * 点赞 / 收藏等写操作接口都要带 csrf=。
     */
    fun csrf(): String? = loginCookies["bili_jct"]

    /**
     * 当前登录用户 mid(供收藏夹列表与收藏状态查询):优先 cookie DedeUserID(扫码登录即有,
     * 免网络);否则经 nav(data.mid,5 分钟缓存)。未登录返回 null。
     */
    fun currentMid(): Long? {
        loginCookies["DedeUserID"]?.toLongOrNull()?.let { return it }
        return navProfile().mid
    }

    /**
     * 查询是否已赞(GET /x/web-interface/archive/has/like?bvid=)。data 为 0/1。
     * 注:该接口按 B 站文档语义查询"近期"点赞态,切歌 / 刚点赞后可能有短暂延迟。
     */
    fun hasLiked(bvid: String, cb: (Boolean, String?) -> Unit) {
        val json = try {
            get("/x/web-interface/archive/has/like", mapOf("bvid" to bvid))
        } catch (e: Exception) {
            cb(false, e.message ?: "Failed to check like status")
            return
        }
        codeError(json, "Check like status")?.let { cb(false, it); return }
        cb(json.get("data").optInt() == 1, null)
    }

    /**
     * 点赞 / 取消点赞(POST /x/web-interface/archive/like,form bvid/like/csrf)。
     * 实测(BBPlayer 源码)该接口直接支持 bvid 参数,无需先经 view 取 aid;
     * like=true 发送 like=1(点赞),false 发送 like=2(取消)。code==0 或 65006(重复点赞)成功。
     */
    fun like(bvid: String, like: Boolean, cb: (String?) -> Unit) {
        val token = csrf()
        if (token.isNullOrBlank()) { cb("Please log in to Bilibili in Settings first"); return }
        val json = try {
            post(
                "/x/web-interface/archive/like",
                mapOf("bvid" to bvid, "like" to if (like) "1" else "2", "csrf" to token),
            )
        } catch (e: Exception) {
            cb(e.message ?: "Failed to like")
            return
        }
        val code = json.get("code").optInt()
        if (code == 0 || code == 65006) cb(null)
        else cb(codeError(json, "Like") ?: "Failed to like")
    }

    /**
     * 收藏夹列表(GET /x/v3/fav/folder/created/list-all?up_mid=,需登录)。
     * [ridAid] 非空时附带 rid+type=2:只返回"包含该视频"的收藏夹,每项 fav_state(1=存在 0=不存在)
     * 即当前视频是否在该夹;为 null 时返回全部收藏夹,无 fav_state(防御取 false)。
     * 已实测:匿名时 code=0 且 data=null,防御按空列表处理。data.list[] 字段防御式解析:
     * id / title / media_count / attr,兼容可能出现的 fid 字段(仅作 FUTURE,当前以 id 为准)。
     */
    fun favFolderList(mid: Long, ridAid: Long? = null, cb: (List<FavFolder>, String?) -> Unit) {
        val params = mutableMapOf("up_mid" to mid.toString())
        if (ridAid != null) {
            params["rid"] = ridAid.toString()
            params["type"] = "2"
        }
        val json = try {
            get("/x/v3/fav/folder/created/list-all", params)
        } catch (e: Exception) {
            cb(emptyList(), e.message ?: "Failed to fetch favorites")
            return
        }
        codeError(json, "Fetch favorites")?.let { cb(emptyList(), it); return }
        // 匿名时 data=null(code=0),optObject 返回 null → 空列表
        val list = json.optObject("data")?.optArray("list") ?: JsonArray()
        val folders = list.mapNotNull { e ->
            if (e.isJsonObject) {
                val o = e.asJsonObject
                val id = o.get("id").optLong()
                val title = o.optString("title")
                if (id > 0 && title != null) {
                    FavFolder(
                        id = id,
                        title = title,
                        mediaCount = o.get("media_count").optInt(),
                        favState = o.get("fav_state").optInt() == 1,
                    )
                } else null
            } else null
        }
        cb(folders, null)
    }

    /**
     * 收藏夹内容(GET /x/v3/fav/resource/list?media_id=&pn=&ps=20&platform=web,需登录)。
     * data.medias[]{ id, title, cover, duration(秒), bvid, upper{name}, intro } → List<Song>。
     * 已实测:匿名时 code=0 且 data=null,防御按空列表处理;未登录调用方(BiliActions)已前置拦截。
     */
    fun favResourceList(fid: Long, pn: Int, cb: (List<Song>, String?) -> Unit) {
        val json = try {
            get(
                "/x/v3/fav/resource/list",
                mapOf(
                    "media_id" to fid.toString(),
                    "pn" to pn.toString(),
                    "ps" to "20",
                    "platform" to "web",
                ),
            )
        } catch (e: Exception) {
            cb(emptyList(), e.message ?: "Failed to fetch folder contents")
            return
        }
        codeError(json, "Fetch folder contents")?.let { cb(emptyList(), it); return }
        val medias = json.optObject("data")?.optArray("medias") ?: JsonArray()
        val songs = medias.mapNotNull { e ->
            if (e.isJsonObject) {
                val o = e.asJsonObject
                val bvid = o.optString("bvid") ?: return@mapNotNull null
                val cover = o.optString("cover")
                Song(
                    id = bvid,
                    title = o.optString("title") ?: "Unknown video",
                    artist = o.optObject("upper")?.optString("name") ?: "",
                    album = "Bilibili",
                    picUrl = if (cover != null && cover.startsWith("//")) "https:$cover" else cover,
                    durationMs = (o.get("duration").optInt().coerceAtLeast(0)) * 1000,
                )
            } else null
        }
        cb(songs, null)
    }

    /**
     * 新建收藏夹(POST /x/v3/fav/folder/add,form title+csrf,需登录)。成功返回 data.fid。
     * 已实测端点存在:未登录返回 code=-101("账号未登录")。
     */
    fun favCreateFolder(title: String, cb: (Long?, String?) -> Unit) {
        val token = csrf()
        if (token.isNullOrBlank()) { cb(null, "Please log in to Bilibili in Settings first"); return }
        val json = try {
            post("/x/v3/fav/folder/add", mapOf("title" to title, "csrf" to token))
        } catch (e: Exception) {
            cb(null, e.message ?: "Failed to create folder")
            return
        }
        val code = json.get("code").optInt()
        if (code == 0) {
            val fid = json.optObject("data")?.get("fid").optLong()
            if (fid > 0) cb(fid, null) else cb(null, "Failed to create folder: response missing fid")
        } else {
            cb(null, codeError(json, "Create folder") ?: "Failed to create folder")
        }
    }

    /**
     * 加入收藏夹(POST /x/v3/fav/resource/deal,form rid=aid/type=2/add_media_ids=fid/csrf)。
     * code==0 成功;90022(已在该收藏夹)按业务提示返回,不视为网络失败。
     */
    fun favAdd(aid: Long, fid: Long, cb: (String?) -> Unit) {
        val token = csrf()
        if (token.isNullOrBlank()) { cb("Please log in to Bilibili in Settings first"); return }
        val json = try {
            post(
                "/x/v3/fav/resource/deal",
                mapOf(
                    "rid" to aid.toString(),
                    "type" to "2",
                    "add_media_ids" to fid.toString(),
                    "csrf" to token,
                ),
            )
        } catch (e: Exception) {
            cb(e.message ?: "Failed to add favorite")
            return
        }
        when (val code = json.get("code").optInt()) {
            0 -> cb(null)
            90022 -> cb("Already in this folder")
            else -> cb(codeError(json, "Add favorite") ?: "Failed to add favorite")
        }
    }

    /**
     * 移出收藏夹(POST /x/v3/fav/resource/deal,form rid=aid/type=2/del_media_ids=fid/csrf,需登录)。
     * code==0 成功;其余业务码按中文提示返回。
     */
    fun favDel(rid: Long, fid: Long, cb: (String?) -> Unit) {
        val token = csrf()
        if (token.isNullOrBlank()) { cb("Please log in to Bilibili in Settings first"); return }
        val json = try {
            post(
                "/x/v3/fav/resource/deal",
                mapOf(
                    "rid" to rid.toString(),
                    "type" to "2",
                    "del_media_ids" to fid.toString(),
                    "csrf" to token,
                ),
            )
        } catch (e: Exception) {
            cb(e.message ?: "Failed to remove favorite")
            return
        }
        val code = json.get("code").optInt()
        if (code == 0) cb(null) else cb(codeError(json, "Remove favorite") ?: "Failed to remove favorite")
    }

    // ---------------- 内部实现 ----------------

    /**
     * wbi 签名 GET:遇风控(code=-403 / -412 / data.v_voucher 存在)时刷 buvid3/4 重试一次。
     */
    private fun wbiGet(path: String, params: Map<String, String>): JsonObject {
        val (imgKey, subKey) = ensureWbiKeys()
        var json = get(path, Wbi.sign(params, imgKey, subKey))
        if (isRiskControl(json)) {
            // 风控:带 buvid 重试一次(登录 cookie 始终携带,风控后再补 buvid)
            ensureBuvid()
            json = get(path, Wbi.sign(params, imgKey, subKey))
        }
        return json
    }

    private fun get(path: String, params: Map<String, String>): JsonObject {
        val query = params.entries.joinToString("&") { "${Wbi.encode(it.key)}=${Wbi.encode(it.value)}" }
        val url = if (query.isBlank()) "$BASE$path" else "$BASE$path?$query"
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Origin", REFERER)
        cookieHeader()?.let { builder.header("Cookie", it) }
        val response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()} for $url")
        }
        val element = JsonParser.parseString(response.body())
        if (!element.isJsonObject) throw IOException("Response is not a JSON object: $url")
        return element.asJsonObject
    }

    /**
     * api.bilibili.com 的 POST(form-urlencoded,带登录 cookie + UA + Referer + Origin)。
     * 业务 code != 0 不在此抛错(点赞需区分 65006、收藏需区分 90022),由调用方判断。
     */
    private fun post(path: String, form: Map<String, String>): JsonObject {
        val url = "$BASE$path"
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Origin", REFERER)
            .header("Content-Type", "application/x-www-form-urlencoded")
        cookieHeader()?.let { builder.header("Cookie", it) }
        val body = form.entries.joinToString("&") { "${Wbi.encode(it.key)}=${Wbi.encode(it.value)}" }
        val response = client.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()} for $url")
        }
        val element = JsonParser.parseString(response.body())
        if (!element.isJsonObject) throw IOException("Response is not a JSON object: $url")
        return element.asJsonObject
    }

    /**
     * passport 域名 GET(扫码登录用):只带 UA + Referer,不带 Cookie / Origin。
     * 返回原始响应(需要读 Set-Cookie 头)。
     */
    private fun passportGet(path: String, params: Map<String, String>): HttpResponse<String> {
        val query = params.entries.joinToString("&") { "${Wbi.encode(it.key)}=${Wbi.encode(it.value)}" }
        val url = if (query.isBlank()) "$PASSPORT_BASE$path" else "$PASSPORT_BASE$path?$query"
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    /** nav 接口取 wbi_img,按天缓存;文件名去扩展名作为 imgKey/subKey */
    private fun ensureWbiKeys(): Pair<String, String> {
        val today = LocalDate.now().toString().replace("-", "")
        val img = wbiImgKey
        val sub = wbiSubKey
        if (wbiDay == today && img != null && sub != null) return img to sub

        val json = get("/x/web-interface/nav", emptyMap())
        // 注:匿名时 nav 返回 code=-101,但 data.wbi_img 仍在——wbi 密钥只看 wbi_img,不要求 code==0
        val wbiImg = json.optObject("data")?.optObject("wbi_img")
        val imgUrl = wbiImg?.optString("img_url")
        val subUrl = wbiImg?.optString("sub_url")
        if (imgUrl.isNullOrBlank() || subUrl.isNullOrBlank()) {
            throw IOException("Failed to get wbi key: response missing wbi_img")
        }
        wbiImgKey = filenameWithoutExtension(imgUrl)
        wbiSubKey = filenameWithoutExtension(subUrl)
        wbiDay = today
        return wbiImgKey!! to wbiSubKey!!
    }

    /** /x/frontend/finger/spi 刷 buvid3/buvid4(仅风控时调用) */
    private fun ensureBuvid() {
        try {
            val json = get("/x/frontend/finger/spi", emptyMap())
            val data = json.optObject("data")
            val b3 = data?.optString("b_3")
            val b4 = data?.optString("b_4")
            if (!b3.isNullOrBlank()) buvid3 = b3
            if (!b4.isNullOrBlank()) buvid4 = b4
        } catch (_: Exception) {
            // 刷 buvid 失败静默(仍走无 cookie 请求)
        }
    }

    /** 合并登录 cookie 与风控 buvid;两者都无时返回 null(请求不带 Cookie 头) */
    private fun cookieHeader(): String? {
        val parts = mutableListOf<String>()
        if (loginCookies.isNotEmpty()) {
            parts += loginCookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        }
        val b3 = buvid3
        if (b3 != null) {
            val b4 = buvid4
            parts += if (b4 != null) "buvid3=$b3; buvid4=$b4" else "buvid3=$b3"
        }
        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }

    /** 从 Set-Cookie 响应头提取 "name=value" 并合并(每个头取分号前第一段;全量保存不挑) */
    private fun parseSetCookies(setCookies: List<String>): String =
        setCookies.mapNotNull { sc ->
            val pair = sc.substringBefore(';').trim()
            if (pair.contains('=')) pair else null
        }.joinToString("; ")

    private fun isRiskControl(json: JsonObject): Boolean {
        val code = json.get("code").takeIf { it.isJsonPrimitive }?.asInt
        if (code == -403 || code == -412) return true
        val data = json.get("data")
        if (data != null && data.isJsonObject && data.asJsonObject.get("v_voucher") != null) {
            val vv = data.asJsonObject.get("v_voucher")
            if (!vv.isJsonNull) return true
        }
        return false
    }

    private fun checkCode(json: JsonObject, what: String) {
        val code = json.get("code").takeIf { it.isJsonPrimitive }?.asInt ?: -1
        if (code != 0) {
            val msg = json.get("message").takeIf { it.isJsonPrimitive }?.asString
            throw IOException("${what} failed (code=$code)${if (msg.isNullOrBlank()) "" else ": $msg"}")
        }
    }

    /**
     * 业务 code != 0 时返回中文错误消息(不抛异常),code==0 返回 null。
     * 供需要区分特殊业务码(65006 重复点赞 / 90022 已在收藏夹)的调用方使用。
     */
    private fun codeError(json: JsonObject, what: String): String? {
        val code = json.get("code").takeIf { it.isJsonPrimitive }?.asInt ?: -1
        if (code == 0) return null
        val msg = json.get("message").takeIf { it.isJsonPrimitive }?.asString
        return "${what} failed (code=$code)${if (msg.isNullOrBlank()) "" else ": $msg"}"
    }

    private fun filenameWithoutExtension(url: String): String =
        url.substringAfterLast('/').substringBeforeLast('.').takeIf { it.isNotEmpty() } ?: url
}

// ---------------- 登录相关数据类型 ----------------

/** 扫码登录二维码(generate 返回;二维码内容 = [url]) */
data class QrData(
    val url: String,
    val qrcodeKey: String,
)

/** 扫码状态(按 poll 的 data.code 语义映射) */
enum class QrStatus { WAIT, SCANNED, SUCCESS, EXPIRED }

/** 轮询结果:成功时 [cookieHeader] 为 Set-Cookie 合并后的登录 cookie 字符串 */
data class QrPollResult(
    val status: QrStatus,
    val cookieHeader: String?,
)

/** nav 登录态:isLogin + uname(昵称;未登录为 null)+ mid(用户 uid;未登录为 null) */
data class NavProfile(
    val isLogin: Boolean,
    val uname: String?,
    val mid: Long? = null,
)

/**
 * 一个收藏夹(收藏夹列表 data.list 的一项,防御式解析):
 * [id] 收藏夹 id(实测字段为 id;FUTURE:个别响应可能用 fid,已兼容解析)、[title] 名称、
 * [mediaCount] 内容数(media_count)、[favState] 当前视频是否在其中(仅带 rid 查询时有意义,否则恒 false)。
 */
data class FavFolder(
    val id: Long,
    val title: String,
    val mediaCount: Int,
    val favState: Boolean,
)

// ---------------- JSON 防御式取值 ----------------

internal fun JsonObject?.optString(key: String): String? =
    this?.get(key)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonElement?.optInt(): Int =
    this?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

internal fun JsonElement?.optLong(): Long =
    this?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

internal fun JsonObject?.optArray(key: String): JsonArray? =
    this?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray

internal fun JsonObject?.optObject(key: String): JsonObject? =
    this?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
