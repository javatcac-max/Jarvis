package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

enum class AppLanguage(val label: String, val locale: Locale, val tag: String, val wikiPrefix: String) {
    ENGLISH("EN", Locale.US, "en-US", "en"),
    NEPALI("नेपा", Locale("ne", "NP"), "ne-NP", "ne"),
    HINDI("हिन्द", Locale("hi", "IN"), "hi-IN", "hi")
}

var activeWorkingModel: String? = null
val chatHistory = mutableListOf<Pair<String, String>>()

class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        var instance: JarvisAccessibilityService? = null
        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}

class JarvisFloatingService : Service() {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null

    companion object {
        var isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            150, 150,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 350
        }

        val orbImage = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundColor(android.graphics.Color.argb(190, 20, 20, 20))
            setPadding(25, 25, 25, 25)
        }
        floatingView = orbImage

        orbImage.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 15 && diffY < 15) {
                            val launchApp = Intent(this@JarvisFloatingService, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            startActivity(launchApp)
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(floatingView, params)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (floatingView != null) windowManager?.removeView(floatingView)
    }
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady by mutableStateOf(false)
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

        textToSpeech = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        setContent {
            JarvisMainScreen(
                context = this,
                prefs = prefs,
                speechRecognizer = speechRecognizer!!,
                onSpeak = { text, lang -> speakWithHumanTouch(text, lang) }
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tuneJarvisVoice(textToSpeech, AppLanguage.ENGLISH)
        }
    }

    private fun tuneJarvisVoice(tts: TextToSpeech?, language: AppLanguage) {
        tts?.let { engine ->
            engine.language = language.locale
            try {
                val availableVoices = engine.voices
                if (availableVoices != null) {
                    val naturalVoice = availableVoices.firstOrNull { v ->
                        v.locale.language == language.locale.language &&
                        !v.isNetworkConnectionRequired &&
                        (v.name.contains("male", ignoreCase = true) || 
                         v.name.contains("neural", ignoreCase = true) ||
                         v.name.contains("en-gb", ignoreCase = true))
                    } ?: availableVoices.firstOrNull { v -> v.locale.language == language.locale.language }

                    if (naturalVoice != null) engine.voice = naturalVoice
                }
            } catch (_: Exception) {}

            engine.setPitch(0.92f)
            engine.setSpeechRate(1.02f)
        }
    }

    private fun speakWithHumanTouch(text: String, language: AppLanguage) {
        if (!isTtsReady) return
        textToSpeech?.let { tts ->
            tuneJarvisVoice(tts, language)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_REPLY")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}

@Composable
fun JarvisMainScreen(
    context: Context,
    prefs: SharedPreferences,
    speechRecognizer: SpeechRecognizer,
    onSpeak: (String, AppLanguage) -> Unit
) {
    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "")?.trim() ?: "") }
    var showKeyDialog by remember { mutableStateOf(apiKey.isEmpty()) }
    var tempKeyInput by remember { mutableStateOf(apiKey) }

    var hapticsEnabled by remember { mutableStateOf(prefs.getBoolean("haptics_enabled", true)) }
    var selectedLanguage by remember { mutableStateOf(AppLanguage.ENGLISH) }
    var isListening by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var rmsLevel by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("TAP ORB TO COMMUNICATE") }
    var liveTranscription by remember { mutableStateOf("") }
    var isFloatingActive by remember { mutableStateOf(JarvisFloatingService.isRunning) }

    val coroutineScope = rememberCoroutineScope()

    fun triggerHaptic(duration: Long = 35) {
        if (!hapticsEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(duration)
            }
        } catch (_: Exception) {}
    }

    fun startVoiceSession() {
        triggerHaptic(40)
        liveTranscription = ""
        statusText = "LISTENING..."
        isListening = true

        startListeningResponsive(
            context = context,
            speechRecognizer = speechRecognizer,
            language = selectedLanguage,
            onStateChange = { listening -> isListening = listening },
            onRms = { rms -> rmsLevel = rms },
            onPartial = { partial -> liveTranscription = "\"$partial\"" },
            onFinalResult = { finalResult ->
                liveTranscription = "\"$finalResult\""
                statusText = "JARVIS IS THINKING..."
                isThinking = true
                isListening = false

                coroutineScope.launch {
                    val reply = processEverything(context, prefs, finalResult, apiKey, selectedLanguage, ::triggerHaptic)
                    isThinking = false
                    statusText = reply
                    onSpeak(reply, selectedLanguage)
                }
            },
            onError = { errMsg ->
                statusText = errMsg
                isListening = false
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startVoiceSession() else statusText = "MICROPHONE PERMISSION NEEDED"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 50.dp, start = 16.dp, end = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppLanguage.entries.forEach { lang ->
                        val isSelected = selectedLanguage == lang
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isSelected) Color(0xFF2A2A2A) else Color(0xFF111111),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    triggerHaptic()
                                    selectedLanguage = lang
                                    statusText = "${lang.name} ACTIVE"
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = lang.label,
                                color = if (isSelected) Color.White else Color(0xFF666666),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val isAccessActive = JarvisAccessibilityService.isEnabled()
                    Box(
                        modifier = Modifier
                            .background(if (isAccessActive) Color(0xFF1B3820) else Color(0xFF382020), RoundedCornerShape(16.dp))
                            .clickable {
                                triggerHaptic()
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isAccessActive) "⚡ ACCESS" else "⚠️ ACCESS",
                            color = if (isAccessActive) Color(0xFF00FF88) else Color(0xFFFF7777),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
                            .clickable {
                                triggerHaptic()
                                showKeyDialog = true
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (apiKey.isEmpty()) "⚠️ KEY" else "🔑 KEY",
                            color = if (apiKey.isEmpty()) Color(0xFFFF5555) else Color(0xFFE0E0E0),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Floating Overlay Pill
                Box(
                    modifier = Modifier
                        .background(if (isFloatingActive) Color(0xFF1B2B38) else Color(0xFF161616), RoundedCornerShape(14.dp))
                        .clickable {
                            triggerHaptic()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } else {
                                if (isFloatingActive) {
                                    context.stopService(Intent(context, JarvisFloatingService::class.java))
                                    isFloatingActive = false
                                } else {
                                    context.startService(Intent(context, JarvisFloatingService::class.java))
                                    isFloatingActive = true
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (isFloatingActive) "⚪ MINI-ORB ON" else "⚫ MINI-ORB OFF",
                        color = if (isFloatingActive) Color(0xFF88CCFF) else Color(0xFF666666),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Vibration / Haptics Toggle
                Box(
                    modifier = Modifier
                        .background(if (hapticsEnabled) Color(0xFF282818) else Color(0xFF161616), RoundedCornerShape(14.dp))
                        .clickable {
                            hapticsEnabled = !hapticsEnabled
                            prefs.edit().putBoolean("haptics_enabled", hapticsEnabled).apply()
                            triggerHaptic(60)
                            statusText = if (hapticsEnabled) "HAPTICS ENABLED" else "HAPTICS DISABLED"
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (hapticsEnabled) "📳 HAPTICS ON" else "📴 HAPTICS OFF",
                        color = if (hapticsEnabled) Color(0xFFFFDD44) else Color(0xFF666666),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Clear Memory
                if (chatHistory.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1C1C1C), RoundedCornerShape(14.dp))
                            .clickable {
                                triggerHaptic()
                                chatHistory.clear()
                                statusText = "CONVERSATION MEMORY CLEARED"
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "🔄 RESET",
                            color = Color(0xFFAAAAAA),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Center: Glowing Living Smoky Orb
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            JarvisEnergyOrb(
                isListening = isListening,
                isThinking = isThinking,
                rmsLevel = rmsLevel,
                onClick = {
                    triggerHaptic()
                    if (isListening) {
                        speechRecognizer.stopListening()
                        isListening = false
                        statusText = "JARVIS IS THINKING..."
                    } else {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasMic) {
                            startVoiceSession()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(36.dp))

            if (liveTranscription.isNotEmpty()) {
                Text(
                    text = liveTranscription,
                    color = Color(0xFFAAAAAA),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Text(
                text = statusText,
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }

        Text(
            text = if (isListening) "MIC ACTIVE • TAP ORB TO SEND EARLY" else "TAP ORB TO SPEAK",
            color = if (isListening) Color(0xFF00FF88) else Color(0xFF444444),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { if (apiKey.isNotEmpty()) showKeyDialog = false },
            containerColor = Color(0xFF141414),
            title = {
                Text("Google Gemini API Key", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            },
            text = {
                Column {
                    Text("Paste your key from aistudio.google.com below:", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempKeyInput,
                        onValueChange = { tempKeyInput = it.trim() },
                        placeholder = { Text("AQ... or AIza...", color = Color(0xFF555555), fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleaned = tempKeyInput.trim()
                        apiKey = cleaned
                        prefs.edit().putString("gemini_api_key", cleaned).apply()
                        activeWorkingModel = null
                        showKeyDialog = false
                        statusText = "KEY CONNECTED! TAP TO START"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Text("SAVE KEY", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (apiKey.isNotEmpty()) {
                    TextButton(onClick = { showKeyDialog = false }) {
                        Text("CANCEL", color = Color.Gray)
                    }
                }
            }
        )
    }
}

// -------------------------------------------------------------
// UNIFIED MASTER ROUTER
// -------------------------------------------------------------
suspend fun processEverything(
    context: Context,
    prefs: SharedPreferences,
    query: String,
    apiKey: String,
    language: AppLanguage,
    onHaptic: (Long) -> Unit
): String = withContext(Dispatchers.IO) {
    val q = query.lowercase().trim()
    val access = JarvisAccessibilityService.instance

    // VIBRATION DIAGNOSTIC / TEST
    if (q.contains("test vibration") || q.contains("vibrate phone") || q == "vibrate") {
        onHaptic(100)
        kotlinx.coroutines.delay(150)
        onHaptic(100)
        kotlinx.coroutines.delay(150)
        onHaptic(150)
        return@withContext "Haptic diagnostic sequence complete, sir."
    }

    // 1. HARDWARE: LOCK SCREEN
    if (q.contains("lock") || q.contains("लक") || q.contains("स्क्रीन बंद")) {
        if (access != null) {
            access.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            return@withContext "Locking your device, sir."
        } else {
            return@withContext "Please tap '⚠️ ACCESS' at the top to enable screen control, sir."
        }
    }

    // 2. HARDWARE: TAKE SCREENSHOT
    if (q.contains("screenshot") || q.contains("स्क्रीनशॉट") || q.contains("स्क्रिनसट")) {
        if (access != null) {
            access.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            return@withContext "Capturing screenshot, sir."
        } else {
            return@withContext "Accessibility permission required for screenshots, sir."
        }
    }

    // 3. GLYPH LIGHT / FLASHLIGHT
    if (q.contains("glyph") || q.contains("flashlight") || q.contains("torch") || q.contains("बत्ती") || q.contains("टर्च") || q.contains("टॉर्च")) {
        val turnOn = q.contains("on") || q.contains("बाल") || q.contains("चालू") || q.contains("जला")
        val success = toggleFlashlight(context, turnOn)
        if (success) {
            return@withContext if (turnOn) "Glyph light illuminated, sir." else "Glyph light turned off, sir."
        }
    }

    // 4. ALARMS & TIMERS
    if (q.contains("alarm") || q.contains("अलार्म")) {
        val alarmSet = setSystemAlarm(context, q)
        if (alarmSet != null) return@withContext alarmSet
    }
    if (q.contains("timer") || q.contains("टाइमर")) {
        val timerSet = setSystemTimer(context, q)
        if (timerSet != null) return@withContext timerSet
    }

    // 5. MEDIA CONTROLS
    if (q.contains("play music") || q.contains("resume music") || q == "play") {
        controlMedia(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        return@withContext "Resuming music playback, sir."
    }
    if (q.contains("pause music") || q.contains("stop music") || q == "pause" || q.contains("बन्द गर")) {
        controlMedia(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        return@withContext "Music paused, sir."
    }
    if (q.contains("next song") || q.contains("next track") || q == "next") {
        controlMedia(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        return@withContext "Skipping to next track, sir."
    }
    if (q.contains("previous song") || q == "previous") {
        controlMedia(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return@withContext "Playing previous track, sir."
    }

    // 6. PERSONAL MEMORY VAULT
    if (q.startsWith("remember that ") || q.startsWith("remember ")) {
        val fact = q.removePrefix("remember that ").removePrefix("remember ").trim()
        val existing = prefs.getString("memory_notes", "") ?: ""
        val updated = if (existing.isEmpty()) fact else "$existing\n$fact"
        prefs.edit().putString("memory_notes", updated).apply()
        return@withContext "Noted and stored in my memory vault, sir."
    }
    if (q.contains("what do you remember") || q.contains("read my notes") || q.contains("where is my") || q.contains("where are my")) {
        val memory = prefs.getString("memory_notes", "") ?: ""
        if (memory.isNotEmpty()) {
            return@withContext "Here is what I have stored in your memory vault: $memory"
        } else {
            return@withContext "Your memory vault is currently empty, sir."
        }
    }

    // 7. PHONE CALLING & WHATSAPP
    if (q.startsWith("call ") || q.startsWith("phone ")) {
        val target = q.removePrefix("call ").removePrefix("phone ").trim()
        val digits = target.filter { it.isDigit() }
        val callIntent = if (digits.length >= 7) {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
        } else {
            Intent(Intent.ACTION_DIAL)
        }.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        try {
            context.startActivity(callIntent)
            return@withContext "Opening dialer for $target, sir."
        } catch (_: Exception) {}
    }
    if (q.startsWith("whatsapp ") || q.startsWith("send whatsapp")) {
        val clean = q.removePrefix("send whatsapp to ").removePrefix("whatsapp ").trim()
        val parts = clean.split(" message ", " text ", " ")
        val phoneNum = parts.firstOrNull { it.filter { c -> c.isDigit() }.length >= 8 } ?: ""
        val msg = if (parts.size > 1) clean.substringAfter(phoneNum).trim() else "Hello"
        val waUrl = "https://api.whatsapp.com/send?phone=$phoneNum&text=${URLEncoder.encode(msg, "UTF-8")}"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(waUrl)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            return@withContext "Launching WhatsApp, sir."
        } catch (_: Exception) {}
    }

    // 8. NAVIGATION
    if (q.contains("go home") || q.contains("home screen")) {
        access?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        return@withContext "Navigating to home screen, sir."
    }
    if (q.contains("go back") || q == "back") {
        access?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return@withContext "Going back, sir."
    }
    if (q.contains("recent apps") || q.contains("recents")) {
        access?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        return@withContext "Showing recent applications, sir."
    }

    // 9. NOTIFICATIONS & QUICK SETTINGS
    if (q.contains("notifications") || q.contains("notification")) {
        access?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        return@withContext "Opening notification panel, sir."
    }
    if (q.contains("quick settings") || q.contains("settings panel")) {
        access?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        return@withContext "Opening quick settings, sir."
    }

    // 10. VOLUME CONTROL
    if (q.contains("volume") || q.contains("आवाज") || q.contains("ध्वनि")) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return@withContext when {
            q.contains("up") || q.contains("increase") || q.contains("बढाउ") || q.contains("बढ़ाओ") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "Volume increased, sir."
            }
            q.contains("down") || q.contains("decrease") || q.contains("घटाउ") || q.contains("कम") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "Volume decreased, sir."
            }
            q.contains("mute") || q.contains("silent") || q.contains("म्युट") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "Audio muted, sir."
            }
            else -> "Adjusting system volume, sir."
        }
    }

    // 11. TIME & DATE
    if (q.contains("time") || q.contains("समय") || q.contains("बजे")) {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val timeStr = sdf.format(Date())
        return@withContext "The time is currently $timeStr, sir."
    }
    if (q.contains("date") || q.contains("today") || q.contains("मिति") || q.contains("तारीख")) {
        val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        val dateStr = sdf.format(Date())
        return@withContext "Today is $dateStr, sir."
    }

    // 12. SYSTEM STATUS
    if (q.contains("status") || q.contains("battery") || q.contains("ब्याट्री") || q.contains("बैटरी") || q.contains("सिस्टम")) {
        return@withContext getDeviceStatus(context, language)
    }

    // 13. UNIVERSAL APP LAUNCHER
    if (q.startsWith("open ") || q.startsWith("launch ") || q.contains(" खोल") || q.contains(" खोल्नुहोस्")) {
        val launchMsg = launchAnyApp(context, q, language)
        if (launchMsg != null) return@withContext launchMsg
    }

    // 14. LIVE WEATHER
    if (q.contains("weather") || q.contains("temperature") || q.contains("मौसम") || q.contains("तापक्रम") || q.contains("गर्मी") || q.contains("पानी पर्छ")) {
        val weatherInfo = fetchLiveWeather(language)
        if (weatherInfo != null) return@withContext weatherInfo
    }

    // 15. WIKIPEDIA
    if (q.startsWith("who is") || q.startsWith("who was") || q.startsWith("tell me about") || q.contains("wikipedia") || q.contains("विकिपीडिया") || q.contains("को हुन्") || q.contains("बारेमा भन")) {
        val wikiTopic = extractWikiTopic(q)
        val wikiAnswer = fetchWikipediaSummary(wikiTopic, language)
        if (wikiAnswer != null) return@withContext wikiAnswer
    }

    // 16. GEMINI MULTI-TURN AI BRAIN
    if (apiKey.isNotEmpty()) {
        return@withContext queryGeminiWithMemory(query, apiKey, language)
    }

    return@withContext "Please tap 🔑 KEY and paste a fresh Google key to chat with AI, sir."
}

// -------------------------------------------------------------
// MEDIA & HARDWARE HELPERS
// -------------------------------------------------------------
fun controlMedia(context: Context, keyCode: Int) {
    try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    } catch (_: Exception) {}
}

fun setSystemAlarm(context: Context, query: String): String? {
    try {
        val digits = query.filter { it.isDigit() || it == ':' || it == ' ' }.trim()
        var hour = 7
        var minute = 0
        val isPm = query.contains("pm", ignoreCase = true)

        if (digits.contains(":")) {
            val parts = digits.split(":")
            hour = parts[0].trim().toIntOrNull() ?: 7
            minute = parts[1].take(2).trim().toIntOrNull() ?: 0
        } else {
            hour = digits.split(" ").firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 7
        }

        if (isPm && hour < 12) hour += 12
        if (!isPm && hour == 12 && query.contains("am", ignoreCase = true)) hour = 0

        val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Alarm")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(alarmIntent)
        val displayMin = if (minute < 10) "0$minute" else "$minute"
        return "Alarm scheduled for $hour:$displayMin, sir."
    } catch (_: Exception) { return null }
}

fun setSystemTimer(context: Context, query: String): String? {
    try {
        val number = query.filter { it.isDigit() }.toIntOrNull() ?: 5
        val seconds = if (query.contains("second")) number else number * 60
        val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Timer")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(timerIntent)
        return "Timer set for $number ${if (query.contains("second")) "seconds" else "minutes"}, sir."
    } catch (_: Exception) { return null }
}

fun toggleFlashlight(context: Context, turnOn: Boolean): Boolean {
    return try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cm.cameraIdList.firstOrNull() ?: return false
        cm.setTorchMode(cameraId, turnOn)
        true
    } catch (_: Exception) { false }
}

fun getDeviceStatus(context: Context, language: AppLanguage): String {
    val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    val batteryStatus = context.registerReceiver(null, ifilter)
    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 85
    val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

    return "All systems nominal, sir. Your battery is at $pct percent and ${if (isCharging) "charging nicely" else "running on battery"}. Neural cores fully active."
}

fun launchAnyApp(context: Context, query: String, language: AppLanguage): String? {
    val cleanTarget = query.lowercase().replace("open", "").replace("launch", "").replace("खोल", "").trim()
    val pm = context.packageManager

    if (cleanTarget.contains("camera") || cleanTarget.contains("क्यामरा") || cleanTarget.contains("कैमरा")) {
        val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        try {
            context.startActivity(camIntent)
            return "Opening camera right away, sir."
        } catch (_: Exception) {}
    }

    val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    for (app in installedApps) {
        val appLabel = pm.getApplicationLabel(app).toString().lowercase()
        if (appLabel.isNotEmpty() && (cleanTarget.contains(appLabel) || appLabel.contains(cleanTarget))) {
            val launchIntent = pm.getLaunchIntentForPackage(app.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                return "Opening ${pm.getApplicationLabel(app)} for you, sir."
            }
        }
    }
    return null
}

fun fetchLiveWeather(language: AppLanguage): String? {
    return try {
        var lat = 26.72
        var lon = 85.92
        var cityName = "Janakpur"

        try {
            val geoUrl = URL("https://freeipapi.com/api/json")
            val connGeo = geoUrl.openConnection() as HttpURLConnection
            connGeo.connectTimeout = 3000
            if (connGeo.responseCode == 200) {
                val geoJson = JSONObject(connGeo.inputStream.bufferedReader().use { it.readText() })
                lat = geoJson.optDouble("latitude", lat)
                lon = geoJson.optDouble("longitude", lon)
                cityName = geoJson.optString("cityName", cityName)
            }
        } catch (_: Exception) {}

        val weatherUrl = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code")
        val conn = weatherUrl.openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        if (conn.responseCode == 200) {
            val res = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val current = res.getJSONObject("current")
            val temp = current.getDouble("temperature_2m").toInt()
            val code = current.getInt("weather_code")

            val condition = when (code) {
                0 -> "clear skies"
                1, 2, 3 -> "partly cloudy skies"
                45, 48 -> "mist and fog"
                51, 53, 55, 61, 63, 65 -> "rain showers"
                71, 73, 75 -> "snowfall"
                95, 96, 99 -> "thunderstorms"
                else -> "pleasant conditions"
            }
            return "In $cityName, it is currently $temp degrees Celsius with $condition, sir."
        }
        null
    } catch (_: Exception) { null }
}

fun extractWikiTopic(query: String): String {
    return query
        .replace("who was", "", ignoreCase = true)
        .replace("who is", "", ignoreCase = true)
        .replace("tell me about", "", ignoreCase = true)
        .replace("what is", "", ignoreCase = true)
        .replace("wikipedia", "", ignoreCase = true)
        .trim()
}

fun fetchWikipediaSummary(topic: String, language: AppLanguage): String? {
    if (topic.isEmpty()) return null
    return try {
        val encoded = URLEncoder.encode(topic, "UTF-8")
        val langCode = language.wikiPrefix

        val searchUrl = URL("https://$langCode.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&utf8=")
        val connSearch = searchUrl.openConnection() as HttpURLConnection
        connSearch.connectTimeout = 4000
        if (connSearch.responseCode != 200) return null

        val searchRes = JSONObject(connSearch.inputStream.bufferedReader().use { it.readText() })
        val searchList = searchRes.optJSONObject("query")?.optJSONArray("search") ?: return null
        if (searchList.length() == 0) return null
        val bestTitle = searchList.getJSONObject(0).getString("title")

        val summaryUrl = URL("https://$langCode.wikipedia.org/api/rest_v1/page/summary/${URLEncoder.encode(bestTitle, "UTF-8")}")
        val connSum = summaryUrl.openConnection() as HttpURLConnection
        connSum.connectTimeout = 4000
        if (connSum.responseCode == 200) {
            val sumRes = JSONObject(connSum.inputStream.bufferedReader().use { it.readText() })
            val extract = sumRes.optString("extract", "")
            if (extract.isNotEmpty()) {
                val clean = extract.replace(Regex("\\[.*?\\]"), "").trim()
                val sentences = clean.split(". ")
                return sentences.take(2).joinToString(". ") + if (sentences.size > 1 && !clean.endsWith(".")) "." else ""
            }
        }
        null
    } catch (_: Exception) { null }
}

// -------------------------------------------------------------
// BULLETPROOF MULTI-TURN GEMINI BRAIN
// -------------------------------------------------------------
suspend fun queryGeminiWithMemory(prompt: String, apiKey: String, language: AppLanguage): String = withContext(Dispatchers.IO) {
    val cleanKey = apiKey.trim()
    val systemInstruction = when (language) {
        AppLanguage.ENGLISH -> "You are JARVIS, a sophisticated personal AI companion with subtle British wit. Speak like a living human companion. Keep answers brief (1 to 2 spoken sentences). Never use markdown or asterisks."
        AppLanguage.NEPALI -> "तपाईं JARVIS हुनुहुन्छ—एक आत्मीय, बुद्धिमान र विनम्र व्यक्तिगत AI। एक सहयोगी मित्र जस्तै स्वाभाविक र न्यानो भाषामा बोल्नुहोस्। उत्तर १ वा २ वाक्यमा छोटो र प्रष्ट दिनुहोस्। नेपाली देवनागरीमा मात्र लेख्नुहोस्।"
        AppLanguage.HINDI -> "आप JARVIS हैं—एक समझदार, विनम्र और मित्रवत AI साथी। इंसान की तरह सहज भाव से बात करें। उत्तर 1 या 2 वाक्यों में संक्षिप्त और स्पष्ट दें। हिन्दी देवनागरी में लिखें।"
    }

    val candidateModels = mutableListOf<String>()
    if (!activeWorkingModel.isNullOrEmpty()) {
        candidateModels.add(activeWorkingModel!!)
    } else {
        val discovered = fetchAvailableModelsFromGoogle(cleanKey)
        candidateModels.addAll(discovered)
        candidateModels.addAll(listOf("gemini-1.5-flash", "gemini-2.0-flash", "gemini-2.5-flash-lite"))
    }

    if (chatHistory.size > 8) {
        chatHistory.removeAt(0)
        chatHistory.removeAt(0)
    }

    var lastError = ""

    for (rawModel in candidateModels.distinct()) {
        val model = rawModel.removePrefix("models/")
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", cleanKey)
                doOutput = true
                connectTimeout = 8000
                readTimeout = 10000
            }

            val payload = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                })

                val contents = JSONArray()
                for (turn in chatHistory) {
                    contents.put(JSONObject().apply {
                        put("role", turn.first)
                        put("parts", JSONArray().put(JSONObject().put("text", turn.second)))
                    })
                }
                contents.put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                })

                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 140)
                    put("temperature", 0.75)
                })
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(resp)
                val candidates = root.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val content = candidates.getJSONObject(0).optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "")
                        if (text.isNotEmpty()) {
                            activeWorkingModel = model
                            chatHistory.add("user" to prompt)
                            chatHistory.add("model" to text.trim())
                            return@withContext text.trim()
                        }
                    }
                }
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code == 401 || err.contains("API_KEY_INVALID") || err.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED")) {
                    return@withContext "Google Error [401]: API Key expired. Please tap 🔑 KEY and paste a fresh key from aistudio.google.com, sir."
                }
                lastError = "[$code]: ${err.take(80)}"
            }
        } catch (e: Exception) {
            lastError = e.localizedMessage ?: "Timeout"
        }
    }
    return@withContext "Google connection hiccup ($lastError). Please try once more, sir."
}

fun fetchAvailableModelsFromGoogle(apiKey: String): List<String> {
    val modelList = mutableListOf<String>()
    try {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("x-goog-api-key", apiKey)
            connectTimeout = 5000
            readTimeout = 5000
        }
        if (conn.responseCode == 200) {
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(resp)
            val modelsArray = root.optJSONArray("models")
            if (modelsArray != null) {
                for (i in 0 until modelsArray.length()) {
                    val item = modelsArray.getJSONObject(i)
                    val name = item.optString("name", "")
                    val methods = item.optJSONArray("supportedGenerationMethods")
                    var canGenerate = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") canGenerate = true
                        }
                    }
                    if (canGenerate) {
                        if (name.contains("flash", ignoreCase = true)) {
                            modelList.add(0, name)
                        } else {
                            modelList.add(name)
                        }
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return modelList
}

// -------------------------------------------------------------
// RESPONSIVE VOICE LISTENER
// -------------------------------------------------------------
fun startListeningResponsive(
    context: Context,
    speechRecognizer: SpeechRecognizer,
    language: AppLanguage,
    onStateChange: (Boolean) -> Unit,
    onRms: (Float) -> Unit,
    onPartial: (String) -> Unit,
    onFinalResult: (String) -> Unit,
    onError: (String) -> Unit
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        onError("Speech recognition not available")
        return
    }

    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    try {
        am?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
    } catch (_: Exception) {}

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.tag)
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, language.tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
    }

    speechRecognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onStateChange(true)
            try { am?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { onRms(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onStateChange(false) }

        override fun onError(error: Int) {
            try { am?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "Didn't hear clearly. Tap orb to speak."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening paused. Tap orb to speak."
                else -> "Standby. Tap orb to speak."
            }
            onError(message)
            onStateChange(false)
        }

        override fun onResults(results: Bundle?) {
            try { am?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotEmpty()) onFinalResult(text) else onError("No speech detected. Tap orb.")
            onStateChange(false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            if (partial.isNotEmpty()) onPartial(partial)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    speechRecognizer.startListening(intent)
}

@Composable
fun JarvisEnergyOrb(
    isListening: Boolean,
    isThinking: Boolean,
    rmsLevel: Float,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_anim")

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isThinking) 1800 else 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val counterRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isThinking) 2600 else 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "counter_rotation"
    )

    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    val dynamicBoost = if (isListening) (rmsLevel.coerceIn(0f, 10f) / 10f) * 0.35f else 0f
    val currentScale = breathScale + dynamicBoost

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(280.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.minDimension / 2.7f) * currentScale

            val filaments = 10
            for (i in 0 until filaments) {
                val phase = (i * (360f / filaments) + (if (i % 2 == 0) rotationAngle else counterRotation)) % 360f
                val radians = Math.toRadians(phase.toDouble())
                val offset = (sin(radians * 2.8) * 8f).toFloat()

                val filamentCenter = Offset(
                    center.x + (cos(radians) * offset).toFloat(),
                    center.y + (sin(radians) * offset).toFloat()
                )

                val alpha = if (isListening) 0.95f else if (i % 2 == 0) 0.9f else 0.5f
                drawCircle(
                    color = Color.White.copy(alpha = alpha),
                    center = filamentCenter,
                    radius = baseRadius + (i * 2.2f),
                    style = Stroke(width = if (i % 3 == 0) 3.5f else 1.8f)
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x77FFFFFF),
                        Color(0x22FFFFFF),
                        Color(0x00000000)
                    ),
                    center = center,
                    radius = baseRadius * 0.92f
                ),
                center = center,
                radius = baseRadius * 0.92f
            )
        }
    }
}
