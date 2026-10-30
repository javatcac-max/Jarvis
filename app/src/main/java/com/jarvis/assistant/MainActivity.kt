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
import androidx.compose.material3.Text
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
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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

var localLlmEngine: LlmInference? = null
var isOfflineAiReady = false

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

        // Auto-check if local model is already saved in app storage
        val defaultModel = File(filesDir, "jarvis_model.bin")
        if (defaultModel.exists() && localLlmEngine == null) {
            try {
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(defaultModel.absolutePath)
                    .setMaxTokens(150)
                    .build()
                localLlmEngine = LlmInference.createFromOptions(this, opts)
                isOfflineAiReady = true
            } catch (_: Exception) {}
        }

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
    var selectedLanguage by remember { mutableStateOf(AppLanguage.ENGLISH) }
    var isListening by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var rmsLevel by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("TAP ORB TO COMMUNICATE") }
    var liveTranscription by remember { mutableStateOf("") }
    var isFloatingActive by remember { mutableStateOf(JarvisFloatingService.isRunning) }
    var hasLocalAi by remember { mutableStateOf(isOfflineAiReady) }

    val coroutineScope = rememberCoroutineScope()

    // Model File Picker for Offline AI (.bin / .task)
    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            statusText = "INSTALLING OFFLINE MODEL TO SNAPDRAGON CORE..."
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val destFile = File(context.filesDir, "jarvis_model.bin")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    val opts = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(destFile.absolutePath)
                        .setMaxTokens(150)
                        .build()
                    localLlmEngine = LlmInference.createFromOptions(context, opts)
                    isOfflineAiReady = true
                    hasLocalAi = true
                    statusText = "100% OFFLINE AI ONLINE! NO INTERNET NEEDED."
                } catch (e: Exception) {
                    statusText = "MODEL LOAD FAILED: ${e.localizedMessage}"
                }
            }
        }
    }

    fun triggerHaptic(duration: Long = 35) {
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
                    val reply = processEverythingOffline(context, prefs, finalResult, selectedLanguage, ::triggerHaptic)
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

                    // 100% Offline AI Model Button (No API Key Box!)
                    Box(
                        modifier = Modifier
                            .background(if (hasLocalAi) Color(0xFF132B1E) else Color(0xFF261D15), RoundedCornerShape(16.dp))
                            .clickable {
                                triggerHaptic()
                                modelPicker.launch(arrayOf("*/*"))
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (hasLocalAi) "🧠 OFFLINE AI" else "📥 LOAD MODEL",
                            color = if (hasLocalAi) Color(0xFF00FF88) else Color(0xFFFF9944),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
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

                // Display Owner Name Pill
                val ownerName = prefs.getString("owner_name", "") ?: ""
                Text(
                    text = if (ownerName.isNotEmpty()) "OWNER: $ownerName" else "OWNER: YOU",
                    color = Color(0xFF777777),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
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
            text = if (isListening) "MIC ACTIVE • TAP ORB TO SEND EARLY" else "TAP ORB TO SPEAK (100% OFFLINE)",
            color = if (isListening) Color(0xFF00FF88) else Color(0xFF444444),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}

// -------------------------------------------------------------
// 100% OFFLINE INTELLIGENCE ROUTER (Zero API Key Needed)
// -------------------------------------------------------------
suspend fun processEverythingOffline(
    context: Context,
    prefs: SharedPreferences,
    query: String,
    language: AppLanguage,
    onHaptic: (Long) -> Unit
): String = withContext(Dispatchers.IO) {
    val q = query.lowercase().trim()
    val access = JarvisAccessibilityService.instance
    val ownerName = prefs.getString("owner_name", "") ?: ""
    val ownerDisplay = if (ownerName.isNotEmpty()) ownerName else "sir"

    // 1. UNBREAKABLE CREATOR & OWNER IDENTITY (100% Offline)
    if (q.contains("who created you") || q.contains("who made you") || q.contains("who built you") ||
        q.contains("who is your creator") || q.contains("who is your owner") || q.contains("who is your boss") ||
        q.contains("कसले बनायो") || q.contains("मालिक को हो") || q.contains("किसने बनाया") || q.contains("मालिक कौन है")) {
        return@withContext when (language) {
            AppLanguage.ENGLISH -> "You did, $ownerDisplay! You designed, built, and brought me to life as your personal AI assistant for this Nothing Phone."
            AppLanguage.NEPALI -> "तपाईंले मलाई बनाउनुभएको हो, $ownerDisplay! म तपाईंको व्यक्तिगत एआई सहायक हुँ।"
            AppLanguage.HINDI -> "आपने ही मुझे बनाया है, $ownerDisplay! मैं आपका निजी एआई सहायक हूँ।"
        }
    }

    // 2. ENROLL OWNER NAME VIA VOICE (100% Offline)
    if (q.startsWith("my name is ") || q.startsWith("i am ") || q.startsWith("call me ")) {
        val name = q.removePrefix("my name is ").removePrefix("i am ").removePrefix("call me ").trim()
            .replaceFirstChar { it.uppercase() }
        prefs.edit().putString("owner_name", name).apply()
        return@withContext "Understood, $name. I have locked your identity into my core memory vault as my master and creator."
    }

    // 3. HARDWARE: LOCK SCREEN
    if (q.contains("lock") || q.contains("लक") || q.contains("स्क्रीन बंद")) {
        if (access != null) {
            access.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            return@withContext "Locking your device, $ownerDisplay."
        } else {
            return@withContext "Please tap '⚠️ ACCESS' at the top to enable screen control, $ownerDisplay."
        }
    }

    // 4. HARDWARE: SCREENSHOT
    if (q.contains("screenshot") || q.contains("स्क्रीनशॉट") || q.contains("स्क्रिनसट")) {
        if (access != null) {
            access.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            return@withContext "Capturing screenshot, $ownerDisplay."
        } else {
            return@withContext "Accessibility permission required for screenshots, $ownerDisplay."
        }
    }

    // 5. HARDWARE: GLYPH LIGHT / FLASHLIGHT
    if (q.contains("glyph") || q.contains("flashlight") || q.contains("torch") || q.contains("बत्ती") || q.contains("टर्च") || q.contains("टॉर्च")) {
        val turnOn = q.contains("on") || q.contains("बाल") || q.contains("चालू") || q.contains("जला")
        val success = toggleFlashlight(context, turnOn)
        if (success) {
            return@withContext if (turnOn) "Glyph light illuminated, $ownerDisplay." else "Glyph light turned off, $ownerDisplay."
        }
    }

    // 6. ALARMS & TIMERS
    if (q.contains("alarm") || q.contains("अलार्म")) {
        val alarmSet = setSystemAlarm(context, q)
        if (alarmSet != null) return@withContext alarmSet
    }
    if (q.contains("timer") || q.contains("टाइमर")) {
        val timerSet = setSystemTimer(context, q)
        if (timerSet != null) return@withContext timerSet
    }

    // 7. MEDIA CONTROLS
    if (q.contains("play music") || q.contains("resume music") || q == "play") {
        controlMedia(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        return@withContext "Resuming music playback, $ownerDisplay."
    }
    if (q.contains("pause music") || q.contains("stop music") || q == "pause" || q.contains("बन्द गर")) {
        controlMedia(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        return@withContext "Music paused, $ownerDisplay."
    }
    if (q.contains("next song") || q.contains("next track") || q == "next") {
        controlMedia(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        return@withContext "Skipping to next track, $ownerDisplay."
    }

    // 8. PERSONAL MEMORY VAULT
    if (q.startsWith("remember that ") || q.startsWith("remember ")) {
        val fact = q.removePrefix("remember that ").removePrefix("remember ").trim()
        val existing = prefs.getString("memory_notes", "") ?: ""
        val updated = if (existing.isEmpty()) fact else "$existing\n$fact"
        prefs.edit().putString("memory_notes", updated).apply()
        return@withContext "Noted and stored in my memory vault, $ownerDisplay."
    }
    if (q.contains("what do you remember") || q.contains("read my notes") || q.contains("where is my") || q.contains("where are my")) {
        val memory = prefs.getString("memory_notes", "") ?: ""
        if (memory.isNotEmpty()) {
            return@withContext "Here is what I have stored in your memory vault: $memory"
        } else {
            return@withContext "Your memory vault is currently empty, $ownerDisplay."
        }
    }

    // 9. TIME & DATE
    if (q.contains("time") || q.contains("समय") || q.contains("बजे")) {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return@withContext "The time is currently ${sdf.format(Date())}, $ownerDisplay."
    }
    if (q.contains("date") || q.contains("today") || q.contains("मिति") || q.contains("तारीख")) {
        val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        return@withContext "Today is ${sdf.format(Date())}, $ownerDisplay."
    }

    // 10. SYSTEM DIAGNOSTIC REPORT
    if (q.contains("status") || q.contains("battery") || q.contains("ब्याट्री") || q.contains("बैटरी") || q.contains("सिस्टम")) {
        return@withContext getDeviceStatus(context, language)
    }

    // 11. UNIVERSAL APP LAUNCHER
    if (q.startsWith("open ") || q.startsWith("launch ") || q.contains(" खोल") || q.contains(" खोल्नुहोस्")) {
        val launchMsg = launchAnyApp(context, q, language)
        if (launchMsg != null) return@withContext launchMsg
    }

    // 12. LIVE WEATHER (If connected)
    if (q.contains("weather") || q.contains("temperature") || q.contains("मौसम")) {
        val weatherInfo = fetchLiveWeather(language)
        if (weatherInfo != null) return@withContext weatherInfo
    }

    // 13. WIKIPEDIA (If connected)
    if (q.startsWith("who is") || q.startsWith("who was") || q.startsWith("tell me about") || q.contains("wikipedia")) {
        val wikiTopic = extractWikiTopic(q)
        val wikiAnswer = fetchWikipediaSummary(wikiTopic, language)
        if (wikiAnswer != null) return@withContext wikiAnswer
    }

    // 14. ON-DEVICE LOCAL LLM (MediaPipe Tasks GenAI - 100% Offline)
    if (localLlmEngine != null) {
        try {
            val localPrompt = "You are JARVIS, a loyal, intelligent personal AI companion for $ownerDisplay. Respond concisely in 1-2 sentences.\nUser: $query\nJARVIS:"
            val answer = localLlmEngine!!.generateResponse(localPrompt)
            if (answer.isNotEmpty()) return@withContext answer.trim()
        } catch (e: Exception) {
            return@withContext "Snapdragon inference error: ${e.localizedMessage}"
        }
    }

    return@withContext "Offline AI model not loaded yet, $ownerDisplay. Please tap '📥 LOAD MODEL' at the top to select your model file."
}

// -------------------------------------------------------------
// HARDWARE CONTROLS & HELPERS
// -------------------------------------------------------------
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

    return "All systems nominal, sir. Your battery is sitting at $pct percent and ${if (isCharging) "charging nicely" else "running on battery"}. Snapdragon neural cores operational."
}

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
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(timerIntent)
        return "Timer set for $number ${if (query.contains("second")) "seconds" else "minutes"}, sir."
    } catch (_: Exception) { return null }
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
        val weatherUrl = URL("https://api.open-meteo.com/v1/forecast?latitude=26.72&longitude=85.92&current=temperature_2m,weather_code")
        val conn = weatherUrl.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        if (conn.responseCode == 200) {
            val res = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val temp = res.getJSONObject("current").getDouble("temperature_2m").toInt()
            return "It is currently $temp degrees Celsius outside, sir."
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
        val summaryUrl = URL("https://${language.wikiPrefix}.wikipedia.org/api/rest_v1/page/summary/${URLEncoder.encode(topic, "UTF-8")}")
        val connSum = summaryUrl.openConnection() as HttpURLConnection
        connSum.connectTimeout = 3000
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
