package com.example.foodcopilot

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.util.Locale

class FoodCopilotService : AccessibilityService(), TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val parser = ScreenParser()
    private val llm = LlmClient()

    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null

    private var lastScreen: ParsedScreen? = null
    private var pending: Decision? = null
    private var pendingScreen: ParsedScreen? = null
    private var queuedSearch: String? = null
    private var queuedAt = 0L
    private var lastWarn = 0L
    private var debounceJob: Job? = null

    override fun onServiceConnected() {
        tts = TextToSpeech(this, this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
    }

    override fun onInit(status: Int) {
        tts.language = Locale("en", "IN")
        speak("Food Copilot is ready. Press volume up and speak your request.")
    }

    override fun onInterrupt() { tts.stop() }

    override fun onDestroy() {
        scope.cancel()
        tts.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(800)
            val screen = parser.parse(rootInActiveWindow)
            lastScreen = screen

            if (screen.isSensitive) {
                queuedSearch = null
                val now = System.currentTimeMillis()
                if (now - lastWarn > 10000) {
                    lastWarn = now
                    speak("This is a payment screen. For your safety, I will not read it. Please complete payment yourself.")
                }
                return@launch
            }

            val q = queuedSearch
            if (q != null) {
                if (System.currentTimeMillis() - queuedAt > 8000) {
                    queuedSearch = null
                } else {
                    val input = screen.elements.firstOrNull { it.type == "input" }
                    if (input != null) {
                        queuedSearch = null
                        if (setText(input.node, q)) {
                            submitSearch(input.node)
                            speak("Searching for $q.")
                            readResultsSoon()
                        } else {
                            speak("I could not type in the search box.")
                        }
                    }
                }
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val pkg = rootInActiveWindow?.packageName?.toString()
            val inFoodApp = pkg == "in.swiggy.android" || pkg == "com.application.zomato"
            if (inFoodApp) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    tts.stop()
                    startListening()
                }
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        }
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val said = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return
            handleUserSpeech(said)
        }
        override fun onError(error: Int) { speak("I did not catch that. Try again.") }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleUserSpeech(said: String) {
        android.util.Log.d("CopilotHeard", said)
        val lower = said.lowercase()

        // 1) Waiting for a yes/no?
        val waiting = pending
        if (waiting != null) {
            val shot = pendingScreen
            pending = null
            pendingScreen = null
            if (listOf("yes", "confirm", "ok", "okay", "do it", "haan").any { lower.contains(it) } && shot != null) {
                execute(waiting, shot, "Done.")
            } else {
                speak("Cancelled.")
            }
            return
        }

        // 2) Never act on payment screens.
        val screen = parser.parse(rootInActiveWindow).also { lastScreen = it }
        if (screen.isSensitive) {
            speak("This is a payment screen. I cannot help here. Please complete it yourself.")
            return
        }

        // 3) Common commands: handled on the phone, no AI, no quota.
        val local = LocalCommands.match(said, screen)
        if (local != null) {
            if (local.action == "click" && !local.text.isNullOrBlank()) {
                queuedSearch = local.text
                queuedAt = System.currentTimeMillis()
            }
            execute(local, screen, local.speak)
            return
        }

        // 4) Everything else: ask Gemini (1 request).
        android.util.Log.d("CopilotHeard", "-> sent to AI")
        speak("One moment.")
        scope.launch {
            val d = llm.decide(screen, said)
            when {
                d.action == "none" -> speak(d.speak)
                d.needsConfirmation -> {
                    pending = d
                    pendingScreen = screen
                    speak("${d.speak} Say yes to confirm.")
                }
                else -> {
                    speak(d.speak)
                    execute(d, screen, null)
                }
            }
        }
    }

    private fun execute(d: Decision, screen: ParsedScreen, announce: String?) {
        val ok = when (d.action) {
            "click" -> screen.elements.firstOrNull { it.id == d.target }?.let { clickNode(it.node) } ?: false
            "type" -> screen.elements.firstOrNull { it.id == d.target }?.let {
                val done = setText(it.node, d.text ?: "")
                if (done) submitSearch(it.node)
                done
            } ?: false
            "scroll" -> scrollFirst()
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> true
        }
        if (!ok) {
            speak("I could not do that. The screen may have changed.")
            return
        }
        if (announce != null) speak(announce)
        if (d.action == "type") readResultsSoon()
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun submitSearch(node: AccessibilityNodeInfo) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun scrollFirst(): Boolean =
        findScrollable(rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false

    private fun readResultsSoon() {
        scope.launch {
            delay(3000)
            val screen = parser.parse(rootInActiveWindow)
            if (screen.isSensitive) return@launch
            lastScreen = screen
            val texts = screen.elements
                .filter { it.type == "text" && !it.label.startsWith("unlabeled") && !it.label.startsWith("[") }
                .map { it.label }
                .distinct()
                .take(6)
            if (texts.isNotEmpty()) speak("Results. " + texts.joinToString(". "))
        }
    }

    private fun speak(text: String) {
        tts.speak(forSpeech(text), TextToSpeech.QUEUE_FLUSH, null, "copilot")
    }
    private fun forSpeech(t: String): String = t
        .replace("₹", " rupees ")
        .replace(Regex("\\bpcs?\\b", RegexOption.IGNORE_CASE), "pieces")
        .replace(Regex("\\bgms?\\b", RegexOption.IGNORE_CASE), "grams")
        .replace(Regex("\\bml\\b", RegexOption.IGNORE_CASE), "millilitres")
        .replace(Regex("\\bkcal\\b", RegexOption.IGNORE_CASE), "calories")
        .replace(Regex("\\s+"), " ").trim()
}