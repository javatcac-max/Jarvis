package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

enum class AppLanguage(val label: String, val locale: Locale, val tag: String) {
    ENGLISH("EN", Locale.US, "en-US"),
    NEPALI("नेपा", Locale("ne", "NP"), "ne-NP"),
    HINDI("हिन्द", Locale("hi", "IN"), "hi-IN")
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady by mutableStateOf(false)
    private var onTtsDoneCallback: (() -> Unit)? = null
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
                onSpeak = { text, lang, onDone ->
                    onTtsDoneCallback = onDone
                    speakOut(text, lang)
                },
                onStopAll = {
                    speechRecognizer?.stopListening()
                    textToSpeech?.stop()
                }
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread { onTtsDoneCallback?.invoke() }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread { onTtsDoneCallback?.invoke() }
                }
            })
        }
    }

    private fun speakOut(text: String, language: AppLanguage) {
        if (!isTtsReady) {
            onTtsDoneCallback?.invoke()
            return
        }
        textToSpeech?.let { tts ->
            tts.language = language.locale
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
    onSpeak: (String, AppLanguage, () -> Unit) -> Unit,
    onStopAll: () -> Unit
) {
    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var showKeyDialog by remember { mutableStateOf(apiKey.isEmpty()) }
    var tempKeyInput by remember { mutableStateOf(apiKey) }

    var selectedLanguage by remember { mutableStateOf(AppLanguage.ENGLISH) }
    var isListening by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var isContinuousMode by remember { mutableStateOf(false) }
    var rmsLevel by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("TAP ORB TO COMMUNICATE") }
    var subtitleText by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    fun triggerListening() {
        if (apiKey.isEmpty()) {
            showKeyDialog = true
            statusText = "API KEY REQUIRED"
            return
        }

        subtitleText = ""
        statusText = "LISTENING..."
        isListening = true

        startListening(
            context = context,
            speechRecognizer = speechRecognizer,
            language = selectedLanguage,
            onStateChange = { listening -> isListening = listening },
            onRms = { rms -> rmsLevel = rms },
            onResultText = { result ->
                subtitleText = "\"$result\""
                statusText = "JARVIS IS THINKING..."
                isThinking = true
                isListening = false

                coroutineScope.launch {
                    val aiReply = queryGemini(result, apiKey, selectedLanguage)
                    isThinking = false
                    statusText = aiReply

                    onSpeak(aiReply, selectedLanguage) {
                        // After speaking finishes, auto-listen if continuous mode is on!
                        if (isContinuousMode) {
                            coroutineScope.launch {
                                delay(350)
                                triggerListening()
                            }
                        } else {
                            statusText = "STANDBY"
                        }
                    }
                }
            },
            onErrorText = { err ->
                statusText = err
                isListening = false
                if (isContinuousMode) {
                    coroutineScope.launch {
                        delay(1200)
                        triggerListening()
                    }
                }
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isContinuousMode = true
            triggerListening()
        } else {
            statusText = "MICROPHONE PERMISSION NEEDED"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        // Top Bar: Language Selector & API Key Settings Icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 54.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Language Pills
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { lang ->
                    val isSelected = selectedLanguage == lang
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isSelected) Color(0xFF2B2B2B) else Color(0xFF111111),
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

            // Key Settings Button
            Box(
                modifier = Modifier
                    .background(Color(0xFF181818), RoundedCornerShape(16.dp))
                    .clickable { showKeyDialog = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (apiKey.isEmpty()) "⚠️ SET KEY" else "🔑 KEY",
                    color = if (apiKey.isEmpty()) Color(0xFFFF5555) else Color(0xFF999999),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Center: Living Smoky Luminous Orb
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            JarvisEnergyOrb(
                isListening = isListening,
                isThinking = isThinking,
                rmsLevel = rmsLevel,
                onClick = {
                    if (isListening || isContinuousMode) {
                        isContinuousMode = false
                        isListening = false
                        onStopAll()
                        statusText = "PAUSED (TAP TO RESUME)"
                    } else {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasMic) {
                            isContinuousMode = true
                            triggerListening()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Subtitle of what user said
            if (subtitleText.isNotEmpty()) {
                Text(
                    text = subtitleText,
                    color = Color(0xFF888888),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 36.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // JARVIS Response / Status
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

        // Subtle bottom hint
        Text(
            text = if (isContinuousMode) "• HANDS-FREE LOOP ACTIVE •" else "TAP ORB TO START",
            color = if (isContinuousMode) Color(0xFF00FF88) else Color(0xFF444444),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }

    // API Key Dialog Box
    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { if (apiKey.isNotEmpty()) showKeyDialog = false },
            containerColor = Color(0xFF141414),
            title = {
                Text(
                    text = "Google Gemini API Key",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "Paste your Google AI Studio key below. It stays 100% private on your phone:",
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempKeyInput,
                        onValueChange = { tempKeyInput = it.trim() },
                        placeholder = { Text("Paste key starting with AQ... or AIza...", color = Color(0xFF555555), fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        apiKey = tempKeyInput.trim()
                        prefs.edit().putString("gemini_api_key", apiKey).apply()
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
            animation = tween(durationMillis = if (isThinking) 2500 else 10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val counterRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isThinking) 3500 else 15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "counter_rotation"
    )

    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
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
            val baseRadius = (size.minDimension / 2.6f) * currentScale

            // High-contrast ethereal white smoky filaments
            val filaments = 10
            for (i in 0 until filaments) {
                val phase = (i * (360f / filaments) + (if (i % 2 == 0) rotationAngle else counterRotation)) % 360f
                val radians = Math.toRadians(phase.toDouble())
                val offset = (sin(radians * 2.5) * 8f).toFloat()

                val filamentCenter = Offset(
                    center.x + (cos(radians) * offset).toFloat(),
                    center.y + (sin(radians) * offset).toFloat()
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x00FFFFFF),
                            Color(0x33FFFFFF),
                            Color(0xEEFFFFFF), // Bright glowing smoke filament
                            Color(0x00000000)
                        ),
                        center = filamentCenter,
                        radius = baseRadius + (i * 3.5f)
                    ),
                    center = filamentCenter,
                    radius = baseRadius + (i * 2.8f),
                    style = Stroke(width = if (i % 2 == 0) 4f else 2.2f)
                )
            }

            // Luminous glowing core
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x55FFFFFF),
                        Color(0x18FFFFFF),
                        Color(0x00000000)
                    ),
                    center = center,
                    radius = baseRadius * 0.9f
                ),
                center = center,
                radius = baseRadius * 0.9f
            )
        }
    }
}

suspend fun queryGemini(prompt: String, apiKey: String, language: AppLanguage): String = withContext(Dispatchers.IO) {
    // Try modern Gemini endpoints
    val endpoints = listOf(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    )

    val systemInstruction = when (language) {
        AppLanguage.ENGLISH -> "You are JARVIS, a helpful, intelligent personal AI. Answer concisely in 1 to 2 spoken sentences. Avoid asterisks or bullet points."
        AppLanguage.NEPALI -> "तपाईं JARVIS हुनुहुन्छ। उत्तर १ वा २ वाक्यमा छोटो र प्रष्ट दिनुहोस्। नेपाली देवनागरी लिपिमा मात्र लेख्नुहोस्।"
        AppLanguage.HINDI -> "आप JARVIS हैं। उत्तर 1 या 2 वाक्यों में संक्षिप्त और स्पष्ट दें। हिन्दी देवनागरी लिपि में लिखें।"
    }

    for (urlStr in endpoints) {
        try {
            val url = URL("$urlStr?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 8000
                readTimeout = 12000
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
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(resp)
                val text = root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                return@withContext text.trim()
            }
        } catch (_: Exception) {
            // try next model fallback
        }
    }
    return@withContext "I am having trouble connecting to Gemini. Please verify your internet and API key."
}

fun startListening(
    context: Context,
    speechRecognizer: SpeechRecognizer,
    language: AppLanguage,
    onStateChange: (Boolean) -> Unit,
    onRms: (Float) -> Unit,
    onResultText: (String) -> Unit,
    onErrorText: (String) -> Unit
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        onErrorText("Speech recognition not available")
        return
    }

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.tag)
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, language.tag)
    }

    speechRecognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { onStateChange(true) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { onRms(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onStateChange(false) }
        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out"
                else -> "Recognition paused"
            }
            onErrorText(message)
            onStateChange(false)
        }
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotEmpty()) onResultText(text) else onErrorText("No input detected")
            onStateChange(false)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    speechRecognizer.startListening(intent)
}
