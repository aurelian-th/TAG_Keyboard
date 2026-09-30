package org.futo.voiceinput.shared

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.konovalov.vad.Vad
import com.konovalov.vad.config.FrameSize
import com.konovalov.vad.config.Mode
import com.konovalov.vad.config.Model
import com.konovalov.vad.config.SampleRate
import com.konovalov.vad.models.VadModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.voiceinput.shared.ggml.InferenceCancelledException
import org.futo.voiceinput.shared.ggml.InvalidModelException
import org.futo.voiceinput.shared.types.AudioRecognizerListener
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunner
import org.futo.voiceinput.shared.whisper.isBlankResult
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private fun getRecordingDeviceKind(type: Int): String {
    return when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV_TUNER"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_DOCK_ANALOG -> "DOCK_ANALOG"
        else -> "unknown@${type}"
    }
}

data class RecordingSettings(
    val preferBluetoothMic: Boolean,
    val requestAudioFocus: Boolean,
    val canExpandSpace: Boolean,
    val useVADAutoStop: Boolean
)

data class AudioRecognizerSettings(
    val modelRunConfiguration: MultiModelRunConfiguration,
    val decodingConfiguration: DecodingConfiguration,
    val recordingConfiguration: RecordingSettings
)

class ModelDoesNotExistException(val models: List<ModelLoader>) : Throwable()

