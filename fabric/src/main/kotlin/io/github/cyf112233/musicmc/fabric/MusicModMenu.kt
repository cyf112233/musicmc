package io.github.cyf112233.musicmc.fabric

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/**
 * ModMenu 配置入口(Fabric 侧;需用户安装 ModMenu 模组)。
 *
 * Mods 列表 → MusicMC → Config 按钮 → 打开 Cloth Config 设置界面。
 * (NeoForge 侧用自带的 Mod 列表 → Config,无需额外模组)
 */
class MusicModMenu : ModMenuApi {

    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> io.github.cyf112233.musicmc.ui.yacl.YaclConfigScreen.open(parent) }
}
