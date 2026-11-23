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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private lateinit var timeAdapter: TimeAdapter
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
        prefs = Prefs(this)

        initViews()
        loadSavedData()
        checkPermissions()
    }

    private fun initViews() {
        val statusText = findViewById<TextView>(R.id.serviceStatus)
        val iconContainer = findViewById<LinearLayout>(R.id.iconListContainer)
        val weekdayContainer = findViewById<LinearLayout>(R.id.weekdayContainer)
        val timeList = findViewById<RecyclerView>(R.id.timeList) ?: return

        updateServiceStatus(statusText)

        for (step in 1..IconConfigManager.MAX_STEPS) {
            iconContainer?.addView(createCoordRow(step))
        }
        if (weekdayContainer != null) initWeekdayButtons(weekdayContainer)

        timeAdapter = TimeAdapter(timeRanges) { position ->
            if (position in timeRanges.indices) {
                timeRanges.removeAt(position)
                timeAdapter.notifyItemRemoved(position)
            }
        }
        timeList.layoutManager = LinearLayoutManager(this)
        timeList.adapter = timeAdapter

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

    private fun createCoordRow(step: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)
            )
            setPadding(0, dpToPx(4), 0, dpToPx(4))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val switch = Switch(this).apply { isChecked = iconManager.isEnabled(step) }
        switches[step] = switch
        row.addView(switch)

        val label = TextView(this).apply {
            text = step.toString()
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        row.addView(label)

        val saved = iconManager.getPoint(step)
        val xInput = EditText(this).apply {
            hint = "X"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(70), -2).apply {
                setMargins(dpToPx(4), 0, dpToPx(4), 0)
            }
            saved?.let { setText(it.x.toString()) }
        }
        val yInput = EditText(this).apply {
            hint = "Y"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(70), -2).apply {
                setMargins(dpToPx(4), 0, dpToPx(4), 0)
            }
            saved?.let { setText(it.y.toString()) }
        }
        row.addView(xInput)
        row.addView(yInput)
        coordInputs[step] = xInput to yInput

        val delayInput = EditText(this).apply {
            hint = "秒"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(55), -2).apply {
                setMargins(dpToPx(4), 0, dpToPx(4), 0)
            }
            setText(iconManager.getPointDelay(step).toString())
        }
        row.addView(delayInput)
        delayInputs[step] = delayInput

        return row
    }

    private fun initWeekdayButtons(container: LinearLayout) {
        val labels = arrayOf("日", "一", "二", "三", "四", "五", "六")
        for (i in 0..6) {
            val dayOfWeek = i + 1
            val button = Button(this).apply {
                text = labels[i]
                textSize = 12f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1f).apply {
                    setMargins(dpToPx(2), 0, dpToPx(2), 0)
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
        button.setBackgroundColor(
            ContextCompat.getColor(this, if (enabled) R.color.weekday_on else R.color.weekday_off)
        )
        button.setTextColor(ContextCompat.getColor(this, R.color.white))
    }

    private fun updateServiceStatus(statusText: TextView?) {
        if (statusText == null) return
        statusText.text = getString(
            if (AutoAccessibilityService.isRunning) R.string.service_running
            else R.string.service_stopped
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
                            timeAdapter.notifyItemInserted(timeRanges.size - 1)
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
            if (::timeAdapter.isInitialized) timeAdapter.notifyDataSetChanged()
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
            toast("请允许精确闹钟权限，否则定时任务不会执行")
            runCatching {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }.onFailure { toast("无法打开闹钟权限设置，请手动开启") }
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus(findViewById(R.id.serviceStatus))
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
