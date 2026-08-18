// native 模块 —— FFmpeg 原生构建(不使用 gradle-javacpp 插件)。
//
// 职责:
//   1. 解析 bytedeco 两个"普通 jar"(不拉平台列化产物家族):
//        org.bytedeco:ffmpeg:7.1.1-1.5.12  绑定 API(对应 FFmpeg 7.1.x ABI;bytedeco 无 7.1.5-* 发布)
//        org.bytedeco:javacpp:1.5.12       运行时 Loader 与 Builder 工具
//   2. 驱动 native/build.sh 完成两阶段原生构建(cppbuild + javacpp Builder)。
//
// 平台名与产物布局见 native/STATUS.md。

val ffmpegBytedecoVersion: String = "7.1.1-1.5.12"
val javacppVersion: String = "1.5.12"

// 让 project(":native") 依赖具备合理坐标(fabric include / neoforge jarJar 需要 semver)
version = providers.gradleProperty("mod_version").getOrElse("0.1.0")
group = providers.gradleProperty("maven_group").getOrElse("io.github.cyf112233.musicmc")

repositories {
    mavenCentral()
}

// 专用解析配置:不进入任何 sourceSet,仅用于把两个 jar 的文件路径传给 Exec 任务。
val bytedeco by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    // 只要这两个普通 jar;ffmpeg POM 传递依赖会拉全部平台 jar(每个 20-40MB,且不需要)
    isTransitive = false
}
dependencies {
    bytedeco("org.bytedeco:ffmpeg:$ffmpegBytedecoVersion")
    bytedeco("org.bytedeco:javacpp:$javacppVersion")
}

// 目标平台:gradle.properties 中 nativePlatform(M3 落该项),默认 linux-x86_64
val nativePlatform: String = providers.gradleProperty("nativePlatform").getOrElse("linux-x86_64")

val nativeBuildDir = layout.buildDirectory.dir("native")

// ---------------------------------------------------------------------------
// :native:buildNative —— cppbuild + javacpp Builder 两阶段构建
// ---------------------------------------------------------------------------
val buildNative = tasks.register<Exec>("buildNative") {
    group = "native"
    description = "构建 FFmpeg 原生库($nativePlatform):cppbuild + javacpp Builder"
    dependsOn(bytedeco)
    workingDir = projectDir
    inputs.file("build.sh")
    inputs.file("cppbuild/ffmpeg/cppbuild.sh")
    inputs.file("ffmpeg/ffmpeg-7.1.5.tar.xz")
    outputs.dir(nativeBuildDir)

    doFirst {
        val ffmpegJar = bytedeco.files.single { it.name.startsWith("ffmpeg-") }.absolutePath
        val javacppJar = bytedeco.files.single { it.name.startsWith("javacpp-") }.absolutePath
        logger.lifecycle("FFMPEG_JAR=$ffmpegJar")
        logger.lifecycle("JAVACPP_JAR=$javacppJar")
        environment("FFMPEG_JAR", ffmpegJar)
        environment("JAVACPP_JAR", javacppJar)
    }
    commandLine("bash", "build.sh", nativePlatform)
}

// ---------------------------------------------------------------------------
// :native:packageNative —— 平台产物打成 musicmc-native-<platform>.jar
// 布局按 javacpp Loader 约定:org/bytedeco/ffmpeg/<platform>/
// ---------------------------------------------------------------------------
val packageNative = tasks.register<Jar>("packageNative") {
    group = "native"
    description = "把 build/native/<platform> 打成 musicmc-native-<platform>.jar"
    dependsOn(buildNative)
    archiveBaseName.set("musicmc-native")
    archiveClassifier.set(nativePlatform)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(nativeBuildDir.map { it.dir(nativePlatform) })
    // .so 文件重复构建时若 mtime 变化会破坏 jar 的 reproducibility,保持默认即可
    // (mod 侧 include/jarJar 需要 jar 稳定,不启用 task 级 up-to-date 覆盖)
    outputs.upToDateWhen { true }
}

// 把平台 jar 暴露为 native 项目的 "default" 消费产物,
// 使 fabric/neoforge 可用 project(":native") 依赖并 include/jarJar 嵌套它。
val defaultConfig: Configuration = configurations.create("default") {
    isCanBeConsumed = true
    isCanBeResolved = true
    // 只消费平台 jar 自身,不传播 bytedeco 的解析配置
    isTransitive = false
}
artifacts {
    add("default", packageNative) {
        builtBy(packageNative)
    }
}