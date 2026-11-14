package it.agrilloit.turegistri.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import it.agrilloit.turegistri.model.LayoutEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renderer di composizione: riceve i flussi camera come SurfaceTexture (GL_TEXTURE_EXTERNAL_OES),
 * li compone secondo il [LayoutEngine.LayoutState] e disegna:
 *  - sul surface di anteprima (SurfaceView);
 *  - sul surface di input del encoder del video composito durante la registrazione.
 *
 * Tutto il lavoro GL avviene su un thread dedicato; i comandi dall'esterno vengono
 * inoltrati con Handler. Le texture/ST vengono create una sola volta per sessione
 * camera: i Surface per le camere si ottengono con [prepareCameraInputs].
 */
class CompositeRenderer {

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            uniform mat4 uSTMatrix;
            uniform mat3 uXform;       /* output -> texture: rotazione+mirror calcolate da Kotlin */
            uniform vec4 uUVCrop;      /* (u0, v0, u1, v1) in spazio uv con v verso l'alto */
            varying vec2 vUV;
            varying vec2 vPos;         /* posizione nel pannello 0..1, per la maschera nel fragment */
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vec2 uv = (uXform * vec3(aPos * 0.5 + 0.5, 1.0)).xy;
                uv = uUVCrop.xy + uv * (uUVCrop.zw - uUVCrop.xy);
                vUV = (uSTMatrix * vec4(uv, 0.0, 1.0)).xy;
                vPos = aPos * 0.5 + 0.5;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUV;
            varying vec2 vPos;
            uniform vec3 uCircle;      /* maschera circolare dell'inserto: (cx, cy, r); r<=0 = disattiva */
            uniform samplerExternalOES sTexture;
            uniform int uFxMode;       /* 0 = nessun effetto live */
            uniform vec4 uFxParams;    /* (preset, strength, aux1, aux2) */
            uniform float uFxTime;
            uniform float uFxAspect;
            uniform float uPeaking;    /* focus peaking 0/1 */
            uniform vec2 uTexel;
            float fLuma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
            void main() {
                if (uCircle.z > 0.0) {
                    vec2 d = vPos - uCircle.xy;
                    if (dot(d, d) > uCircle.z * uCircle.z) { discard; }
                }
                vec2 uv = vUV;
                if (uFxMode == 7) {
                    /* kaleidoscope: piega speculare a settori attorno al centro */
                    vec2 p = (vPos - vec2(uFxParams.z, uFxParams.w)) * vec2(uFxAspect, 1.0);
                    float seg = 6.2831853 / max(2.0, uFxParams.x);
                    float a = mod(atan(p.y, p.x), seg);
                    a = abs(a - seg * 0.5);
                    vec2 q = vec2(cos(a), sin(a)) * length(p);
                    q = q / vec2(uFxAspect, 1.0) + vec2(uFxParams.z, uFxParams.w);
                    uv = vUV + (q - vPos) * (uUVCrop.zw - uUVCrop.xy);
                }
                if (uFxMode == 8) {
                    /* rgbOffset: separazione dei canali lungo un angolo */
                    vec2 dir = vec2(cos(uFxParams.z), sin(uFxParams.z)) * uFxParams.x * uTexel * 6.0;
                    float r = texture2D(sTexture, vUV + dir).r;
                    float g = texture2D(sTexture, vUV).g;
                    float b = texture2D(sTexture, vUV - dir).b;
                    gl_FragColor = vec4(r, g, b, 1.0);
                } else {
                    vec4 c = texture2D(sTexture, uv);
                    float strength = uFxParams.y;
                    if (uFxMode >= 1 && uFxMode <= 6) {
                        float l = fLuma(c.rgb);
                        vec3 col = c.rgb;
                        if (uFxMode == 1) {
                            /* silentTint: preset tinta (0 blu notte,1 ambra,2 rosso,3 verde,4 seppia) */
                            vec3 t = uFxParams.x < 0.5 ? vec3(0.55, 0.68, 1.0) :
                                     (uFxParams.x < 1.5 ? vec3(1.0, 0.82, 0.55) :
                                     (uFxParams.x < 2.5 ? vec3(1.0, 0.45, 0.35) :
                                     (uFxParams.x < 3.5 ? vec3(0.55, 1.0, 0.62) : vec3(0.87, 0.72, 0.53))));
                            float g2 = clamp((l - 0.5) * 1.1 + 0.5, 0.0, 1.0);
                            col = mix(vec3(g2), clamp(g2 * t * 1.15, 0.0, 1.0), strength);
                        } else if (uFxMode == 2) {
                            /* noir */
                            l = clamp((l - 0.5) * 1.4 + 0.5, 0.0, 1.0);
                            col = mix(c.rgb, vec3(l), strength);
                        } else if (uFxMode == 3) {
                            /* bleach bypass */
                            vec3 silver = vec3(l);
                            vec3 screened = 1.0 - (1.0 - col) * (1.0 - silver);
                            vec3 bb = mix(col * silver * 1.6, screened, smoothstep(0.35, 0.65, l));
                            bb = mix(vec3(fLuma(bb)), bb, 0.75);
                            col = mix(col, bb, strength);
                        } else if (uFxMode == 4) {
                            /* day for night */
                            vec3 dfn = vec3(l) * vec3(0.55, 0.65, 1.0) * 0.8
                                     + smoothstep(0.62, 1.0, l) * vec3(0.15, 0.18, 0.26);
                            col = mix(col, dfn, strength);
                        } else if (uFxMode == 5) {
                            /* quantization (posterize) */
                            float n = max(2.0, uFxParams.x);
                            col = floor(col * (n - 1.0) + 0.5) / (n - 1.0);
                            col = mix(c.rgb, col, strength);
                        } else if (uFxMode == 6) {
                            /* colorIsolation: mantieni la tonalità scelta */
                            vec3 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0).xyz;
                            /* usa formule RGB→HSL compatte */
                            float mn = min(min(col.r, col.g), col.b);
                            float mx = max(max(col.r, col.g), col.b);
                            float delta = mx - mn;
                            float hue = 0.0;
                            if (delta > 0.0001) {
                                if (mx == col.r) hue = mod((col.g - col.b) / delta, 6.0);
                                else if (mx == col.g) hue = (col.b - col.r) / delta + 2.0;
                                else hue = (col.r - col.g) / delta + 4.0;
                                hue *= 60.0;
                            }
                            float dist = abs(mod(hue - uFxParams.x + 540.0, 360.0) - 180.0);
                            float keep = 1.0 - smoothstep(uFxParams.z, uFxParams.z + 25.0, dist);
                            col = mix(vec3(l), col, keep);
                        }
                        gl_FragColor = vec4(clamp(col, 0.0, 1.0), c.a);
                    } else if (uFxMode == 9) {
                        /* negativeStrobe */
                        float invert = step(fract(uFxTime * uFxParams.x), 0.5);
                        gl_FragColor = vec4(mix(c.rgb, 1.0 - c.rgb, invert * strength), c.a);
                    } else if (uFxMode == 10) {
                        /* widescreen bars: ratio in uFxParams.x */
                        float inside = 1.0;
                        if (uFxAspect < uFxParams.x) {
                            float bar = (1.0 - uFxAspect / uFxParams.x) * 0.5;
                            inside = step(bar, vPos.y) * step(vPos.y, 1.0 - bar);
                        } else {
                            float bar = (1.0 - uFxParams.x / uFxAspect) * 0.5;
                            inside = step(bar, vPos.x) * step(vPos.x, 1.0 - bar);
                        }
                        gl_FragColor = vec4(c.rgb * inside, c.a);
                    } else {
                        gl_FragColor = c;
                    }
                }
                if (uPeaking > 0.5) {
                    float l0 = fLuma(texture2D(sTexture, vUV).rgb);
                    float l1 = fLuma(texture2D(sTexture, vUV + vec2(uTexel.x, 0.0)).rgb);
                    float l2 = fLuma(texture2D(sTexture, vUV - vec2(uTexel.x, 0.0)).rgb);
                    float l3 = fLuma(texture2D(sTexture, vUV + vec2(0.0, uTexel.y)).rgb);
                    float l4 = fLuma(texture2D(sTexture, vUV - vec2(0.0, uTexel.y)).rgb);
                    float edge = abs(l1 - l0) + abs(l2 - l0) + abs(l3 - l0) + abs(l4 - l0);
                    if (edge > 0.22) {
                        gl_FragColor.rgb = mix(gl_FragColor.rgb, vec3(1.0, 0.18, 0.08), 0.85);
                    }
                }
            }
        """

        private const val FLOAT_SIZE = 4

        val IDENTITY_MATRIX = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        val IDENTITY_XFORM = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )

        fun directFloatBuffer(values: FloatArray) =
            java.nio.ByteBuffer
                .allocateDirect(values.size * FLOAT_SIZE)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }

    /** Informazioni di flusso di una camera, necessarie per disegnarla correttamente. */
    data class StreamInfo(
        val bufferWidth: Int,
        val bufferHeight: Int,
        /** Orientamento del sensore in gradi (0/90/180/270): rotazione per visualizzare dritto. */
        val sensorOrientation: Int,
        /** Specchia orizzontalmente (frontale in anteprima naturale). */
        val mirror: Boolean,
    )

    /** Maschera circolare per un placement (coordinate normalizzate della regione). */
    data class CircleMask(val cx: Float, val cy: Float, val radius: Float) {
        companion object {
            val NONE = CircleMask(0f, 0f, 0f)
        }
    }

    @Volatile
    var pipCircleMask: CircleMask = CircleMask.NONE
        private set

    /** Attiva/disattiva la maschera circolare dell'inserto PiP. */
    fun setPipCircle(enabled: Boolean) {
        pipCircleMask = if (enabled) CircleMask(0.5f, 0.5f, 0.5f) else CircleMask.NONE
        requestDraw()
    }

    private val thread = HandlerThread("TuRegistriGL").apply { start() }
    private val handler = Handler(thread.looper)
    private val started = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    // Stato EGL (solo thread GL)
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewWidth = 0
    private var previewHeight = 0

    /** Pbuffer 1x1: mantiene il contesto GL corrente sul thread quando non si disegna. */
    private var dummySurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderWidth = 0
    private var encoderHeight = 0

    // Input camera
    private var surfaceTextures: Array<SurfaceTexture?> = arrayOfNulls(0)
    private var cameraInputSurfaces: Array<Surface?> = arrayOfNulls(0)
    private val pendingFrame = ArrayList<Boolean>()
    private val streamInfo = ArrayList<StreamInfo>()
    private val stMatrix = Array(4) { FloatArray(16) }

    /** Affine della ST per camera (dal primo frame) e mat3 finale per lo shader. */
    private val stAffine = arrayOfNulls<FloatArray>(4) // [a,b,c,d,tx,ty]: (u,v)->(a*u+c*v+tx, b*u+d*v+ty)
    private val xformMat3 = Array(4) { FloatArray(9) }
    private val xformReady = BooleanArray(4)

    @Volatile
    var layoutState: LayoutEngine.LayoutState = LayoutEngine.LayoutState()
        private set

    @Volatile
    private var recording = false
    private var drawScheduled = false

    /** Finestra del preview in modalità flash estremo: [x, yTop, w, h] in px surface. */
    @Volatile
    private var previewWindowPx: IntArray? = null

    /** Clear bianco per la modalità flash estremo (solo pass di anteprima). */
    @Volatile
    private var flashWhite = false

    /** Effetto live corrente (applicato a tutte le lane in preview e registrazione). */
    @Volatile
    private var liveEffect: it.agrilloit.turegistri.gl.LiveEffect? = null

    @Volatile
    private var peakingEnabled = false

    fun setLiveEffect(fx: it.agrilloit.turegistri.gl.LiveEffect?) {
        liveEffect = fx
    }

    fun setPeaking(enabled: Boolean) {
        peakingEnabled = enabled
    }

    /** Imposta il rettangolo (px) in cui disegnare l'anteprima (flash estremo). */
    fun setPreviewWindow(x: Int, yTop: Int, w: Int, h: Int) {
        previewWindowPx = intArrayOf(x, yTop, w, h)
        flashWhite = true
        requestDraw()
    }

    /** Ripristina l'anteprima a schermo intero. */
    fun clearPreviewWindow() {
        previewWindowPx = null
        flashWhite = false
        requestDraw()
    }

    fun start() {
        // Idempotente: l'engine è un singleton che sopravvive alla ricreazione
        // dell'Activity, quindi start() può essere richiamato più volte.
        if (!started.compareAndSet(false, true)) return
        handler.post {
            runCatching {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay fallito" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize fallito" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val configSpec = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            check(
                EGL14.eglChooseConfig(eglDisplay, configSpec, 0, configs, 0, 1, numConfigs, 0) &&
                    numConfigs[0] > 0
            ) { "Nessuna config EGL compatibile (EGL_RECORDABLE_ANDROID)" }
            eglConfig = configs[0]
            val ctxSpec = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxSpec, 0)
            check(eglContext !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext fallito" }

            // Surface pbuffer temporanea: resta corrente sul thread così il contesto
            // GL è sempre valido quando si creano programma e texture.
            dummySurface = EGL14.eglCreatePbufferSurface(
                eglDisplay, eglConfig,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            EGL14.eglMakeCurrent(eglDisplay, dummySurface, dummySurface, eglContext)
            buildProgram()
            }.onFailure {
                // Un errore GL non deve mai uccidere il processo: disabilita il rendering.
                android.util.Log.e("TuRegistriGL", "Init GL fallito: ${it.message}")
                eglConfig = null
            }
        }
    }

    /** Blocca il chiamante finché le superfici camera non sono pronte. */
    fun prepareCameraInputs(sizes: List<Size>): List<Surface> {
        val latch = CountDownLatch(1)
        val out = arrayOfNulls<Surface>(sizes.size)
        handler.post {
            try {
                releaseCameraInputsLocked()
                val n = sizes.size
                val textures = IntArray(n)
                GLES20.glGenTextures(n, textures, 0)
                texIds = textures.copyOf()
                surfaceTextures = arrayOfNulls(n)
                cameraInputSurfaces = arrayOfNulls(n)
                pendingFrame.clear()
                streamInfo.clear()
                for (i in 0 until n) {
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[i])
                    GLES20.glTexParameterf(
                        GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                        GLES20.GL_LINEAR.toFloat()
                    )
                    GLES20.glTexParameterf(
                        GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                        GLES20.GL_LINEAR.toFloat()
                    )
                    GLES20.glTexParameteri(
                        GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                        GLES20.GL_CLAMP_TO_EDGE
                    )
                    GLES20.glTexParameteri(
                        GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                        GLES20.GL_CLAMP_TO_EDGE
                    )
                    val st = SurfaceTexture(textures[i])
                    st.setDefaultBufferSize(sizes[i].width, sizes[i].height)
                    val index = i
                    st.setOnFrameAvailableListener({
                        onFrameAvailableInternal(index)
                    }, handler)
                    surfaceTextures[i] = st
                    cameraInputSurfaces[i] = Surface(st)
                    pendingFrame.add(false)
                    // Info provvisorie: la rotation la aggiorna CameraController.
                    streamInfo.add(StreamInfo(sizes[i].width, sizes[i].height, 0, false))
                    stAffine[i] = null
                    xformReady[i] = false
                    out[i] = cameraInputSurfaces[i]
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return out.filterNotNull()
    }

    /** Aggiorna le info di flusso (orientamento sensore / mirror) per la camera [index]. */
    fun setStreamInfo(index: Int, info: StreamInfo) {
        android.util.Log.d("TuRegistriGL", "setStreamInfo[$index]: ${info.bufferWidth}x${info.bufferHeight} rot=${info.sensorOrientation} mirror=${info.mirror}")
        handler.post {
            if (index < streamInfo.size) {
                streamInfo[index] = info
                // Mirror/orientamento cambiati: ricalcola la trasformazione.
                if (stAffine.getOrNull(index) != null) recomputeXform(index)
            }
            requestDraw()
        }
    }

    fun setLayout(state: LayoutEngine.LayoutState) {
        layoutState = state
        requestDraw()
    }

    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) {
        handler.post {
            if (previewEglSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
                previewEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (surface != null && surface.isValid && eglConfig != null) {
                previewEglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
                )
                previewWidth = width
                previewHeight = height
                if (previewEglSurface !== EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)
                    EGL14.eglSwapInterval(eglDisplay, 0)
                }
            }
            // Il contesto deve avere SEMPRE una surface corrente: updateTexImage e
            // i comandi GL girano su questo thread tra un pass e l'altro.
            EGL14.eglMakeCurrent(eglDisplay, dummySurface, dummySurface, eglContext)
            requestDraw()
        }
    }

    /**
     * Cattura un fotogramma dell'anteprima composita come Bitmap (render dedicato
     * + glReadPixels con flip verticale). Callback sul thread GL.
     */
    fun captureStill(callback: (android.graphics.Bitmap?) -> Unit) {
        handler.post {
            if (previewEglSurface === EGL14.EGL_NO_SURFACE || previewWidth <= 0) {
                callback(null)
                return@post
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)) {
                callback(null)
                return@post
            }
            drawPass(previewWidth, previewHeight)
            val w = previewWidth
            val h = previewHeight
            val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4)
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            buf.rewind()
            EGL14.eglMakeCurrent(eglDisplay, dummySurface, dummySurface, eglContext)

            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val srcRow = (h - 1 - y) * w * 4
                for (x in 0 until w) {
                    val o = srcRow + x * 4
                    pixels[y * w + x] = 0xFF000000.toInt() or
                        ((buf.get(o).toInt() and 0xFF) shl 16) or
                        ((buf.get(o + 1).toInt() and 0xFF) shl 8) or
                        (buf.get(o + 2).toInt() and 0xFF)
                }
            }
            val bmp = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            callback(bmp)
        }
    }

    /** Inizia a disegnare anche sul surface di input del encoder composito. */
    fun startEncode(encoderInput: Surface, width: Int, height: Int) {
        handler.post {
            if (encoderEglSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglConfig != null) {
                encoderEglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, encoderInput, intArrayOf(EGL14.EGL_NONE), 0
                )
                if (encoderEglSurface === EGL14.EGL_NO_SURFACE) {
                    android.util.Log.e(
                        "TuRegistriGL",
                        "eglCreateWindowSurface encoder fallito: err=${EGL14.eglGetError()}"
                    )
                }
                encoderWidth = width
                encoderHeight = height
                recording = encoderEglSurface !== EGL14.EGL_NO_SURFACE
                if (recording) {
                    EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
                    EGL14.eglSwapInterval(eglDisplay, 0)
                    EGL14.eglMakeCurrent(eglDisplay, dummySurface, dummySurface, eglContext)
                }
                android.util.Log.d("TuRegistriGL", "startEncode: ${width}x${height} ok=$recording")
            }
        }
    }

    fun stopEncode() {
        handler.post {
            recording = false
            if (encoderEglSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
            // Ripristina sempre il contesto dummy: senza surface corrente
            // updateTexImage e i comandi GL falliscono.
            EGL14.eglMakeCurrent(eglDisplay, dummySurface, dummySurface, eglContext)
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        handler.post {
            recording = false
            releaseCameraInputsLocked()
            if (previewEglSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
                previewEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (encoderEglSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (dummySurface !== EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, dummySurface)
                    dummySurface = EGL14.EGL_NO_SURFACE
                }
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ interno

    private fun onFrameAvailableInternal(index: Int) {
        // Già sul thread GL (listener registrato con questo handler).
        if (index < pendingFrame.size) {
            pendingFrame[index] = true
            requestDraw()
        }
    }

    private fun requestDraw() {
        if (drawScheduled) return
        drawScheduled = true
        handler.post(this::drawOnce)
    }

    private fun drawOnce() {
        drawScheduled = false
        if (eglConfig == null) return
        // Aggiorna le texture con i frame nuovi; al primo frame ricava la trasformazione.
        for (i in surfaceTextures.indices) {
            val st = surfaceTextures[i] ?: continue
            if (i < pendingFrame.size && pendingFrame[i]) {
                pendingFrame[i] = false
                // updateTexImage può lanciare se la sorgente camera è appena morta:
                // non deve MAI far crashare il thread GL durante una registrazione.
                try {
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId(i))
                    st.updateTexImage()
                    st.getTransformMatrix(stMatrix[i])
                } catch (e: Exception) {
                    android.util.Log.e("TuRegistriGL", "updateTexImage[$i] fallito: ${e.message}")
                    continue
                }
                if (!xformReady[i] && i < stAffine.size) {
                    stAffine[i] = affineFromST(stMatrix[i])
                    recomputeXform(i)
                    android.util.Log.d(
                        "TuRegistriGL",
                        "ST[$i] affine=${stAffine[i]?.contentToString()} rot=${streamInfo.getOrNull(i)?.sensorOrientation}"
                    )
                }
            }
        }
        if (recording && encoderEglSurface !== EGL14.EGL_NO_SURFACE) {
            if (EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)) {
                drawPass(encoderWidth, encoderHeight)
                if (!EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)) {
                    android.util.Log.e("TuRegistriGL", "eglSwapBuffers encoder fallito: err=${EGL14.eglGetError()}")
                }
            } else {
                android.util.Log.e("TuRegistriGL", "eglMakeCurrent encoder fallito: err=${EGL14.eglGetError()}")
            }
        }
        if (previewEglSurface !== EGL14.EGL_NO_SURFACE && previewWidth > 0) {
            if (EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)) {
                val win = previewWindowPx
                if (win != null && win[2] > 0 && win[3] > 0) {
                    val vyGL = previewHeight - win[1] - win[3]
                    drawPass(win[2], win[3], win[0], vyGL, flashWhite = true)
                } else {
                    drawPass(previewWidth, previewHeight)
                }
                EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)
            }
        }
    }

    private fun drawPass(viewportW: Int, viewportH: Int, offsetX: Int = 0, offsetYGL: Int = 0, flashWhite: Boolean = false) {
        // Clear dell'intera superficie (bianco in modalità flash estremo).
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        if (flashWhite) {
            GLES20.glClearColor(1f, 1f, 1f, 1f)
        } else {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (surfaceTextures.isEmpty()) return

        val aspects = streamInfo.map { info ->
            val rotated = info.sensorOrientation % 180 != 0
            if (rotated) {
                info.bufferHeight.toFloat() / info.bufferWidth.toFloat()
            } else {
                info.bufferWidth.toFloat() / info.bufferHeight.toFloat()
            }
        }
        val viewportAspect = viewportW.toFloat() / viewportH.toFloat()
        val placements = LayoutEngine.compute(
            layoutState, aspects, viewportAspect, surfaceTextures.size
        )
        val offX = offsetX
        val offY = offsetYGL
        var placementIndex = 0
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPosHandle)
        GLES20.glVertexAttribPointer(aPosHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)

        for (placement in placements) {
            val index = placement.cameraIndex
            if (index >= surfaceTextures.size) continue
            val info = streamInfo.getOrNull(index) ?: continue
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId(index))
            // Regione in pixel GL (origine viewport in basso a sinistra).
            val rx = offX + (placement.region.x * viewportW).toInt()
            val rw = (placement.region.w * viewportW).toInt()
            val rh = (placement.region.h * viewportH).toInt()
            val ry = offY + viewportH - ((placement.region.y * viewportH).toInt() + rh)
            GLES20.glViewport(rx, ry, rw, rh)
            // Crop convertito in spazio v-up: (u0, 1-v1, u1, 1-v0).
            val c = placement.crop
            GLES20.glUniform4f(uUVCropHandle, c.u0, 1f - c.v1, c.u1, 1f - c.v0)
            val xf = if (index < xformMat3.size) xformMat3[index] else IDENTITY_XFORM
            GLES20.glUniformMatrix3fv(uXformHandle, 1, false, xf, 0)
            // Maschera circolare solo sull'inserto PiP (secondo placement in PIP).
            val mask = if (layoutState.mode == it.agrilloit.turegistri.model.LayoutMode.PIP && placementIndex > 0) {
                pipCircleMask
            } else {
                CircleMask.NONE
            }
            GLES20.glUniform3f(uCircleHandle, mask.cx, mask.cy, mask.radius)
            val mat = if (index < stMatrix.size) stMatrix[index] else IDENTITY_MATRIX
            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, mat, 0)
            // Effetto live + focus peaking (parametri per lane).
            val fx = liveEffect
            if (uFxModeHandle >= 0) {
                if (fx != null && fx.mode != 0) {
                    GLES20.glUniform1i(uFxModeHandle, fx.mode)
                    GLES20.glUniform4f(
                        uFxParamsHandle, fx.p1, fx.p2, fx.p3, fx.p4
                    )
                    GLES20.glUniform1f(
                        uFxTimeHandle,
                        (System.nanoTime() / 1_000_000_000.0).toFloat()
                    )
                    GLES20.glUniform1f(uFxAspectHandle, viewportAspect)
                } else {
                    GLES20.glUniform1i(uFxModeHandle, 0)
                }
            }
            if (uPeakingHandle >= 0) {
                GLES20.glUniform1f(uPeakingHandle, if (peakingEnabled) 1f else 0f)
                if (uTexelHandle >= 0) {
                    GLES20.glUniform2f(
                        uTexelHandle,
                        1f / info.bufferWidth.coerceAtLeast(1),
                        1f / info.bufferHeight.coerceAtLeast(1),
                    )
                }
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            placementIndex++
        }
        GLES20.glDisableVertexAttribArray(aPosHandle)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun textureId(index: Int): Int =
        if (index in texIds.indices) texIds[index] else 0

    // ------------------------------------------------- trasformazione output->texture

    /**
     * Estrae l'affine 2D [a,b,c,d,tx,ty] dalla matrice 4x4 column-major della
     * SurfaceTexture: (u,v) -> (a*u + c*v + tx, b*u + d*v + ty).
     */
    private fun affineFromST(m: FloatArray): FloatArray =
        floatArrayOf(m[0], m[1], m[4], m[5], m[12], m[13])

    /** Composizione p∘q (applica prima q, poi p). */
    private fun compose(p: FloatArray, q: FloatArray): FloatArray = floatArrayOf(
        p[0] * q[0] + p[2] * q[1],
        p[1] * q[0] + p[3] * q[1],
        p[0] * q[2] + p[2] * q[3],
        p[1] * q[2] + p[3] * q[3],
        p[0] * q[4] + p[2] * q[5] + p[4],
        p[1] * q[4] + p[3] * q[5] + p[5],
    )

    /**
     * W per rotazione del contenuto di theta gradi in senso orario (per visualizzare
     * dritto in orientamento naturale portrait): mappa uv0 dell'output nell'uv del
     * texel da campionare.
     */
    private fun contentRotation(theta: Int): FloatArray = when (((theta % 360) + 360) % 360) {
        90 -> floatArrayOf(0f, 1f, -1f, 0f, 1f, 0f)    // (1-v, u)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, 1f, 1f)  // (1-u, 1-v)
        // (v, 1-u): chiude il gruppo (W90 ∘ W180 = W270).
        270 -> floatArrayOf(0f, -1f, 1f, 0f, 0f, 1f)
        else -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)   // identità
    }

    /**
     * Calcola la mat3 per lo shader: M = L⁻¹ ∘ W_theta, poi compone il mirror.
     * Così il contenuto risulta dritto QUALSIASI sia la convenzione della matrice
     * ST del device (flip, rotazioni, mirror pre-applicati dall'HAL).
     */
    private fun recomputeXform(index: Int) {
        val affine = stAffine.getOrNull(index) ?: return
        val info = streamInfo.getOrNull(index) ?: return
        // L⁻¹ (affine = [a,b,c,d,tx,ty])
        val a = affine[0]
        val b = affine[1]
        val c = affine[2]
        val d = affine[3]
        val tx = affine[4]
        val ty = affine[5]
        val det = a * d - c * b
        val inv = if (kotlin.math.abs(det) > 1e-9f) {
            val ia = d / det
            val ib = -b / det
            val ic = -c / det
            val id = a / det
            val itx = -(ia * tx + ic * ty)
            val ity = -(ib * tx + id * ty)
            floatArrayOf(ia, ib, ic, id, itx, ity)
        } else {
            floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        }
        var m = compose(inv, contentRotation(info.sensorOrientation))
        if (info.mirror) {
            // Mirror dell'output: si applica per primo, quindi compone a destra.
            m = compose(m, floatArrayOf(-1f, 0f, 0f, 1f, 1f, 0f))
        }
        // mat3 column-major per glUniformMatrix3fv.
        xformMat3[index] = floatArrayOf(
            m[0], m[1], 0f,
            m[2], m[3], 0f,
            m[4], m[5], 1f,
        )
        xformReady[index] = true
    }

    private fun releaseCameraInputsLocked() {
        for (st in surfaceTextures) {
            st?.setOnFrameAvailableListener(null)
            st?.release()
        }
        if (texIds.isNotEmpty()) {
            GLES20.glDeleteTextures(texIds.size, texIds, 0)
        }
        for (s in cameraInputSurfaces) s?.release()
        surfaceTextures = arrayOfNulls(0)
        cameraInputSurfaces = arrayOfNulls(0)
        texIds = IntArray(0)
        pendingFrame.clear()
        streamInfo.clear()
        for (i in stAffine.indices) stAffine[i] = null
        for (i in xformReady.indices) xformReady[i] = false
    }

    // ------------------------------------------------------------------ programma GL

    private var program = 0
    private var aPosHandle = 0
    private var uSTMatrixHandle = 0
    private var uUVCropHandle = 0
    private var uXformHandle = 0
    private var uCircleHandle = 0
    private var uFxModeHandle = 0
    private var uFxParamsHandle = 0
    private var uFxTimeHandle = 0
    private var uFxAspectHandle = 0
    private var uPeakingHandle = 0
    private var uTexelHandle = 0
    private var texIds: IntArray = IntArray(0)

    private val quadBuffer = directFloatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
    )

    private fun buildProgram() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) {
            "Link shader fallito: " + GLES20.glGetProgramInfoLog(program)
        }
        aPosHandle = GLES20.glGetAttribLocation(program, "aPos")
        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uUVCropHandle = GLES20.glGetUniformLocation(program, "uUVCrop")
        uXformHandle = GLES20.glGetUniformLocation(program, "uXform")
        uCircleHandle = GLES20.glGetUniformLocation(program, "uCircle")
        uFxModeHandle = GLES20.glGetUniformLocation(program, "uFxMode")
        uFxParamsHandle = GLES20.glGetUniformLocation(program, "uFxParams")
        uFxTimeHandle = GLES20.glGetUniformLocation(program, "uFxTime")
        uFxAspectHandle = GLES20.glGetUniformLocation(program, "uFxAspect")
        uPeakingHandle = GLES20.glGetUniformLocation(program, "uPeaking")
        uTexelHandle = GLES20.glGetUniformLocation(program, "uTexel")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) {
            "Compilazione shader fallita: " + GLES20.glGetShaderInfoLog(shader)
        }
        return shader
    }
}
