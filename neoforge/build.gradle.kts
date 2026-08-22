// MusicMC NeoForge 模块(ModDevGradle 2.0.144 + Kotlin)。
//
// 调研结论(2026-08,均已通过 curl / GitHub API / javap 验证):
//  * MDG README(neoforged/ModDevGradle@main)全文无 "Kotlin" 字样,未给出官方 Kotlin 章节;
//    但 MDG 的 ModDevPlugin.java 内部应用 JavaLibraryPlugin + JarJarPlugin + NeoFormRuntimePlugin,
//    且 ModDevArtifactsWorkflow.addToSourceSet() 将 MC/NeoForge 依赖通过 extendsFrom 挂到标准
//    compileClasspath / runtimeClasspath 上 —— 因此直接叠加 org.jetbrains.kotlin.jvm 插件即可编译。
//    官方证据:thedarkcolour/KotlinModdingSkeleton 的 26.1-neoforge 分支即
//    "net.neoforged.moddev 2.0.141 + org.jetbrains.kotlin.jvm 2.3.0" 直连使用;
//    MDG issue #221(kts 属性语法)/#223(为 Kotlin 增加 getter)已修复,印证 Kotlin 项目受支持。
//  * Kotlin 运行时策略(按用户优先级第一项):MDG 支持叠加 KGP => 直接应用 kotlin 插件,
//    并把 kotlin-stdlib 用 jarJar 打进 mod jar(不依赖 KFF 第三方运行时 mod)。
//    KFF 官方 maven maven.kotlinforforge.org 本机 DNS 不可达(exit=6),GitHub Pages 镜像
//    (thedarkcolour.github.io/KotlinForForge/)虽可达,但方案 1 已可行,无需引入 KFF。
//  * ModernUI-MC README(BloCamLimb/ModernUI-MC)ModDevGradle 小节的官方写法:
//        implementation("icyllis.modernui:ModernUI-NeoForge:${mc}-${ver}")
//        additionalRuntimeClasspath(compileOnly("dev.icyllis:modernui-core:...")) { exclude ... }
//        additionalRuntimeClasspath(compileOnly("icyllis.modernui:ModernUI-Markflow:...")) { exclude ... }
//    ModernUI-NeoForge-26.1.2-3.13.0.5.pom 为 packaging=pom、零依赖("瘦包"),
//    故 modernui-core / ModernUI-Markflow 需单独挂载,三者缺一不可。
//  * 26.1 起 MC 面向 Java 25;MDG 对 java toolchain 有默认约定,本模块不显式配置 toolchain。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 顺序与 KotlinModdingSkeleton 26.1-neoforge 官方模板一致:moddev 在前,KGP 在后。
    // 版本号已在根 settings.gradle.kts 的 plugins 块声明(apply false),此处不再重复。
    id("net.neoforged.moddev")
    id("org.jetbrains.kotlin.jvm")
}

// 注意:gradle.properties 的键是 snake_case,`by project` 委托会按变量名(camelCase)查找,
// 找不到属性,故这里统一用 property("...") 显式读取(与 common/fabric 模块一致)。
val modVersion: String = property("mod_version") as String
val mavenGroup: String = property("maven_group") as String
val archivesBaseName: String = property("archives_base_name") as String
val neoforgeVersion: String = property("neoforge_version") as String
val kotlinVersion: String = property("kotlin_version") as String
val modernuiMcVersion: String = property("modernui_mc_version") as String
val modernuiCoreVersion: String = property("modernui_core_version") as String
val markflowVersion: String = property("markflow_version") as String
val gsonVersion: String = property("gson_version") as String
val zxingVersion: String = property("zxing_version") as String
val ffmpegApiVersion: String = property("ffmpeg_api_version") as String
val javacppVersion: String = property("javacpp_version") as String

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

// native 平台 jar 的 jarJar 处理(两种模式统一):
// MDG 的 JarJar 任务对"文件型"jarJar 依赖要求显式模块名(否则构建期 GradleException);
// FML 运行时还会对 jarjar 嵌套 jar 做 JPMS 模块名校验(Checks.requireModuleName)。
// 注意:'native' 是 Java 关键字,按文件名推导或写成 musicmc.native.* 都会导致
// Invalid module name 启动崩溃(Fabric 无此校验,NeoForge 必炸),故统一用 nativelib 段。
data class NativeJarBundle(val tag: String, val jarName: String, val moduleName: String)

val allNativeBundles: List<NativeJarBundle> = listOf(
    NativeJarBundle("linux_x86_64", "musicmc-native-linux-x86_64.jar", "musicmc.nativelib.linux_x86_64"),
    NativeJarBundle("linux_arm64", "musicmc-native-linux-arm64.jar", "musicmc.nativelib.linux_arm64"),
    NativeJarBundle("windows_x86_64", "musicmc-native-windows-x86_64.jar", "musicmc.nativelib.windows_x86_64"),
    NativeJarBundle("windows_arm64", "musicmc-native-windows-arm64.jar", "musicmc.nativelib.windows_arm64"),
    NativeJarBundle("android_arm64", "musicmc-native-android-arm64.jar", "musicmc.nativelib.android_arm64"),
    NativeJarBundle("android_x86_64", "musicmc-native-android-x86_64.jar", "musicmc.nativelib.android_x86_64"),
    NativeJarBundle("javacpp_windows_arm64", "musicmc-javacpp-windows-arm64.jar", "musicmc.javacpp.windows_arm64"),
)

