package com.tankful.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tankful.android.reminders.SnoozeReceiver
import com.tankful.android.ui.TankfulRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deliverSnooze(intent)
        setContent { TankfulRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deliverSnooze(intent)
    }

    private fun deliverSnooze(intent: Intent?) {
        val id = intent?.getStringExtra(SnoozeReceiver.EXTRA_ID) ?: return
        sendBroadcast(Intent(this, SnoozeReceiver::class.java).putExtra(SnoozeReceiver.EXTRA_ID, id))
    }
}
