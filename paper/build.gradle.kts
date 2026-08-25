// MusicMC Paper 服务端房间中继插件。
// 复用 common 模块的房间协议(RoomProtocol)与中继核心(RoomManager),经 Paper 的
// PluginMessenger 收发 musicmc:room 通道。用 shadowJar 把依赖打进单一插件 jar。
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradleup.shadow") version "8.3.5"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    // 房间纯协议/逻辑模块(零 MC 依赖);shadowJar 打进插件 jar
    implementation(project(":room"))
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveBaseName.set("musicmc-room-paper")
    archiveClassifier.set("")
    // 只打进 common 里房间相关的纯逻辑类,避免把 MC/loader 相关 common 类误带进来
    relocate("io.github.cyf112233.musicmc.room", "io.github.cyf112233.musicmc.room") {
        // 不重定位(保持包名,便于与客户端 mod 的协议字节一致;协议本身与包名无关)
    }
}
