package org.futo.voiceinput.shared.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.futo.voiceinput.shared.R
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.ui.theme.Typography

data class MicrophoneDeviceState(
    val bluetoothAvailable: Boolean,
    val bluetoothActive: Boolean,
    val bluetoothPreferredByUser: Boolean,
    val setBluetooth: (Boolean) -> Unit,
    val deviceName: String
)

@Composable
fun SoundWaveVisualizer(
    magnitude: MutableFloatState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_pulse")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Row(
        modifier = modifier.height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val barCount = 7
        for (i in 0 until barCount) {
            val dist = kotlin.math.abs(i - 3)
            val centerWeight = (1.0f - dist * 0.18f).coerceAtLeast(0.35f)
            val waveOscillation = (kotlin.math.sin(phase + i * 0.7f).toFloat() + 1.0f) * 0.5f
            val mag = magnitude.floatValue.coerceIn(0.0f, 1.0f)
            val heightFraction = (0.22f + (0.35f * waveOscillation + 0.55f * mag) * centerWeight).coerceIn(0.18f, 1.0f)

            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height((44 * heightFraction).dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        ),
                        shape = RoundedCornerShape(3.dp)
                    )
            )
        }
    }
}

@Composable
fun AnimatedRecognizeCircle(magnitude: MutableFloatState = mutableFloatStateOf(0.5f)) {
    SoundWaveVisualizer(magnitude = magnitude)
}

@Composable
private fun FakeToast(modifier: Modifier, message: String?) {
    val visible = remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if(message != null) {
            visible.value = true
            delay(2500L)
            visible.value = false
        } else {
            visible.value = false
        }
    }

    if(message != null) {
        AnimatedVisibility(
            visible = visible.value,
            modifier = modifier
                .alpha(0.9f)
                .background(MaterialTheme.colorScheme.surfaceDim, RoundedCornerShape(100))
                .padding(16.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box {
                Text(message, modifier = Modifier.align(Alignment.Center), style = Typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun BoxScope.BluetoothToggleIcon(device: MutableState<MicrophoneDeviceState>? = null) {
    FakeToast(modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-16).dp),
        message = if(device?.value?.bluetoothActive == true) {
            stringResource(R.string.using_bluetooth_mic, device.value.deviceName)
        } else if(device?.value?.bluetoothAvailable == true || device?.value?.bluetoothPreferredByUser == true) {
            stringResource(R.string.using_built_in_mic, device.value.deviceName)
        } else {
            null
        }
    )

    if(device?.value?.bluetoothAvailable == true) {
        val bluetoothColor = MaterialTheme.colorScheme.primary
        val iconColor = if(device.value.bluetoothActive) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        IconButton(modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = (-16).dp, y = (-16).dp)
            .drawBehind {
                val radius = size.height / 4.0f
                drawRoundRect(
                    bluetoothColor,
                    topLeft = Offset(size.width * 0.1f, size.height * 0.05f),
                    size = Size(size.width * 0.8f, size.height * 0.9f),
                    cornerRadius = CornerRadius(radius, radius),
                    style = if (device.value.bluetoothActive) {
                        Fill
                    } else {
                        Stroke(width = 4.0f)
                    }
                )
            }
            .clearAndSetSemantics {
                this.text = AnnotatedString("Use bluetooth mic")
                this.role = Role.Switch
                this.toggleableState = ToggleableState(device.value.bluetoothActive)
            },
            onClick = {
                device.value.setBluetooth(!device.value.bluetoothActive)
            },
        ) {
            Icon(
                painter = painterResource(id = R.drawable.bluetooth),
                contentDescription = null,
                tint = iconColor
            )
        }
    }
}

@Composable
fun InnerRecognize(
    magnitude: MutableFloatState = mutableFloatStateOf(0.5f),
    state: MutableState<MagnitudeState> = mutableStateOf(MagnitudeState.MIC_MAY_BE_BLOCKED),
    device: MutableState<MicrophoneDeviceState>? = null,
    liveText: String = ""
) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor_pulse")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor"
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Live voice typing bubble
            if (liveText.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = liveText,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .size(width = 2.dp, height = 16.dp)
                                .alpha(cursorAlpha)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
                        )
                    }
                }
            }

            // Animated waveform visualizer
            SoundWaveVisualizer(magnitude = magnitude)

            Spacer(modifier = Modifier.height(10.dp))

            // Modern status badge
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mic_2_),
                        contentDescription = stringResource(R.string.stop_recording),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = when (state.value) {
                            MagnitudeState.NOT_TALKED_YET -> stringResource(R.string.try_saying_something)
                            MagnitudeState.MIC_MAY_BE_BLOCKED -> stringResource(R.string.no_audio_detected_is_your_microphone_blocked)
                            MagnitudeState.TALKING -> stringResource(R.string.listening)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        BluetoothToggleIcon(device)
    }
}


@Composable
fun ColumnScope.RecognizeLoadingCircle(text: String = "Initializing...") {
    CircularProgressIndicator(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(text, modifier = Modifier.align(Alignment.CenterHorizontally))
}

@Composable
fun ColumnScope.PartialDecodingResult(text: String = "I am speaking [...]") {
    CircularProgressIndicator(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        color = MaterialTheme.colorScheme.onPrimary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Surface(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(8.dp)
                .defaultMinSize(0.dp, 64.dp),
            textAlign = TextAlign.Start,
            style = Typography.bodyMedium
        )
    }
}

@Composable
fun ColumnScope.RecognizeMicError(openSettings: () -> Unit) {
    Box(Modifier
        .fillMaxSize()
        .clickable { openSettings() }) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.grant_microphone_permission_to_use_voice_input),
                modifier = Modifier
                    .padding(8.dp, 2.dp)
                    .align(Alignment.CenterHorizontally),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.grant_microphone_permission_to_use_voice_input),
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}