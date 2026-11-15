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

    /**
     * The package that should open [link]: a known service, else the app on
     * this device that opens it, else an installed app named like the service
     * ([serviceName], else the link's site — some apps, like Starz on phones,
     * don't claim their website's links), else JustWatch's service name.
     */
    fun packageFor(context: Context, link: String, technicalName: String? = null, serviceName: String? = null): String? =
        MediaLink.normalize(link)?.knownPackage
            ?: appFor(context, link)
            ?: serviceName?.let { appByName(context, it) }
            ?: serviceOfLink(link)?.let { appByName(context, it) }
            ?: technicalName?.let { PACKAGES[it] }

    /** Whether [pkg] says it opens [link] (else it can only be opened at its home screen). */
    fun claimsLink(context: Context, link: String, pkg: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg)
        return context.packageManager.queryIntentActivities(view, 0).isNotEmpty()
    }

    /** An installed app (one with a launcher icon) whose name matches [service]. */
    fun appByName(context: Context, service: String): String? {
        val pm = context.packageManager
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (info in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName) continue
                if (sameService(info.loadLabel(pm).toString(), service)) return pkg
            }
        }
        return null
    }

    /** "STARZ" and "Starz", "Paramount+" and "Paramount Plus", "Peacock" and "peacocktv":
     *  the same service. Short names must match exactly, so "Max" isn't in every name. */
    fun sameService(appLabel: String, service: String): Boolean {
        val a = squash(appLabel)
        val b = squash(service)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= 5 && long.startsWith(short)
    }

    private fun squash(s: String) = s.lowercase(java.util.Locale.ROOT).replace("+", "plus").filter { it.isLetterOrDigit() }

    /** The site's name in a link: "https://www.starz.com/us/en/play/1" → "starz". */
    fun serviceOfLink(link: String): String? {
        val host = try {
            Uri.parse(link).host
        } catch (e: Exception) {
            null
        } ?: return null
        val parts = host.split('.').filter { it.isNotEmpty() }
        return if (parts.size >= 2) parts[parts.size - 2] else null
    }

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
