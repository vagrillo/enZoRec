package it.agrilloit.turegistri.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Trova la migliore combinazione di camere registrabili simultaneamente.
 *
 * Da Android 11 (API 30, il minSdk di questa app) [CameraManager.getConcurrentCameraIds]
 * dichiara quali camere possono lavorare insieme: qualsiasi sottoinsieme del set
 * restituito è garantito simultaneo. Preferiamo sempre frontale + posteriore.
 */
object ConcurrentCameraDetector {

    enum class ComboKind { BACK_FRONT, BACK_BACK, SINGLE, NONE }

    data class Combo(
        val kind: ComboKind,
        /** ID camera in ordine: [A, B] (A = posteriore/principale, B = frontale/secondaria). */
        val ids: List<String>,
    )

    fun detect(manager: CameraManager): Combo {
        // Restituisce le COMBINAZIONI di camere simultanee: ogni Set<String> interno
        // contiene camere che possono lavorare insieme.
        val combinations: Set<Set<String>> = manager.concurrentCameraIds
        if (combinations.isEmpty()) {
            // Nessuna combinazione simultanea dichiarata: single camera.
            val first = pickFirstAvailable(manager)
            return if (first != null) Combo(ComboKind.SINGLE, listOf(first)) else Combo(ComboKind.NONE, emptyList())
        }

        val facingOf = { id: String ->
            runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            }.getOrNull()
        }

        // Preferisce una combinazione con posteriore+frontale, altrimenti la più grande.
        val chosen = combinations.firstOrNull { group ->
            val facings = group.mapNotNull { facingOf(it) }
            facings.contains(CameraCharacteristics.LENS_FACING_BACK) &&
                facings.contains(CameraCharacteristics.LENS_FACING_FRONT)
        } ?: combinations.maxByOrNull { it.size }
        ?: return Combo(ComboKind.NONE, emptyList())

        val back = chosen.filter { facingOf(it) == CameraCharacteristics.LENS_FACING_BACK }
        val front = chosen.filter { facingOf(it) == CameraCharacteristics.LENS_FACING_FRONT }

        return when {
            back.isNotEmpty() && front.isNotEmpty() ->
                Combo(ComboKind.BACK_FRONT, listOf(pickPrimary(manager, back), pickPrimary(manager, front)))

            back.size >= 2 ->
                Combo(ComboKind.BACK_BACK, listOf(pickPrimary(manager, back), pickSecondary(manager, back)))

            front.size >= 2 ->
                Combo(ComboKind.BACK_BACK, listOf(pickPrimary(manager, front), pickSecondary(manager, front)))

            chosen.size == 1 ->
                Combo(ComboKind.SINGLE, listOf(chosen.first()))

            else ->
                Combo(ComboKind.SINGLE, listOf(chosen.first()))
        }
    }

    /**
     * Costruisce una combinazione da ID espliciti scelti dall'utente.
     * Restituisce null se gli id non esistono o (per coppie) non sono simultanei.
     */
    fun fromIds(manager: CameraManager, ids: List<String>): Combo? {
        if (ids.isEmpty()) return null
        val available = manager.cameraIdList.toSet()
        if (ids.any { it !in available }) return null
        if (ids.size >= 2 && !canRunTogether(manager, ids[0], ids[1])) return null

        val facingOf = { id: String ->
            runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            }.getOrNull()
        }
        val kind = when {
            ids.size == 1 -> ComboKind.SINGLE
            facingOf(ids[0]) != facingOf(ids[1]) -> ComboKind.BACK_FRONT
            else -> ComboKind.BACK_BACK
        }
        return Combo(kind, ids.take(2))
    }

    /** Vero se le due camere possono lavorare contemporaneamente su questo device. */
    fun canRunTogether(manager: CameraManager, idA: String, idB: String): Boolean {
        if (idA == idB) return false
        val combinations: Set<Set<String>> = manager.concurrentCameraIds
        if (combinations.isEmpty()) return false
        return combinations.any { it.contains(idA) && it.contains(idB) }
    }

    private fun pickPrimary(manager: CameraManager, ids: List<String>): String =
        // La "primaria" è quella con il campo visivo più stretto (focale più lunga in mm equivalenti
        // è difficile da ricavare in modo affidabile: usiamo l'area del sensore attiva più grande
        // come proxy della camera principale).
        ids.maxByOrNull { activeAreaPixels(manager, it) } ?: ids.first()

    private fun pickSecondary(manager: CameraManager, ids: List<String>): String =
        ids.minByOrNull { activeAreaPixels(manager, it) } ?: ids.last()

    private fun activeAreaPixels(manager: CameraManager, id: String): Long {
        return runCatching {
            val size = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            size?.let { it.width.toLong() * it.height.toLong() } ?: 0L
        }.getOrDefault(0L)
    }

    private fun pickFirstAvailable(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val facing = runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            }.getOrNull()
            if (facing == CameraCharacteristics.LENS_FACING_BACK ||
                facing == CameraCharacteristics.LENS_FACING_FRONT
            ) {
                return id
            }
        }
        return manager.cameraIdList.firstOrNull()
    }
}
