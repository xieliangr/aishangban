# 爱上班 (Ai Shangban)

基于原「爱学习 v1.4.8」重构的定时自动点击工具。**这是全新版本，版本号从 `1.0.0`（versionCode 1）重新开始**，应用名、包名、偏好设置命名空间均已独立，不与旧版冲突。

## 版本信息

| 项目 | 旧版 | 新版 |
|------|------|------|
| 应用名 | 爱学习 | **爱上班** |
| 包名 | `com.aixuexi.auto` | `com.aishangban.auto` |
| 版本 | 1.4.8 (code 30) | **1.0.0 (code 1)** |
| 偏好设置 | `aixuexi` | `aishangban` |
| 语言 | Kotlin | Kotlin |

## 闪退修复清单

原版从 APK 反编译后定位到多处会导致“偶尔闪退/卡死”的问题，已逐项修复：

1. **全局异常处理器（主因）**：原版在 `MainActivity.onCreate` 里安装 `UncaughtExceptionHandler`，只写日志、既不恢复默认处理器也不结束进程，导致主线程死亡后界面卡白屏，且每次重建 Activity 重复安装。现改为在 `App` 中只安装一次，写日志后交回系统默认处理器并正常结束进程。
2. **日志文件写入异常**：`LogManager.log()` 未捕获 `IOException`，磁盘满或文件占用时抛到工作线程。现全部 `runCatching` 并加锁串行写。
3. **SharedPreferences 解析异常**：坐标读取用 `toInt()`，脏数据会抛 `NumberFormatException`。现用 `toIntOrNull()` 并校验范围。
4. **无障碍服务实例可见性 / 手势异常**：`instance` 改为 `@Volatile`；`dispatchGesture`、`wakeAndUnlock`、`dismissKeyguard` 全部捕获异常。
5. **WakeLock 泄漏**：获取/释放成对，异常路径也会释放。
6. **精确闹钟权限崩溃（高频）**：Android 12+ 缺少 `SCHEDULE_EXACT_ALARM` 时 `setAlarmClock()` 抛 `SecurityException`。现先检查权限并兜底；设置与取消闹钟的 `PendingIntent` flag 统一为 `FLAG_IMMUTABLE`，避免取消失败导致重复闹钟。
7. **配置读取越界**：时间段读取带默认值与范围校验（封装在 `Prefs`）。
8. **视图空指针**：所有 `findViewById` 安全获取；权限跳转包 `runCatching`；RecyclerView 删除用 `bindingAdapterPosition`。
9. **重启后任务失效**：新增 `BootReceiver`，开机/升级后自动恢复闹钟。

## 功能

- 设置 8 个可启用的点击坐标及各自延迟
- 添加多个执行时间段，每天在区间内随机时间触发一轮
- 按星期选择执行日
- 执行日志查看与清空
- 手动停止并取消所有闹钟

## 构建

需要 Android Studio（Gradle 8.9 / AGP 8.5.2 / compileSdk 34）：

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 注意

无障碍服务涉及自动化点击，请在系统设置中手动开启「爱上班」的无障碍权限后使用。请遵守所用平台的服务条款。
