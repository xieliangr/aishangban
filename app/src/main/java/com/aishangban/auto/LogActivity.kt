package com.aishangban.auto

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 执行日志查看界面。 */
class LogActivity : AppCompatActivity() {

    private lateinit var logManager: LogManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        logManager = LogManager(this)
        val logText = findViewById<TextView>(R.id.logText)
        val clearBtn = findViewById<Button>(R.id.clearBtn)

        renderLogs(logText)
        clearBtn?.setOnClickListener {
            logManager.clear()
            renderLogs(logText)
        }
    }

    private fun renderLogs(logText: TextView?) {
        if (logText == null) return
        val lines = logManager.readLogs()
        logText.text = if (lines.isEmpty()) {
            getString(R.string.no_log)
        } else {
            lines.joinToString("\n")
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
