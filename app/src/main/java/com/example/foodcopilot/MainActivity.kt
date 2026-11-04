package com.example.foodcopilot

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/** Minimal screen: asks for mic permission and opens Accessibility settings. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val info = TextView(this).apply {
            text = "1. Allow microphone.\n2. Turn on Food Copilot in Accessibility settings.\n" +
                    "3. Open Swiggy or Zomato. Press Volume Up and speak."
            textSize = 18f
            contentDescription = text
        }
        val micBtn = Button(this).apply {
            text = "Allow microphone"
            setOnClickListener {
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), 1
                )
            }
        }
        val settingsBtn = Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(info); addView(micBtn); addView(settingsBtn)
        })
    }
}
