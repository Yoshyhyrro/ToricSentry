package com.yoshyhyrro.toricsentry

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yoshyhyrro.toricsentry.core.runDetectorDemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val runButton = findViewById<Button>(R.id.runDemoButton)
        val logText = findViewById<TextView>(R.id.logText)

        runButton.setOnClickListener {
            runButton.isEnabled = false
            logText.text = "Running detector demo...\n"

            lifecycleScope.launch(Dispatchers.Default) {
                val logs = mutableListOf<String>()
                runDetectorDemo { line -> logs.add(line) }

                withContext(Dispatchers.Main) {
                    logText.text = logs.joinToString(separator = "\n")
                    runButton.isEnabled = true
                }
            }
        }
    }
}
