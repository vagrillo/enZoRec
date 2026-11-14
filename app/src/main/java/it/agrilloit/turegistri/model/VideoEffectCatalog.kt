package it.agrilloit.turegistri.model

/**
 * Catalogo degli effetti video (port di `VideoEffectCatalog` iOS): stesso set di
 * look dello shader Metal `VideoEffects.metal`, implementati come fragment
 * shader ES2 applicati per frame nella pipeline media3 (export) e ExoPlayer
 * (anteprima).
 *
 * Ogni effetto dichiara i propri parametri come nomi di uniform GLSL; il
 * programma generico ([it.agrilloit.turegistri.ui.editor.VideoEffectShaderProgram])
 * li passa al singolo frame. Il body GLSL definisce `vec4 applyEffect(vec2 uv)`
 * e può campionare con `sampleAt(p)`.
 *
 * Uniform sempre disponibili nel body:
 *  - `uTexSampler` (input), `uTimeSec` (tempo dal inizio clip), `uClipDurSec`
 *  - `uAspect` (w/h frame), `uRes` (vec2 pixel)
 */
object VideoEffectCatalog {

    /** Un effetto applicato a un clip: id di catalogo + valori dei parametri. */
    data class ClipEffect(
        val uid: String,
        val defId: String,
        val params: Map<String, Float> = emptyMap(),
        val enabled: Boolean = true,
        /** Riferimento a un dato esterno (es. percorso file LUT .cube per "lut3d"). */
        val dataRef: String? = null,
    )

    /** Parametro di un effetto (slider o selettore a preset). */
    data class VideoEffectParam(
        val id: String,
        val label: String,
        val min: Float,
        val max: Float,
        val default: Float,
        val presetLabels: List<String>? = null,
    )

    data class VideoEffectDef(
        val id: String,
        val label: String,
        val section: String,
        val desc: String,
        val params: List<VideoEffectParam>,
        /** GLSL: deve definire `vec4 applyEffect(vec2 uv)`. */
        val fragmentBody: String,
        /** false = applicabile solo in editor/export (troppo costoso per il live). */
        val liveCapable: Boolean = true,
    )

    private val LUMA_HELPERS = "" // (gli helper GLSL vivono in VideoEffectShaderProgram.buildFragmentShader)

