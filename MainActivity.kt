package com.example.minimalmeditationtimer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
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
    private lateinit var aboutContainer: LinearLayout

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

        // Конвертер DP в PX для красивых отступов и размеров на любых экранах
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }

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
                crossfade(mainContainer, settingsContainer)
            }
        }
        val settingsBtnParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            setMargins(0, dp(20), dp(20), 0)
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
            setPadding(0, 0, 0, dp(8))
            gravity = Gravity.CENTER
        }
        centerBlock.addView(statusTextView)

        timeTextView = TextView(this).apply {
            text = "00:00"
            textSize = 80f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(24))
            gravity = Gravity.CENTER
        }
        centerBlock.addView(timeTextView)

        val actionButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        
        // Красивый стиль для кнопок Старт/Стоп
        val buttonBackground = GradientDrawable().apply {
            setColor(0xFF2A2A2A.toInt())
            cornerRadius = dp(8).toFloat()
        }
        
        val startButton = Button(this).apply { 
            text = "Старт"
            setTextColor(0xFFFFFFFF.toInt())
            background = buttonBackground.constantState?.newDrawable()
            setPadding(dp(24), dp(12), dp(24), dp(12)) 
            setOnClickListener { startTimerService() } 
        }
        val stopButton = Button(this).apply { 
            text = "Стоп"
            setTextColor(0xFFFFFFFF.toInt())
            background = buttonBackground.constantState?.newDrawable()
            setPadding(dp(24), dp(12), dp(24), dp(12)) 
            setOnClickListener { stopTimerService() } 
        }
        actionButtonsLayout.addView(startButton)
        actionButtonsLayout.addView(TextView(this).apply { width = dp(24) }) // отступ между кнопками
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
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        settingsContainer.addView(TextView(this).apply { text = "Время подготовки:"; setTextColor(0xFF888888.toInt()); setPadding(0, dp(8), 0, dp(4)) })
        val prepLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        prepMinEditText = createTimeEditText("Мин", dp)
        prepSecEditText = createTimeEditText("Сек", dp)
        prepLayout.addView(prepMinEditText)
        prepLayout.addView(TextView(this).apply { text = " : "; setTextColor(0xFFFFFFFF.toInt()); textSize = 18f })
        prepLayout.addView(prepSecEditText)
        settingsContainer.addView(prepLayout)

        settingsContainer.addView(TextView(this).apply { text = "Время медитации:"; setTextColor(0xFF888888.toInt()); setPadding(0, dp(8), 0, dp(4)) })
        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        mainMinEditText = createTimeEditText("Мин", dp)
        mainSecEditText = createTimeEditText("Сек", dp)
        mainLayout.addView(mainMinEditText)
        mainLayout.addView(TextView(this).apply { text = " : "; setTextColor(0xFFFFFFFF.toInt()); textSize = 18f })
        mainLayout.addView(mainSecEditText)
        settingsContainer.addView(mainLayout)

        // Настройка автоперехода фокуса между полями ввода
        setupAutoAdvance(prepMinEditText, prepSecEditText)
        setupAutoAdvance(prepSecEditText, mainMinEditText)
        setupAutoAdvance(mainMinEditText, mainSecEditText)

        settingsContainer.addView(TextView(this).apply { text = "Громкость гонга:"; setTextColor(0xFF888888.toInt()); setPadding(0, dp(8), 0, dp(4)) })
        val volumeLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        
        // Кастомизация SeekBar под общий дзен-стиль
        volumeSeekBar = SeekBar(this).apply { 
            max = 100
            progressDrawable?.setColorFilter(0xFF666666.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
            thumb?.setColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        volumeValueTextView = TextView(this).apply { text = "70%"; setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(8), 0, 0, 0) }
        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                savedVolumeProgress = progress
                volumeValueTextView.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        volumeLayout.addView(volumeSeekBar, LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT))
        volumeLayout.addView(volumeValueTextView)
        settingsContainer.addView(volumeLayout)

        soundNameTextView = TextView(this).apply { text = "Звук по умолчанию"; setTextColor(0xFF888888.toInt()); setPadding(0, dp(4), 0, dp(8)); gravity = Gravity.CENTER }
        
        val pickSoundButton = Button(this).apply { 
            text = "Выбрать аудиофайл"
            setTextColor(0xFFFFFFFF.toInt())
            background = buttonBackground.constantState?.newDrawable()
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { audioPickerLauncher.launch("audio/*") } 
        }
        settingsContainer.addView(pickSoundButton)
        settingsContainer.addView(soundNameTextView)

        // Горизонтальный ряд для кнопок «О программе» и «Сохранить» (НАД разрешениями)
        val buttonsRowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }

        // Кнопка «О программе» (Прозрачная с тонкой рамкой в стиле Outlined Button)
        val outlinedBackground = GradientDrawable().apply {
            setColor(0x00000000)
            setStroke(dp(1), 0xFF555555.toInt())
            cornerRadius = dp(6).toFloat()
        }

        val aboutButton = Button(this).apply {
            text = "О программе"
            setTextColor(0xFFCCCCCC.toInt())
            background = outlinedBackground.constantState?.newDrawable()
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                hideKeyboard()
                crossfade(settingsContainer, aboutContainer)
            }
        }

        // Кнопка «Сохранить»
        val closeSettingsButton = Button(this).apply {
            text = "Сохранить"
            setTextColor(0xFFFFFFFF.toInt())
            background = buttonBackground.constantState?.newDrawable()
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener {
                savePreferences()
                hideKeyboard()
                crossfade(settingsContainer, mainContainer)
            }
        }

        buttonsRowLayout.addView(aboutButton)
        buttonsRowLayout.addView(TextView(this).apply { width = dp(16) }) // Отступ между кнопками
        buttonsRowLayout.addView(closeSettingsButton)
        settingsContainer.addView(buttonsRowLayout)

        // Кнопка режима «Не беспокоить» (Текстовая ссылка)
        val dndButton = Button(this).apply {
            text = "Разрешить режим «Не беспокоить»"
            setTextColor(0xFF888888.toInt())
            setBackgroundColor(0x00000000)
            textSize = 13f
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    // ИСПРАВЛЕНО: Теперь используется корректный метод Android API
                    if (!nm.isNotificationPolicyAccessGranted) {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    } else {
                        Toast.makeText(this@MainActivity, "Разрешение уже есть!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        settingsContainer.addView(dndButton)

        // Кнопка «Разрешить работу в фоне» (Текстовая ссылка под DND)
        val backgroundButton = Button(this).apply {
            text = "Разрешить работу в фоне"
            setTextColor(0xFF888888.toInt())
            setBackgroundColor(0x00000000)
            textSize = 13f
            setOnClickListener {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (pm.isIgnoringBatteryOptimizations(packageName)) {
                        Toast.makeText(this@MainActivity, "Разрешение уже предоставлено!", Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            startActivity(intent)
                        }
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Разрешение уже предоставлено!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        settingsContainer.addView(backgroundButton)

        val settingsParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        rootLayout.addView(settingsContainer, settingsParams)


        // ================= ЭКРАН 3: О ПРОГРАММЕ (ПОЛНОЕ ПЕРЕКРЫТИЕ) =================
        aboutContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(0xFF1E1E1E.toInt()) // Полное аскетичное перекрытие настроек
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        // Заголовок программы
        val aboutTitle = TextView(this).apply {
            text = "Zazen Timer v1.0"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(20))
            gravity = Gravity.CENTER
        }
        aboutContainer.addView(aboutTitle)

        // Описание программы
        val aboutText = TextView(this).apply {
            text = "Минималистичный таймер для практики медитации.\n\nТаймер делает только то, что должен: переводит телефон в режим «Не беспокоить», отсчитывает время и подает сигналы начала и окончания медитации."
            textSize = 15f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 0, 0, dp(20))
            gravity = Gravity.CENTER
        }
        aboutContainer.addView(aboutText)

        // Автор и кликабельный сайт
        val aboutAuthor = TextView(this).apply {
            text = "Автор: Игорь Василевский\nigorvasilevsky.com"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        android.text.util.Linkify.addLinks(aboutAuthor, android.text.util.Linkify.WEB_URLS)
        aboutAuthor.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        aboutContainer.addView(aboutAuthor)

        // Кнопка возврата назад в настройки
        val aboutBackButton = Button(this).apply {
            text = "Назад"
            setTextColor(0xFFFFFFFF.toInt())
            background = buttonBackground.constantState?.newDrawable()
            setPadding(dp(24), dp(8), dp(24), dp(8))
            setOnClickListener {
                crossfade(aboutContainer, settingsContainer)
            }
        }
        aboutContainer.addView(aboutBackButton)

        val aboutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        rootLayout.addView(aboutContainer, aboutParams)

        setContentView(rootLayout)
        loadPreferences()
    }

    // Создание красивых полей ввода со скругленными углами
    private fun createTimeEditText(hintText: String, dp: (Int) -> Int) = EditText(this).apply {
        hint = hintText
        setHintTextColor(0xFF555555.toInt())
        setTextColor(0xFFFFFFFF.toInt())
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(android.text.InputFilter.LengthFilter(2))
        gravity = Gravity.CENTER
        width = dp(64)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        
        // Убираем старую линию снизу, делаем аккуратный темный блок
        val editBg = GradientDrawable().apply {
            setColor(0xFF2B2B2B.toInt())
            cornerRadius = dp(6).toFloat()
        }
        background = editBg
    }

    // Слушатель для автоперехода фокуса на следующее поле, как только введены 2 цифры
    private fun setupAutoAdvance(current: EditText, next: EditText) {
        current.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s?.length == 2) {
                    next.requestFocus()
                    next.setSelection(next.text.length)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    // Безопасное принудительное скрытие клавиатуры при сохранении
    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    // Функция плавной дзен-анимации перехода между экранами
    private fun crossfade(fromView: View, toView: View) {
        toView.alpha = 0f
        toView.visibility = View.VISIBLE

        toView.animate()
            .alpha(1f)
            .setDuration(250)
            .setListener(null)

        fromView.animate()
            .alpha(0f)
            .setDuration(250)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fromView.visibility = View.GONE
                }
            })
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
        if (aboutContainer.visibility == View.VISIBLE) {
            crossfade(aboutContainer, settingsContainer)
        } else if (settingsContainer.visibility == View.VISIBLE) {
            crossfade(settingsContainer, mainContainer)
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
        try {
            unregisterReceiver(timerReceiver)
        } catch (e: Exception) {
            // Изолированная защита
        }
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        releaseWakeLock()
        super.onDestroy()
    }
}
