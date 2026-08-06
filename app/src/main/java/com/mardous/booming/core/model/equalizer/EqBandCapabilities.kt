package com.mardous.booming.core.model.equalizer

import androidx.compose.runtime.Immutable

@Immutable
class EqBandCapabilities(
    /**
     * Supported band level range, in decibels.
     */
    val bandRange: ClosedFloatingPointRange<Float>,
    /**
     * Supported band configurations.
     */
    val bandConfigurations: Set<BandConfiguration>,
    /**
     * How many bands the engine can handle when Pro Mode isn't enabled.
     */
    val maxBandCountInNormalMode: Int
) {
    val hasMultipleBandConfigurations: Boolean = bandConfigurations.size > 1

    fun getAvailableBandCounts(isProMode: Boolean) =
        bandConfigurations.filter { it.bandCount <= maxBandCountInNormalMode || isProMode }
            .map { it.bandCount }

    fun isBandCountSupported(bandCount: Int) =
        bandConfigurations.any { it.bandCount == bandCount }

    fun getFrequencies(bandCount: Int): IntArray {
        val config = bandConfigurations.firstOrNull { it.bandCount == bandCount }
            ?: bandConfigurations.first()
        return config.bandFrequenciesInHz
    }

    fun getBands(profile: EqProfile, bandCount: Int): List<EqBand> {
        if (bandConfigurations.isEmpty())
            return emptyList()

        val config = bandConfigurations.firstOrNull { it.bandCount == bandCount }
            ?: bandConfigurations.first()
        val freqInHz = config.bandFrequenciesInHz
        val actualBandCount = config.bandCount
        val levels = if (profile.isValid && profile.levels.size == actualBandCount) {
            profile.levels
        } else {
            FloatArray(actualBandCount)
        }
        return (0 until actualBandCount).map {
            EqBand(
                index = it,
                value = levels[it],
                valueRange = bandRange,
                frequencyInHz = freqInHz[it]
            )
        }
    }

    class BandConfiguration(val bandCount: Int, val bandFrequenciesInHz: IntArray) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as BandConfiguration

            if (bandCount != other.bandCount) return false
            if (!bandFrequenciesInHz.contentEquals(other.bandFrequenciesInHz)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = bandCount
            result = 31 * result + bandFrequenciesInHz.contentHashCode()
            return result
        }
    }

    companion object {
        val Empty = EqBandCapabilities(-1f..0f, emptySet(), -1)
    }
}