// 单平台模式同样要补合法 Automatic-Module-Name:否则 FML 按文件名推导出
// musicmc.native.<platform>(含关键字 native)照样启动崩溃。
val singleNativeBundle: NativeJarBundle? = if (allNativeMode) null else {
    val p = nativePlatforms.single()
    NativeJarBundle(
        tag = p.replace('-', '_'),
        jarName = "musicmc-native-$p.jar",
        moduleName = "musicmc.nativelib.${p.replace('-', '_')}",
    )
}

fun NativeJarBundle.prepareProvider(): TaskProvider<Jar> = tasks.register<Jar>("prepareJarjar$tag") {
    group = "build"
    description = "为 jarJar 准备 $jarName(补 Automatic-Module-Name)"
    val srcJar = nativeLibsDir.file(jarName)
    doFirst {
        check(srcJar.asFile.isFile) {
            "$jarName 缺失:先执行 :native:packageNative 产出(见 native/README.md)"
        }
    }
    archiveFileName.set(jarName)
    destinationDirectory.set(layout.buildDirectory.dir("allNativeJars"))
    from(zipTree(srcJar)) { exclude("META-INF/MANIFEST.MF") }
    manifest { attributes["Automatic-Module-Name"] = moduleName }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val preparedNativeJarFiles = if (allNativeMode) {
    files(allNativeBundles.map { it.prepareProvider().flatMap { task -> task.archiveFile } })
} else if (singleNativeBundle != null) {
    files(singleNativeBundle.prepareProvider().flatMap { task -> task.archiveFile })
} else {
    files()
}

version = modVersion
group = mavenGroup

base {
    // MDG 应用了 java-library 插件,base 扩展可用(KotlinModdingSkeleton 亦用 base.archivesName)。
    archivesName = "$archivesBaseName-neoforge"
}

// 共享 common 模块的源码:仅并入源目录(common 源码直接编译进本模块 jar,
// 不再作为 project 依赖,否则类会重复打包)。common 模块自身需应用 kotlin 插件,
// 其 sourceSets.main.kotlin 才存在。
sourceSets {
    main {
        val commonMain = project(":common").sourceSets.main.get()
        kotlin.srcDir(commonMain.kotlin.srcDirs)
        java.srcDir(commonMain.java.srcDirs)
    }
}

// 在 build.gradle 中声明 repositories 会使 MDG 的集中式仓库(RepositoriesPlugin)
// 对本项目失效(MDG README 明确说明),故需把全部仓库显式列出。
repositories {
    mavenCentral()
    // ModernUI(ModernUI-NeoForge / ModernUI-Markflow 发布于此;modernui-core >= 3.13.0 在 Central)
    maven("https://maven.izzel.io/releases/")
    // net.neoforged:neoforge 等(ModDevGradle 编译/运行期解析)
    maven("https://maven.neoforged.net/releases/")
    maven("https://maven.shedaniel.me/")
}

neoForge {
    version = neoforgeVersion

    runs {
        configureEach {
            // 开发环境日志级别(MDG README 推荐配置)
            logLevel = org.slf4j.event.Level.INFO
        }
        create("client") {
            client()
        }
        create("server") {
            server()
        }
    }

    // mod <-> 源码绑定(MDG 2.x 官方 API)。
    // 注意:MDG 没有 Loom 的 modSource(SourceSet)写法;runs 的 loadedMods
    // 默认即为本 mods 块声明的全部 mod,无需在 runs 内重复声明。
    mods {
        create("musicmc") {
            // Gradle 9 KTS 中 sourceSets.main 是 NamedDomainObjectProvider,需 .get() 解包
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    // ---- ModernUI(ModernUI-MC README 的 ModDevGradle 推荐写法)----
    // ModernUI-NeoForge 是 mod jar("瘦包"),声明为 implementation 使其进入 dev 运行 classpath,
    // FML 在开发环境按 classpath mod 加载它。
    implementation("icyllis.modernui:ModernUI-NeoForge:$modernuiMcVersion")

    // modernui-core 与 Markflow 为纯库:implementation(编译期可见 + dev 运行期可见)。
    // 注:MDG README 说明 MC >= 1.21.9 起普通依赖即可被 runs 加载,
    // 无需 additionalRuntimeClasspath(该配置仅在 legacy classpath 版本存在)。
    // 排除传递依赖(防污染:slf4j/log4j/icu4j/fastutil 由 MC 自带,jsr305/annotations 仅编译期用)。
    implementation("dev.icyllis:modernui-core:$modernuiCoreVersion") {
        exclude(group = "org.slf4j")
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "com.google.code.findbugs")
        exclude(group = "org.jetbrains")
        exclude(group = "com.ibm.icu")
        exclude(group = "it.unimi.dsi")
    }
    implementation("icyllis.modernui:ModernUI-Markflow:$markflowVersion") {
        exclude(group = "org.slf4j")
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "com.google.code.findbugs")
        exclude(group = "org.jetbrains")
        exclude(group = "com.ibm.icu")
        exclude(group = "it.unimi.dsi")
    }

    // ---- Cloth Config(配置界面:uiMode 等设置;jarJar 打包进 mod jar,无需用户另装)----

    // ---- 运行时库:gson + zxing,jarJar 打包进 mod jar ----
    // KTS 中 jarJar(implementation(...)) 的 implementation(...) 返回可空 Dependency,
    // 与 jarJar(Any) 签名不匹配,故拆分为两行等价写法(效果与 MDG README 的 Groovy 写法一致)。
    implementation("com.google.code.gson:gson:$gsonVersion")
    jarJar("com.google.code.gson:gson:$gsonVersion")
    // zxing(扫码登录二维码生成):jarJar 打包进 mod jar
    implementation("com.google.zxing:core:$zxingVersion")
    jarJar("com.google.zxing:core:$zxingVersion")

    // ---- FFmpeg 解码引擎三件套(播放器主引擎)----
    // ffmpeg.jar(绑定 API)+ javacpp.jar(Loader 运行时)+ javacpp 平台 jar(本机 libjnijavacpp.so)。
    // javacpp 平台 jar 的 classifier 即平台名(linux-x86_64);FFmpeg 原生 .so 由
    // musicmc-native 的平台 jar 承载(org/bytedeco/ffmpeg/<platform>/ 布局,Loader 约定)。
    implementation("org.bytedeco:ffmpeg:$ffmpegApiVersion") { isTransitive = false }
    jarJar("org.bytedeco:ffmpeg:$ffmpegApiVersion")
    implementation("org.bytedeco:javacpp:$javacppVersion") { isTransitive = false }
    jarJar("org.bytedeco:javacpp:$javacppVersion")

    if (allNativeMode) {
        // ---- all 模式 ----
        // 6 个 native 平台 jar + javacpp-windows-arm64 本地文件:先经 prepareJarjar* 补
        // Automatic-Module-Name(见上),再 jarJar 嵌套;同一批副本挂 implementation 使
        // dev 运行期 classpath 可见(与单平台 implementation+jarJar 语义对齐)。
        implementation(preparedNativeJarFiles)
        jarJar(preparedNativeJarFiles)
        // 5 个 javacpp 官方平台坐标
        javacppPlatforms.forEach { p ->
            implementation("org.bytedeco:javacpp:$javacppVersion:$p") { isTransitive = false }
            jarJar("org.bytedeco:javacpp:$javacppVersion:$p")
        }
    } else {
        // ---- 单平台模式(默认,与历史写法一致)----
        // native 平台 jar(含 .so)经 prepareJarjar 补 Automatic-Module-Name 后 jarJar 嵌套,
        // 供 javacpp Loader 从 classpath 资源 org/bytedeco/ffmpeg/<platform>/ 提取。
        implementation(preparedNativeJarFiles)
        jarJar(preparedNativeJarFiles)
        implementation("org.bytedeco:javacpp:$javacppVersion:${nativePlatforms.single()}") { isTransitive = false }
        jarJar("org.bytedeco:javacpp:$javacppVersion:${nativePlatforms.single()}")
    }

    // ---- Kotlin stdlib ----
    // KGP 已自动将 kotlin-stdlib 挂到 implementation;此处再 jarJar 进 mod jar,
    // 使最终产物自包含(不依赖 KFF 等第三方运行时 mod)。
    jarJar("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")

    // LDlib 现代化 UI:common 源码编译期引用其 API(common/libs 本地 jar;
    // 运行期由用户安装的 LDlib mod 提供,本 mod 不打包)
    compileOnly(files("../common/libs/yacl-3.9.6-26.1.jar"))
}

kotlin {
    compilerOptions {
        // 与 common 模块保持一致(Mojang 26.1 面向 Java 25;KotlinModdingSkeleton 26.1 分支同用 JVM_25)。
        // 若 Kotlin 2.4.0 报 "Unsupported JVM target",改为 JvmTarget.JVM_24(与 common 同步修改)。
        jvmTarget = JvmTarget.JVM_25
    }
}

java {
    // 与 Kotlin 的 JVM_25 对齐,避免 KGP 的 JVM target 一致性校验失败(compileJava=26 vs compileKotlin=25)
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

// 不显式配置 java toolchain:MDG 会按 MC 版本约定(26.1 = Java 25)。
// processResources:neoforge.mods.toml 中版本信息为硬编码(0.1.0),
// 不引入变量替换(MDG 2.0.144 无 standardProperties 支持,硬编码更稳)。