class AudioRecognizer(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    modelManager: ModelManager,
    private val listener: AudioRecognizerListener,
    private val settings: AudioRecognizerSettings
) {
    private var isRecording = false
    private var recorder: AudioRecord? = null

    private val modelRunner = MultiModelRunner(modelManager)

    private val canExpandSpace = settings.recordingConfiguration.canExpandSpace
    private val useVAD = settings.recordingConfiguration.useVADAutoStop

    private var floatSamples: FloatBuffer = FloatBuffer.allocate(16000 * 30)
    private var recorderJob: Job? = null
    private var modelJob: Job? = null
    private var loadModelJob: Job? = null
    private var liveStreamingJob: Job? = null

    private var focusRequest: AudioFocusRequest? = null

    private var communicationDevice = "unknown"

    private fun focusAudio() {
        unfocusAudio()

        if(!settings.recordingConfiguration.requestAudioFocus) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                focusRequest =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .build()
                audioManager.requestAudioFocus(focusRequest!!)
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unfocusAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest!!)
                }
                focusRequest = null
            }
        }catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isBluetoothAvailable(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.availableCommunicationDevices

                return devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } != null
            }
        } catch(_: Exception) {}

        return false
    }

    private fun setCommunicationDevice(preferBluetoothMic: Boolean): Pair<Boolean, String> {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.availableCommunicationDevices
                val tgtDevice =
                    devices.firstOrNull { preferBluetoothMic && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?:
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?:
                    devices.firstOrNull { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO  } ?:
                    devices.first()

                if (!audioManager.setCommunicationDevice(tgtDevice)) {
                    audioManager.clearCommunicationDevice()
                    return Pair(false, "")
                } else {
                    return Pair(tgtDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO, tgtDevice.productName.toString())
                }
            }
        } catch(_: Exception) {}
        return Pair(false, "")
    }

    private fun clearCommunicationDevice() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    @Throws(ModelDoesNotExistException::class)
    private fun verifyModelsExist() {
        val modelsThatDoNotExist = mutableListOf<ModelLoader>()

        if (!settings.modelRunConfiguration.primaryModel.exists(context)) {
            modelsThatDoNotExist.add(settings.modelRunConfiguration.primaryModel)
        }

        for (model in settings.modelRunConfiguration.languageSpecificModels.values) {
            if (!model.exists(context)) {
                modelsThatDoNotExist.add(model)
            }
        }

        if (modelsThatDoNotExist.isNotEmpty()) {
            throw ModelDoesNotExistException(modelsThatDoNotExist)
        }
    }

    init {
        verifyModelsExist()
    }

    fun reset() {
        try {
            recorder?.stop()
        } catch (_: Throwable) { }
        try {
            recorderJob?.cancel()
        } catch (_: Throwable) { }

        try {
            recorder?.release()
        } catch (_: Throwable) { }
        recorder = null

        try {
            modelJob?.cancel()
        } catch (_: Throwable) { }
        try {
            liveStreamingJob?.cancel()
        } catch (_: Throwable) { }
        isRecording = false

        try {
            modelRunner.cancelAll()
        } catch (_: Throwable) { }

        try {
            unfocusAudio()
        } catch (_: Throwable) { }

        try {
            clearCommunicationDevice()
        } catch (_: Throwable) { }
    }

    fun finish() {
        try {
            if(!isRecording) return
            onFinishRecording()
        } catch (t: Throwable) {
            Log.e("AudioRecognizer", "finish error", t)
            reset()
            listener.cancelled()
        }
    }

    fun cancel() {
        try {
            reset()
            listener.cancelled()
        } catch (t: Throwable) {
            Log.e("AudioRecognizer", "cancel error", t)
        }
    }

    fun openPermissionSettings() {
        try {
            val micPermissionRequester = Intent()
            micPermissionRequester.setClassName(context, "org.futo.inputmethod.latin.MicPermissionActivity")
            micPermissionRequester.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            context.startActivity(micPermissionRequester)
        } catch (e: Throwable) {
            Log.e("AudioRecognizer", "Failed to start MicPermissionActivity, trying system settings", e)
            try {
                val appSettings = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
                appSettings.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(appSettings)
            } catch (e2: Throwable) {
                Log.e("AudioRecognizer", "Failed to open app settings", e2)
            }
        }

        cancel()
    }

    fun start() {
        try {
            listener.loading()

            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermission()
            } else {
                startRecording()
            }
        } catch (t: Throwable) {
            Log.e("AudioRecognizer", "start failed", t)
            listener.cancelled()
        }
    }

    private fun requestPermission() {
        try {
            listener.needPermission { wasGranted ->
                if(wasGranted) {
                    startRecording()
                } else {
                    Log.w("AudioRecognizer", "Permission was not granted")
                }
            }
        } catch (t: Throwable) {
            Log.e("AudioRecognizer", "requestPermission error", t)
        }
    }


    @Throws(Exception::class)
    private fun createAudioRecorder(): AudioRecord {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = if (minBufferSize > 0) {
            maxOf(minBufferSize * 2, sampleRate * 2 * 5)
        } else {
            sampleRate * 2 * 5
        }

        // Try VOICE_RECOGNITION first, then fallback to MIC, then DEFAULT
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )

        var lastException: Throwable? = null
        for (source in sources) {
            try {
                val rec = AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    this.recorder = rec
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            rec.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
                        } catch (_: Throwable) { }
                    }
                    return rec
                } else {
                    rec.release()
                }
            } catch (t: Throwable) {
                lastException = t
            }
        }

        throw lastException ?: IllegalStateException("Unable to initialize AudioRecord with any audio source")
    }

    private suspend fun preloadModels() {
        modelRunner.preload(settings.modelRunConfiguration)
    }

    private fun expandSpaceIfAllowed(): Boolean {
        if(canExpandSpace) {
            // Allocate an extra 30 seconds
            val newSampleBuffer = FloatBuffer.allocate(floatSamples.capacity() + 16000 * 30)
            //Log.d("AudioRecognizer", "Allocating extra space: ${floatSamples.capacity() / 16000} -> ${newSampleBuffer.capacity() / 16000}")
            newSampleBuffer.put(floatSamples.array(), 0, floatSamples.capacity() - floatSamples.remaining())
            floatSamples = newSampleBuffer
            return true
        }
        return false
    }

    private suspend fun recordingJob(recorder: AudioRecord, vad: VadModel?) {
        var hasTalked = false
        var anyNoiseAtAll = false

        val canMicBeBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(SensorPrivacyManager::class.java) as SensorPrivacyManager).supportsSensorToggle(
                SensorPrivacyManager.Sensors.MICROPHONE
            )
        } else {
            false
        }
        var isMicBlocked = false

        val vadSampleBuffer = ShortBuffer.allocate(480)
        var numConsecutiveNonSpeech = 0
        var numConsecutiveSpeech = 0

        val samples = ShortArray(1600)

        while (isRecording) {
            yield()
            val nRead = recorder.read(samples, 0, 1600, AudioRecord.READ_BLOCKING)
            if (nRead <= 0) break
            yield()

            var isRunningOutOfSpace = (floatSamples.remaining() < nRead.coerceAtLeast(1600)) && !expandSpaceIfAllowed()

            val hasNotTalkedRecently = hasTalked && (numConsecutiveNonSpeech > 28) && useVAD
            if (isRunningOutOfSpace || hasNotTalkedRecently) {
                yield()
                withContext(Dispatchers.Main) {
                    finish()
                }
                return
            }

            // Run VAD
            if(useVAD && vad != null) {
                var remainingSamples = nRead
                var offset = 0
                while (remainingSamples > 0) {
                    if (!vadSampleBuffer.hasRemaining()) {
                        val isSpeech = vad.isSpeech(vadSampleBuffer.array())
                        vadSampleBuffer.clear()
                        vadSampleBuffer.rewind()

                        if (!isSpeech) {
                            numConsecutiveNonSpeech++
                            numConsecutiveSpeech = 0
                        } else {
                            numConsecutiveNonSpeech = 0
                            numConsecutiveSpeech++
                        }
                    }

                    val samplesToRead = min(min(remainingSamples, 480), vadSampleBuffer.remaining())
                    for (i in 0 until samplesToRead) {
                        vadSampleBuffer.put(
                            samples[offset]
                        )
                        offset += 1
                        remainingSamples -= 1
                    }
                }
            }

            floatSamples.put(samples.sliceArray(0 until nRead).map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray())

            // Don't set hasTalked if the start sound may still be playing, otherwise on some
            // devices the rms just explodes and `hasTalked` is always true
            val startSoundPassed = (floatSamples.position() > 16000 * 0.6)
            if (!startSoundPassed) {
                numConsecutiveSpeech = 0
                numConsecutiveNonSpeech = 0
            }

            val rms = sqrt(samples.sumOf { (it.toFloat() / Short.MAX_VALUE.toFloat()).pow(2).toDouble() } / samples.size).toFloat()

            if (startSoundPassed && ((rms > 0.004f) || (numConsecutiveSpeech > 3))) {
                hasTalked = true
            }

            if (rms > 0.0001) {
                anyNoiseAtAll = true
                isMicBlocked = false
            }

            // Check if mic is blocked
            val blockCheckTimePassed = (floatSamples.position() > 2 * 16000) // two seconds
            if (!anyNoiseAtAll && canMicBeBlocked && blockCheckTimePassed) {
                isMicBlocked = true
            }

            val magnitude = (1.0f - 0.1f.pow(24.0f * rms))

            val state = if (hasTalked) {
                MagnitudeState.TALKING
            } else if (isMicBlocked) {
                MagnitudeState.MIC_MAY_BE_BLOCKED
            } else {
                MagnitudeState.NOT_TALKED_YET
            }

            yield()
            withContext(Dispatchers.Main) {
                listener.updateMagnitude(magnitude, state)
            }

            // Skip ahead as much as possible, in case we are behind (taking more than
            // 100ms to process 100ms)
            while (true) {
                yield()
                val nRead2 = recorder.read(
                    samples, 0, 1600, AudioRecord.READ_NON_BLOCKING
                )
                if (nRead2 > 0) {
                    if (floatSamples.remaining() < nRead2 && !expandSpaceIfAllowed()) {
                        yield()
                        withContext(Dispatchers.Main) {
                            finish()
                        }
                        break
                    }
                    floatSamples.put(samples.sliceArray(0 until nRead2).map { it.toFloat() / Short.MAX_VALUE.toFloat() }.toFloatArray())
                } else {
                    break
                }
            }
        }
        println("isRecording loop exited")
    }

    private fun createVad(): VadModel {
        return Vad.builder().setModel(Model.WEB_RTC_GMM).setMode(Mode.VERY_AGGRESSIVE)
            .setFrameSize(FrameSize.FRAME_SIZE_480).setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setSpeechDurationMs(150).setSilenceDurationMs(300).build()
    }

    private fun createRecorderAndJob(preferBluetoothMic: Boolean): MicrophoneDeviceState {
        isRecording = false
        try {
            recorder?.stop()
        } catch (_: Throwable) { }

        val bluetoothInfo = setCommunicationDevice(preferBluetoothMic)

        val task = {
            try {
                try {
                    recorder?.release()
                } catch (_: Throwable) { }

                val rec = createAudioRecorder()

                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord failed to initialize (state is UNINITIALIZED)")
                }

                rec.startRecording()
                this.recorder = rec

                isRecording = true

                recorderJob = lifecycleScope.launch {
                    withContext(Dispatchers.Default) {
                        try {
                            if(useVAD) {
                                try {
                                    createVad().use { vad ->
                                        recordingJob(rec, vad)
                                    }
                                } catch (vadEx: Throwable) {
                                    Log.w("AudioRecognizer", "VAD failed to initialize, running without VAD", vadEx)
                                    recordingJob(rec, null)
                                }
                            } else {
                                recordingJob(rec, null)
                            }
                        } catch (t: Throwable) {
                            Log.e("AudioRecognizer", "recordingJob encountered error", t)
                            withContext(Dispatchers.Main) {
                                cancel()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e("AudioRecognizer", "Failed to start recording in task", t)
                isRecording = false
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    clearCommunicationDevice()
                    requestPermission()
                } else {
                    listener.cancelled()
                }
            }
        }

        if(recorderJob != null) {
            lifecycleScope.launch {
                try {
                    recorderJob?.cancelAndJoin()
                } catch (_: Throwable) { }
                task()
            }
        } else {
            task()
        }


        return MicrophoneDeviceState(
            bluetoothAvailable = bluetoothInfo.first || isBluetoothAvailable(),
            bluetoothActive = bluetoothInfo.first,
            deviceName = bluetoothInfo.second,
            bluetoothPreferredByUser = settings.recordingConfiguration.preferBluetoothMic,
            setBluetooth = {
                try {
                    listener.recordingStarted(createRecorderAndJob(it))
                } catch (t: Throwable) {
                    Log.e("AudioRecognizer", "setBluetooth error", t)
                }
            }
        )
    }

    private fun startRecording() {
        val device = try {
            createRecorderAndJob(settings.recordingConfiguration.preferBluetoothMic)
        } catch (e: SecurityException) {
            // It's possible we may have lost permission, so let's just ask for permission again
            clearCommunicationDevice()
            requestPermission()
            return
        } catch (e: Throwable) {
            Log.e("AudioRecognizer", "Failed to createRecorderAndJob", e)
            clearCommunicationDevice()
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermission()
            } else {
                listener.cancelled()
            }
            return
        }

        try {
            focusAudio()
            listener.recordingStarted(device)
        } catch (t: Throwable) {
            Log.e("AudioRecognizer", "Error focusing audio or notifying recordingStarted", t)
        }

        loadModelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    preloadModels()
                } catch(t: Throwable) {
                    Log.e("AudioRecognizer", "Failed to preload models", t)
                    withContext(Dispatchers.Main) {
                        reset()
                        listener.modelLoadingFailed()
                    }
                }
            }
        }

        // Live real-time streaming recognition loop:
        // As audio arrives in floatSamples, periodically infer snapshots so words appear
        // immediately on the user's screen in real time while they speak.
        liveStreamingJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                try {
                    loadModelJob?.join()
                } catch (_: Throwable) { }

                delay(400)
                var lastInferredLen = 0

                while (isRecording && isActive) {
                    try {
                        val currentLen = floatSamples.position()
                        if (currentLen >= 8000 && currentLen > lastInferredLen + 3200 && isRecording) {
                            lastInferredLen = currentLen
                            val snapshot = floatSamples.array().sliceArray(0 until currentLen)
                            val liveText = modelRunner.runStreaming(
                                snapshot,
                                settings.modelRunConfiguration,
                                settings.decodingConfiguration,
                                runnerCallback
                            )
                            if (liveText.isNotBlank() && isRecording) {
                                runnerCallback.partialResult(liveText)
                            }
                        }
                    } catch (_: Throwable) { }
                    delay(350)
                }
            }
        }
    }

    private val runnerCallback: ModelInferenceCallback = object : ModelInferenceCallback {
        override fun updateStatus(state: InferenceState) {
            listener.decodingStatus(state)
        }

        override fun languageDetected(language: Language) {
            listener.languageDetected(language)
        }

        override fun partialResult(string: String) {
            if(isBlankResult(string)) return
            listener.partialResult(string)
        }
    }

    private suspend fun runModel() {
        try {
            loadModelJob?.let {
                if (it.isActive) {
                    println("Model was not finished loading...")
                    it.join()
                }
            }

            val floatArray = floatSamples.array().sliceArray(0 until floatSamples.position())

            yield()
            val outputText = try {
                 modelRunner.run(
                    floatArray,
                    settings.modelRunConfiguration,
                    settings.decodingConfiguration,
                    runnerCallback
                ).trim()
            } catch(e: InferenceCancelledException) {
                yield()
                return
            } catch (t: Throwable) {
                Log.e("AudioRecognizer", "modelRunner.run error", t)
                yield()
                withContext(Dispatchers.Main) {
                    listener.cancelled()
                }
                return
            }

            val text = when {
                isBlankResult(outputText) -> ""
                else -> outputText
            }

            yield()
            lifecycleScope.launch {
                withContext(Dispatchers.Main) {
                    try {
                        yield()
                        listener.finished(text)
                    } catch (t: Throwable) {
                        Log.e("AudioRecognizer", "listener.finished error", t)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("AudioRecognizer", "runModel general error", t)
            withContext(Dispatchers.Main) {
                listener.cancelled()
            }
        }
    }

    private fun onFinishRecording() {
        try {
            try {
                recorderJob?.cancel()
            } catch (_: Throwable) { }
            try {
                liveStreamingJob?.cancel()
            } catch (_: Throwable) { }
            try {
                modelRunner.cancelAll()
            } catch (_: Throwable) { }

            if (!isRecording) {
                return
            }

            isRecording = false
            try {
                recorder?.stop()
            } catch (e: Throwable) {
                Log.w("AudioRecognizer", "Error stopping recorder", e)
            }

            listener.processing()

            modelJob = lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    try {
                        runModel()
                    } catch (t: Throwable) {
                        Log.e("AudioRecognizer", "runModel error", t)
                        withContext(Dispatchers.Main) {
                            listener.cancelled()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("AudioRecognizer", "onFinishRecording error", t)
            reset()
            listener.cancelled()
        }
    }
}