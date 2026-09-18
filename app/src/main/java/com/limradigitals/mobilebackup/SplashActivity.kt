package com.limradigitals.mobilebackup

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.activity.ComponentActivity

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_app_icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        setContentView(logo)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 750)
    }
}
