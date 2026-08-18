package io.github.cyf112233.musicmc.net

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 通用 HTTP 客户端封装(供图片加载、流打开与第三方歌词源使用;无登录,不管理 cookie)。
 *
 * 统一带上与 curl 验证一致的请求头:
 *   User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/131
 *   Referer: https://www.bilibili.com/
 *
 * 说明:网易云 era 的 weapi/eapi 加密、会话 cookie 管理、播放地址 probe
 * 校验链均已随网易云音源移除;B 站接口统一走 [io.github.cyf112233.musicmc.bilibili.BiliHttp]
 * (含 wbi 签名与登录 cookie),本对象保留通用 GET/POST/PUT 与流下载能力,
 * 供图片加载、流打开以及 lyrics 包(网易云/QQ音乐/酷狗/Hub)使用。
 */
object Http {

    // 较新指纹(Chrome 131 / Edge 131)
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"

    /** 默认 Referer:哔哩哔哩(唯一音源;CDN 图片 / 音频流需要) */
    private const val REFERER = "https://www.bilibili.com/"

    private val gson = Gson()

    /** 通用客户端:跟随重定向、连接超时 15s */
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    /**
     * 音频流客户端:独立实例,不设请求超时(修复长音频流 90s 超时问题),
     * 只设连接超时 15s;保留跟随重定向(音频直链 / 302 都需要)。
     */
    private val streamClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    private fun checkStatus(status: Int, url: String) {
        if (status !in 200..299) {
            throw IOException("HTTP $status for $url")
        }
    }

    private fun parseJson(body: String, url: String): JsonObject {
        val element = JsonParser.parseString(body)
        return if (element.isJsonObject) element.asJsonObject
        else throw IOException("响应不是 JSON 对象: $url")
    }

    /** 基础请求头;referer 非空时覆盖默认 Referer;extraHeaders 覆盖同名头(如歌词源的专属 UA) */
    private fun baseHeaders(referer: String? = null, extraHeaders: Map<String, String> = emptyMap()): Array<String> {
        val list = mutableListOf<String>()
        list += "User-Agent"
        list += extraHeaders["User-Agent"] ?: USER_AGENT
        list += "Referer"
        list += extraHeaders["Referer"] ?: (referer ?: REFERER)
        for ((k, v) in extraHeaders) {
            if (k == "User-Agent" || k == "Referer") continue
            list += k
            list += v
        }
        return list.toTypedArray()
    }

    // ---------------- JSON endpoints ----------------

    /**
     * GET 请求并解析为 JsonObject;非 2xx 或解析失败抛 IOException。
     * [referer] 非空时覆盖默认 Referer(如网易云歌词接口需 https://music.163.com/)。
     * [extraHeaders] 覆盖同名头(如酷狗搜索需专属 UA / UNI-UserAgent)。
     */
    fun getJson(url: String, referer: String? = null, extraHeaders: Map<String, String> = emptyMap()): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .headers(*baseHeaders(referer, extraHeaders))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        checkStatus(response.statusCode(), url)
        return parseJson(response.body(), url)
    }

    /**
     * POST application/x-www-form-urlencoded 并解析为 JsonObject。
     * [referer] 非空时覆盖默认 Referer(如网易云歌词接口需 https://music.163.com/)。
     */
    fun postForm(
        url: String,
        params: Map<String, String>,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .headers(*baseHeaders(referer, extraHeaders))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        checkStatus(response.statusCode(), url)
        return parseJson(response.body(), url)
    }

    /** POST application/json 并解析为 JsonObject(如 QQ 音乐 musicu.fcg 歌词搜索) */
    fun postJson(
        url: String,
        jsonBody: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject = sendJson("POST", url, jsonBody, referer, extraHeaders)

    /** PUT application/json 并解析为 JsonObject(如自建歌词 Hub 的 lyrics/:key 上传) */
    fun putJson(
        url: String,
        jsonBody: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject = sendJson("PUT", url, jsonBody, referer, extraHeaders)

    private fun sendJson(
        method: String,
        url: String,
        jsonBody: String,
        referer: String?,
        extraHeaders: Map<String, String>,
    ): JsonObject {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json;charset=utf-8")
            .headers(*baseHeaders(referer, extraHeaders))
        val request = if (method == "PUT") {
            builder.PUT(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build()
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build()
        }
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        checkStatus(response.statusCode(), url)
        return parseJson(response.body(), url)
    }

    // ---------------- stream ----------------

    /**
     * 打开一个响应体流(用于音频流 / 图片)。
     * 走独立 [streamClient]:无请求超时,长音频不会 90s 被掐断;调用方负责 close()。
     * [referer] 非空时用该 Referer(如 B 站 CDN);null 用默认 https://www.bilibili.com/。
     */
    fun openStream(url: String, referer: String? = null): InputStream = openStreamInfo(url, -1, referer).body

    /**
     * 打开一个响应体流并附带流信息(用于 Range 定位 seek)。
     *
     * 走独立 [streamClient](无请求超时,长音频不会 90s 被掐断)。
     * - 200:服务器返回完整流(忽略 Range),[StreamInfo.partial]=false,总大小取 Content-Length;
     * - 206:服务器支持 Range 且已定位到 [rangeStart],[StreamInfo.partial]=true,
     *   总大小从 Content-Range "bytes N-M/TOTAL" 解析(TOTAL 缺失或为 * 时 totalSize=-1);
     * - 416:请求的起始字节超出文件范围,抛 IOException("范围越界")。
     *
* [rangeStart]>=0 时发 Range 头;[rangeEnd]>0 时附加终点(半开区间语义为
 * "bytes=start-end" 的闭区间,即最多读到 end 字节;FfmpegDecoder 的 seek 回调用它
 * 按 offset 重开 Range 流定位)。默认 -1 = 开到文件结尾。
     * [referer] 非空时用该 Referer(如 B 站 CDN);null 用默认 https://www.bilibili.com/。
     * 调用方负责 close() 响应体流。
     */
    fun openStreamInfo(url: String, rangeStart: Long = -1, referer: String? = null, rangeEnd: Long = -1): StreamInfo {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .headers(*baseHeaders(referer))
            .GET()
        if (rangeStart >= 0) {
            builder.header("Range", "bytes=$rangeStart-${if (rangeEnd > 0) rangeEnd else ""}")
        }
        val response = streamClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        return when (response.statusCode()) {
            200 -> StreamInfo(
                body = response.body(),
                totalSize = response.headers().firstValue("Content-Length")
                    .orElse(null)?.toLongOrNull() ?: -1,
                partial = false,
            )
            206 -> StreamInfo(
                body = response.body(),
                totalSize = response.headers().firstValue("Content-Range")
                    .orElse(null)?.substringAfter('/')
                    ?.trim()
                    ?.takeIf { it != "*" && it.isNotEmpty() }
                    ?.toLongOrNull() ?: -1,
                partial = true,
            )
            416 -> {
                // 先关掉响应体再抛,避免泄漏连接
                runCatching { response.body().close() }
                throw IOException("范围越界")
            }
            else -> {
                runCatching { response.body().close() }
                throw IOException("HTTP ${response.statusCode()} for $url")
            }
        }
    }
}

/** 打开的流的信息(openStreamInfo 的返回值) */
data class StreamInfo(
    val body: InputStream,
    /** 流总字节数;Content-Length/Content-Range 缺失时为 -1 */
    val totalSize: Long,
    /** true=服务器返回了 206 部分流(已按 Range 定位);false=完整流 */
    val partial: Boolean,
)
