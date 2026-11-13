package it.agrilloit.turegistri.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Analizza i microfoni del dispositivo e decide la strategia di cattura:
 *
 * 1. [Plan.Multichannel] — un solo AudioRecord con CHANNEL_INDEX_MASK che copre
 *    tutti i canali del microfono principale: canali sample-allineati dall'hardware,
 *    ideale per la postproduzione (una traccia WAV multicanale).
 * 2. [Plan.PerMic] — un AudioRecord per ogni microfono integrato, instradato con
 *    setPreferredDevice: un WAV mono per microfono.
 * 3. [Plan.Single] — un AudioRecord sulla sorgente MIC predefinita.
 */
object MicEnumerator {

    sealed class Plan {
        abstract val label: String

        data class Multichannel(
            val channelIndexMask: Int,
            val channels: Int,
            val deviceAddress: String?,
        ) : Plan() {
            override val label: String = "multicanale (${channels} canali)"
        }

        data class PerMic(val devices: List<AudioDeviceInfo>) : Plan() {
            override val label: String = "${devices.size} microfoni separati"
        }

        data class Single(val source: Int) : Plan() {
            override val label: String = "microfono predefinito"
        }
    }

    fun builtinMics(audioManager: AudioManager): List<AudioDeviceInfo> {
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    /**
     * Costruisce il piano di cattura provando le strategie nell'ordine, verificando
     * davvero che ogni AudioRecord si inizializzi (non basta dichiarare il supporto).
     * Con [preferredAddress] forza la cattura sul microfono integrato scelto.
     */
    fun plan(audioManager: AudioManager, preferredAddress: String? = null): Plan {
        val mics = builtinMics(audioManager)

        // Microfono scelto dall'utente: cattura mono su quel device.
        if (preferredAddress != null) {
            val chosen = mics.firstOrNull { it.address == preferredAddress }
            if (chosen != null) {
                val test = buildRecord(
                    source = MediaRecorder.AudioSource.MIC,
                    sampleRate = SAMPLE_RATE,
                    channelConfig = AudioFormat.CHANNEL_IN_MONO,
                    preferredDevice = chosen,
                )
                val ok = test != null && test.state == AudioRecord.STATE_INITIALIZED
                test?.release()
                if (ok) return Plan.PerMic(listOf(chosen))
            }
        }

        // Strategia 1: multicanale via channel index mask.
        if (mics.isNotEmpty()) {
            for (mic in mics) {
                val mask = mic.channelIndexMasks
                    ?.maxByOrNull { Integer.bitCount(it) }
                    ?: continue
                val channels = Integer.bitCount(mask)
                if (channels < 2) continue // un canale solo non giustifica il multicanale
                val test = buildRecord(
                    source = MediaRecorder.AudioSource.MIC,
                    sampleRate = SAMPLE_RATE,
                    channelConfig = AudioFormat.CHANNEL_IN_FRONT, // ignorato con index mask
                    indexMask = mask,
                )
                if (test != null && test.state == AudioRecord.STATE_INITIALIZED) {
                    test.release()
                    return Plan.Multichannel(mask, channels, mic.address)
                }
                test?.release()
            }
        }

        // Strategia 2: un record per microfono fisico.
        if (mics.size >= 2) {
            val ok = mics.filter { mic ->
                val test = buildRecord(
                    source = MediaRecorder.AudioSource.MIC,
                    sampleRate = SAMPLE_RATE,
                    channelConfig = AudioFormat.CHANNEL_IN_MONO,
                    preferredDevice = mic,
                )
                val good = test != null && test.state == AudioRecord.STATE_INITIALIZED
                test?.release()
                good
            }
            if (ok.size >= 2) return Plan.PerMic(ok)
        }

        // Strategia 3: sorgente predefinita.
        return Plan.Single(MediaRecorder.AudioSource.MIC)
    }

    /**
     * Crea un AudioRecord, restituisce null se i parametri non sono supportati.
     * Chi va in errore va rilasciato dal chiamante.
     */
    fun buildRecord(
        source: Int,
        sampleRate: Int,
        channelConfig: Int,
        indexMask: Int = 0,
        preferredDevice: AudioDeviceInfo? = null,
    ): AudioRecord? {
        return try {
            val formatBuilder = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
            // Maschera di canali e index mask sono mutuamente esclusive.
            if (indexMask != 0) {
                formatBuilder.setChannelIndexMask(indexMask)
            } else {
                formatBuilder.setChannelMask(channelConfig)
            }
            val format = formatBuilder.build()
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return null
            val record = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
            if (preferredDevice != null) {
                record.preferredDevice = preferredDevice
            }
            record
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    const val SAMPLE_RATE = 48_000
}
