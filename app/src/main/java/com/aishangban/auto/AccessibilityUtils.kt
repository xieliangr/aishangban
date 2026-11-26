package com.aishangban.auto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 无障碍服务状态工具。
 *
 * v1.0.3 新增：解决「服务系统里明明开着，但界面显示未启动、任务误判失败」的问题。
 *
 * 关键点：`AutoAccessibilityService.isRunning` 只反映**当前进程内**是否绑定了服务实例。
 * 进程一旦被系统回收或异常退出后重启，静态变量 instance 会被清零，此时即使系统在
 * 设置里仍然开启了无障碍，isRunning 也是 false，界面误报「未启动」、任务直接放弃。
 *
 * 这里改用系统权威来源 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 判断服务是否
 * 真的被开启，它不随进程重启而丢失，可作为「是否需要重新拉起服务」的依据。
 */
object AccessibilityUtils {

    /** 无障碍服务连接/断开时发出的本地广播，界面据此即时刷新状态。 */
    const val ACTION_SERVICE_STATE_CHANGED = "com.aishangban.auto.ACCESSIBILITY_STATE_CHANGED"

    fun notifyServiceStateChanged(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(ACTION_SERVICE_STATE_CHANGED).setPackage(context.packageName))
        }
    }

    /** 系统设置层面是否已开启本应用的无障碍服务（权威状态，跨进程重启保持）。 */
    fun isEnabledInSystem(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (enabled.isEmpty()) return false
        val component = ComponentName(context, AutoAccessibilityService::class.java)
        val flattened = component.flattenToString()
        val short = component.flattenToShortString()
        enabled.split(':').any { entry ->
            entry.equals(flattened, ignoreCase = true) ||
                entry.equals(short, ignoreCase = true) ||
                entry.contains(component.className, ignoreCase = true)
        }
    }.getOrDefault(false)

    /**
     * 综合状态：系统已开启视为「服务有效」。
     * 即使进程内实例暂时为空（服务正在重连），也不应判定为「未开启」而误导用户或放弃任务。
     */
    fun isServiceEffective(context: Context): Boolean =
        AutoAccessibilityService.isRunning || isEnabledInSystem(context)
}
