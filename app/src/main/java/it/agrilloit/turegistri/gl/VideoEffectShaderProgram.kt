package it.agrilloit.turegistri.gl

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import it.agrilloit.turegistri.model.VideoEffectCatalog

/**
 * Programma shader generico per gli effetti del [VideoEffectCatalog]: compila
 * il body GLSL dell'effetto dentro il template (uniform comuni + helper) e
 * disegna un quad per frame. Parametri dell'effetto = uniform float per nome.
 *
 * Stessa interfaccia del port iOS (VideoEffects.metal): ogni effetto è una
 * funzione `vec4 applyEffect(vec2 uv)` che campiona con `sampleAt(p)`.
 */
@UnstableApi
class VideoEffectShaderProgram(
    private val def: VideoEffectCatalog.VideoEffectDef,
    private val values: Map<String, Float>,
    private val clipDurMs: Long,
) : BaseGlShaderProgram(/* useSamplerOES = */ false, /* textureUnitCount = */ 1) {

    private var width = 0
    private var height = 0
    private val glProgram = GlProgram(VERTEX_SHADER, buildFragmentShader(def.fragmentBody))

    init {
        // Quad a tutto schermo (stesso setup di ColorLutShaderProgram).
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            4,
        )
        glProgram.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
        glProgram.setFloatsUniformIfPresent(
            "uTimeSec", floatArrayOf(presentationTimeUs / 1_000_000f)
        )
        glProgram.setFloatsUniformIfPresent(
            "uClipDurSec", floatArrayOf(clipDurMs / 1000f)
        )
        if (width > 0 && height > 0) {
            glProgram.setFloatsUniformIfPresent("uAspect", floatArrayOf(width.toFloat() / height))
            glProgram.setFloatsUniformIfPresent(
                "uRes", floatArrayOf(width.toFloat(), height.toFloat())
            )
        }
        for ((name, v) in values) {
            glProgram.setFloatsUniformIfPresent(name, floatArrayOf(v))
        }
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        glProgram.delete()
        super.release()
    }

    companion object {
        private val VERTEX_SHADER = """
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5,
                              aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
""".trim()

        /** Template fragment: uniform comuni + helper + body dell'effetto + main. */
        fun buildFragmentShader(body: String): String {
            val helpers = """
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float hash21(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}
float grainAt(vec2 uv) {
  return hash21(uv * uRes + vec2(fract(uTimeSec * 13.73) * 91.7, fract(uTimeSec * 7.31) * 57.3));
}
float bayer2(vec2 a) { a = floor(a); return fract(a.x * 0.5 + a.y * a.x * 0.75); }
float bayer4(vec2 a) { return bayer2(0.5 * a) * 0.25 + bayer2(a); }
vec2 weaveOffset(float amount) {
  float t = fract(uTimeSec * 9.5);
  return (vec2(hash21(vec2(t, 3.7)), hash21(vec2(7.1, t))) - 0.5) * amount / uRes;
}
float easeInOut(float t) {
  t = clamp(t, 0.0, 1.0);
  return t < 0.5 ? 2.0 * t * t : 1.0 - 2.0 * (1.0 - t) * (1.0 - t);
}
vec3 rgb2hsv(vec3 c) {
  vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
  float d = q.x - min(q.w, q.y);
  float e = 1.0e-10;
  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
  vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - vec3(3.0));
  return c.z * mix(vec3(1.0), clamp(p - vec3(1.0), 0.0, 1.0), c.y);
}
"""
            return """
#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform float uTimeSec;
uniform float uClipDurSec;
uniform float uAspect;
uniform vec2 uRes;
varying vec2 vTexSamplingCoord;
vec4 sampleAt(vec2 p) { return texture2D(uTexSampler, p); }
$helpers
$body
void main() { gl_FragColor = applyEffect(vTexSamplingCoord); }
""".trim()
        }
    }
}

/**
 * Effetto video del catalogo come [GlEffect] media3: utilizzabile nell'export
 * (Transformer) e nell'anteprima (ExoPlayer.setVideoEffects).
 */
@UnstableApi
class VideoEffectGlEffect(
    private val def: VideoEffectCatalog.VideoEffectDef,
    private val values: Map<String, Float>,
    private val clipDurMs: Long,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHDR: Boolean): GlShaderProgram =
        VideoEffectShaderProgram(def, values, clipDurMs)
}
