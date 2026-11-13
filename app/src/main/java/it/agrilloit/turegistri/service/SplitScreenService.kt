package it.agrilloit.turegistri.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * Servizio accessibilità minimale: espone GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
 * per entrare in split screen da codice (l'unica via supportata dal sistema).
 * Va abilitato una volta: Impostazioni → Accessibilità → YouRec Split.
 */
class SplitScreenService : AccessibilityService() {

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        instance = this
        android.util.Log.d("YouRecSplit", "servizio accessibilità collegato, pid=${android.os.Process.myPid()}")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: SplitScreenService? = null

        fun isReady(context: Context): Boolean =
            instance != null || isAccessibilityEnabled(context)

        /**
         * Il servizio è elencato come abilitato ma non collegato (dopo un force-stop)?
         * Riaggancia riscrivendo enabled_accessibility_services (richiede
         * WRITE_SECURE_SETTINGS, concessa via adb una sola volta).
         */
        fun rebindIfNeeded(context: Context): Boolean {
            if (instance != null) return true
            val comp = android.content.ComponentName(
                context, SplitScreenService::class.java
            ).flattenToString()
            val current = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (!current.split(":").contains(comp)) return false
            // Toggle off/on: il sistema riaggancia il servizio nel processo corrente.
            runCatching {
                val without = current.split(":").filter { it != comp }.joinToString(":")
                android.provider.Settings.Secure.putString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    without
                )
                Thread.sleep(350)
                val with = if (without.isBlank()) comp else "$without:$comp"
                android.provider.Settings.Secure.putString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    with
                )
            }
            // Attende il riaggancio (fino a 3 s).
            var waited = 0
            while (instance == null && waited < 3000) {
                Thread.sleep(150)
                waited += 150
            }
            return instance != null
        }

        /** Attiva lo split screen sull'app in primo piano. */
        fun toggleSplit(): Boolean {
            val svc = instance
            if (svc == null) {
                android.util.Log.e("YouRecSplit", "toggleSplit: instance NULL (servizio non collegato)")
                return false
            }
            val globalOk = runCatching {
                svc.performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            }.getOrDefault(false)
            android.util.Log.d("YouRecSplit", "toggleSplit: performGlobalAction=$globalOk")
            if (globalOk) return true

            // OneUI non supporta l'azione globale: usa il gesto nativo Samsung
            // "Swipe for split screen" (due dita dal bordo inferiore verso l'alto).
            return splitViaGesture(svc)
        }

        /** Gesto due dita dal bordo inferiore (gesto OneUI per lo split). */
        private fun splitViaGesture(svc: SplitScreenService): Boolean {
            val metrics = svc.resources.displayMetrics
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            val startX1 = w * 0.40f
            val startX2 = w * 0.60f
            val yStart = h * 0.94f
            val yEnd = h * 0.45f
            val path1 = android.graphics.Path().apply {
                moveTo(startX1, yStart)
                lineTo(startX1, yEnd)
            }
            val path2 = android.graphics.Path().apply {
                moveTo(startX2, yStart)
                lineTo(startX2, yEnd)
            }
            val stroke1 = android.accessibilityservice.GestureDescription.StrokeDescription(
                path1, 0, 320
            )
            val stroke2 = android.accessibilityservice.GestureDescription.StrokeDescription(
                path2, 0, 320
            )
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(stroke1)
                .addStroke(stroke2)
                .build()
            val dispatched = svc.dispatchGesture(gesture, null, null)
            android.util.Log.d("YouRecSplit", "splitViaGesture: dispatch=$dispatched")
            return dispatched
        }

        private fun isAccessibilityEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            return am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_GENERIC
            ).any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        }
    }
}
