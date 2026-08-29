package com.example.nasbrowser

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级取消标志，由 UI（[BrowserViewModel]）和后台 [DownloadService] 共享。
 *
 * 为什么要放在单例里而不是只存在于 Service 内部：
 * 之前取消完全依赖 `ACTION_CANCEL` 这个 Intent 被 Service 的 `onStartCommand` 收到后才去改状态、
 * 才去置标志。一旦该 Intent 因任何原因没送达（前后台限制、Service 重启竞态等），点"取消"就会"什么都没发生"。
 *
 * 现在改成：用户一点"取消"，UI 就立刻置位这里的共享标志、并马上把传输状态改成 PAUSED（列表文字立即更新），
 * 不依赖 Intent 是否送达。Service 的下载循环每读完一个分块就检查同一个标志，置位后最多再读一个分块就会停下。
 * Service 仍会收到取消 Intent 作为"加速器"（立即关连接 / 中断线程），但即使收不到，核心取消语义也已生效。
 */
object CancelHub {
    private val flags = ConcurrentHashMap<String, AtomicBoolean>()

    /** 返回 [id] 对应的（共享）取消标志，不存在则创建为未取消。 */
    fun flagFor(id: String): AtomicBoolean = flags.computeIfAbsent(id) { AtomicBoolean(false) }

    /** 将 [id] 标记为已取消（幂等）。 */
    fun mark(id: String) {
        flagFor(id).set(true)
    }

    /** [id] 是否已被取消。 */
    fun isCancelled(id: String): Boolean = flags[id]?.get() ?: false

    /** 清除 [id] 的取消标志（（重新）开始 / 继续传输时调用）。 */
    fun clear(id: String) {
        flags.remove(id)
    }

    /** 清除全部取消标志。 */
    fun clearAll() {
        flags.clear()
    }
}
