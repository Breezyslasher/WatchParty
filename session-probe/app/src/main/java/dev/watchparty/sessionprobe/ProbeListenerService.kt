package dev.watchparty.sessionprobe

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Android only hands an app the list of other apps'
 * media sessions (MediaSessionManager.getActiveSessions) when that app has
 * an enabled notification listener, so this service is the key, not a
 * feature. It never reads or posts notifications.
 */
class ProbeListenerService : NotificationListenerService()
