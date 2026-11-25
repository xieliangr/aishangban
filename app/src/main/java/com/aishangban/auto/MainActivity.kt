package com.aishangban.auto

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 主界面。
 *
 * 闪退修复点 #8：
 * 1) 所有 findViewById 都用安全方式获取，缺少视图时不再 NPE；
 * 2) 权限申请与跳转系统设置都包 runCatching，避免 ActivityNotFoundException；
 * 3) 坐标输入全部安全解析并校验，非法输入给出提示而不是抛异常；
 * 4) RecyclerView 删除使用 bindingAdapterPosition，避免旧位置越界崩溃；
 * 5) 时间段读取使用 Prefs 安全封装。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var iconManager: IconConfigManager
    private lateinit var prefs: Prefs

    private val timeRanges = mutableListOf<TimeRange>()
    private val coordInputs = mutableMapOf<Int, Pair<EditText, EditText>>()
    private val delayInputs = mutableMapOf<Int, EditText>()
    private val switches = mutableMapOf<Int, Switch>()
    private val weekdayButtons = mutableMapOf<Int, Button>()
    private val weekdayButtonState = mutableMapOf<Int, Boolean>()

    private var pendingStartHour = 0
    private var pendingStartMinute = 0

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted == false) toast("建议开启通知权限以接收任务状态")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        iconManager = IconConfigManager(this)
        iconManager.ensureDefaults()
        prefs = Prefs(this)

        initViews()
        loadSavedData()
        checkPermissions()
    }

    private fun initViews() {
        val statusText = findViewById<TextView>(R.id.serviceStatus)
        val iconContainer = findViewById<LinearLayout>(R.id.iconListContainer)
        val weekdayContainer = findViewById<LinearLayout>(R.id.weekdayContainer)

        updateServiceStatus(statusText)

        for (step in 1..IconConfigManager.MAX_STEPS) {
            iconContainer?.addView(createCoordRow(step))
        }
        if (weekdayContainer != null) initWeekdayButtons(weekdayContainer)

        renderTimeList()

        findViewById<View>(R.id.btnStartService)?.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { toast("无法打开无障碍设置") }
        }
        findViewById<View>(R.id.btnAddTime)?.setOnClickListener { showRangePicker() }
        findViewById<View>(R.id.btnStop)?.setOnClickListener {
            TaskManager.stopActive()
            AlarmReceiver.cancelAlarms(this)
            LogManager(this).log("手动停止", "已取消所有闹钟")
            toast("任务已停止，闹钟已取消")
        }
        findViewById<View>(R.id.btnLog)?.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<View>(R.id.btnSave)?.setOnClickListener { saveAll() }
    }

    /**
     * 重新渲染时间段列表。
     *
     * 这里改用 LinearLayout 平铺条目，而不是 RecyclerView：
     * 页面外层是 ScrollView，ScrollView 里嵌套 wrap_content 的 RecyclerView
     * 在部分系统上高度测量不可靠，只会显示第一个条目，导致第二个时间段
     * “看不到也滑不到”。时间段数量很少（通常 1~3 个），直接平铺更简单可靠，
     * 且能随外层 ScrollView 一起正常滚动。
     */
    private fun renderTimeList() {
        val container = findViewById<LinearLayout>(R.id.timeList) ?: return
        container.removeAllViews()

        if (timeRanges.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.time_empty)
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                gravity = android.view.Gravity.CENTER
                setPadding(0, dpToPx(24), 0, dpToPx(24))
            }
            container.addView(empty)
        } else {
            timeRanges.forEachIndexed { index, range ->
                container.addView(createTimeRow(range, index))
            }
        }

        findViewById<TextView>(R.id.timeCountText)?.text =
            getString(R.string.time_count, timeRanges.size)
    }

    private fun createTimeRow(range: TimeRange, index: Int): View {
        val row = layoutInflater.inflate(R.layout.item_time, null, false)
        val indexText = row.findViewById<TextView>(R.id.timeIndex)
        indexText.text = (index + 1).toString()

        row.findViewById<TextView>(R.id.timeText).text = range.displayText

        row.findViewById<View>(R.id.deleteBtn).setOnClickListener {
            // 用捕获的 index 删除，删除后立即重绘，避免持有旧 position 越界
            if (index in timeRanges.indices) {
                timeRanges.removeAt(index)
                renderTimeList()
            }
        }
        return row
    }

    private fun createCoordRow(step: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(50)
            )
            setPadding(0, dpToPx(3), 0, dpToPx(3))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val switch = Switch(this).apply {
            isChecked = iconManager.isEnabled(step)
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), -2)
        }
        switches[step] = switch
        row.addView(switch)

        val label = TextView(this).apply {
            text = step.toString()
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        row.addView(label)

        val saved = iconManager.getPoint(step)
        val xInput = makeCoordInput("X", saved?.x?.toString().orEmpty(), 70)
        val yInput = makeCoordInput("Y", saved?.y?.toString().orEmpty(), 70)
        row.addView(xInput)
        row.addView(yInput)
        coordInputs[step] = xInput to yInput

        val delayInput = makeCoordInput("秒", iconManager.getPointDelay(step).toString(), 60)
        row.addView(delayInput)
        delayInputs[step] = delayInput

        return row
    }

    /** 统一的坐标/间隔输入框：圆角背景、居中、数字键盘。 */
    private fun makeCoordInput(hint: String, value: String, widthDp: Int): EditText =
        EditText(this).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            background = ContextCompat.getDrawable(context, R.drawable.bg_input)
            setPadding(0, dpToPx(10), 0, dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(dpToPx(widthDp), -2).apply {
                setMargins(dpToPx(3), 0, dpToPx(3), 0)
            }
            if (value.isNotEmpty()) setText(value)
        }

    private fun initWeekdayButtons(container: LinearLayout) {
        val labels = arrayOf("日", "一", "二", "三", "四", "五", "六")
        for (i in 0..6) {
            val dayOfWeek = i + 1
            val button = Button(this).apply {
                text = labels[i]
                textSize = 14f
                isAllCaps = false
                minHeight = 0
                minimumHeight = 0
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(44), 1f).apply {
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                }
            }
            val enabled = iconManager.isWeekdayEnabled(dayOfWeek)
            weekdayButtonState[dayOfWeek] = enabled
            updateWeekdayButtonColor(button, enabled)
            button.setOnClickListener {
                val current = weekdayButtonState[dayOfWeek] ?: true
                val next = !current
                weekdayButtonState[dayOfWeek] = next
                updateWeekdayButtonColor(button, next)
            }
            weekdayButtons[dayOfWeek] = button
            container.addView(button)
        }
    }

    private fun updateWeekdayButtonColor(button: Button, enabled: Boolean) {
        button.background = roundedDrawable(
            ContextCompat.getColor(this, if (enabled) R.color.weekday_on else R.color.weekday_off)
        )
        button.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.white else R.color.weekday_off_text)
        )
    }

    /** 生成圆角背景，让星期按钮更柔和。 */
    private fun roundedDrawable(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8).toFloat()
            setColor(color)
        }

    private fun updateServiceStatus(statusText: TextView?) {
        if (statusText == null) return
        val running = AutoAccessibilityService.isRunning
        statusText.text = getString(if (running) R.string.service_running else R.string.service_stopped)
        statusText.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.status_running else R.color.status_stopped)
        )
    }

    private fun showRangePicker() {
        val now = Calendar.getInstance()
        TimePickerDialog(
            this,
            { _, startHour, startMinute ->
                pendingStartHour = startHour
                pendingStartMinute = startMinute
                TimePickerDialog(
                    this,
                    { _, endHour, endMinute ->
                        val range = TimeRange(pendingStartHour, pendingStartMinute, endHour, endMinute)
                        if (!range.isValid) {
                            toast("结束时间必须晚于开始时间")
                        } else {
                            timeRanges.add(range)
                            renderTimeList()
                            toast("已添加第 ${timeRanges.size} 个时间段，别忘点保存")
                        }
                    },
                    now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true
                ).show()
            },
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true
        ).show()
    }

    private fun loadSavedData() {
        runCatching {
            for (i in 0 until prefs.timeCount) {
                prefs.getRange(i)?.let { timeRanges.add(it) }
            }
            renderTimeList()
        }.onFailure { LogManager(this).log("加载配置异常", it.message ?: "") }
    }

    private fun saveAll() {
        val switchStates = switches.mapValues { it.value.isChecked }
        iconManager.setEnabledBatch(switchStates)
        iconManager.setWeekdaysBatch(weekdayButtonState)

        var validCount = 0
        for (step in 1..IconConfigManager.MAX_STEPS) {
            val pair = coordInputs[step] ?: continue
            val xs = pair.first.text?.toString()?.trim().orEmpty()
            val ys = pair.second.text?.toString()?.trim().orEmpty()
            if (xs.isEmpty() || ys.isEmpty()) {
                iconManager.removePoint(step)
            } else {
                val x = xs.toIntOrNull()
                val y = ys.toIntOrNull()
                if (x != null && y != null && x in 0..10000 && y in 0..10000) {
                    iconManager.setPoint(step, x, y)
                    if (switchStates[step] == true) validCount++
                } else {
                    toast("图标 #$step 坐标无效，已跳过")
                }
            }

            delayInputs[step]?.let { input ->
                val delay = input.text?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 10
                input.setText(delay.toString())
                iconManager.setPointDelay(step, delay)
            }
        }

        if (validCount < 2) {
            toast("请至少设置2个图标的坐标并启用")
            return
        }
        if (timeRanges.isEmpty()) {
            toast("请至少添加一个时间段")
            return
        }

        prefs.saveRanges(timeRanges)
        AlarmReceiver.scheduleAlarms(this)
        toast("保存成功！每天将在时间段内随机时间执行一轮")
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // 精确闹钟权限可能被国产 ROM 在数天后或重启后悄悄回收。
            // 只在首次发现时引导一次：之前每次打开都自动跳系统设置页，
            // 用户看到的就是“打开闪一下就退出”。之后保持静默，
            // 任务调度层已自动降级为非精确闹钟，无需反复打扰。
            if (!prefs.exactAlarmHintShown) {
                prefs.markExactAlarmHintShown()
                toast("请允许精确闹钟权限；未开启时任务将以兼容模式运行")
                runCatching {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                }.onFailure { toast("无法打开设置，任务将以兼容模式继续运行") }
                // 补回可能被系统清掉的闹钟（以兼容模式重新排程）
                AlarmReceiver.scheduleAlarms(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus(findViewById(R.id.serviceStatus))
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
