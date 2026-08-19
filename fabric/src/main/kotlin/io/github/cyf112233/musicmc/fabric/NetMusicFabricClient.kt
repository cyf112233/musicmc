// MusicMC Fabric 客户端入口。
//
// 注意:KeyMapping 构造签名待构建校验 —— 26.1 中 vanilla KeyMapping 的
// 构造参数可能已变化(如新增 modId 参数),构建代理会通过 javap 校验并修正。
//
// 另注:26.1 起 Fabric API 将 KeyBindingHelper 重命名为
// KeyMappingHelper(net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper),
// 方法 registerKeyBinding 亦更名为 registerKeyMapping。
package io.github.cyf112233.musicmc.fabric

import com.mojang.blaze3d.platform.InputConstants
import io.github.cyf112233.musicmc.NetMusic
import io.github.cyf112233.musicmc.client.ChatLyricSender
import io.github.cyf112233.musicmc.client.GuiGraphicsHudGui
import io.github.cyf112233.musicmc.client.MusicHudRenderer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import io.github.cyf112233.musicmc.player.PlayerState
import org.lwjgl.glfw.GLFW

class NetMusicFabricClient : ClientModInitializer {

    // 26.1 起 KeyMapping 构造第四参为 KeyMapping.Category(Identifier),不再是 String 分类
    private val openMusicKey: KeyMapping = KeyMapping(
        "key.musicmc.open",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_M,
        KeyMapping.Category(Identifier.fromNamespaceAndPath("musicmc", "musicmc")),
    )

    override fun onInitializeClient() {
        NetMusic.init(FabricPlatform())

        // 聊天栏歌词:每句歌词同步输出到玩家聊天栏(独立于 HUD,开关见设置)
        NetMusic.player.addListener(ChatLyricSender)

        KeyMappingHelper.registerKeyMapping(openMusicKey)

        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            while (openMusicKey.consumeClick()) {
                NetMusic.openScreen()
            }
            suppressVanillaMusic()
        }

        // 游戏内 HUD(悬浮音乐面板)。26.1 起 fabric-api 的 HUD 回调为
        // HudElementRegistry.addLast(Identifier, HudElement)(旧版 HudRenderCallback
        // 已移除,javap 已核实 fabric-rendering-v1 25.3.2 只有 HudElementRegistry)。
        // 回调拿到 (GuiGraphicsExtractor, DeltaTracker) 后立即包成统一绘制接口
        // HudGui(见 common GuiGraphicsHudGui),common 渲染逻辑不再直接碰 MC
        // blit / fill / text 等版本差异大的内部 API —— 版本差异全部隔离在该适配器内。
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("musicmc", "music_hud"),
        ) { graphics, _ ->
            MusicHudRenderer.onFrame(GuiGraphicsHudGui(graphics))
        }

        // 配置入口不再注册 /netmusic 命令:统一走 ModMenu(Mods 列表 → MusicMC → Config)
    }

    /**
     * 播放中抑制 Minecraft 环境音乐。
     *
     * 无 mixin 方案下,vanilla 的 MusicManager.tick 每 client tick 都会运行,
     * 播完或被 stopPlaying 打断后会按自己的节拍重新 startPlaying 环境音乐,
     * 所以不能在开始播放时只调用一次 stopPlaying,必须在 PLAYING 状态下
     * 每个 client tick 持续抑制;暂停/停止后条件不成立,vanilla 音乐自动
     * 恢复,符合预期。
     *
     * 注:已 javap 核实 MC 26.1.2 的 MusicManager 没有 isPlaying() 方法
     * (仅有 isPlayingMusic(Music) 需要传入当前播放的 Music 实例,此处无法得知),
     * 故保持"每 tick 直接 stopPlaying"的现状,不做空调用优化。
     */
    private fun suppressVanillaMusic() {
        if (NetMusic.config.pauseGameMusicOnPlay && NetMusic.player.state == PlayerState.PLAYING) {
            Minecraft.getInstance().musicManager.stopPlaying()
        }
    }
}
