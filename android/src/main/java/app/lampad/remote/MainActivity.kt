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
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), NetworkController.Listener {

    private lateinit var network: NetworkController
    private var speechRecognizer: SpeechRecognizer? = null
    private var dictationEnabled = false
    private var listening = false
    private var controlModeActive = false
    private var previousInterruptionFilter: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val restartListeningRunnable = Runnable {
        if (dictationEnabled) startListening()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = Color.BLACK
                window.navigationBarColor = Color.BLACK
            }

            val savedHost = getSharedPreferences("lampad", MODE_PRIVATE)
                .getString("pc_host", "")
                .orEmpty()

            network = NetworkController(savedHost)
            showSetup(savedHost)
        } catch (t: Throwable) {
            showRecoveryScreen(t)
        }
    }

    private fun showSetup(prefillHost: String = getSavedHost()) {
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
            text = "گوشی و کامپیوتر را به یک Wi‑Fi وصل کن.\nIP نمایش‌داده‌شده در برنامه ویندوز را اینجا وارد کن."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 22)
        }

        val ipInput = EditText(this).apply {
            hint = "مثلاً 192.168.1.20"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            gravity = Gravity.CENTER
            setText(prefillHost)
            selectAll()
        }

        val dndButton = Button(this).apply {
            text = if (hasDndAccess()) {
                "جلوگیری از اعلان‌ها: فعال"
            } else {
                "فعال‌سازی جلوگیری از اعلان‌ها"
            }
            setOnClickListener {
                if (!hasDndAccess()) {
                    try {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    } catch (_: Throwable) {
                        Toast.makeText(
                            this@MainActivity,
                            "تنظیمات اعلان در این گوشی در دسترس نیست.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        val connect = Button(this).apply {
            text = "شروع کنترل"
            setOnClickListener {
                val host = ipInput.text.toString().trim()
                if (host.isBlank()) {
                    ipInput.error = "IP کامپیوتر را وارد کن"
                    return@setOnClickListener
                }

                getSharedPreferences("lampad", MODE_PRIVATE)
                    .edit()
                    .putString("pc_host", host)
                    .apply()

                network.setHost(host)
                network.start(this@MainActivity)
                showTouchpad()
            }
        }

        val note = TextView(this).apply {
            text = "برای برگشت از حالت تاچ‌پد، سه انگشت را حدود یک ثانیه نگه دار."
            textSize = 13f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 26, 0, 0)
        }

        layout.addView(title)
        layout.addView(helpText)
        layout.addView(ipInput)
        layout.addView(dndButton)
        layout.addView(connect)
        layout.addView(note)
        setContentView(layout)
    }

    private fun showRecoveryScreen(error: Throwable) {
        try {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
                setBackgroundColor(Color.rgb(18, 18, 18))
            }

            val title = TextView(this).apply {
                text = "لم‌پد با خطای شروع روبه‌رو شد"
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }

            val details = TextView(this).apply {
                text = error.javaClass.simpleName + ": " + (error.message ?: "خطای ناشناخته")
                textSize = 13f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 20)
            }

            val retry = Button(this).apply {
                text = "تلاش دوباره"
                setOnClickListener {
                    recreate()
                }
            }

            layout.addView(title)
            layout.addView(details)
            layout.addView(retry)
            setContentView(layout)
        } catch (_: Throwable) {
            finish()
        }
    }

    private fun getSavedHost(): String =
        getSharedPreferences("lampad", MODE_PRIVATE)
            .getString("pc_host", "")
            .orEmpty()

    private fun showTouchpad() {
        controlModeActive = true
        hideSystemUi()

        val touchpad = TouchpadView(this, network) {
            showSetup()
        }

        setContentView(touchpad)
        enterFocusMode()
    }

    private fun hasDndAccess(): Boolean {
        return try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.isNotificationPolicyAccessGranted == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun enterFocusMode() {
        val manager = try {
            getSystemService(NotificationManager::class.java)
        } catch (_: Throwable) {
            null
        }

        if (manager?.isNotificationPolicyAccessGranted == true) {
            previousInterruptionFilter = try {
                manager.currentInterruptionFilter
            } catch (_: Throwable) {
                null
            }

            try {
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            } catch (_: Throwable) {
            }
        }

        mainHandler.postDelayed({
            if (!controlModeActive) return@postDelayed
            try {
                startLockTask()
            } catch (_: Throwable) {
            }
        }, 350)
    }

    private fun leaveFocusMode() {
        if (!controlModeActive) return
        controlModeActive = false

        try {
            stopLockTask()
        } catch (_: Throwable) {
        }

        val previous = previousInterruptionFilter
        if (previous != null && hasDndAccess()) {
            try {
                getSystemService(NotificationManager::class.java)
                    ?.setInterruptionFilter(previous)
            } catch (_: Throwable) {
            }
        }

        previousInterruptionFilter = null
        showSystemUi()
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
        runOnUiThread {
            Toast.makeText(
                this,
                "اتصال لم‌پد: " + message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun enableDictation() {
        if (dictationEnabled) return
        dictationEnabled = true

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }

        startListening()
    }

    private fun disableDictation() {
        dictationEnabled = false
        stopListening()
    }

    private fun ensureRecognizer(): Boolean {
        if (speechRecognizer != null) return true

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "سرویس تشخیص گفتار روی این گوشی در دسترس نیست.",
                Toast.LENGTH_LONG
            ).show()
            dictationEnabled = false
            return false
        }

        return try {
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
            true
        } catch (t: Throwable) {
            dictationEnabled = false
            Toast.makeText(
                this,
                "تشخیص گفتار اجرا نشد: " + t.javaClass.simpleName,
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    private fun startListening() {
        if (!dictationEnabled || listening) return
        if (!ensureRecognizer()) return

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
        } catch (_: Throwable) {
            listening = false
            restartListeningSoon()
        }
    }

    private fun restartListeningSoon() {
        mainHandler.removeCallbacks(restartListeningRunnable)
        if (dictationEnabled) {
            mainHandler.postDelayed(restartListeningRunnable, 650)
        }
    }

    private fun stopListening() {
        mainHandler.removeCallbacks(restartListeningRunnable)
        try {
            speechRecognizer?.cancel()
        } catch (_: Throwable) {
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
                network.send("TEXT|" + payload)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != REQUEST_AUDIO) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            dictationEnabled = false
            Toast.makeText(
                this,
                "برای تایپ صوتی باید دسترسی میکروفون را بدهی.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    @Suppress("DEPRECATION")
    private fun showSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && controlModeActive) {
            hideSystemUi()
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (controlModeActive) {
            showSetup()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            leaveFocusMode()
        } catch (_: Throwable) {
        }

        dictationEnabled = false
        mainHandler.removeCallbacksAndMessages(null)

        try {
            speechRecognizer?.destroy()
        } catch (_: Throwable) {
        }
        speechRecognizer = null

        if (::network.isInitialized) {
            network.close()
        }

        super.onDestroy()
    }

    companion object {
        private const val REQUEST_AUDIO = 2001
    }
}
