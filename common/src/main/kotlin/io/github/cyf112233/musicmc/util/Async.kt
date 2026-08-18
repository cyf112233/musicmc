package io.github.cyf112233.musicmc.util

import icyllis.modernui.mc.MuiModApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 后台任务与 UI 线程调度工具。
 */
object Async {

    /** 共享的缓存线程池,用于网络请求等后台任务 */
    val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "NetMusic-Worker").apply { isDaemon = true }
    }

    /** 在后台线程执行 */
    fun run(block: () -> Unit) {
        executor.execute(block)
    }

    /** 切回 UI 线程(ModernUI 主线程)执行 */
    fun onUi(block: Runnable) {
        // postToUiThread 是 MuiModApi 的静态方法(javap 已核实),须经类名调用
        MuiModApi.postToUiThread(block)
    }
}
