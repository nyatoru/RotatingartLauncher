package com.app.ralaunch.core.platform.runtime.dotnet

import android.app.ActivityManager
import android.content.Context
import com.app.ralaunch.core.logging.AppLog
import com.app.ralaunch.core.platform.runtime.EnvVarsManager
import com.app.ralaunch.core.common.SettingsAccess
import org.koin.java.KoinJavaComponent

/**
 * CoreCLR 配置工具类
 *
 * 负责将用户设置的 CoreCLR 配置应用到环境变量中，
 * 这些环境变量会在 .NET 运行时启动时被读取。
 *
 * 支持的配置项：
 * - GC 配置：Server GC、Concurrent GC、Heap Count、Retain VM
 * - JIT 配置：Tiered Compilation、Quick JIT、Optimize Type
 */
object CoreCLRConfig {
    private const val TAG = "CoreCLRConfig"

    /** 低于该物理内存总量时自动启用保守 GC（6 GiB） */
    private const val LOW_RAM_THRESHOLD_BYTES = 6L * 1024 * 1024 * 1024

    /** 可用内存低于该值时启用更强的保守 GC（3 GiB） */
    private const val VERY_LOW_AVAIL_BYTES = 3L * 1024 * 1024 * 1024

    /** GCConserveMemory 取值范围 0-9，值越大 GC 越倾向于压缩/归还内存 */
    private const val GC_CONSERVE_MEMORY_LOW_RAM = "5"
    private const val GC_CONSERVE_MEMORY_VERY_LOW_RAM = "7"

    /**
     * 应用 CoreCLR 配置到 native 层
     * 此方法需要在启动 .NET 运行时之前调用
     *
     * @param context Android Context
     */
    fun applyConfigAndInitHooking() {
        val settings = SettingsAccess
        val context: Context = KoinJavaComponent.get(Context::class.java)
        val gcConserveMemory = resolveAutoGcConserveMemory(context)
        EnvVarsManager.quickSetEnvVars(
            // 应用 GC 配置
            "DOTNET_gcServer" to if (settings.isServerGC) "1" else "0",
            "DOTNET_gcConcurrent" to if (settings.isConcurrentGC) "1" else "0",
            "DOTNET_GCHeapCount" to settings.gcHeapCount.takeIf { it != "auto" },
            "DOTNET_GCRetainVM" to if (settings.isRetainVM) "1" else "0",
            // GC 堆硬限制（百分比，.NET 要求十六进制格式；0 表示不限制）
            "DOTNET_GCHeapHardLimitPercent" to settings.gcHeapHardLimitPercent
                .takeIf { it > 0 }
                ?.let { Integer.toHexString(it) },
            // 低内存设备自动启用保守 GC，减小托管堆占用；
            // 用户已手动设置堆硬限制时交给用户配置接管
            "DOTNET_GCConserveMemory" to gcConserveMemory,

            // 应用 JIT 配置
            "DOTNET_TieredCompilation" to if (settings.isTieredCompilation) "1" else "0",
            "DOTNET_TC_QuickJit" to if (settings.isTieredCompilation && settings.isQuickJIT) "1" else "0",
            "DOTNET_JitOptimizeType" to settings.jitOptimizeType.toString(),

            // 应用日志配置
            "COMPlus_DebugWriteToStdErr" to "1",
            "COREHOST_TRACE" to if (settings.isVerboseLogging) "1" else "0",
            "COREHOST_TRACEFILE" to
                    if (settings.isVerboseLogging)
                        context.getExternalFilesDir(null)?.resolve("corehost_trace.txt")?.absolutePath
                    else
                        null,

            // 应用版本前滚策略
            "DOTNET_ROLL_FORWARD" to "LatestMajor",
            "DOTNET_ROLL_FORWARD_ON_NO_CANDIDATE_FX" to "2",
            "DOTNET_ROLL_FORWARD_TO_PRERELEASE" to "1",
        )

        if (gcConserveMemory != null) {
            AppLog.i(TAG, "Low-memory device detected, DOTNET_GCConserveMemory=$gcConserveMemory applied")
        }

        if (settings.isVerboseLogging) {
            CoreHostHooks.initTraceHooks()
        }
    }

    /**
     * 按可用内存分档返回保守 GC 档位：
     * - 系统已处于 lowMemory 或可用内存 ≤ 3 GiB：更强档位（适配大型 Mod 场景）
     * - 系统标记 low-ram 或物理内存 ≤ 6 GiB：基础档位
     * - 否则返回 null（由 .NET 默认值生效）
     * 用户已手动设置堆硬限制时不覆盖。
     */
    private fun resolveAutoGcConserveMemory(context: Context): String? {
        if (SettingsAccess.gcHeapHardLimitPercent > 0) return null
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        val memInfo = ActivityManager.MemoryInfo()
        return try {
            activityManager.getMemoryInfo(memInfo)
            when {
                memInfo.lowMemory || memInfo.availMem <= VERY_LOW_AVAIL_BYTES ->
                    GC_CONSERVE_MEMORY_VERY_LOW_RAM
                activityManager.isLowRamDevice || memInfo.totalMem <= LOW_RAM_THRESHOLD_BYTES ->
                    GC_CONSERVE_MEMORY_LOW_RAM
                else -> null
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to query memory info: ${e.message}")
            null
        }
    }

    /**
     * 获取当前 CoreCLR 配置的摘要信息
     *
     * @param context Android Context
     * @return 配置摘要字符串
     */
    fun getConfigSummary(context: Context?): String {
        val settings = SettingsAccess
        val sb = StringBuilder()

        sb.append("CoreCLR 配置摘要:\n")
        sb.append("  GC:\n")
        sb.append("    Server GC: ").append(if (settings.isServerGC) "启用" else "关闭")
            .append("\n")
        sb.append("    Concurrent GC: ").append(if (settings.isConcurrentGC) "启用" else "关闭")
            .append("\n")
        sb.append("    Heap Count: ").append(settings.gcHeapCount).append("\n")
        if (settings.gcHeapHardLimitPercent > 0) {
            sb.append("    Heap Hard Limit: ").append(settings.gcHeapHardLimitPercent).append("%\n")
        }
        sb.append("    Retain VM: ").append(if (settings.isRetainVM) "启用" else "关闭")
            .append("\n")
        sb.append("  JIT:\n")
        sb.append("    Tiered Compilation: ")
            .append(if (settings.isTieredCompilation) "启用" else "关闭").append("\n")
        sb.append("    Quick JIT: ").append(if (settings.isQuickJIT) "启用" else "关闭")
            .append("\n")

        val optimizeType = settings.jitOptimizeType
        val optimizeTypeName: String?
        when (optimizeType) {
            1 -> optimizeTypeName = "体积优先"
            2 -> optimizeTypeName = "速度优先"
            else -> optimizeTypeName = "混合"
        }
        sb.append("    Optimize Type: ").append(optimizeTypeName).append("\n")

        return sb.toString()
    }
}
