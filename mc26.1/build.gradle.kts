// MusicMC mc26.1 模块 —— net.minecraft / com.mojang 重度依赖类的归属模块
// (MC 26.1.x 适配层:client 渲染/纹理/聊天歌词、McScreens 双版本桥、
//  YaclTheme 与全部 Yacl*Screen、QrCodeNative)。
//
// 设计(2026-08 三模块化):
// - common:纯逻辑 + ModernUI Fragments(icyllis,无 net.minecraft 依赖);
// - mc26.1:所有重依赖 net.minecraft / com.mojang 的类(本模块);
// - fabric / neoforge:平台入口,源码共享 common + mc26.1(见各平台 build.gradle.kts
//   的 sourceSets 并源目录),产物仍是单 jar。
// 版本差异(Minecraft.screen 字段 26.1→26.2 迁移)由 McScreens 反射桥在运行期
// 自适应,同一份 mc26.1 源码即可跑 26.1 / 26.2;mc26.2 模块为 26.2 专项适配预留。
//
// 注意:本模块与 common 一样"仅独立编译参考"——实际随 fabric/neoforge 编译
// (MC 构件由 Loom / MDG 提供),此处 compileOnly 声明仅供本模块独立编译与 IDE 使用。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
    // ModernUI-NeoForge 的发布仓库(MusicMainFragment 等 ModernUI 界面引用 icyllis API;
    // 本模块不直接引用 ModernUI,但 common 源码共享编译时需要该仓库解析)
    maven("https://maven.izzel.io/releases/")
    // Mojang 官方构件仓库(joml 等 MC 运行时依赖在此解析)
    maven("https://libraries.minecraft.net")
    // fabric-loom 生成的 Minecraft 反混淆构件(与 common 同源说明):
    // `minecraft("com.mojang:minecraft:...")` 由 Loom 下载并发布到本地
    // ~/.gradle/caches/fabric-loom/minecraftMaven,坐标 net.minecraft:minecraft-merged-deobf。
    maven {
        url = uri("${System.getProperty("user.home")}/.gradle/caches/fabric-loom/minecraftMaven")
    }
}

dependencies {
    // common 纯逻辑(本模块的 Yacl*/client 类引用 NetMusic / LyricManager / UiText 等)
    implementation(project(":common"))

    // Minecraft 客户端(仅独立编译参考;实际随 loader 模块编译,由 Loom / MDG 提供)。
    compileOnly("net.minecraft:minecraft-merged-deobf:${property("minecraft_version")}")
    // MC 运行时依赖(版本取自 MC 26.1.2 piston-meta libraries:org.joml:joml:1.10.8)
    compileOnly("org.joml:joml:1.10.8")
    // LWJGL(MusicHudRenderer / CoverTextureCache 引用的 GL 上下文类型;运行期由 MC 提供)
    compileOnly("org.lwjgl:lwjgl:3.4.2")
    // YACL 现代化 UI(Android / 无 ModernUI 时的第二 UI 后端;双平台 fabric+neoforge)。
    // 编译用 fabric 构件(gui 核心类双平台一致;运行期由用户安装对应平台 YACL mod 提供)
    compileOnly(files("../common/libs/yacl-3.9.6-26.1.jar"))

    // 运行期依赖(随 fabric include / neoforge jarJar 打入最终产物;此处声明使独立编译可见)
    implementation("com.google.code.gson:gson:${property("gson_version")}")
}

kotlin {
    compilerOptions {
        // 与 common 模块保持一致(MC 26.1 面向 Java 25;若 Kotlin 2.4.0 不支持 JVM_25
        // 则改 JVM_24,与 common/fabric/neoforge 同步修改)
        jvmTarget = JvmTarget.JVM_25
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
