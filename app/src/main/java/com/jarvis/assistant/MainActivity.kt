package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textToSpeech = TextToSpeech(this, this)

        setContent {
            JarvisMainScreen(
                context = this,
                onSpeak = { text, lang -> speakOut(text, lang) }
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
    onSpeak: (String, AppLanguage) -> Unit
) {
    var selectedLanguage by remember { mutableStateOf(AppLanguage.ENGLISH) }
    var isListening by remember { mutableStateOf(false) }
    var rmsLevel by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("TAP ORB TO COMMUNICATE") }
    var subtitleText by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startListening(
                context = context,
                speechRecognizer = speechRecognizer,
                language = selectedLanguage,
                onStateChange = { listening -> isListening = listening },
                onRms = { rms -> rmsLevel = rms },
                onResultText = { result ->
                    subtitleText = "\"$result\""
                    statusText = "PROCESSING..."
                    coroutineScope.launch {
                        delay(600)
                        val reply = when (selectedLanguage) {
                            AppLanguage.ENGLISH -> "I heard you. Audio systems are fully functional."
                            AppLanguage.NEPALI -> "मैले सुनेँ। अडियो प्रणाली सक्रिय छ।"
                            AppLanguage.HINDI -> "मैंने सुन लिया। ऑडियो सिस्टम ठीक से काम कर रहा है।"
                        }
                        statusText = reply
                        onSpeak(reply, selectedLanguage)
                    }
                },
                onErrorText = { err ->
                    statusText = err
                    isListening = false
                }
            )
        } else {
            statusText = "MICROPHONE PERMISSION DENIED"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        // Language Selector Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 54.dp, start = 24.dp, end = 24.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            AppLanguage.entries.forEach { lang ->
                val isSelected = selectedLanguage == lang
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .background(
                            color = if (isSelected) Color(0xFF222222) else Color.Transparent,
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
                        color = if (isSelected) Color.White else Color(0xFF555555),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
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
                rmsLevel = rmsLevel,
                onClick = {
                    if (isListening) {
                        speechRecognizer.stopListening()
                        isListening = false
                        statusText = "STANDBY"
                    } else {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            statusText = "LISTENING..."
                            subtitleText = ""
                            startListening(
                                context = context,
                                speechRecognizer = speechRecognizer,
                                language = selectedLanguage,
                                onStateChange = { isListening = it },
                                onRms = { rmsLevel = it },
                                onResultText = { result ->
                                    subtitleText = "\"$result\""
                                    statusText = "PROCESSING..."
                                    coroutineScope.launch {
                                        delay(600)
                                        val reply = when (selectedLanguage) {
                                            AppLanguage.ENGLISH -> "I heard you. Audio systems are fully functional."
                                            AppLanguage.NEPALI -> "मैले सुनेँ। अडियो प्रणाली सक्रिय छ।"
                                            AppLanguage.HINDI -> "मैंने सुन लिया। ऑडियो सिस्टम ठीक से काम कर रहा है।"
                                        }
                                        statusText = reply
                                        onSpeak(reply, selectedLanguage)
                                    }
                                },
                                onErrorText = { err ->
                                    statusText = err
                                    isListening = false
                                }
                            )
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Subtle Status Text
            Text(
                text = statusText,
                color = Color(0xFFAAAAAA),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            if (subtitleText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = subtitleText,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
            }
        }
    }
}

@Composable
fun JarvisEnergyOrb(
    isListening: Boolean,
    rmsLevel: Float,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_rotation")

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val counterRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "counter_rotation"
    )

    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    val dynamicBoost = if (isListening) (rmsLevel.coerceIn(0f, 10f) / 10f) * 0.25f else 0f
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

            // Draw smoky ethereal rings using multiple sweeping layers
            val ringCount = 8
            for (i in 0 until ringCount) {
                val phase = (i * (360f / ringCount) + (if (i % 2 == 0) rotationAngle else counterRotation)) % 360f
                val radians = Math.toRadians(phase.toDouble())
                val offsetDist = (sin(radians * 2) * 6f).toFloat()

                val arcCenter = Offset(
                    center.x + (cos(radians) * offsetDist).toFloat(),
                    center.y + (sin(radians) * offsetDist).toFloat()
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x00FFFFFF),
                            Color(0x22ECECEC),
                            Color(0x88FFFFFF),
                            Color(0x00000000)
                        ),
                        center = arcCenter,
                        radius = baseRadius + (i * 3f)
                    ),
                    center = arcCenter,
                    radius = baseRadius + (i * 2.5f),
                    style = Stroke(width = if (i % 2 == 0) 3.5f else 1.8f)
                )
            }

            // Core smoky glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x33FFFFFF),
                        Color(0x10FFFFFF),
                        Color(0x00000000)
                    ),
                    center = center,
                    radius = baseRadius * 0.95f
                ),
                center = center,
                radius = baseRadius * 0.95f
            )
        }
    }
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
            val recognizedText = matches?.firstOrNull() ?: ""
            if (recognizedText.isNotEmpty()) {
                onResultText(recognizedText)
            } else {
                onErrorText("No input detected")
            }
            onStateChange(false)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    speechRecognizer.startListening(intent)
}
