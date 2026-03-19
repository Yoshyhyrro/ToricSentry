package com.yoshyhyrro.toricsentry

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yoshyhyrro.toricsentry.core.runDetectorDemo
import com.yoshyhyrro.toricsentry.core.runQDeformedDetectorDemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val runButton = findViewById<Button>(R.id.runDemoButton)
        val runQDeformedButton = findViewById<Button>(R.id.runQDeformedDemoButton)
        val logText = findViewById<TextView>(R.id.logText)

        runButton.setOnClickListener {
            runDemo(
                primaryButton = runButton,
                secondaryButton = runQDeformedButton,
                logText = logText,
                startMessage = "Running detector demo..."
            ) { logs ->
                runDetectorDemo { line -> logs.add(line) }
            }
        }

        runQDeformedButton.setOnClickListener {
            runDemo(
                primaryButton = runQDeformedButton,
                secondaryButton = runButton,
                logText = logText,
                startMessage = "Running Q-Deformed detector demo..."
            ) { logs ->
                runQDeformedDetectorDemo { line -> logs.add(line) }
            }
        }
    }

    private fun runDemo(
        primaryButton: Button,
        secondaryButton: Button,
        logText: TextView,
        startMessage: String,
        block: suspend (MutableList<String>) -> Unit
    ) {
        primaryButton.isEnabled = false
        secondaryButton.isEnabled = false
        logText.text = "$startMessage\n"

        lifecycleScope.launch(Dispatchers.Default) {
            val logs = mutableListOf<String>()
            block(logs)

            withContext(Dispatchers.Main) {
                logText.text = logs.joinToString(separator = "\n")
                primaryButton.isEnabled = true
                secondaryButton.isEnabled = true
            }
        }
    }
}
