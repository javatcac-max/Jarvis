package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
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
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

enum class AppLanguage(val label: String, val locale: Locale, val tag: String, val wikiPrefix: String) {
    ENGLISH("EN", Locale.US, "en-US", "en"),
    NEPALI("नेपा", Locale("ne", "NP"), "ne-NP", "ne"),
    HINDI("हिन्द", Locale("hi", "IN"), "hi-IN", "hi")
}

var activeWorkingModel: String? = null

// -------------------------------------------------------------
// SYSTEM ACCESSIBILITY CONTROLLER (Full Device Automation)
// -------------------------------------------------------------
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

    var selectedLanguage by remember { mutableStateOf(AppLanguage.ENGLISH) }
    var isListening by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var rmsLevel by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("TAP ORB TO COMMUNICATE") }
    var liveTranscription by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    fun startVoiceSession() {
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
                    val reply = processEverything(context, finalResult, apiKey, selectedLanguage)
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
        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 54.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { lang ->
                    val isSelected = selectedLanguage == lang
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isSelected) Color(0xFF2A2A2A) else Color(0xFF111111),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                selectedLanguage = lang
                                statusText = "${lang.name} ACTIVE"
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
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
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isAccessActive) "⚡ FULL ACCESS" else "⚠️ ACCESSIBILITY",
                        color = if (isAccessActive) Color(0xFF00FF88) else Color(0xFFFF7777),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
                        .clickable { showKeyDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (apiKey.isEmpty()) "⚠️ SET KEY" else "🔑 KEY",
                        color = if (apiKey.isEmpty()) Color(0xFFFF5555) else Color(0xFFE0E0E0),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Center: Living Smoky Orb
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            JarvisEnergyOrb(
                isListening = isListening,
                isThinking = isThinking,
                rmsLevel = rmsLevel,
                onClick = {
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
                    Text("Paste your key below. Saved safely on your device:", color = Color(0xFFAAAAAA), fontSize = 12.sp)
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
// UNIFIED MASTER INTELLIGENCE ROUTER (Accessibility & Hardware)
// -------------------------------------------------------------
suspend fun processEverything(
    context: Context,
    query: String,
    apiKey: String,
    language: AppLanguage
): String = withContext(Dispatchers.IO) {
    val q = query.lowercase().trim()
    val access = JarvisAccessibilityService.instance

    // 1. HARDWARE: LOCK SCREEN (Accessibility Power)
    if (q.contains("lock") || q.contains("लक") || q.contains("स्क्रीन बंद")) {
        if (access != null) {
            access.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            return@withContext when (language) {
                AppLanguage.ENGLISH -> "Locking your device right now, sir."
                AppLanguage.NEPALI -> "फोन लक गर्दै छु, हजुर।"
                AppLanguage.HINDI -> "डिवाइस लॉक किया जा रहा है।"
            }
        } else {
            return@withContext "Please tap '⚠️ ACCESSIBILITY' at the top to enable screen control, sir."
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

    // 3. HARDWARE: NAVIGATION (Home / Back / Recents)
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

    // 4. HARDWARE: NOTIFICATIONS & QUICK SETTINGS
    if (q.contains("notifications") || q.contains("notification")) {
        access?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        return@withContext "Opening notification panel, sir."
    }
    if (q.contains("quick settings") || q.contains("settings panel")) {
        access?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        return@withContext "Opening quick settings, sir."
    }

    // 5. HARDWARE: VOLUME CONTROL
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

    // 6. HARDWARE: FLASHLIGHT
    if (q.contains("flashlight") || q.contains("torch") || q.contains("बत्ती") || q.contains("टर्च") || q.contains("टॉर्च") || q.contains("फ्लैशलाइट")) {
        val turnOn = q.contains("on") || q.contains("बाल") || q.contains("चालू") || q.contains("जला")
        val success = toggleFlashlight(context, turnOn)
        if (success) {
            return@withContext when (language) {
                AppLanguage.ENGLISH -> if (turnOn) "Right away, sir. Flashlight is on." else "Flashlight turned off, sir."
                AppLanguage.NEPALI -> if (turnOn) "हस्, टर्च बालियो।" else "टर्च बन्द गरियो, हजुर।"
                AppLanguage.HINDI -> if (turnOn) "टॉर्च चालू कर दी गई है।" else "टॉर्च बंद कर दी गई है।"
            }
        }
    }

    // 7. HARDWARE: SYSTEM STATUS
    if (q.contains("status") || q.contains("battery") || q.contains("ब्याट्री") || q.contains("बैटरी") || q.contains("सिस्टम")) {
        return@withContext getDeviceStatus(context, language)
    }

    // 8. HARDWARE: APP LAUNCHER
    if (q.startsWith("open ") || q.startsWith("launch ") || q.contains(" खोल") || q.contains(" खोल्नुहोस्")) {
        val launchMsg = launchAppByName(context, q, language)
        if (launchMsg != null) return@withContext launchMsg
    }

    // 9. LIVE WEATHER
    if (q.contains("weather") || q.contains("temperature") || q.contains("मौसम") || q.contains("तापक्रम") || q.contains("गर्मी") || q.contains("पानी पर्छ")) {
        val weatherInfo = fetchLiveWeather(language)
        if (weatherInfo != null) return@withContext weatherInfo
    }

    // 10. WIKIPEDIA FACTUAL SUMMARY
    if (q.startsWith("who is") || q.startsWith("who was") || q.startsWith("tell me about") || q.contains("wikipedia") || q.contains("विकिपीडिया") || q.contains("को हुन्") || q.contains("बारेमा भन")) {
        val wikiTopic = extractWikiTopic(q)
        val wikiAnswer = fetchWikipediaSummary(wikiTopic, language)
        if (wikiAnswer != null) return@withContext wikiAnswer
    }

    // 11. GEMINI AI AUTO-DISCOVERY WITH HUMAN PERSONA
    if (apiKey.isNotEmpty()) {
        return@withContext queryGeminiAutoDiscover(query, apiKey, language)
    }

    return@withContext "Please set your Gemini key to enable full AI conversation."
}

// -------------------------------------------------------------
// HARDWARE CONTROLS
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

    return when (language) {
        AppLanguage.ENGLISH -> "All systems nominal, sir. Your battery is sitting at $pct percent and ${if (isCharging) "charging nicely" else "running on battery"}. Neural cores are operating smoothly."
        AppLanguage.NEPALI -> "सबै प्रणाली सामान्य छन्, हजुर। ब्याट्री $pct प्रतिशत छ र ${if (isCharging) "चार्ज हुँदैछ" else "ब्याट्रीमा चल्दैछ"}।"
        AppLanguage.HINDI -> "सभी सिस्टम सामान्य हैं। बैटरी $pct प्रतिशत है और ${if (isCharging) "चार्ज हो रही है" else "बैटरी पर चल रही है"}।"
    }
}

fun launchAppByName(context: Context, query: String, language: AppLanguage): String? {
    val q = query.lowercase()
    val appMap = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "chrome" to "com.android.chrome",
        "settings" to "com.android.settings",
        "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "facebook" to "com.facebook.katana",
        "instagram" to "com.instagram.android"
    )

    if (q.contains("camera") || q.contains("क्यामरा") || q.contains("कैमरा")) {
        val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        try {
            context.startActivity(camIntent)
            return when (language) {
                AppLanguage.ENGLISH -> "Opening the camera right now, sir."
                AppLanguage.NEPALI -> "क्यामेरा खोल्दै छु, हजुर।"
                AppLanguage.HINDI -> "कैमरा खोला जा रहा है।"
            }
        } catch (_: Exception) {}
    }

    for ((name, pkg) in appMap) {
        if (q.contains(name)) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent != null) {
                context.startActivity(intent)
                return when (language) {
                    AppLanguage.ENGLISH -> "Opening ${name.replaceFirstChar { it.uppercase() }} for you, sir."
                    AppLanguage.NEPALI -> "${name.replaceFirstChar { it.uppercase() }} खोल्दै छु, हजुर।"
                    AppLanguage.HINDI -> "${name.replaceFirstChar { it.uppercase() }} खोला जा रहा है।"
                }
            }
        }
    }
    return null
}

// -------------------------------------------------------------
// LIVE WEATHER
// -------------------------------------------------------------
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
                0 -> if (language == AppLanguage.NEPALI) "सफा आकाश" else if (language == AppLanguage.HINDI) "साफ आसमान" else "clear skies"
                1, 2, 3 -> if (language == AppLanguage.NEPALI) "आंशिक बादल" else if (language == AppLanguage.HINDI) "हल्के बादल" else "partly cloudy skies"
                45, 48 -> if (language == AppLanguage.NEPALI) "कुहिरो" else if (language == AppLanguage.HINDI) "कोहरा" else "mist and fog"
                51, 53, 55, 61, 63, 65 -> if (language == AppLanguage.NEPALI) "वर्षा" else if (language == AppLanguage.HINDI) "बारिश" else "rain showers"
                71, 73, 75 -> if (language == AppLanguage.NEPALI) "हिउँ" else if (language == AppLanguage.HINDI) "बर्फबारी" else "snowfall"
                95, 96, 99 -> if (language == AppLanguage.NEPALI) "चट्याङ" else if (language == AppLanguage.HINDI) "तूफान" else "thunderstorms"
                else -> if (language == AppLanguage.NEPALI) "सामान्य मौसम" else if (language == AppLanguage.HINDI) "सामान्य मौसम" else "pleasant conditions"
            }

            return when (language) {
                AppLanguage.ENGLISH -> "In $cityName, it is currently $temp degrees Celsius with $condition, sir."
                AppLanguage.NEPALI -> "$cityName मा अहिले तापक्रम $temp डिग्री सेल्सियस र $condition छ, हजुर।"
                AppLanguage.HINDI -> "$cityName में अभी तापमान $temp डिग्री सेल्सियस और $condition है।"
            }
        }
        null
    } catch (_: Exception) { null }
}

