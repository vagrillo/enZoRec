package it.agrilloit.turegistri.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.sqrt

/**
 * Catalogo delle camere del dispositivo con etichette di zoom equivalenti
 * (stile 0.5x / 1x / 3x / 10x) e verifica delle combinazioni simultanee.
 *
 * Lo zoom equivalente è calcolato dalla focale in 35mm equivalente, rapportata
 * alla camera principale posteriore (sensore più grande): i valori seguono la
 * convenzione del produttore (ultragrande ≈ 0.5-0.6x, tele ≈ 3x/5x/10x).
 */
object CameraCatalog {

    data class CameraOption(
        val id: String,
        val isFront: Boolean,
        val sensorOrientation: Int,
        /** Etichetta stile "0.6x", "1x", "3x", "Frontale". */
        val label: String,
        /** Focale 35mm equivalente (per ordinare/etichettare). */
        val focal35mm: Float,
    )

    fun catalog(manager: CameraManager): List<CameraOption> {
        data class Raw(
            val id: String, val isFront: Boolean, val orientation: Int,
            val focal35: Float, val sensorArea: Float, val isLogical: Boolean,
        )

        // Le camere fisiche figlie di camere logiche multi-camera non sono apribili
        // stand-alone: le escludiamo dal catalogo (evita i doppioni "Frontale").
        val physicalChildren = HashSet<String>()
        for (id in manager.cameraIdList) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                physicalChildren.addAll(chars.physicalCameraIds)
            }
        }

        val raws = ArrayList<Raw>()
        for (id in manager.cameraIdList) {
            if (id in physicalChildren) continue
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull() ?: 0f
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            // Focale 35mm equivalente: diagonale 35mm (43.27mm) / diagonale sensore.
            val diag = physical?.let { sqrt((it.width * it.width + it.height * it.height).toDouble()) } ?: 0.0
            val focal35 = if (diag > 0 && focal > 0) (focal * 43.27 / diag).toFloat() else 0f
            val isLogical =
                chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
            raws.add(
                Raw(
                    id = id,
                    isFront = facing == CameraCharacteristics.LENS_FACING_FRONT,
                    orientation = orientation,
                    focal35 = focal35,
                    sensorArea = active?.let { (it.width().toFloat() * it.height()) } ?: 0f,
                    isLogical = isLogical,
                )
            )
        }
        if (raws.isEmpty()) return emptyList()

        // Riferimento 1x: camera posteriore fisica con il sensore più grande.
        val reference = raws
            .filter { !it.isFront && !it.isLogical && it.focal35 > 0 }
            .maxByOrNull { it.sensorArea }
            ?: raws.first { !it.isFront }

        val sorted = raws.sortedWith(compareBy({ it.isFront }, { it.focal35 }))
        // Se due camere producono la stessa etichetta, smezzale con un progressivo.
        val seen = HashMap<String, Int>()
        return sorted.map { raw ->
            val zoom = if (reference.focal35 > 0 && raw.focal35 > 0) raw.focal35 / reference.focal35 else 1f
            var label = if (raw.isFront) "Frontale" else "%.1fx".format(java.util.Locale.US, zoom)
            val count = seen.merge(label, 1, Int::plus) ?: 1
            if (count > 1) label = "$label #$count"
            CameraOption(
                id = raw.id,
                isFront = raw.isFront,
                sensorOrientation = raw.orientation,
                label = label,
                focal35mm = raw.focal35,
            )
        }
    }

    /**
     * Vero se le due camere possono lavorare contemporaneamente su questo device.
     * Con un solo id è sempre vero (modalità singola).
     */
    fun canRunTogether(manager: CameraManager, idA: String, idB: String): Boolean {
        if (idA == idB) return false
        val combinations: Set<Set<String>> = manager.concurrentCameraIds
        if (combinations.isEmpty()) return false
        return combinations.any { it.contains(idA) && it.contains(idB) }
    }
}
