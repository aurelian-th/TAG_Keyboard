package org.futo.inputmethod.latin.uix.actions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.permissions.PermissionsManager
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.ACTIVE_SLM_MODEL_FILE
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.RAMBLER_AI_MODE
import org.futo.inputmethod.latin.uix.RAMBLER_DIACRITICS
import org.futo.inputmethod.latin.xlm.ModelPaths
import org.futo.inputmethod.latin.uix.RAMBLER_DISFLUENCIES
import org.futo.inputmethod.latin.uix.RAMBLER_ENABLED
import org.futo.inputmethod.latin.uix.RAMBLER_SMART_PUNCTUATION
import org.futo.inputmethod.latin.uix.RAMBLER_STREAMING_PREVIEW
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.USE_PERSONAL_DICT
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.VERBOSE_PROGRESS
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.utils.ModelOutputSanitizer
import org.futo.inputmethod.latin.xlm.UserDictionaryObserver
import org.futo.inputmethod.updates.openURI
import org.futo.voiceinput.shared.ModelDoesNotExistException
import org.futo.voiceinput.shared.RecognizerView
import org.futo.voiceinput.shared.RecognizerViewListener
import org.futo.voiceinput.shared.RecognizerViewSettings
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.SoundPlayer
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.getLanguageFromWhisperString
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import java.util.Locale

val SystemVoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.action_system_voice_input_title,
    simplePressImpl = { it, _ ->
        it.triggerSystemVoiceInput()
    },
    persistentState = null,
    windowImpl = null,
    shownInEditor = false
)


@Composable
fun NoModelInstalled(locale: Locale) {
    val context = LocalContext.current
    Box(modifier = Modifier
        .fillMaxSize()
        .clickable(
            enabled = true,
            onClickLabel = null,
            onClick = {
                try {
                    SettingsActivity.openToNavDest(context, "voiceModelsDownload")
                } catch (t: Throwable) {
                    context.openURI("https://keyboard.futo.tech/voice-input-models", true)
                }
            },
            role = null,
            indication = null,
            interactionSource = remember { MutableInteractionSource() })) {
        Text(
            stringResource(
                R.string.action_voice_input_no_model_for_language_x_installed,
                locale.getDisplayName(locale)
            ), modifier = Modifier
                .align(Alignment.Center)
                .padding(8.dp), textAlign = TextAlign.Center)
    }
}

@Composable
fun RamblerThinkingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "rambler_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .padding(16.dp)
            .wrapContentSize()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(30.dp)
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize()
                )
                Icon(
                    painter = painterResource(id = R.drawable.mic_fill),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                    modifier = Modifier.size(15.dp)
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.rambler_thinking_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.rambler_thinking_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
    }
}

class VoiceInputPersistentState(val manager: KeyboardManagerForAction) : PersistentActionState {
    val modelManager = ModelManager(manager.getContext())
    val soundPlayer = SoundPlayer(manager.getContext())
    val userDictionaryObserver = UserDictionaryObserver(manager.getContext())

    override suspend fun cleanUp() {
        try {
            modelManager.cleanUp()
        } catch (t: Throwable) {
            android.util.Log.e("VoiceInputAction", "Error during modelManager.cleanUp", t)
        }
    }

    override fun close() {
        try {
            runBlocking { modelManager.cleanUp() }
        } catch (t: Throwable) {
            android.util.Log.e("VoiceInputAction", "Error during modelManager.cleanUp on close", t)
        }
        try {
            userDictionaryObserver.unregister()
        } catch (t: Throwable) {
            android.util.Log.e("VoiceInputAction", "Error unregistering userDictionaryObserver", t)
        }
    }
}

