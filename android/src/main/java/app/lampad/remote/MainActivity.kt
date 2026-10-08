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
    private var touchpadView: TouchpadView? = null

    private var voiceEnabled = true
    private var listening = false
    private var editableFocus = false
    private var controlModeActive = false
    private var previousInterruptionFilter: Int? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val restartListeningRunnable = Runnable {
        if (voiceEnabled && controlModeActive) startListening()
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
        if (::network.isInitialized) network.send("RELEASE_ALL")
        leaveFocusMode()
        stopListening()
        touchpadView = null

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 28, 48, 28)
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        val title = TextView(this).apply {
            text = "لم‌پد"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val helpText = TextView(this).apply {
            text = "گوشی و کامپیوتر را به یک Wi‑Fi یا LAN وصل کن.\n" +
                "IP نمایش‌داده‌شده در LemPad Companion ویندوز را وارد کن."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
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

        val dndButton = Button(this).apply {
            text = if (hasDndAccess()) {
                "اعلان‌ها هنگام کنترل: مسدود"
            } else {
                "جلوگیری از مزاحمت اعلان‌ها"
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

        val note = TextView(this).apply {
            text = "در حالت کنترل: گوشی افقی است، میکروفون فرمان‌ها را می‌شنود و دکمه ⌨ کیبورد را باز می‌کند. " +
                "سه انگشت را حدود یک ثانیه نگه دار تا به تنظیمات برگردی."
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }

        layout.addView(title)
        layout.addView(helpText)
        layout.addView(ipInput)
        layout.addView(connect)
        layout.addView(dndButton)
        layout.addView(note)
        setContentView(layout)
    }

    private fun showTouchpad() {
        controlModeActive = true
        hideSystemUi()

        val view = TouchpadView(
            this,
            network,
            onSettingsRequested = { showSetup() },
            onVoiceToggle = { enabled ->
                voiceEnabled = enabled
                if (enabled) {
                    enableVoice()
                } else {
                    stopListening()
                }
            }
        )

        touchpadView = view
        view.setVoiceEnabled(voiceEnabled)
        view.setEditableFocus(editableFocus)
        view.setStatus("در حال اتصال به کامپیوتر…")
        setContentView(view)

        enterFocusMode()
        network.send("HELLO")

        if (voiceEnabled) {
            enableVoice()
        }
    }

    override fun onCommand(command: String) {
        runOnUiThread {
            when {
                command.startsWith("HELLO_ACK|") -> {
                    val machine = command.substringAfter('|').trim()
                    touchpadView?.setStatus("متصل به " + machine)
                }

                command == "FOCUS|EDITABLE" || command == "DICTATION_ON" -> {
                    editableFocus = true
                    touchpadView?.setEditableFocus(true)
                }

                command == "FOCUS|COMMAND" || command == "DICTATION_OFF" -> {
                    editableFocus = false
                    touchpadView?.setEditableFocus(false)
                }

                command.startsWith("STATUS|") -> {
                    val message = decodeBase64(command.substringAfter('|'))
                    if (message.isNotBlank()) touchpadView?.setStatus(message)
                }

                command.startsWith("MODIFIER|") -> {
                    val parts = command.split('|')
                    if (parts.size >= 3) {
                        touchpadView?.setModifierState(
                            parts[1],
                            parts[2].equals("ON", ignoreCase = true)
                        )
                    }
                }
            }
        }
    }

    override fun onConnectionError(message: String) {
        runOnUiThread {
            touchpadView?.setStatus("ارتباط: " + message)
        }
    }

    private fun enableVoice() {
        voiceEnabled = true
        touchpadView?.setVoiceEnabled(true)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }

        startListening()
    }

    private fun ensureRecognizer(): Boolean {
        if (speechRecognizer != null) return true

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            touchpadView?.setStatus("سرویس تشخیص گفتار روی این گوشی در دسترس نیست")
            voiceEnabled = false
            touchpadView?.setVoiceEnabled(false)
            return false
        }

        return try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        listening = true
                        touchpadView?.setStatus(
                            if (editableFocus) "گوش می‌دم؛ متن یا فرمان بگو…" else "گوش می‌دم؛ فرمانت رو بگو…"
                        )
                    }

                    override fun onBeginningOfSpeech() {
                        touchpadView?.setStatus("دارم می‌شنوم…")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        listening = false
                        touchpadView?.setStatus("در حال پردازش…")
                    }

                    override fun onError(error: Int) {
                        listening = false

                        if (voiceEnabled && controlModeActive) {
                            when (error) {
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                    voiceEnabled = false
                                    touchpadView?.setVoiceEnabled(false)
                                    touchpadView?.setStatus("دسترسی میکروفون داده نشده")
                                }

                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartListeningSoon(900)
                                else -> restartListeningSoon(650)
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        listening = false

                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()

                        if (!text.isNullOrBlank()) {
                            touchpadView?.setStatus("شنیدم: " + text.take(70))
                            sendVoice(text)
                        }

                        restartListeningSoon(450)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            true
        } catch (t: Throwable) {
            voiceEnabled = false
            touchpadView?.setVoiceEnabled(false)
            touchpadView?.setStatus("تشخیص گفتار اجرا نشد: " + t.javaClass.simpleName)
            false
        }
    }

    private fun startListening() {
        if (!voiceEnabled || !controlModeActive || listening) return
        if (!ensureRecognizer()) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 4)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Throwable) {
            listening = false
            restartListeningSoon(900)
        }
    }

    private fun sendVoice(text: String) {
        val payload = Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        network.send("VOICE|" + payload)
    }

    private fun restartListeningSoon(delayMs: Long) {
        mainHandler.removeCallbacks(restartListeningRunnable)

        if (voiceEnabled && controlModeActive) {
            mainHandler.postDelayed(restartListeningRunnable, delayMs)
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

    private fun decodeBase64(payload: String): String {
        return try {
            String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
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
                setOnClickListener { recreate() }
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
            voiceEnabled = false
            touchpadView?.setVoiceEnabled(false)
            touchpadView?.setStatus("برای فرمان و تایپ صوتی، دسترسی میکروفون لازم است")
        }
    }

    override fun onDestroy() {
        try {
            if (::network.isInitialized) network.send("RELEASE_ALL")
            leaveFocusMode()
        } catch (_: Throwable) {
        }

        voiceEnabled = false
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