    // ---------------------------------------------------------------- look ---
    val SILENT_TINT = VideoEffectDef(
        id = "silentTint", label = "Silent Tint", section = "Colore e stile",
        desc = "Tinta d'epoca a tutto frame (Blu notte, Ambra, Rosso fuoco, Verde natura, Seppia) + contrasto.",
        params = listOf(
            VideoEffectParam("uTintPreset", "Tinta", 0f, 4f, 0f,
                presetLabels = listOf("Blu notte", "Ambra", "Rosso fuoco", "Verde natura", "Seppia")),
            VideoEffectParam("uStrength", "Intensità", 0f, 1f, 0.8f),
            VideoEffectParam("uContrast", "Contrasto", 0.5f, 2f, 1.1f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      vec3 t;
      if (uTintPreset < 0.5)      t = vec3(0.55, 0.68, 1.0);
      else if (uTintPreset < 1.5) t = vec3(1.0, 0.82, 0.55);
      else if (uTintPreset < 2.5) t = vec3(1.0, 0.45, 0.35);
      else if (uTintPreset < 3.5) t = vec3(0.55, 1.0, 0.62);
      else                        t = vec3(0.87, 0.72, 0.53);
      float g = luma(c.rgb);
      g = clamp((g - 0.5) * uContrast + 0.5, 0.0, 1.0);
      vec3 col = mix(vec3(g), clamp(g * t * 1.15, 0.0, 1.0), uStrength);
      return vec4(col, c.a);
    }
    """.trimIndent(),
    )

    val THREE_STRIP = VideoEffectDef(
        id = "threeStrip", label = "Three-Strip", section = "Colore e stile",
        desc = "Look dye-transfer a tre strisce Technicolor: saturazione, rosso profondo, calore.",
        params = listOf(
            VideoEffectParam("uSaturation", "Saturazione", 0.5f, 2f, 1.25f),
            VideoEffectParam("uRedDepth", "Rosso", 0.5f, 2f, 1.3f),
            VideoEffectParam("uWarmth", "Calore", -1f, 1f, 0.3f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      vec3 col = c.rgb;
      float l = luma(col);
      col = clamp(mix(vec3(l), col, uSaturation), 0.0, 1.0);
      col.r = clamp(pow(col.r, 1.0 / max(uRedDepth, 0.2)), 0.0, 1.0);
      col *= vec3(1.0 + uWarmth * 0.15, 1.0, 1.0 - uWarmth * 0.15);
      return vec4(clamp(col, 0.0, 1.0), c.a);
    }
    """.trimIndent(),
    )

    val BLEACH_BYPASS = VideoEffectDef(
        id = "bleachBypass", label = "Bleach Bypass", section = "Colore e stile",
        desc = "Look metallico desaturato ad alto contrasto (silver retention), con slider di forza.",
        params = listOf(VideoEffectParam("uStrength", "Forza", 0f, 1f, 1f)),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      vec3 base = c.rgb;
      float l = luma(base);
      vec3 silver = vec3(l);
      vec3 screened = 1.0 - (1.0 - base) * (1.0 - silver);
      vec3 multiplied = base * silver * 1.6;
      vec3 bb = mix(multiplied, screened, smoothstep(0.35, 0.65, l));
      bb = mix(vec3(luma(bb)), bb, 0.75);
      return vec4(clamp(mix(base, bb, uStrength), 0.0, 1.0), c.a);
    }
    """.trimIndent(),
    )

    val DAY_FOR_NIGHT = VideoEffectDef(
        id = "dayForNight", label = "Day for Night", section = "Colore e stile",
        desc = "Grado blu-scuro da girato di giorno, luci lunari su alti toni.",
        params = listOf(
            VideoEffectParam("uStrength", "Forza", 0f, 1f, 1f),
            VideoEffectParam("uMoonlight", "Luci lunari", 0f, 1f, 1f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      float l = luma(c.rgb);
      vec3 dfn = vec3(l) * vec3(0.55, 0.65, 1.0) * 0.8;
      dfn += smoothstep(0.62, 1.0, l) * uMoonlight * vec3(0.15, 0.18, 0.26);
      return vec4(clamp(mix(c.rgb, dfn, uStrength), 0.0, 1.0), c.a);
    }
    """.trimIndent(),
    )

