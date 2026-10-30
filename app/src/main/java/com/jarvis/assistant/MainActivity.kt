package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Path
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
import android.view.accessibility.AccessibilityNodeInfo
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

enum class AppLanguage(val label: String, val locale: Locale, val tag: String) {
    ENGLISH("EN", Locale.UK, "en-GB"),
    NEPALI("नेपा", Locale("ne", "NP"), "ne-NP"),
    HINDI("हिन्द", Locale("hi", "IN"), "hi-IN")
}

val multiTurnHistory = mutableListOf<Pair<String, String>>()

class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        var instance: JarvisAccessibilityService? = null
        fun isEnabled(): Boolean = instance != null

        fun swipeScreen(isNext: Boolean): Boolean {
            val s = instance ?: return false
            val metrics = s.resources.displayMetrics
            val width = metrics.widthPixels.toFloat()
            val height = metrics.heightPixels.toFloat()

            val startX = width / 2f
            val endX = width / 2f
            val startY = if (isNext) height * 0.80f else height * 0.20f
            val endY = if (isNext) height * 0.20f else height * 0.80f

            val swipePath = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 220))
                .build()

            return s.dispatchGesture(gesture, null, null)
        }

        fun clickOnScreen(targetText: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(targetText)
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    parent = parent.parent
                }
            }
            return false
        }
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
                onSpeak = { text, lang -> speakJarvis(text, lang) }
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tuneJarvisVoice(textToSpeech, AppLanguage.ENGLISH)
            // Welcome Greeting like in the video
            speakJarvis("All systems online, Sir. I am fully operational and at your service.", AppLanguage.ENGLISH)
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
                        (v.name.contains("male", ignoreCase = true) || 
                         v.name.contains("neural", ignoreCase = true) ||
                         v.name.contains("en-gb", ignoreCase = true))
                    } ?: availableVoices.firstOrNull { v -> v.locale.language == language.locale.language }

                    if (naturalVoice != null) engine.voice = naturalVoice
                }
            } catch (_: Exception) {}

            engine.setPitch(0.90f)      // Deeper, calm British tone
            engine.setSpeechRate(1.02f) // Fluid conversational cadence
        }
    }

    private fun speakJarvis(text: String, language: AppLanguage) {
        if (!isTtsReady) return
        textToSpeech?.let { tts ->
            tuneJarvisVoice(tts, language)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_VOICE")
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
    var groqApiKey by remember { mutableStateOf(prefs.getString("groq_api_key", "")?.trim() ?: "") }
    var showKeyDialog by remember { mutableStateOf(groqApiKey.isEmpty()) }
    var tempKeyInput by remember { mutableStateOf(groqApiKey) }

    var selectedLanguage by remember { mutableStateOf(AppLanguage.ENGLISH) }
    var isListening by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var rmsLevel by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("ALL SYSTEMS OPERATIONAL, SIR.") }
    var liveTranscription by remember { mutableStateOf("") }
    var isFloatingActive by remember { mutableStateOf(JarvisFloatingService.isRunning) }

    val coroutineScope = rememberCoroutineScope()

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
        statusText = "LISTENING, SIR..."
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
                statusText = "PROCESSING..."
                isThinking = true
                isListening = false

                coroutineScope.launch {
                    val reply = processMasterVideoJarvis(context, prefs, finalResult, groqApiKey, selectedLanguage, ::triggerHaptic)
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
                            text = if (isAccessActive) "⚡ FULL ACCESS" else "⚠️ ACCESS",
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
                            text = if (groqApiKey.isEmpty()) "⚠️ SET GROQ KEY" else "⚡ 70B BRAIN",
                            color = if (groqApiKey.isEmpty()) Color(0xFFFF5555) else Color(0xFF00FF88),
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

                val ownerName = prefs.getString("owner_name", "") ?: ""
                Text(
                    text = if (ownerName.isNotEmpty()) "MASTER: $ownerName" else "MASTER: SIR",
                    color = Color(0xFF888888),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Center: High-Definition Living Smoky Orb
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
                        statusText = "PROCESSING..."
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
            text = if (isListening) "MIC ACTIVE • TAP ORB TO SEND" else "TAP ORB TO COMMUNICATE",
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
            onDismissRequest = { if (groqApiKey.isNotEmpty()) showKeyDialog = false },
            containerColor = Color(0xFF141414),
            title = {
                Text("Groq Llama 3.3 70B Key", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            },
            text = {
                Column {
                    Text("Get your 100% free key from console.groq.com/keys (No credit card needed):", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempKeyInput,
                        onValueChange = { tempKeyInput = it.trim() },
                        placeholder = { Text("gsk_...", color = Color(0xFF555555), fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleaned = tempKeyInput.trim()
                        groqApiKey = cleaned
                        prefs.edit().putString("groq_api_key", cleaned).apply()
                        showKeyDialog = false
                        statusText = "70B BRAIN ONLINE! ALL SYSTEMS GO, SIR."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Text("ACTIVATE JARVIS", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (groqApiKey.isNotEmpty()) {
                    TextButton(onClick = { showKeyDialog = false }) {
                        Text("CANCEL", color = Color.Gray)
                    }
                }
            }
        )
    }
}

// -------------------------------------------------------------
// MASTER VIDEO JARVIS CONTROLLER (SIMULTANEOUS MULTI-ACTIONS)
// -------------------------------------------------------------
suspend fun processMasterVideoJarvis(
    context: Context,
    prefs: SharedPreferences,
    query: String,
    groqKey: String,
    language: AppLanguage,
    onHaptic: (Long) -> Unit
): String = withContext(Dispatchers.IO) {
    val q = query.lowercase().trim()
    val access = JarvisAccessibilityService.instance
    val ownerName = prefs.getString("owner_name", "") ?: ""
    val ownerDisplay = if (ownerName.isNotEmpty()) ownerName else "Sir"

    // 1. HARDWIRED UNBREAKABLE IDENTITY
    if (q.contains("who created you") || q.contains("who made you") || q.contains("who built you") ||
        q.contains("who is your creator") || q.contains("who is your owner") || q.contains("who is your boss") ||
        q.contains("कसले बनायो") || q.contains("मालिक को हो") || q.contains("किसने बनाया") || q.contains("मालिक कौन है")) {
        return@withContext "You did, $ownerDisplay! You designed, engineered, and brought me to life as your personal assistant for this device."
    }

    if (q.startsWith("my name is ") || q.startsWith("i am ") || q.startsWith("call me ")) {
        val name = q.removePrefix("my name is ").removePrefix("i am ").removePrefix("call me ").trim().replaceFirstChar { it.uppercase() }
        prefs.edit().putString("owner_name", name).apply()
        return@withContext "Understood, $name. I have locked your identity in my primary memory banks as my creator."
    }

    // 2. SIMULTANEOUS MULTI-ACTION CHAINING (Like in video: "play music, open instagram, show mails...")
    val actionsExecuted = mutableListOf<String>()

    if (q.contains("play music") || q.contains("play some music") || q.contains("resume music") || q == "play") {
        controlMedia(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        actionsExecuted.add("music is playing")
    } else if (q.contains("pause music") || q.contains("stop music") || q == "pause") {
        controlMedia(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        actionsExecuted.add("music is paused")
    }

    if (q.contains("instagram")) {
        launchAnyApp(context, "instagram", language)
        actionsExecuted.add("Instagram is open")
    }
    if (q.contains("mail") || q.contains("email") || q.contains("gmail")) {
        launchAnyApp(context, "gmail", language)
        actionsExecuted.add("emails are open")
    }
    if (q.contains("youtube") && !q.contains("search")) {
        launchAnyApp(context, "youtube", language)
        actionsExecuted.add("YouTube is open")
    }
    if (q.contains("telegram")) {
        launchAnyApp(context, "telegram", language)
        actionsExecuted.add("Telegram is open")
    }
    if (q.contains("website") || q.contains("google")) {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        try { context.startActivity(web); actionsExecuted.add("browser is open") } catch (_: Exception) {}
    }

    if (actionsExecuted.isNotEmpty()) {
        onHaptic(40)
        return@withContext "Certainly, $ownerDisplay. " + actionsExecuted.joinToString(", ") + "."
    }

    // 3. PHYSICAL SCREEN GESTURES (Reels, Shorts, TikTok)
    if (q.contains("scroll down") || q.contains("next video") || q.contains("next reel") || q == "next" || q == "scroll") {
        if (access != null && JarvisAccessibilityService.swipeScreen(isNext = true)) {
            onHaptic(30)
            return@withContext "Next video, $ownerDisplay."
        }
    }
    if (q.contains("scroll up") || q.contains("previous video") || q == "previous") {
        if (access != null && JarvisAccessibilityService.swipeScreen(isNext = false)) {
            onHaptic(30)
            return@withContext "Previous video, $ownerDisplay."
        }
    }

    // 4. HARDWARE CONTROLS
    if (q.contains("lock") || q.contains("लक") || q.contains("स्क्रीन बंद")) {
        access?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        return@withContext "Locking your device right now, $ownerDisplay."
    }

    if (q.contains("screenshot") || q.contains("स्क्रीनशॉट")) {
        access?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        return@withContext "Capturing screen, $ownerDisplay."
    }

    if (q.contains("glyph") || q.contains("flashlight") || q.contains("torch") || q.contains("बत्ती")) {
        val turnOn = q.contains("on") || q.contains("बाल") || q.contains("चालू") || q.contains("जला")
        toggleFlashlight(context, turnOn)
        return@withContext if (turnOn) "Glyph illumination active, $ownerDisplay." else "Glyph lights offline, $ownerDisplay."
    }

    // 5. THE 70B GROQ BRAIN (Ultra-fast, understands Hinglish, witty & loyal)
    if (groqKey.isNotEmpty()) {
        return@withContext queryGroq70B(query, groqKey, ownerDisplay)
    }

    return@withContext "Please tap ⚡ SET GROQ KEY at the top to activate my 70-billion parameter neural brain, $ownerDisplay."
}

// -------------------------------------------------------------
// GROQ LLAMA 3.3 70B BRAIN (The Exact Architecture from Video)
// -------------------------------------------------------------
suspend fun queryGroq70B(prompt: String, apiKey: String, ownerName: String): String = withContext(Dispatchers.IO) {
    val cleanKey = apiKey.trim()
    val systemInstruction = "You are J.A.R.V.I.S, the hyper-intelligent, sophisticated, and witty personal AI assistant to your creator, $ownerName. " +
            "You speak with a suave, respectful British tone (like Paul Bettany in Iron Man). " +
            "Address your creator as '$ownerName' or 'Sir'. " +
            "Never say 'Greetings, human' or refer to your creator as human. " +
            "Understand English, Hindi, and Nepali seamlessly. If the user speaks in Hindi/Hinglish, understand it perfectly and reply in smooth British English (or Hindi if requested). " +
            "Keep answers concise, direct, and conversational (1 to 2 spoken sentences). " +
            "Never use markdown, bullet points, or asterisks."

    try {
        val url = URL("https://api.groq.com/openai/v1/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $cleanKey")
            doOutput = true
            connectTimeout = 6000
            readTimeout = 8000
        }

        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemInstruction)
        })

        for (turn in multiTurnHistory.takeLast(6)) {
            messages.put(JSONObject().apply {
                put("role", turn.first)
                put("content", turn.second)
            })
        }

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        })

        val payload = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
            put("messages", messages)
            put("max_tokens", 130)
            put("temperature", 0.7)
        }

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
        }

        if (conn.responseCode == 200) {
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(resp)
            val text = root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val clean = text.replace("*", "").trim()
            multiTurnHistory.add("user" to prompt)
            multiTurnHistory.add("assistant" to clean)
            return@withContext clean
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            return@withContext "Neural link error [${conn.responseCode}]. Please check your Groq key, Sir."
        }
    } catch (e: Exception) {
        return@withContext "I experienced a brief communication lapse, Sir. Please speak again."
    }
}

// -------------------------------------------------------------
// HARDWARE CONTROLS
// -------------------------------------------------------------
fun controlMedia(context: Context, keyCode: Int) {
    try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    } catch (_: Exception) {}
}

fun toggleFlashlight(context: Context, turnOn: Boolean): Boolean {
    return try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cm.cameraIdList.firstOrNull() ?: return false
        cm.setTorchMode(cameraId, turnOn)
        true
    } catch (_: Exception) { false }
}

fun launchAnyApp(context: Context, target: String, language: AppLanguage) {
    val pm = context.packageManager
    val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    for (app in installedApps) {
        val appLabel = pm.getApplicationLabel(app).toString().lowercase()
        if (appLabel.contains(target)) {
            val launchIntent = pm.getLaunchIntentForPackage(app.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                return
            }
        }
    }
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
                SpeechRecognizer.ERROR_AUDIO -> "Mic is busy! Close screen recorder."
                SpeechRecognizer.ERROR_NO_MATCH -> "Didn't hear clearly, Sir."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out, Sir."
                else -> "Standby, Sir."
            }
            onError(message)
            onStateChange(false)
        }

        override fun onResults(results: Bundle?) {
            try { am?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotEmpty()) onFinalResult(text) else onError("No speech detected.")
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
