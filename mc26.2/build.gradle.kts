// MusicMC mc26.2 模块 —— MC 26.2.x 专项适配层(骨架)。
//
// 设计:26.1 / 26.2 的版本差异(Minecraft.screen → Minecraft.gui.screen 迁移)目前
// 由 mc26.1 的 McScreens 反射桥在运行期自适应,同一份源码即可跑两版本,因此
// mc26.2 在**当前构建中不参与编译**(源码为空,仅注册模块与版本坐标)。
//
// 何时启用:MC 26.2 升级引入 mc26.1 反射桥无法覆盖的编译期差异时——
//   1. 把差异类移入本模块 src(从 mc26.1 复制并修改);
//   2. 把 fabric / neoforge 的 sourceSets 并源目录从 :mc26.1 切到 :mc26.2
//      (或同时并入,注意同名类冲突需移除旧版);
//   3. 本模块 compileOnly 的 minecraft_version 改为 26.2.x 坐标
//      (fabric-loom 生成对应 merged-deobf 后即可解析)。
// 模块坐标 group/version 与根 gradle.properties 一致(maven_group / mod_version)。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
    maven("https://maven.izzel.io/releases/")
    maven("https://libraries.minecraft.net")
    maven {
        url = uri("${System.getProperty("user.home")}/.gradle/caches/fabric-loom/minecraftMaven")
    }
}

dependencies {
    implementation(project(":common"))
    // 26.2 坐标占位:当前 gradle.properties minecraft_version=26.1.2 时该构件不可解析,
    // 故本模块默认不参与构建(无任务依赖它);启用 26.2 时同步改 minecraft_version。
    compileOnly("net.minecraft:minecraft-merged-deobf:${property("minecraft_version")}")
    compileOnly("org.joml:joml:1.10.8")
    compileOnly(files("../common/libs/yacl-3.9.6-26.1.jar"))
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
