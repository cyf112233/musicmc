// 音乐房间纯协议/逻辑模块(零 Minecraft / loader 依赖)。
// 供 common(客户端)、fabric/neoforge 服务端中继、paper 服务端中继复用,
// 保证"同一份协议,跨平台、跨 MC 版本"。
plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// 供 neoforge jarJar 嵌套:jar 需带 Automatic-Module-Name
tasks.jar {
    manifest.attributes["Automatic-Module-Name"] = "io.github.cyf112233.musicmc.room"
}
