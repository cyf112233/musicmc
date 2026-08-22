pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net")
        maven("https://maven.neoforged.net/releases")
        // Cloth Config(配置界面 me.shedaniel.cloth)
        maven("https://maven.shedaniel.me/")
    }
}

// 注意:settings.gradle.kts 的 plugins 块无法引用 gradle.properties 中的属性,
// 因此以下版本为硬编码数值,并与 gradle.properties 中的
// loom_version / moddev_gradle_version / kotlin_version 保持一致。
plugins {
    id("net.fabricmc.fabric-loom") version "1.17.19" apply false
    id("net.neoforged.moddev") version "2.0.144" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
}

rootProject.name = "musicmc"

include("common")
include("fabric")
include("neoforge")
// FFmpeg 原生构建模块(不参与 common/fabric/neoforge 编译,只承载原生构建与打包任务)
include("native")