// -------------------------------------------------------------
// WIKIPEDIA
// -------------------------------------------------------------
fun extractWikiTopic(query: String): String {
    return query
        .replace("who was", "", ignoreCase = true)
        .replace("who is", "", ignoreCase = true)
        .replace("tell me about", "", ignoreCase = true)
        .replace("what is", "", ignoreCase = true)
        .replace("wikipedia", "", ignoreCase = true)
        .replace("को हुन्", "", ignoreCase = true)
        .replace("बारेमा भन", "", ignoreCase = true)
        .replace("विकिपीडिया", "", ignoreCase = true)
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
// GEMINI AUTO-DISCOVERY WITH HUMAN PERSONA
// -------------------------------------------------------------
suspend fun queryGeminiAutoDiscover(prompt: String, apiKey: String, language: AppLanguage): String = withContext(Dispatchers.IO) {
    val cleanKey = apiKey.trim()
    val systemInstruction = when (language) {
        AppLanguage.ENGLISH -> "You are JARVIS, a sophisticated, warm personal AI companion with subtle British wit. Speak like a living human companion, not a textbook. Use natural conversational phrases like 'Certainly, sir', 'Well, allow me to explain', or 'Indeed'. Keep responses brief (1 to 2 spoken sentences max). Never use asterisks or formatting."
        AppLanguage.NEPALI -> "तपाईं JARVIS हुनुहुन्छ—एक आत्मीय, बुद्धिमान र विनम्र व्यक्तिगत AI। एक सहयोगी मित्र जस्तै स्वाभाविक र न्यानो भाषामा बोल्नुहोस्। उत्तर १ वा २ वाक्यमा छोटो र प्रष्ट दिनुहोस्। नेपाली देवनागरीमा मात्र लेख्नुहोस्।"
        AppLanguage.HINDI -> "आप JARVIS हैं—एक समझदार, विनम्र और मित्रवत AI साथी। इंसान की तरह सहज भाव से बात करें। उत्तर 1 या 2 वाक्यों में संक्षिप्त और स्पष्ट दें। हिन्दी देवनागरी में लिखें।"
    }

    val candidateModels = mutableListOf<String>()
    if (!activeWorkingModel.isNullOrEmpty()) {
        candidateModels.add(activeWorkingModel!!)
    } else {
        val discovered = fetchAvailableModelsFromGoogle(cleanKey)
        candidateModels.addAll(discovered)
        candidateModels.addAll(listOf(
            "gemini-1.5-flash",
            "gemini-1.5-flash-latest",
            "gemini-2.0-flash",
            "gemini-2.0-flash-exp",
            "gemini-2.5-flash-lite"
        ))
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
                val contents = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            put(JSONObject().apply { put("text", "$systemInstruction\n\nUser: $prompt") })
                        }
                        put("parts", parts)
                    }
                    put(contentObj)
                }
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 120)
                    put("temperature", 0.75)
                })
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
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
                            return@withContext text.trim()
                        }
                    }
                }
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                lastError = "[${conn.responseCode}]: $err"
            }
        } catch (e: Exception) {
            lastError = e.localizedMessage ?: "Timeout"
        }
    }
    return@withContext "Google servers are experiencing a brief spike ($lastError). Please give me another try, sir."
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
