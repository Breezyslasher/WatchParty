package io.github.breezyslasher.watchparty

import android.service.notification.NotificationListenerService

/**
 * Never reads notifications. Android only hands an app other apps' media
 * sessions (what's playing, pause/play/seek) if it has notification access,
 * and that access is granted to a listener service — this one.
 *
 * Grant it once with adb (TVs hide the settings screen):
 *   adb shell cmd notification allow_listener io.github.breezyslasher.watchparty/io.github.breezyslasher.watchparty.SessionAccessService
 */
class SessionAccessService : NotificationListenerService()