private class VoiceInputActionWindow(
    val manager: KeyboardManagerForAction, val state: VoiceInputPersistentState,
    val model: ModelLoader, val locales: List<Locale>
) : ActionWindow(), RecognizerViewListener {
    val context = manager.getContext()

    private var shouldPlaySounds: Boolean = false
    private fun loadSettings(): RecognizerViewSettings {
        val enableSound = context.getSetting(ENABLE_SOUND)
        val verboseFeedback = false//context.getSetting(VERBOSE_PROGRESS)
        val disallowSymbols = context.getSetting(DISALLOW_SYMBOLS)
        val useBluetoothAudio = context.getSetting(PREFER_BLUETOOTH)
        val requestAudioFocus = context.getSetting(AUDIO_FOCUS)
        val canExpandSpace = context.getSetting(CAN_EXPAND_SPACE)
        val useVAD = context.getSetting(USE_VAD_AUTOSTOP)
        val usePersonalDict = context.getSetting(USE_PERSONAL_DICT)
        val animateBubble = context.getSetting(ANIMATE_BUBBLE)

        val primaryModel = model
        val languageSpecificModels = mutableMapOf<Language, ModelLoader>()
        val baseLanguages = locales.mapNotNull { getLanguageFromWhisperString(it.language) }.toSet()
        val codeSwitchDialects = setOf(Language.Romanian, Language.Russian, Language.English, Language.French)
        val isMultilingual = primaryModel != org.futo.voiceinput.shared.BUILTIN_ENGLISH_MODEL
        val allowedLanguages = if (isMultilingual) {
            if (baseLanguages.any { it in codeSwitchDialects } || baseLanguages.size > 1) {
                baseLanguages + codeSwitchDialects
            } else {
                baseLanguages.ifEmpty { codeSwitchDialects }
            }
        } else {
            baseLanguages.ifEmpty { setOf(Language.English) }
        }
        val glossary = if(usePersonalDict) {
            state.userDictionaryObserver.getWords(locales).filter { it.shortcut.isNullOrEmpty() }.map { it.word }
        } else {
            emptyList()
        }

        val useStreamingPreview = context.getSetting(RAMBLER_STREAMING_PREVIEW)

        shouldPlaySounds = enableSound

        return RecognizerViewSettings(
            shouldShowInlinePartialResult = useStreamingPreview,
            shouldShowVerboseFeedback = verboseFeedback,
            shouldAnimateBubble = animateBubble,
            modelRunConfiguration = MultiModelRunConfiguration(
                primaryModel = primaryModel,
                languageSpecificModels = languageSpecificModels
            ),
            decodingConfiguration = DecodingConfiguration(
                glossary = glossary,
                languages = allowedLanguages,
                suppressSymbols = disallowSymbols
            ),
            recordingConfiguration = RecordingSettings(
                preferBluetoothMic = useBluetoothAudio,
                requestAudioFocus = requestAudioFocus,
                canExpandSpace = canExpandSpace,
                useVADAutoStop = useVAD
            )
        )
    }

    private var recognizerView: MutableState<RecognizerView?> = mutableStateOf(null)
    private var modelException: MutableState<ModelDoesNotExistException?> = mutableStateOf(null)
    private val isRamblerThinking = mutableStateOf(false)

    private val initJob = manager.getLifecycleScope().launch(Dispatchers.Default) {
        try {
            yield()
            val settings = try {
                loadSettings()
            } catch (t: Throwable) {
                android.util.Log.e("VoiceInputAction", "loadSettings error", t)
                null
            } ?: return@launch

            yield()
            val recognizerView = try {
                RecognizerView(
                    context = manager.getContext(),
                    listener = this@VoiceInputActionWindow,
                    settings = settings,
                    lifecycleScope = manager.getLifecycleScope(),
                    modelManager = state.modelManager
                )
            } catch (t: Throwable) {
                android.util.Log.w("VoiceInputAction", "Primary model failed to construct, falling back to built-in model", t)
                try {
                    val fallbackSettings = settings.copy(
                        modelRunConfiguration = settings.modelRunConfiguration.copy(
                            primaryModel = org.futo.voiceinput.shared.BUILTIN_ENGLISH_MODEL,
                            languageSpecificModels = emptyMap()
                        )
                    )
                    RecognizerView(
                        context = manager.getContext(),
                        listener = this@VoiceInputActionWindow,
                        settings = fallbackSettings,
                        lifecycleScope = manager.getLifecycleScope(),
                        modelManager = state.modelManager
                    )
                } catch (t2: Throwable) {
                    android.util.Log.e("VoiceInputAction", "Both primary and fallback models failed", t2)
                    modelException.value = (t as? ModelDoesNotExistException) ?: ModelDoesNotExistException(listOf(model))
                    return@launch
                }
            }

            this@VoiceInputActionWindow.recognizerView.value = recognizerView

            try {
                recognizerView.reset()
                recognizerView.start()
            } catch (t: Throwable) {
                android.util.Log.e("VoiceInputAction", "Failed to start recognizerView", t)
            }
        } catch(t: Throwable) {
            android.util.Log.e("VoiceInputAction", "Failed to initialize RecognizerView", t)
            modelException.value = ModelDoesNotExistException(listOf(model))
        }
    }

    private var inputTransaction = manager.createInputTransaction()

    @Composable
    private fun ModelDownloader(modelException: ModelDoesNotExistException) {
        NoModelInstalled(locales.firstOrNull() ?: Locale.ROOT)
    }

    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Box(modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = !isRamblerThinking.value,
                onClickLabel = null,
                onClick = { recognizerView.value?.finish() },
                role = null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() })
            .semantics(mergeDescendants = true) {
                traversalIndex = -1.0f
            }) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                when {
                    isRamblerThinking.value -> RamblerThinkingIndicator()
                    modelException.value != null -> ModelDownloader(modelException.value!!)
                    recognizerView.value != null -> recognizerView.value!!.Content()
                }
            }
        }
    }

    override fun close(): CloseResult {
        try {
            inputTransaction.cancel()
        } catch (_: Throwable) { }
        try {
            runBlocking { initJob.cancelAndJoin() }
        } catch (_: Throwable) { }
        try {
            recognizerView.value?.cancel()
        } catch (_: Throwable) { }
        try {
            state.modelManager.cancelAll()
        } catch (_: Throwable) { }
        return CloseResult.Default
    }

    private var wasFinished = false
    private var cancelPlayed = false
    override fun cancelled() {
        if (!wasFinished) {
            if (shouldPlaySounds && !cancelPlayed) {
                try {
                    state.soundPlayer.playCancelSound()
                } catch (_: Throwable) { }
                cancelPlayed = true
            }
            try {
                inputTransaction.cancel()
            } catch (_: Throwable) { }
        }
    }

    override fun recordingStarted(device: MicrophoneDeviceState) {
        if (shouldPlaySounds) {
            try {
                state.soundPlayer.playStartSound()
            } catch (_: Throwable) { }
        }

        // Only set the setting if bluetooth is available, else it would reset the setting
        // every time it's used without a bluetooth device connected.
        if(device.bluetoothAvailable) {
            manager.getLifecycleScope().launch {
                try {
                    context.setSetting(PREFER_BLUETOOTH, device.bluetoothActive)
                } catch (_: Throwable) { }
            }
        }
    }

    private fun getRamblerModelFile(): java.io.File? {
        val activeModel = context.getSetting(ACTIVE_SLM_MODEL_FILE)
        if (activeModel.isNotEmpty()) {
            val candidates = listOf(
                java.io.File(ModelPaths.getModelDirectory(context), activeModel),
                java.io.File(context.getExternalFilesDir("models"), activeModel),
                java.io.File(context.getExternalFilesDir(null), activeModel)
            )
            val found = candidates.firstOrNull { it.exists() && it.length() > 10 * 1024 * 1024 }
            if (found != null) return found
        }
        val dirs = listOf(
            ModelPaths.getModelDirectory(context),
            context.getExternalFilesDir("models"),
            context.getExternalFilesDir(null)
        )
        for (dir in dirs) {
            if (dir != null && dir.exists()) {
                val gguf = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") && f.length() > 10 * 1024 * 1024 }
                if (!gguf.isNullOrEmpty()) {
                    return gguf.maxByOrNull { it.lastModified() }
                }
            }
        }
        return null
    }

    private val qwenGenerator: org.futo.inputmethod.latin.xlm.QwenTextGenerator? by lazy {
        val modelFile = getRamblerModelFile()
        if (modelFile != null) {
            android.util.Log.i("VoiceInputAction", "Qwen model file found: ${modelFile.absolutePath}")
            val gen = org.futo.inputmethod.latin.xlm.QwenTextGenerator(modelFile)
            // Pre-load the model asynchronously
            manager.getLifecycleScope().launch(Dispatchers.Default) {
                try {
                    gen.loadModel()
                    android.util.Log.i("VoiceInputAction", "Qwen model pre-loaded successfully")
                } catch (t: Throwable) {
                    android.util.Log.e("VoiceInputAction", "Failed to pre-load Qwen model", t)
                }
            }
            gen
        } else {
            android.util.Log.w("VoiceInputAction", "No Qwen GGUF model file found, AI mode will use regex only")
            null
        }
    }

    private val ramblerEngine by lazy {
        val qwenRewriterImpl = qwenGenerator?.let { gen ->
            object : org.futo.voiceinput.shared.rambler.QwenRewriter {
                override suspend fun rewrite(prompt: String): String {
                    return gen.generate(
                        prompt = prompt,
                        maxTokens = 512,
                        temperature = 0.3f,
                        topP = 0.9f,
                        repetitionPenalty = 1.1f
                    )
                }

                override fun isReady(): Boolean = gen.isModelLoaded()
            }
        }

        org.futo.voiceinput.shared.rambler.RamblerEngine(
            modelFile = getRamblerModelFile(),
            qwenRewriter = qwenRewriterImpl
        )
    }

    override fun finished(result: String) {
        wasFinished = true

        manager.getLifecycleScope().launch(Dispatchers.Default) {
            try {
                val sanitized = ModelOutputSanitizer.sanitize(result, inputTransaction.textContext)
                val useRambler = context.getSetting(RAMBLER_ENABLED)

                if (useRambler) {
                    withContext(Dispatchers.Main) {
                        isRamblerThinking.value = true
                    }
                }

                // 1. Check if the utterance is an edit command on existing text ("change red to green", "from 10 to 12")
                val editPair = if (useRambler) ramblerEngine.parseEditCommand(sanitized) else null
                if (editPair != null) {
                    val handled = withContext(Dispatchers.Main) {
                        inputTransaction.replaceInContext(editPair.first, editPair.second)
                    }
                    if (handled) {
                        withContext(Dispatchers.Main) {
                            manager.announce("Changed ${editPair.first} to ${editPair.second}")
                            isRamblerThinking.value = false
                            manager.closeActionWindow()
                        }
                        return@launch
                    }
                }

                val textContextString = (inputTransaction.textContext.selectedText
                    ?: inputTransaction.textContext.beforeCursor)?.toString()

                // 2. Perform intelligent Rambler rewrites and edits
                val finalOutput = if (useRambler) {
                    try {
                        val stripDisfluencies = context.getSetting(RAMBLER_DISFLUENCIES)
                        val restoreDiacritics = context.getSetting(RAMBLER_DIACRITICS)
                        val smartPunctuation = context.getSetting(RAMBLER_SMART_PUNCTUATION)
                        val aiMode = context.getSetting(RAMBLER_AI_MODE)

                        ramblerEngine.rewrite(
                            rawText = sanitized,
                            stripDisfluencies = stripDisfluencies,
                            restoreDiacritics = restoreDiacritics,
                            smartPunctuation = smartPunctuation,
                            aiMode = aiMode,
                            textContext = textContextString
                        )
                    } catch (e: Throwable) {
                        android.util.Log.e("VoiceInputAction", "RamblerEngine rewrite failed, falling back to raw sanitized text", e)
                        sanitized
                    }
                } else {
                    sanitized
                }

                // 3. Atomically commit final polished text (replaces composing text without double paste)
                withContext(Dispatchers.Main) {
                    try {
                        inputTransaction.commit(finalOutput)
                        manager.announce(finalOutput)
                    } catch (e: Throwable) {
                        android.util.Log.e("VoiceInputAction", "Failed to commit final text", e)
                    } finally {
                        isRamblerThinking.value = false
                        manager.closeActionWindow()
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("VoiceInputAction", "finished processing error", t)
                withContext(Dispatchers.Main) {
                    isRamblerThinking.value = false
                    manager.closeActionWindow()
                }
            }
        }
    }

    override fun partialResult(result: String) {
        try {
            val useStreamingPreview = context.getSetting(RAMBLER_STREAMING_PREVIEW)
            if (useStreamingPreview) {
                manager.getLifecycleScope().launch(Dispatchers.Main) {
                    try {
                        val sanitized = ModelOutputSanitizer.sanitize(result, inputTransaction.textContext)
                        inputTransaction.updatePartial(sanitized)
                    } catch (e: Throwable) {
                        android.util.Log.e("VoiceInputAction", "partialResult update error", e)
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("VoiceInputAction", "partialResult error", t)
        }
    }

    override fun requestPermission(onGranted: () -> Unit, onRejected: () -> Unit): Boolean {
        return try {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                onGranted()
                return true
            }
            PermissionsManager.get(context).requestPermissions(
                { allGranted ->
                    if (allGranted) {
                        onGranted()
                    } else {
                        onRejected()
                    }
                },
                null,
                Manifest.permission.RECORD_AUDIO
            )
            true
        } catch (e: Throwable) {
            android.util.Log.e("VoiceInputAction", "Failed to request permission", e)
            false
        }
    }

    override fun openSettings() {
        try {
            SettingsActivity.openToNavDest(context, "languages")
        } catch (t: Throwable) {
            android.util.Log.e("VoiceInputAction", "openSettings error", t)
        }
    }
}

private class VoiceInputNoModelWindow(val locale: Locale) : ActionWindow() {
    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        NoModelInstalled(locale)
    }
}

val VoiceInputAction = Action(icon = R.drawable.mic_fill,
    name = R.string.action_voice_input_title,
    simplePressImpl = null,
    keepScreenAwake = true,
    persistentState = { VoiceInputPersistentState(it) },
    windowImpl = { manager, persistentState ->
        try {
            val locales = manager.getActiveLocales()

            val model = locales.firstNotNullOfOrNull {
                ResourceHelper.tryFindingVoiceInputModelForLocale(manager.getContext(), it)
            } ?: ResourceHelper.findAnyImportedVoiceInputModel(manager.getContext())
              ?: ResourceHelper.BuiltInVoiceInputFallbacks["en"]
              ?: org.futo.voiceinput.shared.BUILTIN_ENGLISH_MODEL

            val state = (persistentState as? VoiceInputPersistentState)
                ?: VoiceInputPersistentState(manager)

            if (model == null) {
                VoiceInputNoModelWindow(locales.firstOrNull() ?: Locale.ROOT)
            } else {
                VoiceInputActionWindow(
                    manager = manager, state = state,
                    locales = locales, model = model
                )
            }
        } catch (t: Throwable) {
            android.util.Log.e("VoiceInputAction", "Failed in windowImpl", t)
            VoiceInputNoModelWindow(Locale.ROOT)
        }
    }
)