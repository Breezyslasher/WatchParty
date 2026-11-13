package dev.watchparty.tv

import android.service.notification.NotificationListenerService

/**
 * Never reads notifications. Android only hands an app other apps' media
 * sessions (what's playing, pause/play/seek) if it has notification access,
 * and that access is granted to a listener service — this one.
 *
 * Grant it once with adb (TVs hide the settings screen):
 *   adb shell cmd notification allow_listener dev.watchparty.tv/dev.watchparty.tv.SessionAccessService
 */
class SessionAccessService : NotificationListenerService()
