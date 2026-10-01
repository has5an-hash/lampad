package app.lampad.remote

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity(), NetworkController.Listener {

    private lateinit var network: NetworkController
    private var speechRecognizer: SpeechRecognizer? = null
    private var dictationEnabled = false
    private var listening = false
    private var controlModeActive = false
    private var previousInterruptionFilter: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        val savedHost = getSharedPreferences("lampad", MODE_PRIVATE)
            .getString("pc_host", "")
            .orEmpty()

        network = NetworkController(savedHost)
        network.start(this)

        if (savedHost.isBlank()) {
            showSetup()
        } else {
            showTouchpad()
        }
    }

    private fun showSetup() {
        leaveFocusMode()
        dictationEnabled = false
        stopListening()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        val title = TextView(this).apply {
            text = "لم‌پد"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val helpText = TextView(this).apply {
            text = "IP کامپیوتر را وارد کن\nبرنامه ویندوز آدرس را نشان می‌دهد"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 22)
        }

        val ipInput = EditText(this).apply {
            setHint("مثلاً 192.168.1.20")
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            gravity = Gravity.CENTER
        }

        val dndButton = Button(this).apply {
            text = if (hasDndAccess()) {
                "حالت بدون اعلان: فعال"
            } else {
                "فعال‌سازی جلوگیری از اعلان‌ها"
            }
            setOnClickListener {
                if (!hasDndAccess()) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            }
        }

        val connect = Button(this).apply {
            text = "اتصال و شروع"
            setOnClickListener {
                val host = ipInput.text.toString().trim()
                if (host.isNotBlank()) {
                    getSharedPreferences("lampad", MODE_PRIVATE)
                        .edit().putString("pc_host", host).apply()
                    network.setHost(host)
                    network.send("HELLO")
                    showTouchpad()
                }
            }
        }

        layout.addView(title)
        layout.addView(helpText)
        layout.addView(ipInput)
        layout.addView(dndButton)
        layout.addView(connect)
        setContentView(layout)
    }

    private fun showTouchpad() {
        controlModeActive = true
        hideSystemUi()
        val touchpad = TouchpadView(this, network) { showSetup() }
        setContentView(touchpad)
        enterFocusMode()
    }

    private fun hasDndAccess(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager?.isNotificationPolicyAccessGranted == true
    }

    private fun enterFocusMode() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.isNotificationPolicyAccessGranted == true) {
            previousInterruptionFilter = manager.currentInterruptionFilter
            try {
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            } catch (_: Exception) {
            }
        }

        mainHandler.postDelayed({
            if (!controlModeActive) return@postDelayed
            try {
                startLockTask()
            } catch (_: Exception) {
            }
        }, 250)
    }

    private fun leaveFocusMode() {
        if (!controlModeActive) return
        controlModeActive = false

        try {
            stopLockTask()
        } catch (_: Exception) {
        }

        val manager = getSystemService(NotificationManager::class.java)
        val previous = previousInterruptionFilter
        if (
            previous != null &&
            manager?.isNotificationPolicyAccessGranted == true
        ) {
            try {
                manager.setInterruptionFilter(previous)
            } catch (_: Exception) {
            }
        }
        previousInterruptionFilter = null
    }

    override fun onCommand(command: String) {
        runOnUiThread {
            when (command.trim()) {
                "DICTATION_ON" -> enableDictation()
                "DICTATION_OFF" -> disableDictation()
            }
        }
    }

    override fun onConnectionError(message: String) {
    }

    private fun enableDictation() {
        if (dictationEnabled) return
        dictationEnabled = true

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2001)
            return
        }

        startListening()
    }

    private fun disableDictation() {
        dictationEnabled = false
        stopListening()
    }

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    listening = false
                }

                override fun onError(error: Int) {
                    listening = false
                    restartListeningSoon()
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()

                    if (!text.isNullOrBlank()) {
                        handleSpeech(text)
                    }

                    restartListeningSoon()
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (!dictationEnabled || listening) return
        ensureRecognizer()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            restartListeningSoon()
        }
    }

    private fun restartListeningSoon() {
        if (!dictationEnabled) return
        mainHandler.postDelayed({ startListening() }, 450)
    }

    private fun stopListening() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
        listening = false
    }

    private fun handleSpeech(raw: String) {
        val text = raw
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .trim()

        when (text.lowercase()) {
            "اینتر", "enter" -> network.send("KEY|ENTER")
            "بک اسپیس", "بک‌اسپیس", "backspace", "پاک کن" ->
                network.send("KEY|BACKSPACE")
            "تب", "tab" -> network.send("KEY|TAB")
            "اسکیپ", "escape", "esc" -> network.send("KEY|ESC")
            "کپی", "copy" -> network.send("HOTKEY|CTRL|C")
            "پیست", "paste" -> network.send("HOTKEY|CTRL|V")
            "کنترل آ", "همه را انتخاب کن", "همه رو انتخاب کن" ->
                network.send("HOTKEY|CTRL|A")
            else -> {
                val payload = Base64.encodeToString(
                    text.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                network.send("TEXT|$payload")
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (
            requestCode == 2001 &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        }
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(
                    WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars()
                )
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onDestroy() {
        leaveFocusMode()
        dictationEnabled = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        network.close()
        super.onDestroy()
    }
}
