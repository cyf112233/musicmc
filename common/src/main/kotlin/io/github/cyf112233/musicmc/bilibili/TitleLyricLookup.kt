package io.github.cyf112233.musicmc.bilibili

/**
 * 标题清洗工具:把歌曲标题经网易云无登录 plain 接口匹配歌词时用的清洗规则
 * (网易云 provider 复用;本对象不再提供 lookup 全链路 —— 歌词加载统一走
 * LyricManager:CC 字幕 → 自动三源匹配 → 手动绑定)。
 *
 * 2026-08 本机 curl 实测(网易云接口仍匿名可用):
 * - POST https://music.163.com/api/search/get/web(form: s/type=1/limit=5/offset=0/total=true,
 *   带 Referer https://music.163.com/ 与浏览器 UA)匿名可用,无加密无登录;
 *   response.result.songs[]{ id(可能超出 Int,须按 Long 读), name, artists[] }。
 * - GET https://music.163.com/api/song/lyric?id=<id>&lv=1&kv=1&tv=-1 匿名可用;
 *   lrc.lyric 为空字符串表示无歌词。
 */
object TitleLyricLookup {

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
}
