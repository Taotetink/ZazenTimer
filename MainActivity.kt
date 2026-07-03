package com.example.minimalmeditationtimer

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var localSoundFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Контейнеры экранов
    private lateinit var mainContainer: RelativeLayout
    private lateinit var settingsContainer: LinearLayout

    // Элементы главного экрана
    private lateinit var statusTextView: TextView
    private lateinit var timeTextView: TextView

    // Элементы экрана настроек
    private lateinit var prepMinEditText: EditText
    private lateinit var prepSecEditText: EditText
    private lateinit var mainMinEditText: EditText
    private lateinit var mainSecEditText: EditText
    private lateinit var soundNameTextView: TextView
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var volumeValueTextView: TextView
    private var savedVolumeProgress = 70

    private val audioPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val cacheFile = File(cacheDir, "meditation_sound.mp3")
                val outputStream = FileOutputStream(cacheFile)
                inputStream?.use { input -> outputStream.use { output -> input.copyTo(output) } }

                val displayName = uri.lastPathSegment ?: "Звук сохранен"
                soundNameTextView.text = displayName

                getSharedPreferences("timer_prefs", Context.MODE_PRIVATE).edit()
                    .putString("sound_display_name", displayName).apply()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка сохранения файла", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                "com.example.minimalmeditationtimer.TICK_PREP" -> {
                    val sec = intent.getLongExtra("SECONDS_LEFT", 0L)
                    statusTextView.text = "Подготовка..."
                    timeTextView.text = formatTime(sec)
                }
                "com.example.minimalmeditationtimer.TICK_MAIN" -> {
                    val sec = intent.getLongExtra("SECONDS_LEFT", 0L)
                    statusTextView.text = "Медитация..."
                    timeTextView.text = formatTime(sec)
                }
                "com.example.minimalmeditationtimer.PREP_FINISHED" -> {
                    Toast.makeText(this@MainActivity, "Сессия началась!", Toast.LENGTH_SHORT).show()
                    playSound()
                }
                "com.example.minimalmeditationtimer.MAIN_FINISHED" -> {
                    statusTextView.text = "Сессия завершена"
                    timeTextView.text = "00:00"
                    playSound()
                }
                "com.example.minimalmeditationtimer.TIMER_STOPPED" -> {
                    statusTextView.text = "Остановлен"
                    timeTextView.text = "00:00"
                    releaseWakeLock()
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localSoundFile = File(cacheDir, "meditation_sound.mp3")

        // Главный корневой контейнер
        val rootLayout = RelativeLayout(this).apply {
            setBackgroundColor(0xFF1E1E1E.toInt())
        }

        // ================= ЭКРАН 1: ГЛАВНЫЙ ТАЙМЕР =================
        mainContainer = RelativeLayout(this).apply {
            visibility = View.VISIBLE
        }

        // 1. Кнопка настроек в верхнем правом углу в виде большого круга "〇"
        val openSettingsButton = Button(this).apply {
            text = "〇"
            textSize = 26f
            setBackgroundColor(0x00000000) // полностью прозрачный фон
            setTextColor(0xFF666666.toInt()) // мягкий темно-серый цвет
            setOnClickListener {
                mainContainer.visibility = View.GONE
                settingsContainer.visibility = View.VISIBLE
            }
        }
        val settingsBtnParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            setMargins(0, 40, 40, 0) // отступы от верхнего и правого краев экрана
        }
        mainContainer.addView(openSettingsButton, settingsBtnParams)

        // 2. Центральный блок с таймером и кнопками управления
        val centerBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        statusTextView = TextView(this).apply {
            text = "Готово к запуску"
            textSize = 22f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 16)
            gravity = Gravity.CENTER
        }
        centerBlock.addView(statusTextView)

        timeTextView = TextView(this).apply {
            text = "00:00"
            textSize = 80f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 48)
            gravity = Gravity.CENTER
        }
        centerBlock.addView(timeTextView)

        val actionButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val startButton = Button(this).apply { text = "Старт"; setPadding(48, 24, 48, 24); setOnClickListener { startTimerService() } }
        val stopButton = Button(this).apply { text = "Стоп"; setPadding(48, 24, 48, 24); setOnClickListener { stopTimerService() } }
        actionButtonsLayout.addView(startButton)
        actionButtonsLayout.addView(TextView(this).apply { width = 48 }) // отступ между кнопками Старт и Стоп
        actionButtonsLayout.addView(stopButton)
        centerBlock.addView(actionButtonsLayout)

        // Размещаем центральный блок строго по центру экрана
        val centerParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.CENTER_IN_PARENT)
        }
        mainContainer.addView(centerBlock, centerParams)
        rootLayout.addView(mainContainer, RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT))


        // ================= ЭКРАН 2: НАСТРОЙКИ =================
        settingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(48, 48, 48, 48)
        }

        settingsContainer.addView(TextView(this).apply { text = "Время подготовки:"; setTextColor(0xFF888888.toInt()); setPadding(0, 16, 0, 8) })
        val prepLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        prepMinEditText = createTimeEditText("Мин")
        prepSecEditText = createTimeEditText("Сек")
        prepLayout.addView(prepMinEditText)
        prepLayout.addView(TextView(this).apply { text = " : "; setTextColor(0xFFFFFFFF.toInt()); textSize = 18f })
        prepLayout.addView(prepSecEditText)
        settingsContainer.addView(prepLayout)

        settingsContainer.addView(TextView(this).apply { text = "Время медитации:"; setTextColor(0xFF888888.toInt()); setPadding(0, 16, 0, 8) })
        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        mainMinEditText = createTimeEditText("Мин")
        mainSecEditText = createTimeEditText("Сек")
        mainLayout.addView(mainMinEditText)
        mainLayout.addView(TextView(this).apply { text = " : "; setTextColor(0xFFFFFFFF.toInt()); textSize = 18f })
        mainLayout.addView(mainSecEditText)
        settingsContainer.addView(mainLayout)

        settingsContainer.addView(TextView(this).apply { text = "Громкость гонга:"; setTextColor(0xFF888888.toInt()); setPadding(0, 16, 0, 8) })
        val volumeLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        volumeSeekBar = SeekBar(this).apply { max = 100 }
        volumeValueTextView = TextView(this).apply { text = "70%"; setTextColor(0xFFFFFFFF.toInt()); setPadding(16, 0, 0, 0) }
        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                savedVolumeProgress = progress
                volumeValueTextView.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        volumeLayout.addView(volumeSeekBar, LinearLayout.LayoutParams(350, LinearLayout.LayoutParams.WRAP_CONTENT))
        volumeLayout.addView(volumeValueTextView)
        settingsContainer.addView(volumeLayout)

        soundNameTextView = TextView(this).apply { text = "Звук по умолчанию"; setTextColor(0xFF888888.toInt()); setPadding(0, 4, 0, 12); gravity = Gravity.CENTER }
        val pickSoundButton = Button(this).apply { text = "Выбрать аудиофайл"; setOnClickListener { audioPickerLauncher.launch("audio/*") } }
        settingsContainer.addView(pickSoundButton)
        settingsContainer.addView(soundNameTextView)

        val dndButton = Button(this).apply {
            text = "Разрешить режим «Не беспокоить»"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (!nm.isNotificationPolicyAccessGranted) {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    } else {
                        Toast.makeText(this@MainActivity, "Разрешение уже есть!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        settingsContainer.addView(dndButton)

        val closeSettingsButton = Button(this).apply {
            text = "Сохранить и вернуться"
            setPadding(0, 24, 0, 0)
            setOnClickListener {
                savePreferences()
                settingsContainer.visibility = View.GONE
                mainContainer.visibility = View.VISIBLE
            }
        }
        settingsContainer.addView(closeSettingsButton)

        val settingsParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        rootLayout.addView(settingsContainer, settingsParams)

        setContentView(rootLayout)
        loadPreferences()
    }
    private fun createTimeEditText(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(0xFF555555.toInt())
        setTextColor(0xFFFFFFFF.toInt())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        gravity = Gravity.CENTER
        width = 150
    }

    private fun playSound() {
        try {
            acquireWakeLock()
            mediaPlayer?.release()

            val volumeLevel = savedVolumeProgress / 100f
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )

                if (localSoundFile != null && localSoundFile!!.exists() && localSoundFile!!.length() > 0) {
                    setDataSource(localSoundFile!!.absolutePath)
                } else {
                    val defaultUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                        ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    setDataSource(this@MainActivity, defaultUri)
                }

                setVolume(volumeLevel, volumeLevel)
                prepare()
                start()
                setOnCompletionListener { releaseWakeLock() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZazenTimer:WakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 1000L)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) { wakeLock?.release() }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startTimerService() {
        savePreferences()
        val prepMin = prepMinEditText.text.toString().toLongOrNull() ?: 0L
        val prepSec = prepSecEditText.text.toString().toLongOrNull() ?: 0L
        val mainMin = mainMinEditText.text.toString().toLongOrNull() ?: 0L
        val mainSec = mainSecEditText.text.toString().toLongOrNull() ?: 0L

        val prepTimeMs = (prepMin * 60 + prepSec) * 1000
        val mainTimeMs = (mainMin * 60 + mainSec) * 1000

        if (mainTimeMs == 0L) {
            Toast.makeText(this, "Задайте время медитации", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, TimerService::class.java).apply {
            action = "START_TIMER"
            putExtra("PREP_TIME_MS", prepTimeMs)
            putExtra("MAIN_TIME_MS", mainTimeMs)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopTimerService() {
        startService(Intent(this, TimerService::class.java).apply { action = "STOP_TIMER" })
    }

    private fun formatTime(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun savePreferences() {
        getSharedPreferences("timer_prefs", Context.MODE_PRIVATE).edit().apply {
            putString("prep_min", prepMinEditText.text.toString())
            putString("prep_sec", prepSecEditText.text.toString())
            putString("main_min", mainMinEditText.text.toString())
            putString("main_sec", mainSecEditText.text.toString())
            putInt("volume", savedVolumeProgress)
            apply()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
        prepMinEditText.setText(prefs.getString("prep_min", ""))
        prepSecEditText.setText(prefs.getString("prep_sec", ""))
        mainMinEditText.setText(prefs.getString("main_min", ""))
        mainSecEditText.setText(prefs.getString("main_sec", ""))
        savedVolumeProgress = prefs.getInt("volume", 70)
        volumeSeekBar.progress = savedVolumeProgress
        volumeValueTextView.text = "$savedVolumeProgress%"
        soundNameTextView.text = prefs.getString("sound_display_name", "Звук по умолчанию")
    }

    override fun onBackPressed() {
        if (settingsContainer.visibility == View.VISIBLE) {
            settingsContainer.visibility = View.GONE
            mainContainer.visibility = View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("com.example.minimalmeditationtimer.TICK_PREP")
            addAction("com.example.minimalmeditationtimer.TICK_MAIN")
            addAction("com.example.minimalmeditationtimer.PREP_FINISHED")
            addAction("com.example.minimalmeditationtimer.MAIN_FINISHED")
            addAction("com.example.minimalmeditationtimer.TIMER_STOPPED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(timerReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(timerReceiver)
        mediaPlayer?.release()
        releaseWakeLock()
        super.onDestroy()
    }
}
