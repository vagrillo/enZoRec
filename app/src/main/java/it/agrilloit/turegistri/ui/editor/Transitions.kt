package it.agrilloit.turegistri.ui.editor

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import it.agrilloit.turegistri.gl.VideoEffectShaderProgram
import it.agrilloit.turegistri.model.VideoEffectCatalog

/**
 * Transizioni tra clip (port di Transitions.swift iOS): la transizione vive sul
 * confine tra due clip. Poiché il Transformer media3 processa ogni EditedMediaItem
 * indipendentemente, le transizioni sono implementate come effetti per-clip:
 * sul clip di sinistra si applica la fase "out", su quello di destra la fase "in",
 * con dissolvenze audio corrispondenti. Lo stile "freeze" è ottenibile col tool
 * Freeze frame (inserisce un item immagine).
 */
enum class TransitionStyle(val id: String, val label: String, val desc: String) {
    DISSOLVE("dissolve", "Dissolvenza", "Fade out verso il nero e fade in dal nero."),
    FADE_COLOR("fadeColor", "Dissolvenza colore", "Attraverso un colore scelto (nero o bianco)."),
    FLASH("flash", "Flash cut", "Burst di luce bianca sul confine tra i due clip."),
    WHIP("whip", "Whip pan", "Sfocatura direzionale veloce in uscita/ingresso."),
    DEFOCUS("defocus", "Defocus", "Bokeh progressivo in uscita e rientro a fuoco."),
}

data class TimelineTransition(
    val key: String, // "<leftClipId>-><rightClipId>"
    val leftId: String,
    val rightId: String,
    val style: TransitionStyle,
    val durationMs: Long = 500,
    val fadeWhite: Boolean = false, // per FADE_COLOR
)

/** Fase della transizione applicata a un clip. */
enum class TransitionPhase { OUT, IN }

@UnstableApi
class TransitionEffect(
    private val style: TransitionStyle,
    private val phase: TransitionPhase,
    private val windowMs: Long,
    private val fadeWhite: Boolean,
    private val clipDurMs: Long,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHDR: Boolean): GlShaderProgram =
        VideoEffectShaderProgram(
            transitionDef(style, phase),
            mapOf(
                "uWindowSec" to windowMs / 1000f,
                "uFadeWhite" to if (fadeWhite) 1f else 0f,
            ),
            clipDurMs,
        )
}

private val transitionDefs = HashMap<String, VideoEffectCatalog.VideoEffectDef>()

