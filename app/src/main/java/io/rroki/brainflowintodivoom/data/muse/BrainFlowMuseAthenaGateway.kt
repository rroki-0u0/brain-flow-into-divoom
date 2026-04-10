package io.rroki.brainflowintodivoom.data.muse

import io.rroki.brainflowintodivoom.domain.model.BrainBand
import java.lang.reflect.Modifier
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class BrainFlowMuseAthenaGateway : MuseStreamGateway {
    private val lock = Any()
    private var boardShim: Any? = null
    private var boardId: Int? = null

    override fun isRuntimeAvailable(): Boolean {
        return runCatching {
            Class.forName(CLASS_BOARD_SHIM)
            Class.forName(CLASS_INPUT_PARAMS)
        }.isSuccess
    }

    override fun isConnected(): Boolean = synchronized(lock) { boardShim != null }

    override suspend fun connect(deviceAddress: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isRuntimeAvailable()) {
                error("BrainFlow runtime not found. Add BrainFlow AAR/JAR to app/libs")
            }

            disconnectInternal()

            val candidateBoardIds = resolveCandidateBoardIds()
            var lastError: Throwable? = null

            for (candidateId in candidateBoardIds) {
                val result = runCatching {
                    connectBoard(boardIdValue = candidateId, deviceAddress = deviceAddress)
                }
                if (result.isSuccess) {
                    return@runCatching
                }
                lastError = result.exceptionOrNull()
            }

            throw IllegalStateException(
                "Could not connect to Muse board. Tried board IDs: ${candidateBoardIds.joinToString()}",
                lastError
            )
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            disconnectInternal()
        }
    }

    override fun streamReadings(pollIntervalMs: Long): Flow<MuseReading> = flow {
        val interval = max(40L, pollIntervalMs)
        while (currentCoroutineContext().isActive) {
            val reading = withContext(Dispatchers.IO) { readCurrentReading() }
            emit(reading)
            delay(interval)
        }
    }

    private fun connectBoard(boardIdValue: Int, deviceAddress: String?) {
        val paramsClass = Class.forName(CLASS_INPUT_PARAMS)
        val params = paramsClass.getDeclaredConstructor().newInstance()
        applyDeviceAddress(params, deviceAddress)

        val boardShimClass = Class.forName(CLASS_BOARD_SHIM)
        invokeStaticIfPresent(boardShimClass, listOf("enable_dev_board_logger", "enableDevBoardLogger"))

        val constructor = boardShimClass.constructors.firstOrNull { ctor ->
            val paramsTypes = ctor.parameterTypes
            paramsTypes.size == 2 && paramsTypes[0] == Int::class.javaPrimitiveType
        } ?: error("BoardShim constructor not found")

        val shim = constructor.newInstance(boardIdValue, params)
        runCatching {
            invokeOnTarget(shim, listOf("prepare_session", "prepareSession"))
            invokeOnTarget(shim, listOf("start_stream", "startStream"))
        }.onFailure {
            runCatching { invokeOnTarget(shim, listOf("stop_stream", "stopStream")) }
            runCatching { invokeOnTarget(shim, listOf("release_session", "releaseSession")) }
            throw it
        }

        synchronized(lock) {
            boardShim = shim
            boardId = boardIdValue
        }
    }

    private fun disconnectInternal() {
        val currentShim = synchronized(lock) {
            val existing = boardShim
            boardShim = null
            boardId = null
            existing
        } ?: return

        runCatching { invokeOnTarget(currentShim, listOf("stop_stream", "stopStream")) }
        runCatching { invokeOnTarget(currentShim, listOf("release_session", "releaseSession")) }
    }

    private fun readCurrentReading(): MuseReading {
        val snapshot = synchronized(lock) {
            val shim = boardShim ?: error("Muse stream is not connected")
            val id = boardId ?: error("Muse board ID missing")
            shim to id
        }

        val (shim, id) = snapshot
        val boardShimClass = Class.forName(CLASS_BOARD_SHIM)

        val sampleRateAny = invokeStaticOnClass(
            boardShimClass,
            listOf("get_sampling_rate", "getSamplingRate"),
            id
        )
        val sampleRate = (sampleRateAny as? Number)?.toInt()?.coerceAtLeast(64) ?: 256

        val eegChannelsAny = invokeStaticOnClass(
            boardShimClass,
            listOf("get_eeg_channels", "getEegChannels"),
            id
        )
        val eegChannels = toIntArray(eegChannelsAny)

        val raw = invokeOnTarget(shim, listOf("get_current_board_data", "getCurrentBoardData"), 256)
        val matrix = toDoubleMatrix(raw)
        if (matrix.isEmpty()) {
            return MuseReading(
                normalizedAlpha = 0.0,
                dominantBand = BrainBand.ALPHA,
                eegSampleCount = 0
            )
        }

        val selectedChannel = eegChannels.firstOrNull { it in matrix.indices } ?: 0
        val signal = matrix[selectedChannel]
        if (signal.size < 64) {
            return MuseReading(
                normalizedAlpha = 0.0,
                dominantBand = BrainBand.ALPHA,
                eegSampleCount = signal.size
            )
        }

        val centered = centerSignal(signal)
        val bandPowers = calculateBandPowers(centered, sampleRate)

        val alpha = bandPowers[BrainBand.ALPHA] ?: 0.0
        val theta = bandPowers[BrainBand.THETA] ?: 0.0
        val beta = bandPowers[BrainBand.BETA] ?: 0.0
        val baseline = max(1e-9, alpha + theta + beta)
        val normalizedAlpha = (alpha / baseline).coerceIn(0.0, 1.0)

        val dominantBand = bandPowers.maxByOrNull { it.value }?.key ?: BrainBand.ALPHA
        val totalPower = bandPowers.values.sum()
        val dominantPower = bandPowers[dominantBand] ?: 0.0
        val alphaRatio = if (totalPower > 1e-9) (alpha / totalPower).coerceIn(0.0, 1.0) else 0.0
        val dominantRatio = if (totalPower > 1e-9) (dominantPower / totalPower).coerceIn(0.0, 1.0) else 0.0

        return MuseReading(
            normalizedAlpha = normalizedAlpha,
            dominantBand = dominantBand,
            eegSampleCount = signal.size,
            alphaRatio = alphaRatio,
            dominantRatio = dominantRatio,
            totalPower = totalPower,
            activity = centered.map { sample -> sample * sample }.average()
        )
    }

    private fun centerSignal(signal: DoubleArray): DoubleArray {
        if (signal.isEmpty()) {
            return signal
        }
        val mean = signal.sum() / signal.size
        return DoubleArray(signal.size) { idx -> signal[idx] - mean }
    }

    private fun calculateBandPowers(signal: DoubleArray, samplingRate: Int): Map<BrainBand, Double> {
        val spectrum = computePowerSpectrum(signal, samplingRate)
        if (spectrum == null) {
            return mapOf(
                BrainBand.DELTA to 0.0,
                BrainBand.THETA to 0.0,
                BrainBand.ALPHA to 0.0,
                BrainBand.BETA to 0.0,
                BrainBand.GAMMA to 0.0
            )
        }

        return mapOf(
            BrainBand.DELTA to calculateBandPowerFromSpectrum(spectrum, 1.0, 4.0),
            BrainBand.THETA to calculateBandPowerFromSpectrum(spectrum, 4.0, 8.0),
            BrainBand.ALPHA to calculateBandPowerFromSpectrum(spectrum, 8.0, 12.0),
            BrainBand.BETA to calculateBandPowerFromSpectrum(spectrum, 12.0, 30.0),
            BrainBand.GAMMA to calculateBandPowerFromSpectrum(spectrum, 30.0, 45.0)
        )
    }

    private data class PowerSpectrum(
        val frequencyResolutionHz: Double,
        val binPowers: DoubleArray
    )

    private fun computePowerSpectrum(signal: DoubleArray, samplingRate: Int): PowerSpectrum? {
        if (signal.size <= 1 || samplingRate <= 0) {
            return null
        }

        val fftSize = nextPowerOfTwo(signal.size)
        if (fftSize <= 1) {
            return null
        }

        val real = DoubleArray(fftSize)
        val imag = DoubleArray(fftSize)
        for (index in signal.indices) {
            real[index] = signal[index]
        }

        fftInPlace(real, imag)

        val nyquistBin = fftSize / 2
        val binPowers = DoubleArray(nyquistBin + 1)
        for (bin in 1..nyquistBin) {
            val re = real[bin]
            val im = imag[bin]
            binPowers[bin] = re * re + im * im
        }

        return PowerSpectrum(
            frequencyResolutionHz = samplingRate.toDouble() / fftSize.toDouble(),
            binPowers = binPowers
        )
    }

    private fun calculateBandPowerFromSpectrum(
        spectrum: PowerSpectrum,
        lowHz: Double,
        highHz: Double
    ): Double {
        val frequencyResolution = spectrum.frequencyResolutionHz
        if (frequencyResolution <= 0.0) {
            return 0.0
        }

        val startBin = max(1, ceil(lowHz / frequencyResolution).toInt())
        val endBin = min(spectrum.binPowers.lastIndex, floor(highHz / frequencyResolution).toInt())
        if (endBin < startBin) {
            return 0.0
        }

        var power = 0.0
        for (bin in startBin..endBin) {
            power += spectrum.binPowers[bin]
        }

        return power
    }

    private fun fftInPlace(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tempReal = real[i]
                val tempImag = imag[i]
                real[i] = real[j]
                imag[i] = imag[j]
                real[j] = tempReal
                imag[j] = tempImag
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len.toDouble()
            val wLenReal = cos(angle)
            val wLenImag = sin(angle)

            var offset = 0
            while (offset < n) {
                var wReal = 1.0
                var wImag = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val evenIndex = offset + k
                    val oddIndex = evenIndex + half

                    val oddReal = (real[oddIndex] * wReal) - (imag[oddIndex] * wImag)
                    val oddImag = (real[oddIndex] * wImag) + (imag[oddIndex] * wReal)

                    val evenReal = real[evenIndex]
                    val evenImag = imag[evenIndex]

                    real[evenIndex] = evenReal + oddReal
                    imag[evenIndex] = evenImag + oddImag
                    real[oddIndex] = evenReal - oddReal
                    imag[oddIndex] = evenImag - oddImag

                    val nextWReal = (wReal * wLenReal) - (wImag * wLenImag)
                    wImag = (wReal * wLenImag) + (wImag * wLenReal)
                    wReal = nextWReal
                }
                offset += len
            }

            len = len shl 1
        }
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) {
            result = result shl 1
        }
        return result
    }

    private fun resolveCandidateBoardIds(): List<Int> {
        val resolved = linkedSetOf<Int>()

        val boardIdsClass = runCatching { Class.forName(CLASS_BOARD_IDS) }.getOrNull()
        if (boardIdsClass != null) {
            listOf("MUSE_S_BOARD", "MUSE_2_BOARD", "MUSE_2016_BOARD").forEach { fieldName ->
                runCatching { boardIdsClass.getField(fieldName).get(null) }
                    .map { enumOrNumberToInt(it) }
                    .onSuccess { maybeId ->
                        if (maybeId != null) {
                            resolved.add(maybeId)
                        }
                    }
            }
        }

        // Common fallback values used by BrainFlow releases.
        resolved.addAll(listOf(38, 22, 39))

        return resolved.toList()
    }

    private fun enumOrNumberToInt(value: Any?): Int? {
        return when (value) {
            null -> null
            is Number -> value.toInt()
            else -> {
                runCatching {
                    val methods = listOf("get_code", "getCode", "get_value", "getValue")
                    methods.firstNotNullOfOrNull { methodName ->
                        value.javaClass.methods
                            .firstOrNull { it.name == methodName && it.parameterCount == 0 }
                            ?.invoke(value)
                            ?.let { it as? Number }
                            ?.toInt()
                    }
                }.getOrNull()
            }
        }
    }

    private fun applyDeviceAddress(params: Any, deviceAddress: String?) {
        val trimmed = deviceAddress?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return
        }

        val assignedViaField = runCatching {
            val field = params.javaClass.getField("mac_address")
            field.set(params, trimmed)
        }.isSuccess

        if (assignedViaField) {
            return
        }

        runCatching {
            params.javaClass.methods
                .firstOrNull { it.name == "setMacAddress" && it.parameterCount == 1 }
                ?.invoke(params, trimmed)
        }
    }

    private fun invokeOnTarget(target: Any, methodNames: List<String>, vararg args: Any?): Any? {
        val method = target.javaClass.methods.firstOrNull { candidate ->
            methodNames.any { it == candidate.name } && candidate.parameterCount == args.size
        } ?: error("Method not found on ${target.javaClass.name}: ${methodNames.joinToString()}")
        return method.invoke(target, *args)
    }

    private fun invokeStaticOnClass(clazz: Class<*>, methodNames: List<String>, vararg args: Any?): Any? {
        val method = clazz.methods.firstOrNull { candidate ->
            methodNames.any { it == candidate.name } &&
                candidate.parameterCount == args.size &&
                Modifier.isStatic(candidate.modifiers)
        } ?: error("Static method not found on ${clazz.name}: ${methodNames.joinToString()}")
        return method.invoke(null, *args)
    }

    private fun invokeStaticIfPresent(clazz: Class<*>, methodNames: List<String>) {
        val method = clazz.methods.firstOrNull { candidate ->
            methodNames.any { it == candidate.name } &&
                candidate.parameterCount == 0 &&
                Modifier.isStatic(candidate.modifiers)
        } ?: return
        runCatching { method.invoke(null) }
    }

    private fun toIntArray(value: Any?): IntArray {
        return when (value) {
            is IntArray -> value
            is Array<*> -> value.mapNotNull { it as? Number }.map { it.toInt() }.toIntArray()
            else -> intArrayOf()
        }
    }

    private fun toDoubleMatrix(value: Any?): List<DoubleArray> {
        if (value !is Array<*>) {
            return emptyList()
        }

        return value.mapNotNull { row ->
            when (row) {
                is DoubleArray -> row
                is Array<*> -> {
                    val numbers = row.mapNotNull { it as? Number }
                    if (numbers.size != row.size) {
                        null
                    } else {
                        DoubleArray(numbers.size) { idx -> numbers[idx].toDouble() }
                    }
                }
                else -> null
            }
        }
    }

    private companion object {
        private const val CLASS_BOARD_SHIM = "brainflow.BoardShim"
        private const val CLASS_INPUT_PARAMS = "brainflow.BrainFlowInputParams"
        private const val CLASS_BOARD_IDS = "brainflow.BoardIds"
    }
}
