package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

enum class AppLanguage(val label: String, val locale: Locale, val tag: String) {
    ENGLISH("EN", Locale.US, "en-US"),
    NEPALI("नेपा", Locale("ne", "NP"), "ne-NP"),
    HINDI("हिन्द", Locale("hi", "IN"), "hi-IN")
}

var activeWorkingModel: String? = null

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
                onSpeak = { text, lang -> speakOut(text, lang) },
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
        }
    }

    private fun speakOut(text: String, language: AppLanguage) {
        if (!isTtsReady) return
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
    onSpeak: (String, AppLanguage) -> Unit,
    onStopAll: () -> Unit
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
        if (apiKey.isEmpty()) {
            showKeyDialog = true
            statusText = "API KEY REQUIRED"
            return
        }

        liveTranscription = ""
        statusText = "I'M LISTENING... TAKE YOUR TIME"
        isListening = true

        startListeningQuietly(
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
                    val aiReply = queryGeminiAutoDiscover(finalResult, apiKey, selectedLanguage)
                    isThinking = false
                    statusText = aiReply
                    onSpeak(aiReply, selectedLanguage)
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
        if (isGranted) {
            startVoiceSession()
        } else {
            statusText = "MICROPHONE PERMISSION NEEDED"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        // Top Bar
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

        // Center: Glowing Smoky Orb
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
            text = if (isListening) "MIC ACTIVE • TAP TO SEND EARLY" else "TAP ORB TO SPEAK",
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
                        activeWorkingModel = null // Reset so it rediscovers
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
            animation = tween(durationMillis = if (isThinking) 2000 else 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val counterRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isThinking) 3000 else 14000, easing = LinearEasing),
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

suspend fun queryGeminiAutoDiscover(prompt: String, apiKey: String, language: AppLanguage): String = withContext(Dispatchers.IO) {
    val cleanKey = apiKey.trim()
    val systemInstruction = when (language) {
        AppLanguage.ENGLISH -> "You are JARVIS, an intelligent, helpful personal AI. Answer concisely in 1 to 2 spoken sentences. Do not use asterisks or formatting."
        AppLanguage.NEPALI -> "तपाईं JARVIS हुनुहुन्छ। उत्तर १ वा २ वाक्यमा छोटो र प्रष्ट दिनुहोस्। नेपाली देवनागरी लिपिमा मात्र लेख्नुहोस्।"
        AppLanguage.HINDI -> "आप JARVIS हैं। उत्तर 1 या 2 वाक्यों में संक्षिप्त और स्पष्ट दें। हिन्दी देवनागरी लिपि में लिखें।"
    }

    // Build list of models to try (auto-discovered from Google first!)
    val candidateModels = mutableListOf<String>()
    if (!activeWorkingModel.isNullOrEmpty()) {
        candidateModels.add(activeWorkingModel!!)
    } else {
        val discovered = fetchAvailableModelsFromGoogle(cleanKey)
        candidateModels.addAll(discovered)
        // Solid fallbacks
        candidateModels.addAll(listOf(
            "gemini-1.5-flash",
            "gemini-1.5-flash-latest",
            "gemini-2.0-flash",
            "gemini-2.0-flash-exp",
            "gemini-1.5-pro"
        ))
    }

    var lastErrorMessage = ""

    for (rawModel in candidateModels.distinct()) {
        val model = rawModel.removePrefix("models/")
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", cleanKey)
                doOutput = true
                connectTimeout = 9000
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
                            activeWorkingModel = model // Successfully locked in!
                            return@withContext text.trim()
                        }
                    }
                }
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                lastErrorMessage = "[$code]: $err"
            }
        } catch (e: Exception) {
            lastErrorMessage = e.localizedMessage ?: "Network error"
        }
    }

    return@withContext "Google Gemini Error: $lastErrorMessage"
}

fun fetchAvailableModelsFromGoogle(apiKey: String): List<String> {
    val modelList = mutableListOf<String>()
    try {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("x-goog-api-key", apiKey)
            connectTimeout = 6000
            readTimeout = 6000
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
                            modelList.add(0, name) // Prioritize flash models at the top
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

fun startListeningQuietly(
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
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L)
    }

    speechRecognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onStateChange(true)
            try {
                am?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {}
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { onRms(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onStateChange(false) }

        override fun onError(error: Int) {
            try {
                am?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {}

            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "Didn't hear clearly. Tap orb to speak."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening paused. Tap orb to speak."
                else -> "Standby. Tap orb to speak."
            }
            onError(message)
            onStateChange(false)
        }

        override fun onResults(results: Bundle?) {
            try {
                am?.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {}

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotEmpty()) onFinalResult(text) else onError("No speech detected. Tap orb.")
            onStateChange(false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            if (partial.isNotEmpty()) {
                onPartial(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    speechRecognizer.startListening(intent)
}
