import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
    // ModernUI-NeoForge 的发布仓库
    maven("https://maven.izzel.io/releases/")
    // Mojang 官方构件仓库(joml 等 MC 运行时依赖在此解析;
    // 注:com.mojang:minecraft:${minecraft_version}-client 该坐标实测 404,
    // 见 mc26.1 模块的 fabric-loom 本地仓库说明)
    maven("https://libraries.minecraft.net")
    // fabric-loom 生成的 Minecraft 反混淆构件(仅独立编译用;实际随 loader 模块编译):
    // `minecraft("com.mojang:minecraft:...")` 由 Loom 下载并发布到本地
    // ~/.gradle/caches/fabric-loom/minecraftMaven,坐标 net.minecraft:minecraft-merged-deobf。
    maven {
        url = uri("${System.getProperty("user.home")}/.gradle/caches/fabric-loom/minecraftMaven")
    }
}

dependencies {
    // ModernUI 仅作为编译期 API 参考,运行期由 fabric / neoforge 侧提供,故 compileOnly。
    // ModernUI Fragments(MusicMainFragment 等)留在 common(依赖 icyllis 而非 net.minecraft)。
    compileOnly("icyllis.modernui:ModernUI-NeoForge:${property("modernui_mc_version")}")
    compileOnly("dev.icyllis:modernui-core:${property("modernui_core_version")}")
    compileOnly("icyllis.modernui:ModernUI-Markflow:${property("markflow_version")}")

    // 注:net.minecraft / com.mojang 重度依赖类已迁入 mc26.1 模块(本 common 仅保留
    // 纯逻辑 + ModernUI Fragments + UiText,UiText 的语言判断经 ModPlatform.isChinese()
    // 委托 loader 侧,不再直接引用 Minecraft)。以下 MC 编译依赖仅供本模块独立编译
    // 时解析 joml/lwjgl(部分残留 import);实际随 loader 模块编译由 Loom / MDG 提供。
    compileOnly("net.minecraft:minecraft-merged-deobf:${property("minecraft_version")}")
    // MC 运行时依赖(版本取自 MC 26.1.2 piston-meta libraries:org.joml:joml:1.10.8)
    compileOnly("org.joml:joml:1.10.8")
    // LWJGL OpenAL(Android 音频输出 OpenAlOutput 用;运行期由 MC 提供,这里仅编译期引用。
    // API 在 3.3.x/3.4.x 稳定一致,版本与 MC 运行期实际值无强绑定)
    compileOnly("org.lwjgl:lwjgl:3.4.2")
    compileOnly("org.lwjgl:lwjgl-openal:3.4.2")

    // 运行期依赖
    implementation("com.google.code.gson:gson:${property("gson_version")}")
    // 二维码生成(扫码登录 UI 用;fabric include / neoforge jarJar 打入最终产物)
    implementation("com.google.zxing:core:${property("zxing_version")}")
    // FFmpeg 解码引擎(唯一播放引擎):org.bytedeco:ffmpeg = Java 绑定 API,
    // org.bytedeco:javacpp = Loader/Pointer 运行时。
    // 两者 POM 无平台传递依赖(ffmpeg POM 仅依赖 javacpp),显式 isTransitive=false 双保险;
    // 原生 .so 由 :native 平台 jar 提供(fabric include / neoforge jarJar),不在此解析。
    implementation("org.bytedeco:ffmpeg:${property("ffmpeg_api_version")}") { isTransitive = false }
    implementation("org.bytedeco:javacpp:${property("javacpp_version")}") { isTransitive = false }
}

kotlin {
    compilerOptions {
        // 使用当前 JDK 26 编译,字节码目标 25(不配置 java toolchain)。
        // 注意:若 Kotlin 2.4.0 报 "Unsupported JVM target" 不支持 JVM_25,
        // 将下面一行改为 jvmTarget = JvmTarget.JVM_24 即可(此处无法编译验证,标注"未验证")。
        jvmTarget = JvmTarget.JVM_25
    }
}

java {
    // 与 Kotlin 的 JVM_25 对齐,避免 KGP 的 JVM target 一致性校验失败(compileJava=26 vs compileKotlin=25)
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
