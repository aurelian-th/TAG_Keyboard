package org.futo.inputmethod.latin.uix

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

val ACTIVE_VOICE_MODEL_FILE = SettingsKey(
    key = stringPreferencesKey("active_voice_model_file"),
    default = ""
)

val ACTIVE_SLM_MODEL_FILE = SettingsKey(
    key = stringPreferencesKey("active_slm_model_file"),
    default = ""
)

val ENABLE_SOUND = SettingsKey(
    key = booleanPreferencesKey("enable_sounds"),
    default = true
)

val VERBOSE_PROGRESS = SettingsKey(
    key = booleanPreferencesKey("verbose_progress"),
    default = false
)

val ENABLE_ENGLISH = SettingsKey(
    key = booleanPreferencesKey("enable_english"),
    default = true
)

val ENABLE_MULTILINGUAL = SettingsKey(
    key = booleanPreferencesKey("enable_multilingual"),
    default = false
)

val DISALLOW_SYMBOLS = SettingsKey(
    key = booleanPreferencesKey("disallow_symbols"),
    default = true
)

val PREFER_BLUETOOTH = SettingsKey(
    key = booleanPreferencesKey("prefer_bluetooth_recording"),
    default = false
)

val CAN_EXPAND_SPACE = SettingsKey(
    key = booleanPreferencesKey("can_expand_space"),
    default = true
)

val AUDIO_FOCUS = SettingsKey(
    key = booleanPreferencesKey("request_audio_focus"),
    default = true
)

val USE_VAD_AUTOSTOP = SettingsKey(
    key = booleanPreferencesKey("use_vad_autostop"),
    default = true
)

val ENGLISH_MODEL_INDEX = SettingsKey(
    key = intPreferencesKey("english_model_index"),
    default = 0
)

val MULTILINGUAL_MODEL_INDEX = SettingsKey(
    key = intPreferencesKey("multilingual_model_index"),
    default = 1
)

val LANGUAGE_TOGGLES = SettingsKey(
    key = stringSetPreferencesKey("enabled_languages"),
    default = setOf()
)

val USE_PERSONAL_DICT = SettingsKey(
    key = booleanPreferencesKey("use_personal_dict_voice_input"),
    default = true
)

val ANIMATE_BUBBLE = SettingsKey(
    key = booleanPreferencesKey("animate_bubble"),
    default = true
)

val RAMBLER_ENABLED = SettingsKey(
    key = booleanPreferencesKey("rambler_enabled"),
    default = true
)

val RAMBLER_STREAMING_PREVIEW = SettingsKey(
    key = booleanPreferencesKey("rambler_streaming_preview"),
    default = true
)

val RAMBLER_DISFLUENCIES = SettingsKey(
    key = booleanPreferencesKey("rambler_disfluencies"),
    default = true
)

val RAMBLER_DIACRITICS = SettingsKey(
    key = booleanPreferencesKey("rambler_diacritics"),
    default = true
)

val RAMBLER_SMART_PUNCTUATION = SettingsKey(
    key = booleanPreferencesKey("rambler_smart_punctuation"),
    default = true
)

val RAMBLER_AI_MODE = SettingsKey(
    key = booleanPreferencesKey("rambler_ai_mode"),
    default = true
)

val EMOJI_KITCHEN_ENABLED = SettingsKey(
    key = booleanPreferencesKey("emoji_kitchen_enabled"),
    default = true
)

val EMOJI_KITCHEN_ONLY_SUPPORTED_APPS = SettingsKey(
    key = booleanPreferencesKey("emoji_kitchen_only_supported_apps"),
    default = false
)

val EMOJI_KITCHEN_REQUIRE_TAP = SettingsKey(
    key = booleanPreferencesKey("emoji_kitchen_require_tap"),
    default = false
)

val EMOJI_KITCHEN_ONLY_MASHUPS = SettingsKey(
    key = booleanPreferencesKey("emoji_kitchen_only_mashups"),
    default = false
)