    val COLOR_ISOLATION = VideoEffectDef(
        id = "colorIsolation", label = "Isolamento colore", section = "Colore e stile",
        desc = "Mantieni un solo colore (tonalità + gamma + morbidezza), il resto in bianco e nero.",
        params = listOf(
            VideoEffectParam("uHue", "Tonalità", 0f, 360f, 0f),
            VideoEffectParam("uRange", "Gamma", 5f, 120f, 40f),
            VideoEffectParam("uSoftness", "Morbidezza", 1f, 90f, 25f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      vec3 hsv = rgb2hsv(clamp(c.rgb, 0.0, 1.0));
      float hueDeg = hsv.x * 360.0;
      float d = abs(mod(hueDeg - uHue + 540.0, 360.0) - 180.0);
      float keep = 1.0 - smoothstep(uRange, uRange + uSoftness, d);
      float l = luma(c.rgb);
      return vec4(mix(vec3(l), c.rgb, keep), c.a);
    }
    """.trimIndent(),
    )

    val NOIR = VideoEffectDef(
        id = "noir", label = "Noir", section = "Colore e stile",
        desc = "B/N con contrasto, neri schiacciati, grana e ombre blu.",
        params = listOf(
            VideoEffectParam("uContrast", "Contrasto", 0.5f, 3f, 1.4f),
            VideoEffectParam("uBlackCrush", "Neri", 0f, 0.5f, 0.08f),
            VideoEffectParam("uGrain", "Grana", 0f, 1f, 0.25f),
            VideoEffectParam("uBlueShadows", "Ombre blu", 0f, 1f, 0.4f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      float l = luma(c.rgb);
      l = clamp((l - 0.5) * uContrast + 0.5, 0.0, 1.0);
      l = clamp((l - uBlackCrush) / max(1.0 - uBlackCrush, 0.01), 0.0, 1.0);
      l = clamp(l + (grainAt(uv) - 0.5) * uGrain * 0.5, 0.0, 1.0);
      vec3 col = vec3(l);
      col += uBlueShadows * (1.0 - smoothstep(0.0, 0.5, l)) * vec3(-0.05, -0.03, 0.12);
      return vec4(clamp(col, 0.0, 1.0), c.a);
    }
    """.trimIndent(),
    )

    val EXPRESSIONISM = VideoEffectDef(
        id = "expressionism", label = "Espressionismo", section = "Colore e stile",
        desc = "Monocromo ad alto contrasto con iride ovale: fuori dall'ovale va al nero.",
        params = listOf(
            VideoEffectParam("uContrast", "Contrasto", 1f, 5f, 2.6f),
            VideoEffectParam("uIris", "Iride", 0.2f, 0.9f, 0.55f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      float l = luma(c.rgb);
      l = clamp((l - 0.5) * uContrast + 0.5, 0.0, 1.0);
      vec2 p = (uv - 0.5) * vec2(uAspect, 1.0);
      l *= 1.0 - smoothstep(uIris, uIris + 0.1, length(p));
      return vec4(vec3(clamp(l, 0.0, 1.0)), c.a);
    }
    """.trimIndent(),
    )

    val QUANTIZATION = VideoEffectDef(
        id = "quantization", label = "Quantizzazione", section = "Colore e stile",
        desc = "Posterizza (2–16 livelli, anche B/N) con dithering ordinato Bayer 4×4.",
        params = listOf(
            VideoEffectParam("uLevels", "Livelli", 2f, 16f, 5f),
            VideoEffectParam("uDither", "Dithering", 0f, 1f, 1f),
            VideoEffectParam("uMono", "B/N", 0f, 1f, 0f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      vec3 col = c.rgb;
      if (uMono > 0.5) col = vec3(luma(col));
      float n = max(2.0, floor(uLevels));
      if (uDither > 0.5) {
        float d = (bayer4(uv * uRes) - 0.3125) / 0.625;
        col += d / n;
      }
      col = floor(col * (n - 1.0) + 0.5) / (n - 1.0);
      return vec4(clamp(col, 0.0, 1.0), c.a);
    }
    """.trimIndent(),
    )

    val HALATION = VideoEffectDef(
        id = "halation", label = "Halation", section = "Colore e stile",
        desc = "Bagliore sui toni alti, frange calde rosse e sollevamento delle ombre (flash).",
        params = listOf(
            VideoEffectParam("uGlow", "Bagliore", 0f, 1f, 0.7f),
            VideoEffectParam("uRadius", "Raggio", 4f, 32f, 14f),
            VideoEffectParam("uFringe", "Frange", 0f, 1f, 0.6f),
            VideoEffectParam("uFlash", "Flash ombre", 0f, 1f, 0.2f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      vec3 glow = vec3(0.0);
      for (int i = 0; i < 12; i++) {
        float a = float(i) * 0.5236;
        vec2 off = vec2(cos(a), sin(a)) * uRadius / uRes;
        glow += sampleAt(uv + off).rgb + sampleAt(uv - off).rgb;
      }
      glow /= 24.0;
      vec3 hi = clamp((c.rgb - 0.6) / 0.4, 0.0, 1.0);
      vec3 col = c.rgb + glow * hi * uGlow * mix(vec3(1.0), vec3(1.0, 0.82, 0.66), uFringe);
      col += uFlash * 0.05;
      return vec4(clamp(col, 0.0, 1.0), c.a);
    }
    """.trimIndent(),
    ).copy(liveCapable = false)

    val SIXTEEN_MM = VideoEffectDef(
        id = "sixteenMM", label = "16mm", section = "Colore e stile",
        desc = "Grana animata (fine/media/grossa), softness ottica, gate weave, bordo arrotondato, B/N opzionale.",
        params = listOf(
            VideoEffectParam("uGrain", "Grana", 0f, 1f, 0.45f),
            VideoEffectParam("uGrainSize", "Grana fine", 1f, 4f, 2f),
            VideoEffectParam("uSoftness", "Softness", 0f, 1f, 0.3f),
            VideoEffectParam("uWeave", "Weave", 0f, 1f, 0.4f),
            VideoEffectParam("uMono", "B/N", 0f, 1f, 0f),
            VideoEffectParam("uRounded", "Bordo", 0f, 1f, 0.7f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec2 uvw = uv + weaveOffset(uWeave * 2.5);
      vec4 c = sampleAt(clamp(uvw, 0.001, 0.999));
      vec3 blur = (sampleAt(uv + vec2(1.5, 0.0) / uRes).rgb +
                   sampleAt(uv - vec2(1.5, 0.0) / uRes).rgb +
                   sampleAt(uv + vec2(0.0, 1.5) / uRes).rgb +
                   sampleAt(uv - vec2(0.0, 1.5) / uRes).rgb) * 0.25;
      vec3 col = mix(c.rgb, blur, uSoftness);
      if (uMono > 0.5) col = vec3(luma(col));
      col = clamp(col + (grainAt(uv / max(uGrainSize, 1.0)) - 0.5) * uGrain * 0.45, 0.0, 1.0);
      vec2 p = (uv - 0.5) * vec2(uAspect, 1.0);
      col *= mix(1.0, 1.0 - smoothstep(0.47, 0.52, length(p)), uRounded);
      return vec4(col, c.a);
    }
    """.trimIndent(),
    )

    val SUPER_EIGHT = VideoEffectDef(
        id = "superEight", label = "Super 8", section = "Colore e stile",
        desc = "Colore caldo sbiadito, grana grossa, weave, light leak animati e bordo pellicola.",
        params = listOf(
            VideoEffectParam("uGrain", "Grana", 0f, 1f, 0.5f),
            VideoEffectParam("uWeave", "Weave", 0f, 1f, 0.5f),
            VideoEffectParam("uLeaks", "Light leak", 0f, 1f, 0.6f),
            VideoEffectParam("uBorder", "Bordo", 0f, 1f, 0.6f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec2 uvw = uv + weaveOffset(uWeave * 3.5);
      vec4 c = sampleAt(clamp(uvw, 0.001, 0.999));
      vec3 col = (c.rgb * vec3(1.08, 0.98, 0.88) + vec3(0.05, 0.03, 0.0));
      col = (col - 0.5) * 0.9 + 0.5;
      col += (grainAt(uv * 0.6) - 0.5) * uGrain * 0.5;
      float leak = smoothstep(0.45, 0.0, distance(uv, vec2(0.18 + 0.1 * sin(uTimeSec * 0.7), 0.1)));
      col += leak * uLeaks * vec3(1.0, 0.85, 0.6) * 0.35;
      vec2 e = max(abs(uv - 0.5) * vec2(uAspect, 1.0) - 0.44, 0.0);
      col *= 1.0 - uBorder * smoothstep(0.0, 0.03, max(e.x, e.y));
      return vec4(clamp(col, 0.0, 1.0), c.a);
    }
    """.trimIndent(),
    )

    val WIDESCREEN = VideoEffectDef(
        id = "widescreen", label = "Widescreen 1953", section = "Camera e frame",
        desc = "Barre cinematografiche (2.39 / 2.20 / 1.85 / 1.33) sul frame di output.",
        params = listOf(
            VideoEffectParam("uRatioVal", "Formato", 1.33f, 2.39f, 2.39f,
                presetLabels = listOf("1.33", "1.85", "2.20", "2.39")),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      float target = uRatioVal;
      float inside = 1.0;
      if (uAspect < target) {
        float bar = (1.0 - uAspect / target) * 0.5;
        inside = step(bar, uv.y) * step(uv.y, 1.0 - bar);
      } else {
        float bar = (1.0 - target / uAspect) * 0.5;
        inside = step(bar, uv.x) * step(uv.x, 1.0 - bar);
      }
      return vec4(c.rgb * inside, c.a);
    }
    """.trimIndent(),
    )

    val KALEIDOSCOPE = VideoEffectDef(
        id = "kaleidoscope", label = "Caleidoscopio", section = "Camera e frame",
        desc = "Riflessione a settori (2–9) attorno a un centro mobile, con rotazione sorgente.",
        params = listOf(
            VideoEffectParam("uDivisions", "Settori", 2f, 9f, 6f),
            VideoEffectParam("uCenterX", "Centro X", 0f, 1f, 0.5f),
            VideoEffectParam("uCenterY", "Centro Y", 0f, 1f, 0.5f),
            VideoEffectParam("uRotate", "Rotazione", 0f, 6.28f, 0f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec2 ctr = vec2(uCenterX, uCenterY);
      vec2 p = (uv - ctr) * vec2(uAspect, 1.0);
      float a = atan(p.y, p.x) - uRotate;
      float r = length(p);
      float seg = 6.2831853 / max(2.0, floor(uDivisions));
      a = mod(a, seg);
      a = abs(a - seg * 0.5);
      vec2 q = vec2(cos(a), sin(a)) * r;
      q = q / vec2(uAspect, 1.0) + ctr;
      return sampleAt(clamp(q, 0.0, 1.0));
    }
    """.trimIndent(),
    )

    val RGB_OFFSET = VideoEffectDef(
        id = "rgbOffset", label = "RGB Offset", section = "Camera e frame",
        desc = "Dislocazione dei canali R/B lungo una direzione: aberrazione cromatica controllata.",
        params = listOf(
            VideoEffectParam("uOffset", "Offset (px)", 0f, 20f, 4f),
            VideoEffectParam("uAngle", "Angolo (°)", 0f, 360f, 0f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec2 dir = vec2(cos(uAngle * 0.017453), sin(uAngle * 0.017453)) * uOffset / uRes;
      float r = sampleAt(uv + dir).r;
      float g = sampleAt(uv).g;
      float b = sampleAt(uv - dir).b;
      return vec4(r, g, b, 1.0);
    }
    """.trimIndent(),
    )

    val NEGATIVE_STROBE = VideoEffectDef(
        id = "negativeStrobe", label = "Negative Strobe", section = "Tempo",
        desc = "Stroboscopio tra immagine e negativo (1–15 Hz), con modalità burst.",
        params = listOf(
            VideoEffectParam("uHz", "Frequenza Hz", 1f, 15f, 6f),
            VideoEffectParam("uBurst", "Burst", 0f, 1f, 0f,
                presetLabels = listOf("Continuo", "Burst")),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      float invert = step(fract(uTimeSec * uHz), 0.5);
      if (uBurst > 0.5) {
        float cycle = mod(uTimeSec, 6.0);
        invert *= step(1.0, cycle) * step(cycle, 3.0);
      }
      return vec4(mix(c.rgb, 1.0 - c.rgb, invert), c.a);
    }
    """.trimIndent(),
    )

    val CHROMA_KEY = VideoEffectDef(
        id = "chromaKey", label = "Chroma Key", section = "Sfondo",
        desc = "Keyer su colore (verde/blu/magenta) con forza, morbidezza, despill e sfondo tinta unita o originale sfocato.",
        params = listOf(
            VideoEffectParam("uKeyPreset", "Schermo", 0f, 2f, 0f,
                presetLabels = listOf("Verde", "Blu", "Magenta")),
            VideoEffectParam("uStrength", "Forza", 0f, 1f, 1f),
            VideoEffectParam("uSoftness", "Morbidezza", 0.02f, 0.5f, 0.12f),
            VideoEffectParam("uSpill", "Despill", 0f, 1f, 0.6f),
            VideoEffectParam("uBgMode", "Sfondo", 0f, 1f, 0f,
                presetLabels = listOf("Tinta unita", "Originale sfocato")),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      vec3 key = uKeyPreset < 0.5 ? vec3(0.20, 0.70, 0.30) :
                 (uKeyPreset < 1.5 ? vec3(0.25, 0.45, 0.75) : vec3(0.72, 0.30, 0.66));
      float dist = length(c.rgb - key);
      float alpha = smoothstep(uSoftness, uSoftness + 0.2, dist);
      alpha = clamp(alpha + (1.0 - uStrength) * (1.0 - alpha) * alpha, 0.0, 1.0);
      if (alpha > 0.995) {
        vec3 col = c.rgb;
        float maxrb = max(col.r, col.b);
        col.g = min(col.g, maxrb + (1.0 - uSpill) * 0.25);
        return vec4(col, c.a);
      }
      vec3 bg;
      if (uBgMode < 0.5) {
        bg = vec3(0.08, 0.08, 0.10);
      } else {
        vec3 acc = vec3(0.0);
        for (int i = 0; i < 8; i++) {
          float a2 = float(i) * 0.7854;
          acc += sampleAt(uv + vec2(cos(a2), sin(a2)) * 12.0 / uRes).rgb;
        }
        bg = acc / 8.0;
      }
      vec3 col = mix(bg, c.rgb, alpha);
      float maxrb2 = max(col.r, col.b);
      col.g = mix(min(col.g, maxrb2 + 0.25), col.g, alpha);
      return vec4(col, c.a);
    }
    """.trimIndent(),
    )

    val NIGHT_FIX = VideoEffectDef(
        id = "nightFix", label = "Night fix", section = "Colore e stile",
        desc = "Recupero riprese notturne: alza le ombre, riduce il rumore e preserva i dettagli.",
        params = listOf(
            VideoEffectParam("uShadowLift", "Ombre", 0f, 1f, 0.5f),
            VideoEffectParam("uDenoise", "Denoise", 0f, 1f, 0.5f),
            VideoEffectParam("uDetail", "Dettaglio", 0f, 1f, 0.4f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      vec3 col = c.rgb;
      vec3 nb = (sampleAt(uv + vec2(1.0, 0.0) / uRes).rgb +
                 sampleAt(uv - vec2(1.0, 0.0) / uRes).rgb +
                 sampleAt(uv + vec2(0.0, 1.0) / uRes).rgb +
                 sampleAt(uv - vec2(0.0, 1.0) / uRes).rgb) * 0.25;
      float shadowMask = 1.0 - smoothstep(0.25, 0.75, luma(col));
      col += uShadowLift * 0.4 * shadowMask * pow(1.0 - col, 2.5);
      float diff = length(nb - col);
      float keepMix = smoothstep(0.0, 0.22, diff);
      col = mix(col, mix(nb, col, keepMix), uDenoise);
      col = clamp(col + (col - nb) * uDetail * 0.7, 0.0, 1.0);
      return vec4(clamp(col, 0.0, 1.0), c.a);
    }
    """.trimIndent(),
    ).copy(liveCapable = false)

    val DOLLY_ZOOM = VideoEffectDef(
        id = "dollyZoom", label = "Dolly Zoom", section = "Tempo",
        desc = "Push-in vertiginoso lento su tutto il clip verso il punto scelto (effetto Vertigo).",
        params = listOf(
            VideoEffectParam("uZoomMax", "Zoom max", 0.1f, 1f, 0.5f),
            VideoEffectParam("uTargetX", "Centro X", 0f, 1f, 0.5f),
            VideoEffectParam("uTargetY", "Centro Y", 0f, 1f, 0.45f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      float t = easeInOut(uTimeSec / max(uClipDurSec, 0.001));
      float z = 1.0 + uZoomMax * t;
      vec2 ctr = vec2(uTargetX, uTargetY);
      vec2 p = (uv - ctr) / z + ctr;
      return sampleAt(clamp(p, 0.0, 1.0));
    }
    """.trimIndent(),
    )

    val CRASH_ZOOM = VideoEffectDef(
        id = "crashZoom", label = "Crash Zoom", section = "Tempo",
        desc = "Zoom rapido all'inizio del clip verso il punto scelto, poi tenuto fino alla fine.",
        params = listOf(
            VideoEffectParam("uZoomMax", "Zoom", 0.1f, 1.5f, 0.6f),
            VideoEffectParam("uPushSec", "Durata push (s)", 0.2f, 2f, 0.5f),
            VideoEffectParam("uTargetX", "Centro X", 0f, 1f, 0.5f),
            VideoEffectParam("uTargetY", "Centro Y", 0f, 1f, 0.45f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      float t = easeInOut(uTimeSec / max(uPushSec, 0.05));
      float z = 1.0 + uZoomMax * clamp(t, 0.0, 1.0);
      vec2 ctr = vec2(uTargetX, uTargetY);
      vec2 p = (uv - ctr) / z + ctr;
      return sampleAt(clamp(p, 0.0, 1.0));
    }
    """.trimIndent(),
    )

    val COLOR_CORRECTION = VideoEffectDef(
        id = "colorCorrection", label = "Color Correction", section = "Colore e stile",
        desc = "Correzione colore completa (port iOS): esposizione, contrasto, ombre, luci, " +
            "neri, bianchi, temperatura, tinta, saturazione, vibrance, tonalità.",
        params = listOf(
            VideoEffectParam("uExposure", "Esposizione (EV)", -4f, 4f, 0f),
            VideoEffectParam("uContrast", "Contrasto", 0.5f, 2f, 1f),
            VideoEffectParam("uShadows", "Ombre", -1f, 1f, 0f),
            VideoEffectParam("uHighlights", "Luci", -1f, 1f, 0f),
            VideoEffectParam("uBlacks", "Neri", -1f, 1f, 0f),
            VideoEffectParam("uWhites", "Bianchi", -1f, 1f, 0f),
            VideoEffectParam("uTemperature", "Temperatura", -1f, 1f, 0f),
            VideoEffectParam("uTint", "Tinta", -1f, 1f, 0f),
            VideoEffectParam("uSaturation", "Saturazione", 0f, 2f, 1f),
            VideoEffectParam("uVibrance", "Vibrance", 0f, 2f, 1f),
            VideoEffectParam("uHue", "Tonalità (°)", -180f, 180f, 0f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      vec3 col = c.rgb;
      col *= pow(2.0, uExposure);
      col.r *= 1.0 + uTemperature * 0.18;
      col.b *= 1.0 - uTemperature * 0.18;
      col.g *= 1.0 + uTint * 0.12;
      col = (col - 0.5) * uContrast + 0.5;
      float l = luma(clamp(col, 0.0, 1.0));
      float shMask = pow(1.0 - smoothstep(0.0, 0.55, l), 1.5);
      float hiMask = smoothstep(0.45, 1.0, l);
      col += uShadows * 0.25 * shMask;
      col += uHighlights * 0.25 * hiMask;
      col += uBlacks * 0.12 * (1.0 - smoothstep(0.0, 0.25, l));
      col += uWhites * 0.12 * smoothstep(0.75, 1.0, l);
      vec3 hsv = rgb2hsv(clamp(col, 0.0, 1.0));
      hsv.y = clamp(hsv.y * uSaturation, 0.0, 1.0);
      hsv.y = clamp(hsv.y + (uVibrance - 1.0) * (1.0 - hsv.y) * 0.6, 0.0, 1.0);
      hsv.x = fract(hsv.x + uHue / 360.0);
      col = hsv2rgb(hsv);
      return vec4(clamp(col, 0.0, 1.0), c.a);
    }
    """.trimIndent(),
    )

    val COLOR_LOOK = VideoEffectDef(
        id = "colorLook", label = "Color look", section = "Colore e stile",
        desc = "14 look di progetto (port iOS ColorLook): Natural, Warm, Cool shadows, Cinema, " +
            "Mono, Portrait, Daylight, Golden hour, Muted film, Rich contrast, Pastel, Night, " +
            "Silver, Ink — con intensità.",
        params = listOf(
            VideoEffectParam("uLookPreset", "Look", 0f, 13f, 0f,
                presetLabels = listOf(
                    "Natural", "Warm", "Cool shadows", "Cinema", "Mono", "Portrait",
                    "Daylight", "Golden hour", "Muted film", "Rich contrast", "Pastel",
                    "Night", "Silver", "Ink",
                )),
            VideoEffectParam("uStrength", "Intensità", 0f, 1f, 1f),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      vec3 col = clamp(c.rgb, 0.0, 1.0);
      float l = luma(col);
      vec3 hsv = rgb2hsv(col);
      if (uLookPreset < 0.5) {
        // natural: nessuna modifica
      } else if (uLookPreset < 1.5) {
        col *= vec3(1.06, 1.0, 0.92);
        hsv.y = clamp(hsv.y * 1.08, 0.0, 1.0); col = hsv2rgb(hsv);
      } else if (uLookPreset < 2.5) {
        col += (1.0 - l) * vec3(-0.03, -0.02, 0.09);
      } else if (uLookPreset < 3.5) {
        col = (col - 0.5) * 1.12 + 0.5;
        col += (1.0 - l) * vec3(-0.02, 0.0, 0.06) + smoothstep(0.55, 1.0, l) * vec3(0.05, 0.02, -0.04);
        hsv.y = clamp(hsv.y * 0.92, 0.0, 1.0); col = hsv2rgb(hsv);
      } else if (uLookPreset < 4.5) {
        col = vec3(l);
      } else if (uLookPreset < 5.5) {
        col *= vec3(1.05, 1.0, 0.95);
        col = (col - 0.5) * 0.93 + 0.5;
        hsv.y = clamp(hsv.y * 0.96, 0.0, 1.0); col = hsv2rgb(hsv);
      } else if (uLookPreset < 6.5) {
        col = col * 1.05 + 0.02;
        hsv.y = clamp(hsv.y * 1.12, 0.0, 1.0); col = hsv2rgb(hsv);
      } else if (uLookPreset < 7.5) {
        col *= vec3(1.16, 1.02, 0.8);
        col = (col - 0.5) * 0.96 + 0.52;
      } else if (uLookPreset < 8.5) {
        hsv.y = clamp(hsv.y * 0.72, 0.0, 1.0); col = hsv2rgb(hsv);
        col = (col - 0.5) * 0.94 + 0.54;
      } else if (uLookPreset < 9.5) {
        col = (col - 0.5) * 1.22 + 0.5;
        hsv.y = clamp(hsv.y + (0.2 * (1.0 - hsv.y)), 0.0, 1.0); col = hsv2rgb(hsv);
      } else if (uLookPreset < 10.5) {
        hsv.y = clamp(hsv.y * 0.8, 0.0, 1.0); col = hsv2rgb(hsv);
        col = col * 1.05;
        col = (col - 0.5) * 0.85 + 0.5;
      } else if (uLookPreset < 11.5) {
        col *= vec3(0.62, 0.72, 1.08);
        col = (col - 0.5) * 1.1 + 0.46;
      } else if (uLookPreset < 12.5) {
        col = vec3(l);
        col = (col - 0.5) * 1.2 + 0.5;
      } else {
        col = vec3(l);
        col = clamp((col - 0.14) / 0.86, 0.0, 1.0);
        col = (col - 0.5) * 1.5 + 0.5;
      }
      col = clamp(col, 0.0, 1.0);
      return vec4(mix(c.rgb, col, uStrength), c.a);
    }
    """.trimIndent(),
    )

    val CANVAS = VideoEffectDef(
        id = "canvas", label = "Canvas", section = "Camera e frame",
        desc = "Riquadra il frame nel canvas scelto (16:9, 9:16, 1:1, 4:5) con barre nere.",
        params = listOf(
            VideoEffectParam("uTargetVal", "Formato", 0.8f, 2.4f, 1.7778f,
                presetLabels = listOf("4:5", "1:1", "9:16", "16:9", "2.39")),
        ),
        fragmentBody = """
    vec4 applyEffect(vec2 uv) {
      vec4 c = sampleAt(uv);
      float target = uTargetVal;
      float inside = 1.0;
      if (uAspect < target) {
        float bar = (1.0 - uAspect / target) * 0.5;
        inside = step(bar, uv.y) * step(uv.y, 1.0 - bar);
      } else {
        float bar = (1.0 - target / uAspect) * 0.5;
        inside = step(bar, uv.x) * step(uv.x, 1.0 - bar);
      }
      return vec4(c.rgb * inside, c.a);
    }
    """.trimIndent(),
    )

    val ALL: List<VideoEffectDef> = listOf(
        SILENT_TINT, THREE_STRIP, BLEACH_BYPASS, DAY_FOR_NIGHT, COLOR_ISOLATION,
        NOIR, EXPRESSIONISM, QUANTIZATION, HALATION, SIXTEEN_MM, SUPER_EIGHT,
        WIDESCREEN, KALEIDOSCOPE, RGB_OFFSET, NEGATIVE_STROBE, CHROMA_KEY,
        NIGHT_FIX, DOLLY_ZOOM, CRASH_ZOOM, COLOR_CORRECTION, COLOR_LOOK, CANVAS,
    )

    fun byId(id: String): VideoEffectDef? = ALL.firstOrNull { it.id == id }

    val SECTIONS: List<String> =
        listOf("Colore e stile", "Camera e frame", "Tempo", "Sfondo")

    /** Valori completi dei parametri (default + override utente, clampati). */
    fun effectiveParams(def: VideoEffectDef, overrides: Map<String, Float>): Map<String, Float> {
        val out = mutableMapOf<String, Float>()
        for (p in def.params) {
            val v = overrides[p.id] ?: p.default
            out[p.id] = v.coerceIn(p.min, p.max)
        }
        return out
    }
}
