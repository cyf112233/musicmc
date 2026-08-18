// MusicMC Fabric 模块。
// 参考 FabricMC/fabric-example-mod 的 26.1 分支:
//   https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.1/build.gradle
// 其关键特点(已通过 curl 验证):
//   * 26.1 起 Loom 不再需要 mappings 声明(官方示例中已无 mappings 相关配置);
//   * fabric-loader 与 fabric-api 用 `implementation` 声明,而非 modImplementation。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import net.fabricmc.loom.task.NestJarsAction

plugins {
    // 注意:Kotlin 插件必须先于 fabric-loom 声明,
    // 以便 Loom 在配置 sourceSet 时能识别 Kotlin 源目录(kotlin.srcDirs)。
    // 26.1 起 MC 为非混淆版本,必须使用新插件 ID net.fabricmc.fabric-loom
    // (旧 ID fabric-loom 走 remap 流程,会报 "Configuration 'mappings' has no dependencies")。
    id("org.jetbrains.kotlin.jvm")
    id("net.fabricmc.fabric-loom")
}

version = property("mod_version") as String
group = property("maven_group") as String

// ===== 平台打包模式(nativePlatform)=====
// 默认(单平台):按 gradle.properties 的 nativePlatform(linux-x86_64)打入该平台 jar;
// -PnativePlatform=all:打入全部 6 个 native 平台 jar + 5 个 javacpp 官方平台坐标
// (bytedeco 1.5.12 无 windows-arm64 坐标,该平台使用 native/build/libs 本地文件)。
val allNativePlatforms: List<String> = listOf(
    "linux-x86_64", "linux-arm64", "windows-x86_64",
    "windows-arm64", "android-arm64", "android-x86_64",
)
// javacpp 1.5.12 官方平台列表(不含 windows-arm64)
val javacppPlatforms: List<String> = listOf(
    "linux-x86_64", "linux-arm64", "windows-x86_64",
    "android-arm64", "android-x86_64",
)
// native 平台 jar 输出目录(:native:packageNative 产出)
val nativeLibsDir = rootProject.layout.projectDirectory.dir("native/build/libs")
val nativePlatforms: List<String> = (findProperty("nativePlatform") as? String ?: "linux-x86_64")
    .let { if (it == "all") allNativePlatforms else listOf(it) }
val allNativeMode: Boolean = nativePlatforms.size > 1
val modVersion: String = property("mod_version") as String

// all 模式专用:把 native/build/libs 的 6 个 native 平台 jar 与 javacpp-windows-arm64 jar
// 复制为带合成 fabric.mod.json 的副本(Loom 的 JarNester 只嵌套"mod jar",见下方依赖块注释),
// 输出到 build/allNativeJars,再经 NestJarsAction 追加进 mod jar 的 META-INF/jars/。
data class NativeJarBundle(val tag: String, val jarName: String, val modId: String)

val allNativeBundles: List<NativeJarBundle> = listOf(
    NativeJarBundle("linux_x86_64", "musicmc-native-linux-x86_64.jar", "musicmc_native_linux_x86_64"),
    NativeJarBundle("linux_arm64", "musicmc-native-linux-arm64.jar", "musicmc_native_linux_arm64"),
    NativeJarBundle("windows_x86_64", "musicmc-native-windows-x86_64.jar", "musicmc_native_windows_x86_64"),
    NativeJarBundle("windows_arm64", "musicmc-native-windows-arm64.jar", "musicmc_native_windows_arm64"),
    NativeJarBundle("android_arm64", "musicmc-native-android-arm64.jar", "musicmc_native_android_arm64"),
    NativeJarBundle("android_x86_64", "musicmc-native-android-x86_64.jar", "musicmc_native_android_x86_64"),
    NativeJarBundle("javacpp_windows_arm64", "musicmc-javacpp-windows-arm64.jar", "musicmc_javacpp_windows_arm64"),
)

