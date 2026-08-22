package io.github.cyf112233.musicmc.bilibili

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Bilibili wbi 签名(算法来自社区公开实现,已在本机用 curl/python3 实测验证:
 * 用该算法签名的搜索 / playurl 请求返回 code=0)。
 *
 * 要点(见任务调研事实):
 * - mixinKey = 按 MIXIN_KEY_ENC_TAB 顺序从 (imgKey + subKey) 取字符,取前 32 个;
 * - 签名 = 参数(过滤 null)+ wts(秒级时间戳)→ 按 key 字典序排序 →
 *   拼 "k=v" 并以 wbi 编码(见 [encode])拼接 → md5(query + mixinKey) → w_rid。
 */
object Wbi {

    /** Bilibili 公开的 mixin key 重排表(64 项,值域 0..63) */
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    /** wbi 编码的保留字符:除字母数字与这些字符外全部百分号编码。
     *  规范(JS encodeURIComponent 语义 + 社区实现一致约定):
     *  - 保留 `!'()*-._~`(encodeURIComponent 不编码的集合);
     *  - 空格 → `%20`(而非 `+`);
     *  - `*` → `%2A`(社区实现对 encodeURIComponent 的唯一覆写)。
     *  不能用 java.net.URLEncoder:它会额外把 `~ ! ' ( )` 编码为
     *  %7E %21 %27 %28 %29,与 wbi 规范不一致,含此类字符的关键词签名校验会失败。 */
    fun encode(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == ' ' -> sb.append("%20")
                ch == '*' -> sb.append("%2A")
                ch.isLetterOrDigit() || ch in "!'()*-._~" -> sb.append(ch)
                else -> {
                    // 其余字符按 UTF-8 逐字节百分号编码
                    for (b in ch.toString().toByteArray(StandardCharsets.UTF_8)) {
                        sb.append('%').append("0123456789ABCDEF"[((b.toInt() ushr 4) and 0xF)])
                            .append("0123456789ABCDEF"[b.toInt() and 0xF])
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * 由 nav 接口的 imgKey/subKey 生成 mixin key。
     * 注意实现方向:从 (imgKey+subKey) 中按表顺序取值(不是先把前 32 字符拿出来再查表,
     * 后者会越界)——本机实测按此实现的签名可正常通过搜索接口。
     */
    fun mixinKey(imgKey: String, subKey: String): String {
        val s = imgKey + subKey
        val sb = StringBuilder(32)
        for (i in 0 until 32) sb.append(s[MIXIN_KEY_ENC_TAB[i]])
        return sb.toString()
    }

    /**
     * 对 [params] 做 wbi 签名,返回带 wts/w_rid 的新参数表(不含 null 值)。
     */
    fun sign(params: Map<String, String>, imgKey: String, subKey: String): Map<String, String> {
        val p = params.filterValues { it.isNotBlank() }.toMutableMap()
        p["wts"] = (System.currentTimeMillis() / 1000).toString()
        val query = p.entries.sortedBy { it.key }
            .joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        p["w_rid"] = md5Hex(query + mixinKey(imgKey, subKey))
        return p
    }

    private fun md5Hex(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
