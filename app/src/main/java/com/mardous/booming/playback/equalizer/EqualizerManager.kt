/*
 * Copyright (c) 2024 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.playback.equalizer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.util.UnstableApi
import com.mardous.booming.core.audio.AudioOutputObserver
import com.mardous.booming.core.model.audiodevice.AudioDevice
import com.mardous.booming.core.model.audiodevice.AudioDeviceType
import com.mardous.booming.core.model.equalizer.BalanceState
import com.mardous.booming.core.model.equalizer.BassBoostState
import com.mardous.booming.core.model.equalizer.CompressorState
import com.mardous.booming.core.model.equalizer.EqBandCapabilities
import com.mardous.booming.core.model.equalizer.EqEngineMode
import com.mardous.booming.core.model.equalizer.EqProfile
import com.mardous.booming.core.model.equalizer.EqSession
import com.mardous.booming.core.model.equalizer.EqSession.SessionType
import com.mardous.booming.core.model.equalizer.EqState
import com.mardous.booming.core.model.equalizer.LimiterState
import com.mardous.booming.core.model.equalizer.LoudnessGainState
import com.mardous.booming.core.model.equalizer.ReplayGainState
import com.mardous.booming.core.model.equalizer.TempoState
import com.mardous.booming.core.model.equalizer.VirtualizerState
import com.mardous.booming.core.model.equalizer.VolumeState
import com.mardous.booming.core.model.equalizer.autoeq.AutoEqProfile
import com.mardous.booming.data.model.replaygain.ReplayGainMode
import com.mardous.booming.extensions.files.getFormattedFileName
import com.mardous.booming.extensions.utilities.toEnum
import com.mardous.booming.playback.equalizer.engine.BasicEQEngine
import com.mardous.booming.playback.equalizer.engine.DynamicsProcessingEngine
import com.mardous.booming.playback.equalizer.engine.EQEngine
import com.mardous.booming.playback.processor.BalanceAudioProcessor
import com.mardous.booming.playback.processor.ReplayGainAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.jvm.Synchronized

val Context.eqDataStore by preferencesDataStore("equalizer")

@OptIn(FlowPreview::class)
@androidx.annotation.OptIn(UnstableApi::class)
class EqualizerManager(
    private val context: Context,
    private val balanceProcessor: BalanceAudioProcessor,
    private val replayGainProcessor: ReplayGainAudioProcessor,
    audioOutputObserver: AudioOutputObserver,
) {

    private val eqScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var eqEngine: EQEngine? = null

    /**
     * True while the engine is attached to an audio session that hasn't
     * been confirmed live yet (no AudioTrack has been created on it yet).
     * Effects attached to a session that doesn't exist yet can silently
     * never process audio on some devices, so the engine is recreated once
     * the session is confirmed (onAudioSessionIdChanged / playback start).
     */
    @Volatile
    private var awaitingSessionConfirm = false

    val eqState =
        combine(
            audioOutputObserver.bitPerfectState,
            context.eqDataStore.data
        ) { bitPerfectState, prefs ->
            val engineMode = prefs[Keys.EQ_ENGINE_MODE]
                ?.toEnum<EqEngineMode>()
                ?: EqEngineMode.Auto

            val disableReason = when {
                bitPerfectState.isActive -> EqState.DisableReason.BitPerfect
                prefs[Keys.AUDIO_OFFLOAD] == true -> EqState.DisableReason.AudioOffload
                else -> null
            }

            EqState(
                supported = prefs[Keys.EQ_SUPPORTED] ?: false,
                enabled = prefs[Keys.EQ_ENABLED] ?: false,
                disableReason = disableReason,
                preferredBandCount = prefs[Keys.EQ_BAND_COUNT] ?: engineMode.defaultBandCount,
                engineMode = engineMode,
                proMode = prefs[Keys.EQ_PRO_MODE_ENABLED] ?: false
            )
        }
        .stateIn(eqScope, SharingStarted.Eagerly, EqState.Unspecified)

    val eqCustomProfile =
        combine(
            eqState.filterNot { it == EqState.Unspecified },
            context.eqDataStore.data
        ) { eqState, prefs ->
            val json = prefs[Keys.CUSTOM_PRESET].orEmpty().trim()
            runCatching {
                Json.decodeFromString<EqProfile>(json)
            }.getOrElse { null }
                ?.takeIf { it.isValid }
                ?: getEmptyCustomProfile(eqState.preferredBandCount)
        }.stateIn(eqScope, SharingStarted.Eagerly, getEmptyCustomProfile(0))

    val eqProfiles = context.eqDataStore.data
        .map { prefs ->
            val json = prefs[Keys.PRESETS].orEmpty().trim()
            runCatching {
                Json.decodeFromString<List<EqProfile>>(json)
            }.getOrElse {
                emptyList()
            }
        }
        .stateIn(eqScope, SharingStarted.Eagerly, emptyList())

    val eqCurrentProfile =
        combine(
            eqState.filterNot { it == EqState.Unspecified },
            eqProfiles,
            context.eqDataStore.data
        ) { state, profiles, prefs ->
            val json = prefs[Keys.PRESET].orEmpty().trim()
            runCatching {
                Json.decodeFromString<EqProfile>(json)
            }.getOrElse {
                profiles.firstOrNull()
                    ?: getEmptyCustomProfile(state.preferredBandCount)
            }
        }
        .stateIn(eqScope, SharingStarted.Eagerly, getEmptyCustomProfile(0))

    val autoEqProfiles = context.eqDataStore.data
        .map { prefs ->
            val json = prefs[Keys.AUTO_EQ_PROFILES].orEmpty().trim()
            runCatching {
                Json.decodeFromString<List<AutoEqProfile>>(json)
            }.getOrElse {
                emptyList()
            }
        }
        .stateIn(eqScope, SharingStarted.Eagerly, emptyList())

    val loudnessGainState = context.eqDataStore.data
        .map { prefs ->
            LoudnessGainState(
                supported = prefs[Keys.LOUDNESS_SUPPORTED] ?: false,
                enabled = prefs[Keys.LOUDNESS_ENABLED] ?: false,
                gainInDb = prefs[Keys.LOUDNESS_GAIN] ?: MINIMUM_LOUDNESS_GAIN,
                gainRange = MINIMUM_LOUDNESS_GAIN..MAXIMUM_LOUDNESS_GAIN,
            )
        }
        .stateIn(eqScope, SharingStarted.Eagerly, LoudnessGainState.Unspecified)

    val bassBoostState = context.eqDataStore.data
        .map { prefs ->
            BassBoostState(
                supported = prefs[Keys.BASS_BOOST_SUPPORTED] ?: false,
                enabled = prefs[Keys.BASS_BOOST_ENABLED] ?: false,
                strength = prefs[Keys.BASS_BOOST_STRENGTH] ?: 0f,
                strengthRange = BASSBOOST_MIN_STRENGTH..BASSBOOST_MAX_STRENGTH
            )
        }
        .stateIn(eqScope, SharingStarted.Eagerly, BassBoostState.Unspecified)

    val virtualizerState = context.eqDataStore.data
        .map { prefs ->
            VirtualizerState(
                supported = prefs[Keys.VIRTUALIZER_SUPPORTED] ?: false,
                enabled = prefs[Keys.VIRTUALIZER_ENABLED] ?: false,
                strength = prefs[Keys.VIRTUALIZER_STRENGTH] ?: 0f,
                strengthRange = VIRTUALIZER_MIN_STRENGTH..VIRTUALIZER_MAX_STRENGTH
            )
        }
        .stateIn(eqScope, SharingStarted.Eagerly, VirtualizerState.Unspecified)

    val tempoState = context.eqDataStore.data
        .map { prefs ->
            TempoState(
                speed = prefs[Keys.SPEED] ?: 1f,
                speedRange = MIN_SPEED..MAX_SPEED,
                pitch = prefs[Keys.PITCH] ?: 1f,
                pitchRange = MIN_PITCH..MAX_PITCH,
                isFixedPitch = prefs[Keys.IS_FIXED_PITCH] ?: true
            )
        }
        .stateIn(eqScope, SharingStarted.Eagerly, TempoState.Unspecified)

    val volumeState = context.eqDataStore.data
        .map { prefs ->
            VolumeState(
                currentVolume = prefs[Keys.VOLUME] ?: 1f,
                volumeRange = MIN_VOLUME..MAX_VOLUME
            )
        }
        .stateIn(eqScope, SharingStarted.Eagerly, VolumeState.Unspecified)

    val balanceState = context.eqDataStore.data
        .map { prefs ->
            BalanceState(
                center = prefs[Keys.CENTER_BALANCE] ?: 0f,
                range = -MAX_VOLUME..MAX_VOLUME
            )
        }
        .stateIn(eqScope, SharingStarted.Eagerly, BalanceState.Unspecified)

    val replayGainState = context.eqDataStore.data
        .map { prefs ->
            ReplayGainState(
                mode = prefs[Keys.REPLAYGAIN_MODE]?.toEnum<ReplayGainMode>() ?: ReplayGainMode.Off,
                preamp = prefs[Keys.REPLAYGAIN_PREAMP] ?: 0f,
                preampWithoutGain = prefs[Keys.REPLAYGAIN_PREAMP_WITHOUT_GAIN] ?: 0f
            )
        }
        .stateIn(eqScope, SharingStarted.Eagerly, ReplayGainState.Unspecified)

    val compressorState = context.eqDataStore.data
        .map { prefs ->
            CompressorState(
                enabled = prefs[Keys.COMPRESSOR_ENABLED] ?: false,
                attackTimeMs = prefs[Keys.COMPRESSOR_ATTACK] ?: CompressorState.Unspecified.attackTimeMs,
                attackTimeRange = CompressorState.Unspecified.attackTimeRange,
                releaseTimeMs = prefs[Keys.COMPRESSOR_RELEASE] ?: CompressorState.Unspecified.releaseTimeMs,
                releaseTimeRange = CompressorState.Unspecified.releaseTimeRange,
                kneeWidth = prefs[Keys.COMPRESSOR_KNEE] ?: CompressorState.Unspecified.kneeWidth,
                kneeWidthRange = CompressorState.Unspecified.kneeWidthRange,
                noiseGateThreshold = prefs[Keys.COMPRESSOR_NOISE_GATE] ?: CompressorState.Unspecified.noiseGateThreshold,
                noiseGateThresholdRange = CompressorState.Unspecified.noiseGateThresholdRange,
                preGain = prefs[Keys.COMPRESSOR_PRE_GAIN] ?: CompressorState.Unspecified.preGain,
                preGainRange = CompressorState.Unspecified.preGainRange,
                postGain = prefs[Keys.COMPRESSOR_POST_GAIN] ?: CompressorState.Unspecified.postGain,
                postGainRange = CompressorState.Unspecified.postGainRange,
                ratio = prefs[Keys.COMPRESSOR_RATIO] ?: CompressorState.Unspecified.ratio,
                ratioRange = CompressorState.Unspecified.ratioRange,
                expanderRatio = prefs[Keys.COMPRESSOR_EXPANDER_RATIO] ?: CompressorState.Unspecified.expanderRatio,
                expanderRatioRange = CompressorState.Unspecified.expanderRatioRange,
                threshold = prefs[Keys.COMPRESSOR_THRESHOLD] ?: CompressorState.Unspecified.threshold,
                thresholdRange = CompressorState.Unspecified.thresholdRange
            )
        }
        .stateIn(eqScope, SharingStarted.Eagerly, CompressorState.Unspecified)

    val limiterState = context.eqDataStore.data
        .map { prefs ->
            LimiterState(
                enabled = prefs[Keys.LIMITER_ENABLED] ?: false,
                attackTimeMs = prefs[Keys.LIMITER_ATTACK] ?: LimiterState.Unspecified.attackTimeMs,
                attackTimeRange = LimiterState.Unspecified.attackTimeRange,
                releaseTimeMs = prefs[Keys.LIMITER_RELEASE] ?: LimiterState.Unspecified.releaseTimeMs,
                releaseTimeRange = LimiterState.Unspecified.releaseTimeRange,
                postGain = prefs[Keys.LIMITER_POST_GAIN] ?: LimiterState.Unspecified.postGain,
                postGainRange = LimiterState.Unspecified.postGainRange,
                ratio = prefs[Keys.LIMITER_RATIO] ?: LimiterState.Unspecified.ratio,
                ratioRange = LimiterState.Unspecified.ratioRange,
                threshold = prefs[Keys.LIMITER_THRESHOLD] ?: LimiterState.Unspecified.threshold,
                thresholdRange = LimiterState.Unspecified.thresholdRange
            )
        }
        .stateIn(eqScope, SharingStarted.Eagerly, LimiterState.Unspecified)

    val bitPerfectAudio = context.eqDataStore.data
        .map { prefs -> prefs[Keys.BIT_PERFECT] ?: false }
        .stateIn(eqScope, SharingStarted.Eagerly, false)

    val audioOffload = context.eqDataStore.data
        .map { prefs -> prefs[Keys.BIT_PERFECT] != true && prefs[Keys.AUDIO_OFFLOAD] == true }
        .stateIn(eqScope, SharingStarted.Eagerly, false)

    val audioFloatOutput = context.eqDataStore.data
        .map { prefs -> prefs[Keys.AUDIO_FLOAT_OUTPUT] ?: false }
        .stateIn(eqScope, SharingStarted.Eagerly, false)

    val skipSilence = context.eqDataStore.data
        .map { prefs -> prefs[Keys.SKIP_SILENCE] ?: false }
        .stateIn(eqScope, SharingStarted.Eagerly, false)

    private val _bandCapabilities = MutableStateFlow(EqBandCapabilities.Empty)
    val bandCapabilities: StateFlow<EqBandCapabilities> get() = _bandCapabilities

    var eqSession = EqSession(SessionType.Internal, NO_SESSION_ID, false)
        private set

    init {
        eqState.filterNot { it == EqState.Unspecified }
            .debounce(10)
            .onEach { newState ->
                val isDisabled = newState.isDisabledByReason
                if (eqEngine == null && eqSession.id != NO_SESSION_ID && !isDisabled) {
                    eqEngine = createEngine(
                        mode = eqState.value.engineMode,
                        sessionId = eqSession.id,
                        bandCount = newState.preferredBandCount
                    )
                    // Created (or failed to create) before the session was
                    // confirmed live by a real AudioTrack — retry/recreate
                    // once it is (see setSessionId/confirmSession).
                    awaitingSessionConfirm = true
                    // Configure and enable the engine immediately (while the
                    // session is silent) so playback start doesn't race with
                    // effect-chain setup.
                    if (newState.isUsable) {
                        applyChangesToEngine(engine = eqEngine, state = newState)
                    }
                } else if (eqEngine != null && newState.isUsable && !isDisabled) {
                    // Engine already exists but may have been initialized with stale
                    // state (e.g., Unspecified before DataStore loaded). Re-apply the
                    // correct configuration now — setSession() below would early-return
                    // because the EqSession hasn't changed.
                    applyChangesToEngine(engine = eqEngine, state = newState)
                }
                if (!isDisabled) {
                    if (newState.isUsable) {
                        setSession(eqSession.copy(type = SessionType.Internal), newState)
                    } else {
                        setSession(eqSession.copy(type = SessionType.External), newState)
                    }
                } else {
                    setSessionIsActive(false, newState)
                }
            }
            .launchIn(eqScope)

        balanceState.debounce(50)
            .onEach { balanceState ->
                balanceProcessor.setBalance(balanceState.left, balanceState.right)
            }
            .flowOn(Dispatchers.Main)
            .launchIn(eqScope)

        replayGainState.filterNot { it == ReplayGainState.Unspecified }
            .debounce(50)
            .onEach { state ->
                if (state.mode.isOn) {
                    replayGainProcessor.mode = state.mode
                    replayGainProcessor.preAmpGain = state.preamp
                    replayGainProcessor.preAmpGainWithoutTag = state.preampWithoutGain
                } else {
                    replayGainProcessor.mode = ReplayGainMode.Off
                    replayGainProcessor.preAmpGain = 0f
                    replayGainProcessor.preAmpGainWithoutTag = 0f
                }
            }
            .flowOn(Dispatchers.Main)
            .launchIn(eqScope)

        bitPerfectAudio.debounce(100)
            .onEach { bitPerfectEnabled ->
                audioOutputObserver.setBitPerfectEnabled(bitPerfectEnabled)
            }
            .flowOn(Dispatchers.Main)
            .launchIn(eqScope)

        audioOutputObserver.audioDevice
            .onEach {
                setCurrentDevice(it)
            }
            .flowOn(IO)
            .launchIn(eqScope)
    }

    @SuppressLint("NewApi")
    suspend fun initializeEqualizer(
        engineMode: EqEngineMode = this.eqState.value.engineMode
    ) = withContext(IO) {
        try {
            val effects = AudioEffect.queryEffects().orEmpty()
            context.eqDataStore.edit { prefs ->
                val eqConfigVersion = prefs[Keys.EQ_CONFIG_VERSION] ?: 0
                if (eqConfigVersion < CURRENT_EQ_CONFIG_VERSION) {
                    // v2: re-enable DynamicsProcessing (5/10/15/32 bands) as
                    // the default — the engine no longer enables MBC/limiter
                    // at creation, which was the actual source of the
                    // static/noise on some HALs. Respect an explicitly
                    // requested engine mode (e.g. from resetConfigurationWithNewEngineMode).
                    val migratedMode =
                        if (EqEngineMode.isSwitchingSupported()) engineMode
                        else EqEngineMode.Basic
                    prefs[Keys.EQ_ENGINE_MODE] = migratedMode.ordinal
                    prefs[Keys.EQ_BAND_COUNT] = migratedMode.defaultBandCount
                    prefs[Keys.PRESETS] = Json.encodeToString(
                        getPresetsByBandCount(migratedMode.defaultBandCount)
                    )
                    // v3: rewrite the active profile to match the new band
                    // count — engines silently ignore profiles with a
                    // mismatched band count, leaving the EQ inaudible.
                    listOf(Keys.PRESET, Keys.CUSTOM_PRESET).forEach { key ->
                        prefs[key]?.let { json ->
                            val profile = runCatching {
                                Json.decodeFromString<EqProfile>(json)
                            }.getOrNull()
                            if (profile != null && profile.isValid &&
                                profile.numberOfBands != migratedMode.defaultBandCount
                            ) {
                                prefs[key] = Json.encodeToString(
                                    profile.copy(
                                        levels = FloatArray(migratedMode.defaultBandCount) { i ->
                                            profile.levels.getOrElse(i) { 0f }
                                        }
                                    )
                                )
                            }
                        }
                    }
                    prefs[Keys.EQ_INITIALIZED] = true
                    prefs[Keys.EQ_CONFIG_VERSION] = CURRENT_EQ_CONFIG_VERSION
                } else if (prefs[Keys.EQ_INITIALIZED] != true) {
                    prefs[Keys.EQ_ENGINE_MODE] = engineMode.ordinal
                    prefs[Keys.PRESETS] = Json.encodeToString(
                        getPresetsByBandCount(engineMode.defaultBandCount)
                    )
                    prefs[Keys.EQ_INITIALIZED] = true
                    prefs[Keys.EQ_CONFIG_VERSION] = CURRENT_EQ_CONFIG_VERSION
                }
                prefs[Keys.EQ_SUPPORTED] = effects.any {
                    it.type == engineMode.type
                }
                prefs[Keys.VIRTUALIZER_SUPPORTED] = effects.any {
                    it.type == AudioEffect.EFFECT_TYPE_VIRTUALIZER
                }
                prefs[Keys.BASS_BOOST_SUPPORTED] = effects.any {
                    it.type == AudioEffect.EFFECT_TYPE_BASS_BOOST
                }
                prefs[Keys.LOUDNESS_SUPPORTED] = effects.any {
                    it.type == AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "The EQ couldn't be initialized. Maybe audio effects aren't available on this device?", e)
        }
    }

    fun release() {
        setSession(EqSession(SessionType.Internal, NO_SESSION_ID, false))
        eqEngine?.release()
        eqEngine = null
        awaitingSessionConfirm = false
    }

    fun isProfileNameAvailable(profileName: String): Boolean {
        return eqProfiles.value.none { it.name.equals(profileName, ignoreCase = true) }
    }

    fun isAutoEqProfileNameAvailable(profileName: String): Boolean {
        return autoEqProfiles.value.none { it.name.equals(profileName, ignoreCase = true) }
    }

    fun getNewExportName(): String = getFormattedFileName("BoomingEQ", "json")

    fun getEmptyCustomProfile(bandCount: Int): EqProfile {
        return EqProfile(EqProfile.CUSTOM_PRESET_NAME, FloatArray(bandCount), isCustom = true)
    }

    fun getNewProfileFromCustom(
        profileName: String,
        associatedDevices: Set<AudioDeviceType>
    ): EqProfile {
        return eqCustomProfile.value.copy(
            name = profileName,
            associations = associatedDevices,
            isCustom = false,
            isAutoEq = false
        )
    }

    suspend fun editProfile(
        profile: EqProfile,
        newName: String,
        newAssociations: Set<AudioDeviceType>
    ): Boolean {
        val trimmedName = newName.trim()
        if (trimmedName.isEmpty()) return false

        val currentProfiles = eqProfiles.value
        if (profile.name != trimmedName &&
            currentProfiles.any { it.name.equals(trimmedName, ignoreCase = true) }) {
            return false
        }

        val targetIndex = currentProfiles.indexOfFirst { it.name == profile.name }
        if (targetIndex == -1) return false

        val newProfiles = currentProfiles.mapIndexedTo(mutableListOf()) { itemIndex, oldProfile ->
            when {
                itemIndex == targetIndex -> oldProfile.copy(
                    name = trimmedName,
                    associations = newAssociations
                )
                else -> oldProfile.associations.intersect(newAssociations).let { intersect ->
                    if (intersect.isNotEmpty()) {
                        oldProfile.copy(associations = oldProfile.associations - intersect)
                    } else {
                        oldProfile
                    }
                }
            }
        }

        setEqualizerProfiles(newProfiles)
        if (profile == eqCurrentProfile.value) {
            setCurrentProfile(currentProfiles[targetIndex])
        }
        return true
    }

    suspend fun addProfile(profile: EqProfile, allowReplace: Boolean, useProfile: Boolean): Boolean {
        if (!profile.isValid) return false

        val currentProfiles = eqProfiles.value.toMutableList()
        val index = currentProfiles.indexOfFirst { it.name.equals(profile.name, ignoreCase = true) }
        if (index != -1) {
            if (allowReplace) {
                currentProfiles[index] = profile
                setEqualizerProfiles(currentProfiles)
                if (useProfile) {
                    setCurrentProfile(profile)
                }
                return true
            }
            return false
        }

        currentProfiles.add(profile)
        setEqualizerProfiles(currentProfiles)
        if (useProfile) {
            setCurrentProfile(profile)
        }
        return true
    }

    suspend fun removeProfile(profile: EqProfile): Boolean {
        val currentProfiles = eqProfiles.value.toMutableList()
        val removed = currentProfiles.removeIf { it.name == profile.name }
        if (!removed) return false

        setEqualizerProfiles(currentProfiles)
        if (profile == eqCurrentProfile.value) {
            setCurrentProfile(eqCustomProfile.value)
        }
        return true
    }

    suspend fun deleteAutoEqProfile(profile: AutoEqProfile): Boolean {
        val currentAutoEqProfiles = autoEqProfiles.value.toMutableList()
        val removed = currentAutoEqProfiles.removeIf { it.name == profile.name }
        if (!removed) return false

        setAutoEqProfiles(currentAutoEqProfiles)
        return true
    }

    suspend fun importProfiles(toImport: List<EqProfile>): Int {
        if (toImport.isEmpty()) return 0

        val currentProfiles = eqProfiles.value.toMutableList()
        val bandCapabilities = bandCapabilities.value

        var imported = 0
        for (profile in toImport) {
            if (!profile.isValid ||
                profile.isCustom ||
                !bandCapabilities.isBandCountSupported(profile.numberOfBands)) {
                continue
            }
            val existingIndex = currentProfiles.indexOfFirst { it.name.equals(profile.name, ignoreCase = true) }
            if (existingIndex >= 0) {
                currentProfiles[existingIndex] = profile
                imported++
            } else {
                currentProfiles.add(profile)
                imported++
            }
        }
        if (imported > 0) {
            setEqualizerProfiles(currentProfiles)
        }
        return imported
    }

    suspend fun importAutoEqProfile(
        profile: AutoEqProfile,
        suggestedName: String,
        allowReplace: Boolean
    ): Boolean {
        val actualProfile = if (profile.name == suggestedName) {
            profile
        } else {
            profile.copy(name = suggestedName)
        }
        if (actualProfile.name.isNotEmpty() && actualProfile.points.isNotEmpty()) {
            val autoEqProfiles = this.autoEqProfiles.value.toMutableList()

            val existingIndex = autoEqProfiles.indexOfFirst { it.name == actualProfile.name }
            if (existingIndex != -1) {
                if (allowReplace) {
                    if (autoEqProfiles.add(actualProfile)) {
                        setAutoEqProfiles(autoEqProfiles)
                        return true
                    }
                }
                return false
            }

            if (autoEqProfiles.add(actualProfile)) {
                setAutoEqProfiles(autoEqProfiles)
                return true
            }
        }
        return false
    }

    private suspend fun setEqualizerProfiles(profiles: List<EqProfile>) {
        context.eqDataStore.edit {
            it[Keys.PRESETS] = Json.encodeToString(profiles)
        }
    }

    private suspend fun setAutoEqProfiles(profiles: List<AutoEqProfile>) {
        context.eqDataStore.edit {
            it[Keys.AUTO_EQ_PROFILES] = Json.encodeToString(profiles)
        }
    }

    suspend fun setCurrentProfile(eqProfile: EqProfile) {
        if (bandCapabilities.value.isBandCountSupported(eqProfile.numberOfBands)) {
            if (eqProfile.numberOfBands != eqState.value.preferredBandCount) {
                setBandCount(
                    bandCount = eqProfile.numberOfBands,
                    profileAfterChange = eqProfile
                )
            } else {
                context.eqDataStore.edit {
                    it[Keys.PRESET] = Json.encodeToString(eqProfile)
                }
                applyChangesToEngine(profile = eqProfile)
            }
        }
    }

    suspend fun setCustomProfileBandGain(band: Int, gainInDb: Float) {
        val currentProfile = eqCurrentProfile.value
        val newBandLevels = currentProfile.levels.copyOf()
        if (band in newBandLevels.indices) {
            newBandLevels[band] = gainInDb
        }
        val customProfile = currentProfile.copy(
            name = EqProfile.CUSTOM_PRESET_NAME,
            levels = newBandLevels,
            isAutoEq = false,
            isCustom = true
        )
        setCustomProfile(customProfile, fromUser = true)
    }

    private suspend fun setCustomProfile(profile: EqProfile, fromUser: Boolean) {
        if (profile.isCustom) {
            val serializedProfile = Json.encodeToString(profile)
            context.eqDataStore.edit {
                it[Keys.CUSTOM_PRESET] = serializedProfile
                if (fromUser) {
                    it[Keys.PRESET] = serializedProfile
                }
            }
            if (fromUser) {
                applyChangesToEngine(profile = profile)
            }
        }
    }

    fun setSessionId(audioSessionId: Int, eqState: EqState = this.eqState.value) {
        // Called from onAudioSessionIdChanged. Media3 may report the
        // session id before the AudioTrack that backs it exists (the id is
        // allocated up front). Creating the engine at that point attaches
        // its effects to a chain with no output thread (io 0); when the
        // real track starts, the chain must migrate to the output thread,
        // and rebuilding the DSP chain on live audio causes a noise burst
        // on some HALs (e.g. Redmi K80). So when no engine exists yet,
        // wait for playback start (confirmSession) to create it — the
        // track is guaranteed to exist by then. Only a speculatively
        // created engine (init block, awaitingSessionConfirm) is
        // (re)created here, and only when it targets this session.
        val engine = eqEngine
        if (engine == null) {
            val oldSession = eqSession
            eqSession = eqSession.copy(
                id = audioSessionId,
                type = if (eqState.enabled) {
                    SessionType.Internal
                } else {
                    SessionType.External
                }
            )
            // Keep the old-session close broadcast behavior of
            // setSession() when the session really changed.
            if (oldSession.type == SessionType.External && oldSession.id != NO_SESSION_ID) {
                val intent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, oldSession.id)
                context.sendBroadcast(intent)
            }
            awaitingSessionConfirm = true
            return
        }
        val confirmNow = awaitingSessionConfirm && engine.sessionId == audioSessionId
        setSession(
            eqSession.copy(
                id = audioSessionId,
                type = if (eqState.enabled) {
                    SessionType.Internal
                } else {
                    SessionType.External
                }
            ),
            eqState,
            forceRecreate = confirmNow
        )
    }

    /**
     * Called when playback starts — the audio session is now (or is about
     * to be) backed by a real AudioTrack. Recreates the engine if it was
     * created before the session existed (see setSessionId).
     */
    fun confirmSession() {
        val confirmNow = awaitingSessionConfirm
        setSession(eqSession.copy(), eqState.value, forceRecreate = confirmNow)
    }

    fun setSessionIsActive(isActive: Boolean, eqState: EqState = this.eqState.value) {
        setSession(
            newSession = eqSession.copy(
                active = isActive,
                type = if (eqState.enabled) {
                    SessionType.Internal
                } else {
                    SessionType.External
                }
            ),
            eqState = eqState
        )
    }

    @Synchronized
    private fun setSession(
        newSession: EqSession,
        eqState: EqState = this.eqState.value,
        forceRecreate: Boolean = false
    ) {
        val oldSession = this.eqSession
        // Rebuild the engine when it exists but its effects failed to
        // attach (e.g. the audio session didn't exist yet) — now that the
        // session is live, recreation should succeed. forceRecreate also
        // bypasses the shortcut: the session was just confirmed live by
        // Media3, and a speculatively created engine must be rebuilt to
        // attach to it.
        if (newSession == oldSession && !forceRecreate && eqEngine?.isOperational != false)
            return

        this.eqSession = newSession
        when (oldSession.type) {
            SessionType.Internal -> {
                // Keep the engine untouched when the session just flips
                // active (play/pause) — disabling and re-enabling it mid-
                // playback causes an audible transient on some devices.
                // Real session changes (new id or leaving Internal) still
                // tear the chain down.
                val staysOnSameSession =
                    newSession.type == SessionType.Internal && newSession.id == oldSession.id
                if (!staysOnSameSession) {
                    eqEngine?.setEnabled(false)
                }
                if (eqState.isDisabledByReason) {
                    eqEngine?.release()
                    eqEngine = null
                    awaitingSessionConfirm = false
                }
            }

            SessionType.External -> {
                if (oldSession.id != NO_SESSION_ID) {
                    val intent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, oldSession.id)

                    context.sendBroadcast(intent)
                }
            }
        }

        if (!eqState.isDisabledByReason && eqState.supported && newSession.id != NO_SESSION_ID) {
            when (newSession.type) {
                SessionType.Internal -> {
                    val engineRecreated = newSession.id != this.eqEngine?.sessionId ||
                        eqEngine?.isOperational == false ||
                        forceRecreate
                    if (engineRecreated) {
                        eqEngine?.release()
                        eqEngine = createEngine(
                            mode = eqState.engineMode,
                            sessionId = newSession.id,
                            bandCount = eqState.preferredBandCount
                        )
                        // A forceRecreate runs on a session confirmed live
                        // by Media3 (onAudioSessionIdChanged) or by playback
                        // start, so the engine is now correctly attached.
                        // A speculative creation (fixed session id assigned
                        // before the AudioTrack existed) stays pending
                        // confirmation and is recreated once the session is
                        // live.
                        awaitingSessionConfirm = !forceRecreate
                    }
                    // Apply the full config when the engine was (re)created
                    // or the session actually changed — createEngine() may
                    // have used a stale eqState (Unspecified) due to a
                    // startup race between DataStore loading and the audio
                    // session being created. A play/pause flip on the same
                    // session keeps the chain untouched: rewriting every DSP
                    // parameter while audio is already flowing causes a
                    // noise burst on some HALs (e.g. Redmi K80).
                    val sessionChanged = newSession.id != oldSession.id ||
                        oldSession.type != SessionType.Internal
                    if (eqState.isUsable && (engineRecreated || sessionChanged)) {
                        applyChangesToEngine(engine = eqEngine, state = eqState)
                    }
                }

                SessionType.External -> {
                    val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, newSession.id)
                        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)

                    context.sendBroadcast(intent)
                }
            }
        }
    }

    suspend fun setEqualizerState(state: EqState, newProfile: EqProfile? = null) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.EQ_ENABLED] = state.enabled
            prefs[Keys.EQ_ENGINE_MODE] = state.engineMode.ordinal
            prefs[Keys.EQ_BAND_COUNT] = state.preferredBandCount
            prefs[Keys.EQ_PRO_MODE_ENABLED] = state.proMode
            if (newProfile != null) {
                val serializedProfile = Json.encodeToString(newProfile)
                prefs[Keys.PRESET] = serializedProfile
                if (newProfile.isCustom) {
                    prefs[Keys.CUSTOM_PRESET] = serializedProfile
                }
            }
        }
        if (newProfile != null) {
            applyChangesToEngine(state = state, profile = newProfile)
        } else {
            applyChangesToEngine(state = state)
        }
    }

    suspend fun setProMode(proModeEnabled: Boolean) {
        if (!proModeEnabled) {
            val newBandCount = minOf(
                eqState.value.preferredBandCount,
                bandCapabilities.value.maxBandCountInNormalMode
            )
            setBandCount(newBandCount)
        }
        setEqualizerState(
            state = eqState.value.copy(
                proMode = proModeEnabled
            )
        )
    }

    suspend fun setLoudnessGain(state: LoudnessGainState) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.LOUDNESS_ENABLED] = state.enabled
            prefs[Keys.LOUDNESS_GAIN] = state.gainInDb
        }
        applyChangesToEngine(loudnessGainState = state)
    }

    suspend fun setBassBoost(state: BassBoostState) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.BASS_BOOST_ENABLED] = state.enabled
            prefs[Keys.BASS_BOOST_STRENGTH] = state.strength
        }
        applyChangesToEngine(bassBoostState = state)
    }

    suspend fun setVirtualizer(state: VirtualizerState) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.VIRTUALIZER_ENABLED] = state.enabled
            prefs[Keys.VIRTUALIZER_STRENGTH] = state.strength
        }
        applyChangesToEngine(virtualizerState = state)
    }

    suspend fun setBandCount(
        bandCount: Int,
        profileAfterChange: EqProfile = getEmptyCustomProfile(bandCount = bandCount)
    ): Boolean {
        if (eqState.value.preferredBandCount == bandCount)
            return false

        val engine = eqEngine
        val bandCapabilities = this.bandCapabilities.value
        if (bandCapabilities.isBandCountSupported(bandCount)) {
            if (engine?.setBandCount(bandCount) == true) {
                setEqualizerState(
                    state = eqState.value.copy(preferredBandCount = bandCount),
                    newProfile = profileAfterChange
                )
                return true
            }
        }
        // The current engine can't switch to this band count (Basic is
        // fixed to the device's native bands) — switch to DynamicsProcessing,
        // which supports 5/10/15/32 bands.
        if (EqEngineMode.isSwitchingSupported() &&
            eqState.value.engineMode != EqEngineMode.DynamicsProcessing &&
            bandCount in DYNAMICS_PROCESSING_BAND_COUNTS
        ) {
            switchEngineMode(EqEngineMode.DynamicsProcessing, bandCount, profileAfterChange)
            return true
        }
        return false
    }

    private suspend fun switchEngineMode(mode: EqEngineMode, bandCount: Int, profile: EqProfile) {
        eqEngine?.release()
        eqEngine = null
        awaitingSessionConfirm = false
        setBandCapabilities(EqBandCapabilities.Empty)
        context.eqDataStore.edit { prefs ->
            prefs[Keys.EQ_ENGINE_MODE] = mode.ordinal
            prefs[Keys.EQ_BAND_COUNT] = bandCount
            prefs[Keys.PRESET] = Json.encodeToString(profile)
        }
    }

    suspend fun setEnableBitPerfect(bitPerfect: Boolean) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.BIT_PERFECT] = bitPerfect
        }
    }

    suspend fun setEnableAudioOffload(audioOffload: Boolean) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.AUDIO_OFFLOAD] = audioOffload
        }
    }

    suspend fun setEnableAudioFloatOutput(audioFloatOutput: Boolean) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.AUDIO_FLOAT_OUTPUT] = audioFloatOutput
        }
    }

    suspend fun setEnableSkipSilence(skipSilence: Boolean) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.SKIP_SILENCE] = skipSilence
        }
    }

    suspend fun setVolume(volume: Float) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.VOLUME] = volume
        }
    }

    suspend fun setBalance(balance: BalanceState) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.CENTER_BALANCE] = balance.center
        }
    }

    suspend fun setTempo(tempo: TempoState) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.SPEED] = tempo.speed
            prefs[Keys.PITCH] = tempo.pitch
            prefs[Keys.IS_FIXED_PITCH] = tempo.isFixedPitch
        }
    }

    suspend fun setReplayGain(replayGain: ReplayGainState) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.REPLAYGAIN_PREAMP] = replayGain.preamp
            prefs[Keys.REPLAYGAIN_PREAMP_WITHOUT_GAIN] = replayGain.preampWithoutGain
            prefs[Keys.REPLAYGAIN_MODE] = replayGain.mode.name
        }
    }

    suspend fun setCompressor(state: CompressorState) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.COMPRESSOR_ENABLED] = state.enabled
            prefs[Keys.COMPRESSOR_ATTACK] = state.attackTimeMs
            prefs[Keys.COMPRESSOR_RELEASE] = state.releaseTimeMs
            prefs[Keys.COMPRESSOR_KNEE] = state.kneeWidth
            prefs[Keys.COMPRESSOR_NOISE_GATE] = state.noiseGateThreshold
            prefs[Keys.COMPRESSOR_PRE_GAIN] = state.preGain
            prefs[Keys.COMPRESSOR_POST_GAIN] = state.postGain
            prefs[Keys.COMPRESSOR_RATIO] = state.ratio
            prefs[Keys.COMPRESSOR_EXPANDER_RATIO] = state.expanderRatio
            prefs[Keys.COMPRESSOR_THRESHOLD] = state.threshold
        }
        applyChangesToEngine(compressorState = state)
    }

    suspend fun setLimiter(state: LimiterState) {
        context.eqDataStore.edit { prefs ->
            prefs[Keys.LIMITER_ENABLED] = state.enabled
            prefs[Keys.LIMITER_ATTACK] = state.attackTimeMs
            prefs[Keys.LIMITER_RELEASE] = state.releaseTimeMs
            prefs[Keys.LIMITER_POST_GAIN] = state.postGain
            prefs[Keys.LIMITER_RATIO] = state.ratio
            prefs[Keys.LIMITER_THRESHOLD] = state.threshold
        }
        applyChangesToEngine(limiterState = state)
    }

    suspend fun setEngineMode(engineMode: EqEngineMode) {
        resetConfigurationWithNewEngineMode(engineMode)
    }

    private fun setBandCapabilities(bandCapabilities: EqBandCapabilities) {
        _bandCapabilities.value = bandCapabilities
    }

    private suspend fun setCurrentDevice(currentDevice: AudioDevice) {
        val eqState = eqState.value
        if (eqState == EqState.Unspecified || !eqState.supported ||
            currentDevice == AudioDevice.UnknownDevice)
            return

        val profileByDevice = eqProfiles.value.firstOrNull { profile ->
            profile.associations.contains(currentDevice.type)
        }
        if (profileByDevice != null) {
            setCurrentProfile(profileByDevice)
        }
    }

    suspend fun setAutoEqProfile(profile: AutoEqProfile) {
        val currentBandCount = eqState.value.preferredBandCount
        val bandCapabilities = bandCapabilities.value
        if (bandCapabilities.isBandCountSupported(currentBandCount)) {
            val frequencies = bandCapabilities.getFrequencies(currentBandCount)
            val profile = EqProfile(
                name = profile.name,
                levels = profile.getBandGains(frequencies, bandCapabilities.bandRange),
                isCustom = true,
                isAutoEq = true
            )
            setCustomProfile(profile, fromUser = true)
        }
    }

    private fun getPresetsByBandCount(bandCount: Int) = when (bandCount) {
        5 -> listOf(
            EqProfile("Bass Boost", floatArrayOf(11f, 6f, -1f, -2.5f, -0.5f)),
            EqProfile("Classical", floatArrayOf(4f, 0.5f, 0.5f, 2.5f, 4.5f)),
            EqProfile("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f)),
            EqProfile("Jazz", floatArrayOf(4f, 2f, 1f, 3.5f, 1.5f)),
            EqProfile("Pop", floatArrayOf(0.5f, 6f, 2f, 0.5f, 4.5f)),
            EqProfile("Rock", floatArrayOf(7f, 2.5f, -2f, 3.5f, 7.5f)),
            EqProfile("Treble Boost", floatArrayOf(-2f, -0.5f, 2f, 7f, 11f)),
            EqProfile("Vocal", floatArrayOf(-2.5f, 2f, 9f, 4f, -0.5f))
        )

        15 -> listOf(
            EqProfile("Bass Boost", floatArrayOf(13f, 12f, 10f, 8f, 6f, 3f, 0f, -2f, -3f, -3f, -2f, -1f, 0f, 0f, 0f)),
            EqProfile("Classical", floatArrayOf(6f, 5f, 3f, 2f, 1f, 0f, 0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 7f)),
            EqProfile("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
            EqProfile("Jazz", floatArrayOf(4f, 5f, 4f, 3f, 2f, 1f, 0f, 2f, 4f, 5f, 4f, 3f, 2f, 1f, 0f)),
            EqProfile("Pop", floatArrayOf(-1f, 0f, 3f, 5f, 7f, 5f, 2f, 0f, -1f, 0f, 2f, 4f, 5f, 6f, 6f)),
            EqProfile("Rock", floatArrayOf(9f, 8f, 6f, 4f, 2f, 0f, -2f, -3f, -2f, 0f, 3f, 6f, 8f, 9f, 10f)),
            EqProfile("Treble Boost", floatArrayOf(-3f, -2f, -1f, 0f, 1f, 2f, 3f, 4f, 6f, 8f, 10f, 12f, 13f, 14f, 14f)),
            EqProfile("Vocal", floatArrayOf(-4f, -3f, -2f, 0f, 4f, 8f, 11f, 12f, 9f, 6f, 3f, 1f, 0f, -1f, -1f))
        )

        else -> listOf(
            EqProfile("Bass Boost", floatArrayOf(12f, 10f, 8f, 4f, 0f, -2f, -3f, -2f, -1f, 0f)),
            EqProfile("Classical", floatArrayOf(5f, 3f, 1f, 0f, 0f, 1f, 2f, 3f, 4f, 5f)),
            EqProfile("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
            EqProfile("Jazz", floatArrayOf(3f, 5f, 3f, 1f, 0f, 2f, 4f, 3f, 2f, 1f)),
            EqProfile("Pop", floatArrayOf(-1f, 2f, 5f, 7f, 4f, 0f, -1f, 2f, 4f, 5f)),
            EqProfile("Rock", floatArrayOf(8f, 6f, 4f, 1f, -2f, -2f, 2f, 5f, 7f, 8f)),
            EqProfile("Treble Boost", floatArrayOf(-2f, -2f, -1f, 0f, 1f, 3f, 6f, 8f, 10f, 12f)),
            EqProfile("Vocal", floatArrayOf(-3f, -2f, 0f, 4f, 8f, 10f, 6f, 2f, 0f, -1f))
        )
    }

    private fun createEngine(mode: EqEngineMode, sessionId: Int, bandCount: Int): EQEngine? {
        return runCatching {
            if (EqEngineMode.isSwitchingSupported()) when (mode) {
                EqEngineMode.Basic -> BasicEQEngine(sessionId)
                EqEngineMode.DynamicsProcessing -> DynamicsProcessingEngine(sessionId, bandCount)
            } else {
                BasicEQEngine(sessionId)
            }
        }.onSuccess { newEngine ->
            // Start disabled — caller must apply full config before enabling
            newEngine.setEnabled(false)
            setBandCapabilities(newEngine.bandCapabilities)
        }.onFailure {
            Log.e(TAG, "Failed to open EQ session", it)
        }.getOrNull()
    }

    private fun applyChangesToEngine(
        engine: EQEngine? = this.eqEngine,
        state: EqState = this.eqState.value,
        profile: EqProfile = this.eqCurrentProfile.value,
        bassBoostState: BassBoostState = this.bassBoostState.value,
        virtualizerState: VirtualizerState = this.virtualizerState.value,
        loudnessGainState: LoudnessGainState = this.loudnessGainState.value,
        compressorState: CompressorState = this.compressorState.value,
        limiterState: LimiterState = this.limiterState.value
    ) {
        engine?.let {
            applyEngine(
                engine = it,
                state = state,
                profile = profile,
                bassBoostState = bassBoostState,
                virtualizerState = virtualizerState,
                loudnessGainState = loudnessGainState,
                compressorState = compressorState,
                limiterState = limiterState
            )
        }
    }

    private fun applyEngine(
        engine: EQEngine,
        state: EqState,
        profile: EqProfile,
        bassBoostState: BassBoostState,
        virtualizerState: VirtualizerState,
        loudnessGainState: LoudnessGainState,
        compressorState: CompressorState,
        limiterState: LimiterState
    ) {
        runCatching {
            if (state.isUsable) {
                // Apply EQ profile first, then enable
                engine.setProfile(profile)

                // Only touch extra effects when the user explicitly enabled them.
                // Creating them just to disable wastes DSP resources and causes
                // static/noise on some hardware.
                if (bassBoostState.isUsable) {
                    engine.setBassBoostState(bassBoostState)
                }

                if (virtualizerState.isUsable) {
                    engine.setVirtualizerState(virtualizerState)
                }

                if (loudnessGainState.isUsable) {
                    engine.setLoudnessGainState(loudnessGainState)
                }

                // Apply Compressor
                if (engine.isMBCSupported && compressorState.enabled && state.proMode) {
                    engine.setCompressorState(compressorState)
                }

                // Apply Limiter
                if (engine.isLimiterSupported && limiterState.enabled && state.proMode) {
                    engine.setLimiterState(limiterState)
                }

                // Enable the engine last, after all parameters are set.
                // Skip enabling when the chain would process nothing (flat
                // profile, no compressor/limiter) — attaching an idle DSP
                // chain still causes a sharp transient on some devices
                // (e.g. Redmi K80) when toggled during playback.
                val needsDspProcessing = profile.levels.any { it != 0f } ||
                    (engine.isMBCSupported && compressorState.enabled && state.proMode) ||
                    (engine.isLimiterSupported && limiterState.enabled && state.proMode)
                if (needsDspProcessing) {
                    engine.setEnabled(true)
                }
            } else {
                engine.setEnabled(false)
                engine.setVirtualizerState(VirtualizerState.Unspecified)
                engine.setBassBoostState(BassBoostState.Unspecified)
                engine.setLoudnessGainState(LoudnessGainState.Unspecified)
                engine.setCompressorState(CompressorState.Unspecified)
                engine.setLimiterState(LimiterState.Unspecified)
            }
        }.onFailure {
            Log.e(TAG, "Error setting up EQ engine", it)
        }
    }

    suspend fun resetConfiguration() {
        resetConfigurationWithNewEngineMode(eqState.value.engineMode)
    }

    private suspend fun resetConfigurationWithNewEngineMode(newEngineMode: EqEngineMode) {
        setBandCapabilities(EqBandCapabilities.Empty)
        context.eqDataStore.edit {
            it.clear()
        }
        eqEngine?.release()
        eqEngine = null
        awaitingSessionConfirm = false
        initializeEqualizer(newEngineMode)
    }

    interface Keys {
        companion object {
            val EQ_CONFIG_VERSION = intPreferencesKey("eq.config.version")
            val EQ_INITIALIZED = booleanPreferencesKey("eq.initialized")
            val EQ_ENABLED = booleanPreferencesKey("eq.enabled")
            val EQ_SUPPORTED = booleanPreferencesKey("eq.supported")
            val EQ_BAND_COUNT = intPreferencesKey("eq.band.count")
            val EQ_ENGINE_MODE = intPreferencesKey("eq.engine")
            val EQ_PRO_MODE_ENABLED = booleanPreferencesKey("eq.pro.enabled")
            val VIRTUALIZER_SUPPORTED = booleanPreferencesKey("eq.virtualizer.supported")
            val VIRTUALIZER_ENABLED = booleanPreferencesKey("eq.virtualizer.enabled")
            val VIRTUALIZER_STRENGTH = floatPreferencesKey("eq.virtualizer.strength")
            val BASS_BOOST_SUPPORTED = booleanPreferencesKey("eq.bassboost.supported")
            val BASS_BOOST_ENABLED = booleanPreferencesKey("eq.bassboost.enabled")
            val BASS_BOOST_STRENGTH = floatPreferencesKey("eq.bassboost.strength")
            val LOUDNESS_SUPPORTED = booleanPreferencesKey("eq.loudness.supported")
            val LOUDNESS_ENABLED = booleanPreferencesKey("eq.loudness.enabled")
            val LOUDNESS_GAIN = floatPreferencesKey("eq.loudness.gain")
            val AUTO_EQ_PROFILES = stringPreferencesKey("eq.profiles.autoeq")
            val PRESETS = stringPreferencesKey("eq.profiles")
            val PRESET = stringPreferencesKey("eq.profile")
            val CUSTOM_PRESET = stringPreferencesKey("eq.profile.custom")
            val BIT_PERFECT = booleanPreferencesKey("audio.bitperfect")
            val AUDIO_OFFLOAD = booleanPreferencesKey("audio.offload")
            val AUDIO_FLOAT_OUTPUT = booleanPreferencesKey("audio.float_output")
            val SKIP_SILENCE = booleanPreferencesKey("audio.skip_silence")
            val REPLAYGAIN_MODE = stringPreferencesKey("replaygain.mode")
            val REPLAYGAIN_PREAMP = floatPreferencesKey("replaygain.preamp")
            val REPLAYGAIN_PREAMP_WITHOUT_GAIN = floatPreferencesKey("replaygain.preamp.without_gain")
            val VOLUME = floatPreferencesKey("player.volume")
            val CENTER_BALANCE = floatPreferencesKey("eq.balance")
            val SPEED = floatPreferencesKey("eq.speed")
            val PITCH = floatPreferencesKey("eq.pitch")
            val IS_FIXED_PITCH = booleanPreferencesKey("eq.pitch.fixed")

            val COMPRESSOR_ENABLED = booleanPreferencesKey("eq.compressor.enabled")
            val COMPRESSOR_ATTACK = floatPreferencesKey("eq.compressor.attack")
            val COMPRESSOR_RELEASE = floatPreferencesKey("eq.compressor.release")
            val COMPRESSOR_KNEE = floatPreferencesKey("eq.compressor.knee")
            val COMPRESSOR_NOISE_GATE = floatPreferencesKey("eq.compressor.noisegate")
            val COMPRESSOR_PRE_GAIN = floatPreferencesKey("eq.compressor.pregain")
            val COMPRESSOR_POST_GAIN = floatPreferencesKey("eq.compressor.postgain")
            val COMPRESSOR_RATIO = floatPreferencesKey("eq.compressor.ratio")
            val COMPRESSOR_EXPANDER_RATIO = floatPreferencesKey("eq.compressor.expanderratio")
            val COMPRESSOR_THRESHOLD = floatPreferencesKey("eq.compressor.threshold")

            val LIMITER_ENABLED = booleanPreferencesKey("eq.limiter.enabled")
            val LIMITER_ATTACK = floatPreferencesKey("eq.limiter.attack")
            val LIMITER_RELEASE = floatPreferencesKey("eq.limiter.release")
            val LIMITER_POST_GAIN = floatPreferencesKey("eq.limiter.postgain")
            val LIMITER_RATIO = floatPreferencesKey("eq.limiter.ratio")
            val LIMITER_THRESHOLD = floatPreferencesKey("eq.limiter.threshold")
        }
    }

    companion object {
        private const val TAG = "EqualizerManager"

        private const val NO_SESSION_ID = 0

        // Bump this to force re-initialization of EQ config on next app start.
        // 0 = initial, 1 = migrate from DynamicsProcessing (Auto default) to Basic,
        // 2 = re-enable DynamicsProcessing (MBC/limiter now disabled at creation),
        // 3 = rewrite the active profile to match the migrated band count.
        private const val CURRENT_EQ_CONFIG_VERSION = 3

        // Band counts the DynamicsProcessing engine can switch between.
        private val DYNAMICS_PROCESSING_BAND_COUNTS = setOf(5, 10, 15, 32)

        const val MINIMUM_LOUDNESS_GAIN = 0f
        const val MAXIMUM_LOUDNESS_GAIN = 40f

        const val BASSBOOST_MIN_STRENGTH = 0f
        const val BASSBOOST_MAX_STRENGTH = 1000f

        const val VIRTUALIZER_MIN_STRENGTH = 0f
        const val VIRTUALIZER_MAX_STRENGTH = 1000f

        const val MIN_SPEED = .5f
        const val MAX_SPEED = 2f

        const val MIN_PITCH = .5f
        const val MAX_PITCH = 2f

        const val MIN_VOLUME = 0f
        const val MAX_VOLUME = 1f
    }
}