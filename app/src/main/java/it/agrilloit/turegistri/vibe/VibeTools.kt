package it.agrilloit.turegistri.vibe

/**
 * Registro dei TOOL di regia disponibili al regista (locale o LLM).
 *
 * Stesso pattern di TuDirector (director_tools.py): ogni tool ha
 *  - id, label,
 *  - descrizione "quando e come usarlo" (entra nel prompt del regista AI),
 *  - schema parametri con default/min/max,
 *  - limiti di durata,
 *  - funzione di clamp dei valori.
 *
 * La lista viene serializzata in JSON per il prompt del regista LLM (fase 2)
 * ed è la stessa usata dal regista euristico locale (VibeDirector.direct).
 */
object VibeTools {

    data class ToolParam(
        val id: String,
        val type: String, // "number" | "enum" | "bool" | "text"
        val min: Double = 0.0,
        val max: Double = 1.0,
        val default: Double = 0.0,
        val desc: String = "",
    )

    data class ToolDef(
        val id: String,
        val label: String,
        val desc: String,
        val params: List<ToolParam>,
        val defaultDurMs: Long,
        val minDurMs: Long,
        val maxDurMs: Long,
    ) {
        /** Applica i limiti ai parametri ricevuti dal regista. */
        fun clamp(params: Map<String, Double>, durMs: Long): Map<String, Double> {
            val out = mutableMapOf<String, Double>()
            for (p in this.params) {
                val v = params[p.id] ?: p.default
                out[p.id] = if (p.type == "enum") v else v.coerceIn(p.min, p.max)
            }
            out["durMs"] = durMs.coerceIn(minDurMs, maxDurMs).toDouble()
            return out
        }

        /** Descrizione JSON per il prompt del regista LLM. */
        fun toJson(): String {
            val sb = StringBuilder()
            sb.append("{\"id\":\"").append(id).append("\",\"label\":\"").append(label)
                .append("\",\"desc\":\"").append(desc.replace("\"", "\\\""))
                .append("\",\"params\":[")
            params.forEachIndexed { i, prm ->
                if (i > 0) sb.append(",")
                sb.append("{\"id\":\"").append(prm.id).append("\",\"type\":\"")
                    .append(prm.type).append("\",\"min\":").append(prm.min)
                    .append(",\"max\":").append(prm.max)
                    .append(",\"default\":").append(prm.default).append("}")
            }
            sb.append("],\"durMs\":[").append(minDurMs).append(",")
                .append(defaultDurMs).append(",").append(maxDurMs).append("]}")
            return sb.toString()
        }
    }

    val ZOOM = ToolDef(
        id = "zoom",
        label = "Zoom (in/out verso un punto)",
        desc = "Zoom progressivo verso il punto (cx, cy) 0..1. USALO quando il " +
            "contenuto chiede attenzione o un primo piano sul volto/dettaglio. " +
            "Non superare 1 zoom ogni 4 secondi.",
        params = listOf(
            ToolParam("cx", "number", min = 0.0, max = 1.0, default = 0.5, desc = "centro X"),
            ToolParam("cy", "number", min = 0.0, max = 1.0, default = 0.45, desc = "centro Y"),
            ToolParam(
                "strength", "number", min = 0.1, max = 0.5, default = 0.3,
                desc = "forza dello zoom",
            ),
        ),
        defaultDurMs = 3000, minDurMs = 1000, maxDurMs = 8000,
    )

    val COLOR = ToolDef(
        id = "color",
        label = "Color correction + mood",
        desc = "Correzione colore del clip: exposure (EV), contrast (1 = neutro), " +
            "saturation (0 = B/N), warmth (−1 freddo/+1 caldo). USALO per dare tono " +
            "alle scene: desat nei momenti calmi/riflessivi, caldo/saturo nei vivaci.",
        params = listOf(
            ToolParam("exposure", "number", min = -1.0, max = 1.0, default = 0.0, desc = "EV"),
            ToolParam("contrast", "number", min = 0.5, max = 1.8, default = 1.0),
            ToolParam("saturation", "number", min = 0.0, max = 2.0, default = 1.0),
            ToolParam("warmth", "number", min = -1.0, max = 1.0, default = 0.0),
        ),
        defaultDurMs = 6000, minDurMs = 1500, maxDurMs = 30000,
    )

    val CUT = ToolDef(
        id = "cut",
        label = "Taglio / split",
        desc = "Divide il clip al tempo indicato: separa argomenti o rimuove " +
            "respiro. USALO sui confini di argomento e sui silenzi lunghi.",
        params = listOf(),
        defaultDurMs = 0, minDurMs = 0, maxDurMs = 0,
    )

    val SPEED = ToolDef(
        id = "speed",
        label = "Velocità del clip",
        desc = "Velocità costante del clip (0.5 = metà, 2 = doppio). USALO per " +
            "accelerare passaggi noiosi o enfatizzare momenti. Audio sincronizzato.",
        params = listOf(
            ToolParam("speed", "number", min = 0.5, max = 2.0, default = 1.0),
        ),
        defaultDurMs = 0, minDurMs = 0, maxDurMs = 0,
    )

    val VOLUME = ToolDef(
        id = "volume",
        label = "Volume del clip",
        desc = "Volume del clip 0..2 (0 = muto). USALO per abbassare i B-roll " +
            "o uniformare i livelli tra i clip.",
        params = listOf(
            ToolParam("volume", "number", min = 0.0, max = 2.0, default = 1.0),
        ),
        defaultDurMs = 0, minDurMs = 0, maxDurMs = 0,
    )

    val CAMERA_SWITCH = ToolDef(
        id = "camera_switch",
        label = "Switch camera",
        desc = "Scambia le due camere (A<->B) da questo momento. USALO nei " +
            "cambi di argomento o di tono quando ci sono più inquadrature.",
        params = listOf(),
        defaultDurMs = 0, minDurMs = 0, maxDurMs = 0,
    )

    val MARKER = ToolDef(
        id = "marker",
        label = "Marker / bookmark",
        desc = "Segna un momento importante della timeline (per revisione o note).",
        params = listOf(),
        defaultDurMs = 0, minDurMs = 0, maxDurMs = 0,
    )

    val ALL: List<ToolDef> = listOf(ZOOM, COLOR, CUT, SPEED, VOLUME, CAMERA_SWITCH, MARKER)

    fun byId(id: String): ToolDef? = ALL.firstOrNull { it.id == id }

    /** Descrizione completa dei tool per il prompt del regista LLM (fase 2). */
    fun toJson(): String {
        val sb = StringBuilder("[")
        ALL.forEachIndexed { i, t ->
            if (i > 0) sb.append(",")
            sb.append(t.toJson())
        }
        sb.append("]")
        return sb.toString()
    }
}
