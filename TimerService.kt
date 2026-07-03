package com.example.minimalmeditationtimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TimerService : Service() {

    private var countDownTimer: CountDownTimer? = null
    private var notificationManager: NotificationManager? = null
    private var originalInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL

    private val CHANNEL_ID = "MeditationTimerChannel"
    private val NOTIFICATION_ID = 101

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_TIMER" -> {
                val prepTimeMs = intent.getLongExtra("PREP_TIME_MS", 0L)
                val mainTimeMs = intent.getLongExtra("MAIN_TIME_MS", 0L)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val nm = notificationManager ?: getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    originalInterruptionFilter = nm.currentInterruptionFilter
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForeground(NOTIFICATION_ID, buildNotification("Медитация", "Идет подготовка..."))
                }
                startSequentialTimers(prepTimeMs, mainTimeMs)
            }
            "STOP_TIMER" -> {
                stopTimerAndService()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSequentialTimers(prepMs: Long, mainMs: Long) {
        countDownTimer = object : CountDownTimer(prepMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                sendTimeTickSignal("TICK_PREP", millisUntilFinished / 1000)
            }
            override fun onFinish() {
                sendBroadcastSignal("PREP_FINISHED")
                setPrioritySilentMode(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    updateNotification("Медитация", "Основная сессия (Режим «Не беспокоить»)...")
                }
                startMainTimer(mainMs)
            }
        }.start()
    }

    private fun startMainTimer(mainMs: Long) {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(mainMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                sendTimeTickSignal("TICK_MAIN", millisUntilFinished / 1000)
            }
            override fun onFinish() {
                sendBroadcastSignal("MAIN_FINISHED")
                stopTimerAndService()
            }
        }.start()
    }

    private fun setPrioritySilentMode(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nm = notificationManager ?: getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                try {
                    if (enable) {
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    } else {
                        nm.setInterruptionFilter(originalInterruptionFilter)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun sendBroadcastSignal(action: String) {
        sendBroadcast(Intent("com.example.minimalmeditationtimer.$action").apply { setPackage(packageName) })
    }

    private fun sendTimeTickSignal(action: String, secondsLeft: Long) {
        sendBroadcast(Intent("com.example.minimalmeditationtimer.$action").apply {
            putExtra("SECONDS_LEFT", secondsLeft)
            setPackage(packageName)
        })
    }

    private fun stopTimerAndService() {
        countDownTimer?.cancel()
        setPrioritySilentMode(false)
        sendBroadcastSignal("TIMER_STOPPED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopTimerAndService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Таймер", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()
    }

    private fun updateNotification(title: String, text: String) {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }
}
