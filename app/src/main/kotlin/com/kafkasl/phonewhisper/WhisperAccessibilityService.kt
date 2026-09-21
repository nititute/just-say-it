package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import okhttp3.Call
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 52
        private const val PAD_DP = 12
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 64
        private const val FEEDBACK_OFFSET_DP = 72
        private const val HIDE_OVERLAY_DELAY_MS = 450L
        private const val REFRESH_OVERLAY_DELAY_MS = 80L
        private const val CANCEL_LONG_PRESS_MS = 2000L
        private const val RESULT_DISPLAY_MS = 1000L
        private const val MAX_TRANSCRIPTION_RETRIES = 2
        private const val TRANSCRIPTION_RETRY_BASE_DELAY_MS = 750L

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_POST_PROCESSING = 0xDD6750A4.toInt()
        private const val COLOR_SUCCESS = 0xDD2E7D32.toInt()
        private const val COLOR_FAILURE = 0xDDC62828.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING, POST_PROCESSING, RESULT }

    private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasRecordingAudioFocus = false
    private var activeTranscriptionCall: Call? = null
    private var transcriptionRetry: Runnable? = null
    private var transcriptionAttempt = 0
    private var transcriptionGeneration = 0
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }
    private val refreshOverlayVisibility = Runnable { updateOverlayVisibility() }
    private val hideOverlay = Runnable {
        if (state == State.IDLE && !hasTextInputContext()) hideOverlayNow()
    }
    private var longPressTriggered = false
    private val cancelLongPress = Runnable {
        if (state == State.RECORDING || state == State.TRANSCRIBING) {
            longPressTriggered = true
            if (state == State.RECORDING) cancelRecording() else cancelTranscription()
        }
    }
    private val finishResultDisplay = Runnable {
        state = State.IDLE
        transitionButtonIcon(R.drawable.ic_mic)
        setAppearance(COLOR_IDLE)
        scheduleOverlayVisibilityUpdate()
    }

    // Local transcription engine (loaded lazily)
    private var localTranscriber: LocalTranscriber? = null

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        showOverlay()
        scheduleOverlayVisibilityUpdate()
        // Try to load local model in background
        thread { initLocalModel() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        scheduleOverlayVisibilityUpdate()
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        abandonRecordingAudioFocus()
        handler.removeCallbacks(refreshOverlayVisibility)
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(hideFeedback)
        handler.removeCallbacks(cancelLongPress)
        handler.removeCallbacks(finishResultDisplay)
        cancelPendingTranscription()
        removeOverlay()
        super.onDestroy()
    }

    private fun initLocalModel() {
        val modelName = prefs().getString("model_name", "") ?: ""
        if (modelName.isBlank()) {
            // Auto-detect first available model
            val models = LocalTranscriber.availableModels(this)
            if (models.isNotEmpty()) {
                Log.i(TAG, "Auto-detected model: ${models.first()}")
                localTranscriber = LocalTranscriber.create(this, models.first())
            }
        } else {
            localTranscriber = LocalTranscriber.create(this, modelName)
        }
        if (localTranscriber != null) {
            Log.i(TAG, "Local transcription ready")
        } else {
            Log.i(TAG, "No local model found, will use API")
        }
    }

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() { thread { initLocalModel() } }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            alpha = 0f
            visibility = View.GONE
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    longPressTriggered = false
                    if (state == State.RECORDING || state == State.TRANSCRIBING) {
                        handler.postDelayed(cancelLongPress, CANCEL_LONG_PRESS_MS)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved >= TAP_THRESHOLD_DP * dp) handler.removeCallbacks(cancelLongPress)
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(cancelLongPress)
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (longPressTriggered) {
                        longPressTriggered = false
                    } else if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(cancelLongPress)
                    longPressTriggered = false
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
    }

    private fun scheduleOverlayVisibilityUpdate() {
        handler.removeCallbacks(refreshOverlayVisibility)
        handler.postDelayed(refreshOverlayVisibility, REFRESH_OVERLAY_DELAY_MS)
    }

    private fun updateOverlayVisibility() {
        if (state != State.IDLE || hasTextInputContext()) {
            handler.removeCallbacks(hideOverlay)
            showOverlayNow()
        } else {
            handler.removeCallbacks(hideOverlay)
            handler.postDelayed(hideOverlay, HIDE_OVERLAY_DELAY_MS)
        }
    }

    private fun showOverlayNow() {
        val view = overlayView ?: return
        if (view.visibility == View.VISIBLE && view.alpha == 1f) return
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(140).start()
    }

    private fun hideOverlayNow() {
        val view = overlayView ?: return
        if (view.visibility != View.VISIBLE) return
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(180).withEndAction {
            if (state == State.IDLE && !hasTextInputContext()) {
                view.visibility = View.GONE
                feedbackView?.visibility = View.GONE
            } else {
                showOverlayNow()
            }
        }.start()
    }

    private fun hasTextInputContext(): Boolean = isImeVisible() || hasFocusedTextInput()

    private fun isImeVisible(): Boolean =
        windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true

    private fun hasFocusedTextInput(): Boolean {
        fun rootHasInputFocus(root: AccessibilityNodeInfo?): Boolean {
            root ?: return false
            return try {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
                try {
                    focused.isEditable ||
                        focused.className?.toString()?.contains("EditText") == true ||
                        focused.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
                } finally {
                    focused.recycle()
                }
            } finally {
                root.recycle()
            }
        }

        if (rootHasInputFocus(rootInActiveWindow)) return true
        return windows
            ?.asSequence()
            ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
            ?.any { rootHasInputFocus(it.root) } == true
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun transitionButtonIcon(iconRes: Int) {
        handler.post {
            val view = button ?: return@post
            view.animate().cancel()
            view.animate().alpha(0f).setDuration(80).withEndAction {
                view.setImageResource(iconRes)
                view.animate().alpha(1f).setDuration(140).start()
            }.start()
        }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    private fun startPulse() {
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> {
                vibrate(VibrationEffect.EFFECT_CLICK)
                startRecording()
            }
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING, State.POST_PROCESSING, State.RESULT -> {}
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in Phone Whisper app"); return
        }

        requestRecordingAudioFocus()

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufSize <= 0) {
            abandonRecordingAudioFocus()
            toast("Unable to initialize microphone")
            return
        }
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to initialize microphone", e)
            abandonRecordingAudioFocus()
            toast("Unable to initialize microphone")
            return
        }

        pcmStream = ByteArrayOutputStream()
        try {
            audioRecord!!.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Unable to start recording", e)
            audioRecord?.release()
            audioRecord = null
            pcmStream = null
            abandonRecordingAudioFocus()
            toast("Unable to start recording")
            return
        }
        state = State.RECORDING
        updateOverlayVisibility()
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        startPulse()

        thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) pcmStream?.write(buf, 0, n)
            }
        }
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        stopPulse()
        transitionButtonIcon(R.drawable.ic_transcribing)
        setAppearance(COLOR_BUSY)
        setBusy(true)

        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord was not recording when stopped", e)
        } finally {
            audioRecord?.release()
            audioRecord = null
            // Resume media as soon as capture ends; transcription can continue silently.
            abandonRecordingAudioFocus()
        }

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty()) { reset("No audio captured"); return }
        vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)

        val useLocal = prefs().getBoolean("use_local", true)
        val local = localTranscriber

        if (useLocal && local != null) {
            transcribeLocal(pcm, local)
        } else {
            transcribeApi(pcm)
        }
    }

    private fun cancelRecording() {
        state = State.RESULT
        stopPulse()
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord was not recording when cancelled", e)
        } finally {
            audioRecord?.release()
            audioRecord = null
            pcmStream = null
            abandonRecordingAudioFocus()
        }
        showResult(success = false, message = "Recording cancelled")
    }

    private fun cancelTranscription() {
        cancelPendingTranscription()
        showResult(success = false, message = "Transcription cancelled")
    }

    private fun cancelPendingTranscription() {
        transcriptionGeneration++
        activeTranscriptionCall?.cancel()
        activeTranscriptionCall = null
        transcriptionRetry?.let(handler::removeCallbacks)
        transcriptionRetry = null
    }

    private fun requestRecordingAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { /* Recording owns focus only while capture is active. */ }
            .build()

        audioFocusRequest = request
        hasRecordingAudioFocus =
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasRecordingAudioFocus) Log.w(TAG, "Recording audio focus was not granted")
    }

    private fun abandonRecordingAudioFocus() {
        val request = audioFocusRequest ?: return
        if (hasRecordingAudioFocus) audioManager.abandonAudioFocusRequest(request)
        audioFocusRequest = null
        hasRecordingAudioFocus = false
    }

    private fun transcribeLocal(pcm: ByteArray, transcriber: LocalTranscriber) {
        thread {
            try {
                // Convert 16-bit PCM bytes to float samples
                val samples = FloatArray(pcm.size / 2)
                for (i in samples.indices) {
                    val lo = pcm[i * 2].toInt() and 0xFF
                    val hi = pcm[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }

                val t0 = System.currentTimeMillis()
                val text = transcriber.transcribe(samples, SAMPLE_RATE)
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "Local transcription: ${ms}ms, ${samples.size / SAMPLE_RATE}s audio")

                handleTranscriptionResult(text)
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                handler.post {
                    showResult(success = false, message = "Local transcription failed")
                }
            }
        }
    }

    private fun transcribeApi(pcm: ByteArray) {
        val wav = WavWriter.encode(pcm)
        val apiKey = prefs().getString("api_key", "") ?: ""
        if (apiKey.isBlank()) { reset("Set API key in Phone Whisper app"); return }

        cancelPendingTranscription()
        val generation = transcriptionGeneration
        transcriptionAttempt = 0
        startTranscriptionAttempt(wav, apiKey, generation)
    }

    private fun startTranscriptionAttempt(wav: ByteArray, apiKey: String, generation: Int) {
        if (generation != transcriptionGeneration || state != State.TRANSCRIBING) return

        activeTranscriptionCall = TranscriberClient.transcribe(wav, apiKey) { result ->
            handler.post {
                if (generation != transcriptionGeneration || state != State.TRANSCRIBING) return@post
                activeTranscriptionCall = null
                if (result.text != null && result.text.isNotBlank()) {
                    handleTranscriptionResult(result.text)
                } else if (result.retryable && transcriptionAttempt < MAX_TRANSCRIPTION_RETRIES) {
                    transcriptionAttempt++
                    val delay = TRANSCRIPTION_RETRY_BASE_DELAY_MS * transcriptionAttempt
                    showFeedback(
                        "Network issue — retrying ($transcriptionAttempt/$MAX_TRANSCRIPTION_RETRIES)",
                        delay + 1000
                    )
                    transcriptionRetry = Runnable {
                        transcriptionRetry = null
                        startTranscriptionAttempt(wav, apiKey, generation)
                    }.also { handler.postDelayed(it, delay) }
                } else {
                    showResult(success = false, message = result.error ?: "Transcription failed")
                }
            }
        }
    }

    private fun handleTranscriptionResult(text: String?) {
        if (text.isNullOrBlank()) {
            handler.post {
                showResult(success = false, message = "No speech detected")
            }
            return
        }

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val apiKey = prefs().getString("api_key", "") ?: ""
        val minimumLength = prefs().getInt("post_processing_min_length", 15)

        if (usePostProcessing && PostProcessor.weightedTextLength(text) >= minimumLength) {
            if (apiKey.isBlank()) {
                handler.post {
                    injectText(text)
                    showResult(success = false, message = "Cleanup skipped — raw text used")
                }
                return
            }

            val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            val model = PostProcessor.Model.fromPreference(
                prefs().getString("post_processing_model", null)
            )
            handler.post {
                state = State.POST_PROCESSING
                transitionButtonIcon(R.drawable.ic_post_processing)
                setAppearance(COLOR_POST_PROCESSING)
                setBusy(true)
                updateOverlayVisibility()
            }

            PostProcessor.process(text, prompt, apiKey, model) { result ->
                handler.post {
                    if (result.text != null && result.text.isNotBlank()) {
                        val injected = injectText(result.text)
                        showResult(
                            success = injected,
                            message = if (injected) null else "Copied to clipboard"
                        )
                    } else {
                        injectText(text, feedback = "Cleanup failed — raw copied to clipboard", feedbackDurationMs = 3000)
                        showResult(success = false, message = "Cleanup failed — raw text used")
                    }
                }
            }
        } else {
            handler.post {
                val injected = injectText(text)
                showResult(
                    success = injected,
                    message = if (injected) null else "Copied to clipboard"
                )
            }
        }
    }

    private fun reset(msg: String) {
        showResult(success = false, message = msg)
    }

    private fun showResult(success: Boolean, message: String? = null) {
        handler.removeCallbacks(finishResultDisplay)
        state = State.RESULT
        stopPulse()
        setBusy(false)
        transitionButtonIcon(
            if (success) R.drawable.ic_result_success else R.drawable.ic_result_failure
        )
        setAppearance(if (success) COLOR_SUCCESS else COLOR_FAILURE)
        updateOverlayVisibility()
        message?.let { showFeedback(it, RESULT_DISPLAY_MS) }
        vibrate(
            if (success) VibrationEffect.EFFECT_HEAVY_CLICK
            else VibrationEffect.EFFECT_TICK
        )
        handler.postDelayed(finishResultDisplay, RESULT_DISPLAY_MS)
    }

    // --- Text injection ---

    private fun injectText(
        text: String,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000
    ): Boolean {
        val clip = ClipData.newPlainText("phonewhisper", text)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        
        val clipboard = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        clipboard.setPrimaryClip(clip)
        feedback?.let { showFeedback(it, feedbackDurationMs) }

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
        return injected
    }

    private fun vibrate(effectId: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createPredefined(effectId))
        }
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        logNode("Trying node", node)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return true

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val replacementStart = minOf(start, end)
            val replacementEnd = maxOf(start, end)
            val updated = current.replaceRange(replacementStart, replacementEnd, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} text=${node.text} desc=${node.contentDescription} actions=[$actions]"
        )
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