val preparedNativeJarFiles = if (allNativeMode) {
    val patchProviders = allNativeBundles.map { bundle ->
        tasks.register<Jar>("prepareInclude${bundle.tag}") {
            group = "build"
            description = "为嵌套准备 ${bundle.jarName}(补合成 fabric.mod.json)"
            val srcJar = nativeLibsDir.file(bundle.jarName)
            val jsonStaging = layout.buildDirectory.dir("allNativeJars").map { it.dir("modJson").dir(bundle.tag) }
            archiveFileName.set(bundle.jarName)
            destinationDirectory.set(layout.buildDirectory.dir("allNativeJars"))
            doFirst {
                check(srcJar.asFile.isFile) {
                    "all 模式缺少 $srcJar:native 平台 jar 需由 :native:packageNative 先产出(见 native/README.md)"
                }
                val jsonDir = jsonStaging.get().asFile.also { it.mkdirs() }
                jsonDir.resolve("fabric.mod.json").writeText(
                    """{"schemaVersion":1,"id":"${bundle.modId}","version":"$modVersion","name":"${bundle.modId}","custom":{"fabric-loom:generated":true}}"""
                )
            }
            from(zipTree(srcJar)) { exclude("META-INF/MANIFEST.MF", "fabric.mod.json") }
            from(jsonStaging)
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
    files(patchProviders.map { it.flatMap { task -> task.archiveFile } })
} else {
    files()
}

repositories {
    mavenCentral()
    // ModernUI(ModernUI-Fabric / ModernUI-Markflow 发布于此;
    // modernui-core >= 3.13.0 改由 Maven Central 提供)
    maven("https://maven.izzel.io/releases/")
    // fabric-loader / fabric-api / fabric-language-kotlin 等 Fabric 构件
    maven("https://maven.fabricmc.net/")
    // Forge Config API Port(Fuzss 将 maven 托管于 GitHub raw 路径)
    maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven/") {
        content {
            includeGroup("fuzs.forgeconfigapiport")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")

    // 26.1 起官方示例使用 implementation 而非 modImplementation
    implementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // Fabric Language Kotlin:按官方 Kotlin 模板写法(implementation,不 include;
    // kotlin stdlib 由 FLK 在运行时提供,不要 include)
    // 注:非混淆版 Loom(net.fabricmc.fabric-loom)不再提供 modImplementation 配置,
    // 统一使用 implementation(官方 26.1 示例亦如此)。
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // ModernUI:MUI README 明确 Minecraft >= 26.1 起无需 remap,用 implementation
    implementation("icyllis.modernui:ModernUI-Fabric:${property("modernui_mc_version")}")
    implementation("dev.icyllis:modernui-core:${property("modernui_core_version")}")
    implementation("icyllis.modernui:ModernUI-Markflow:${property("markflow_version")}")

    // Forge Config API Port(Fabric 端,来自 Fuzss maven)
    implementation("fuzs.forgeconfigapiport:forgeconfigapiport-fabric:${property("forgeconfigapiport_version")}")

    // 打进 jar 的运行时库:声明 implementation 以参与编译,并用 include 打包进产物
    implementation("com.google.code.gson:gson:${property("gson_version")}")
    include("com.google.code.gson:gson:${property("gson_version")}")
    implementation("com.google.zxing:core:${property("zxing_version")}")
    include("com.google.zxing:core:${property("zxing_version")}")

    // FFmpeg 解码引擎三件套(播放器主引擎):
    //   ffmpeg.jar(绑定 API)+ javacpp.jar(Loader 运行时)+ javacpp 平台 jar(本机 libjnijavacpp.so)。
    // javacpp 平台 jar 的 classifier 即平台名(linux-x86_64);不对 ffmpeg 拉平台 jar——
    // FFmpeg 原生 .so 由 musicmc-native 平台 jar 承载,Loader 按 presets 资源路径
    // org/bytedeco/ffmpeg/<platform>/ 从 include 嵌套 jar 中提取。
    implementation("org.bytedeco:ffmpeg:${property("ffmpeg_api_version")}") { isTransitive = false }
    include("org.bytedeco:ffmpeg:${property("ffmpeg_api_version")}")
    implementation("org.bytedeco:javacpp:${property("javacpp_version")}") { isTransitive = false }
    include("org.bytedeco:javacpp:${property("javacpp_version")}")

    if (allNativeMode) {
        // ---- all 模式:6 个 native 平台 jar + 5 个 javacpp 平台坐标 + windows-arm64 本地文件 ----
        // Loom 1.17.19 实测:include 只接受模块构件(file 依赖在 processIncludeJars 报
        // "not a module component and has no capabilities");JarNester 只嵌套"mod jar"
        // (须含 fabric.mod.json)。故 7 个本地文件用 prepareInclude* 副本(补合成
        // fabric.mod.json,仿 Loom 对 include 产物合成的 json)走 NestJarsAction —— 与 Loom
        // 处理 include 产物完全相同的机制 —— 追加进 mod jar 的 META-INF/jars/;
        // 副本同时挂 implementation 供 dev 运行期 classpath 可见(与单平台 include+implementation 一致)。
        implementation(preparedNativeJarFiles)
        tasks.jar {
            NestJarsAction.addToTask(this, preparedNativeJarFiles)
        }
        javacppPlatforms.forEach { p ->
            include("org.bytedeco:javacpp:${property("javacpp_version")}:$p")
        }
    } else {
        // ---- 单平台模式(默认,与历史写法一致)----
        implementation("org.bytedeco:javacpp:${property("javacpp_version")}:${nativePlatforms.single()}") { isTransitive = false }
        include("org.bytedeco:javacpp:${property("javacpp_version")}:${nativePlatforms.single()}")
        // FFmpeg 原生库:native 模块的平台 jar(含 .so),include 嵌套进最终 mod jar 以便
        // javacpp Loader 从 classpath 资源 org/bytedeco/ffmpeg/<platform>/ 提取。
        // native 模块暴露的 "default" 配置只含平台 jar 本身(非传递),implementation 仅用于 classpath 可见性。
        implementation(project(":native"))
        include(project(":native"))
    }
}

// 共享 common 模块的源码(common 的源码与 Kotlin/Java 插件由 common 模块代理提供,
// 此处仅把其 main sourceSet 的源目录并入本模块的 main sourceSet)。
sourceSets {
    main {
        val commonMain = project(":common").sourceSets.main.get()
        kotlin.srcDir(commonMain.kotlin.srcDirs)
        java.srcDir(commonMain.java.srcDirs)
    }
}

kotlin {
    compilerOptions {
        // 与 common 模块保持一致;若 Kotlin 2.4.0 不支持 JVM_25,则改用 JVM_24
        jvmTarget = JvmTarget.JVM_25
    }
}

java {
    // 与 Kotlin 的 JVM_25 对齐,避免 KGP 的 JVM target 一致性校验失败(compileJava=26 vs compileKotlin=25)
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

base {
    archivesName = "musicmc-fabric"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

// 不显式配置 java toolchain:使用运行 Gradle 的 JDK。
// loom 的客户端 runs(如 runClient)使用默认配置即可,官方 26.1 示例亦未显式配置。
