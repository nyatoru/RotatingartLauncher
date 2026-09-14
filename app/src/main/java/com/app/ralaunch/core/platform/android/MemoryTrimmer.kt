package com.app.ralaunch.core.platform.android

import android.app.ActivityManager
import android.content.Context
import com.app.ralaunch.core.logging.AppLog

/**
 * 启动前内存整理
 * Pre-launch memory trimmer
 *
 * tModLoader + Calamity 这类 Mod 组合在加载期需要一次性申请数百 MB 的连续内存。
 * 在 4-6 GiB 设备上，如果启动时可用内存不足，GC 大数组分配会直接 OutOfMemory
 *（与堆上限设置无关）。本工具在 .NET 运行时启动前检查可用内存，仅在低于阈值
 * 时清理后台进程，把连续内存还给游戏。只调用 killBackgroundProcesses（系统仅
 * 对后台进程生效，前台/可见进程不受影响），不触碰用户数据。
 */
object MemoryTrimmer {
    private const val TAG = "MemoryTrimmer"

    /** 可用内存低于该值才触发清理（默认 1.5 GiB） */
    const val DEFAULT_MIN_AVAIL_BYTES: Long = 1536L * 1024 * 1024

    data class TrimResult(
        val beforeBytes: Long,
        val afterBytes: Long,
        val killedPackages: Int
    ) {
        val freedBytes: Long get() = (afterBytes - beforeBytes).coerceAtLeast(0L)
    }

    /**
     * @return null 表示内存充足未执行清理；否则返回清理前后快照
     */
    @JvmStatic
    fun trimBeforeLaunch(
        context: Context,
        minAvailBytes: Long = DEFAULT_MIN_AVAIL_BYTES
    ): TrimResult? {
        val activityManager =
            context.getSystemService(ActivityManager::class.java) ?: return null
        val before = availMem(activityManager)
        if (before >= minAvailBytes) {
            AppLog.d(
                TAG,
                "可用内存充足无需清理 / No trim needed: avail=${formatMb(before)} MB"
            )
            return null
        }

        AppLog.i(
            TAG,
            "可用内存过低，清理后台进程 / Low memory, trimming: avail=${formatMb(before)} MB"
        )
        val ownPackage = context.packageName
        val processes = activityManager.runningAppProcesses.orEmpty()
        // 前台/可见应用不动（比如正在播放的音乐），只清理纯后台包。
        // killBackgroundProcesses 本身也只对后台进程生效，这里是双保险。
        val protectedPkgs = processes
            .filter {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            }
            .flatMap { it.pkgList?.toList().orEmpty() }
            .toSet()
        val candidates = processes
            .flatMap { proc -> proc.pkgList?.toList().orEmpty() }
            .filter { pkg -> pkg != ownPackage && pkg !in protectedPkgs }
            .toSet()

        var killed = 0
        for (pkg in candidates) {
            try {
                activityManager.killBackgroundProcesses(pkg)
                killed++
            } catch (e: SecurityException) {
                AppLog.w(TAG, "无权限清理 $pkg / No permission: ${e.message}")
            } catch (e: Exception) {
                AppLog.w(TAG, "清理 $pkg 失败 / Failed: ${e.message}")
            }
        }

        // kill 是异步生效的，这里只是尽力而为的复查快照
        val after = availMem(activityManager)
        AppLog.i(
            TAG,
            "内存整理完成 / Trim done: ${formatMb(before)} -> ${formatMb(after)} MB " +
                "(freed~${formatMb((after - before).coerceAtLeast(0L))} MB, pkgs=$killed)"
        )
        return TrimResult(before, after, killed)
    }

    /** 当前可用内存（字节），查询失败返回 Long.MAX_VALUE（= 不触发清理） */
    @JvmStatic
    fun availMem(context: Context): Long {
        val activityManager =
            context.getSystemService(ActivityManager::class.java) ?: return Long.MAX_VALUE
        return availMem(activityManager)
    }

    private fun availMem(activityManager: ActivityManager): Long {
        return try {
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            info.availMem
        } catch (e: Exception) {
            AppLog.w(TAG, "查询可用内存失败 / getMemoryInfo failed: ${e.message}")
            Long.MAX_VALUE
        }
    }

    private fun formatMb(bytes: Long): String = "%.0f".format(bytes / 1048576.0)
}
