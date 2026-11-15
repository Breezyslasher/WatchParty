package dev.watchparty.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Which installed app opens a link, and app names. */
object Apps {

    /** Android TV packages of JustWatch services, for when this TV doesn't
     *  have the app (other TVs in the room may). */
    private val PACKAGES = mapOf(
        "disneyplus" to MediaLink.DISNEY_PACKAGE,
        "max" to MediaLink.HBO_PACKAGE,
        "hbomax" to MediaLink.HBO_PACKAGE,
        "netflix" to MediaLink.NETFLIX_TV_PACKAGE,
        "netflixbasicwithads" to MediaLink.NETFLIX_TV_PACKAGE,
        "appletvplus" to MediaLink.APPLE_TV_PACKAGE,
        "appletv" to MediaLink.APPLE_TV_PACKAGE,
        "amazonprime" to "com.amazon.amazonvideo.livingroom",
        "amazonprimevideo" to "com.amazon.amazonvideo.livingroom",
        "amazonprimevideowithads" to "com.amazon.amazonvideo.livingroom",
        "youtube" to "com.google.android.youtube.tv",
        "youtubefree" to "com.google.android.youtube.tv",
        "plex" to "com.plexapp.android",
        "crunchyroll" to MediaLink.CRUNCHYROLL_PACKAGE,
    )

    /** The package that should open [link]: a known service, else the app
     *  on this TV that opens it, else JustWatch's service name. */
    fun packageFor(context: Context, link: String, technicalName: String? = null): String? =
        MediaLink.normalize(link)?.knownPackage
            ?: appFor(context, link)
            ?: technicalName?.let { PACKAGES[it] }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** The app (not a browser) that opens [link] on this TV, if any. */
    fun appFor(context: Context, link: String): String? {
        val pm = context.packageManager
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
        val handlers = pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.distinct()
        // anything that also opens an arbitrary website is a browser
        val browsers = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_DEFAULT_ONLY,
        ).map { it.activityInfo.packageName }.toSet()
        return handlers.firstOrNull { it !in browsers && it != context.packageName }
    }

    fun name(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