/** Definizione shader per lo stile/fase: uniform uWindowSec + uFadeWhite. */
fun transitionDef(style: TransitionStyle, phase: TransitionPhase): VideoEffectCatalog.VideoEffectDef {
    val key = "${style.id}_${phase.name}"
    transitionDefs[key]?.let { return it }
    val timeExpr = when (phase) {
        // progress 0..1 dentro la finestra: OUT = ultima parte del clip, IN = prima parte
        TransitionPhase.OUT -> "(uClipDurSec - uTimeSec)"
        TransitionPhase.IN -> "uTimeSec"
    }
    val body = when (style) {
        TransitionStyle.DISSOLVE -> """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      float p = clamp($timeExpr / max(uWindowSec, 0.001), 0.0, 1.0);
      return vec4(c.rgb * p, c.a);
    }
    """.trimIndent()

        TransitionStyle.FADE_COLOR -> """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      float p = clamp($timeExpr / max(uWindowSec, 0.001), 0.0, 1.0);
      vec3 target = mix(vec3(0.0), vec3(1.0), uFadeWhite);
      return vec4(mix(target, c.rgb, p), c.a);
    }
    """.trimIndent()

        TransitionStyle.FLASH -> """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      float p = clamp($timeExpr / max(uWindowSec, 0.001), 0.0, 1.0);
      float burst = pow(1.0 - p, 2.0) * step(0.55, 1.0 - p);
      return vec4(mix(c.rgb, vec3(1.0), burst), c.a);
    }
    """.trimIndent()

        TransitionStyle.WHIP -> """
    vec4 applyEffect(vec2 uv) {
      float p = clamp($timeExpr / max(uWindowSec, 0.001), 0.0, 1.0);
      float amt = (1.0 - p) * 26.0;
      vec3 acc = vec3(0.0);
      for (int i = 0; i < 8; i++) {
        float fi = float(i) / 7.0;
        vec2 off = vec2((fi - 0.5) * amt, 0.0) / uRes;
        acc += sampleAt(clamp(uv + off, 0.001, 0.999)).rgb;
      }
      return vec4(acc / 8.0, 1.0);
    }
    """.trimIndent()

        TransitionStyle.DEFOCUS -> """
    vec4 applyEffect(vec2 uv) {
      float p = clamp($timeExpr / max(uWindowSec, 0.001), 0.0, 1.0);
      float amt = (1.0 - p) * 14.0;
      vec3 acc = vec3(0.0);
      for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.7854;
        vec2 off = vec2(cos(a), sin(a)) * amt / uRes;
        acc += sampleAt(clamp(uv + off, 0.001, 0.999)).rgb;
      }
      return vec4(acc / 8.0, 1.0);
    }
    """.trimIndent()
    }
    val def = VideoEffectCatalog.VideoEffectDef(
        id = key,
        label = "${style.label} (${if (phase == TransitionPhase.OUT) "out" else "in"})",
        section = "Transizioni",
        desc = style.desc,
        params = listOf(),
        fragmentBody = body,
    )
    transitionDefs[key] = def
    return def
}

/**
 * Dissolvenza audio a inizio/fine clip (l'audio dell'item esportato è già
 * clippato al trim, quindi il ramp sui primi/ultimi campioni è corretto).
 */
class FadeAudioProcessor(
    private val phase: TransitionPhase,
    private val windowMs: Long,
    private val sampleRate: Int = 48000,
) : androidx.media3.common.audio.BaseAudioProcessor() {

    private var windowSamples = (sampleRate * windowMs / 1000).coerceAtLeast(1)
    private var totalSamplesSeen = 0L
    private var itemSamples = -1L // determinato a fine flusso: fade-out usa i campioni rimanenti

    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat):
        androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // il Transformer conosce la durata: riusiamo setItemSamples se chiamato prima
        return inputAudioFormat
    }

    /** Chiamato dal ViewModel quando la durata dell'item è nota (effectiveMs). */
    fun setItemSamples(samples: Long) {
        itemSamples = samples
    }

    private fun applyGain(indexFromItemStart: Long, total: Long): Float = when (phase) {
        TransitionPhase.IN -> {
            val inWin = minOf(windowSamples.toLong(), total / 2)
            if (inWin <= 0) 1f else (indexFromItemStart.toFloat() / inWin).coerceIn(0f, 1f)
        }

        TransitionPhase.OUT -> {
            val inWin = minOf(windowSamples.toLong(), total / 2)
            val fromEnd = total - indexFromItemStart
            if (inWin <= 0) 1f else (fromEnd.toFloat() / inWin).coerceIn(0f, 1f)
        }
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) {
            replaceOutputBuffer(0)
            return
        }
        val shorts = ShortArray(remaining / 2)
        inputBuffer.asShortBuffer().get(shorts)
        inputBuffer.position(inputBuffer.limit())

        val start = totalSamplesSeen
        val end = start + shorts.size
        totalSamplesSeen = end

        if (itemSamples > 0) {
            val frameSize = maxOf(1, inputAudioFormat.channelCount)
            for (i in shorts.indices) {
                val sampleIndex = start + i / frameSize
                val g = applyGain(sampleIndex, itemSamples)
                val v = (shorts[i] * g).toInt()
                shorts[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        val out = replaceOutputBuffer(shorts.size * 2)
        out.asShortBuffer().put(shorts)
    }
}
