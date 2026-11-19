package io.github.breezyslasher.watchparty

/**
 * Kodi → Android: a Kodi member shares a library file (smb://…, a library
 * path) with the title's identity (engine.py's _identity). This device can't
 * open the file, so it finds the same title on JustWatch — by TMDB id, else
 * IMDb id, else title and year (an episode by its show's title, then the
 * season and episode) — and opens it on a streaming app installed here.
 *
 * The other way needs nothing here: Android's items from Browse carry the
 * identity too (RelayClient.appItem), and Kodi plays its library copy.
 *
 * The JustWatch calls are parameters so the choice can be unit-tested.
 */
object Bridge {

    /** Where to watch [identity]: the services with a link for that movie or episode. */
    fun offers(
        identity: Identity,
        find: (title: String, tmdbId: String?, objectType: String, imdbId: String?, year: Int?) -> JustWatch.Title?,
        titleOffers: (titleId: String) -> List<JustWatch.Offer>,
        seasons: (showId: String) -> List<JustWatch.Season>,
        episodes: (seasonId: String) -> List<JustWatch.Episode>,
    ): List<JustWatch.Offer> {
        if (identity.type == "movie") {
            val t = find(identity.title ?: return emptyList(), identity.ids["tmdb"], "MOVIE", identity.ids["imdb"], identity.year)
                ?: return emptyList()
            return JustWatch.discoveryPlayLinks(titleOffers(t.id))
        }
        // an episode's ids are the episode's, not the show's: find the show by its title, and
        // its show_tmdb / show_imdb and premiere year when sent (Kodi 2026.11.17.8, shows sharing a name)
        val show = find(identity.show ?: return emptyList(), identity.ids["show_tmdb"], "SHOW", identity.ids["show_imdb"],
            identity.year) ?: return emptyList()
        val season = seasons(show.id).firstOrNull { it.number == identity.season } ?: return emptyList()
        val list = episodes(season.id)
        val episode = list.firstOrNull { it.number == identity.episode } ?: return emptyList()
        // a link the next (or previous) episode shares is the show's page, not this episode
        val other = list.firstOrNull { it.number == episode.number + 1 } ?: list.firstOrNull { it.number == episode.number - 1 }
        val showLevel = if (other != null) JustWatch.sharedLinks(episode.offers, other.offers) else emptySet()
        return episode.offers.filter { it.link !in showLevel }
    }

    /** The first service (JustWatch's order) whose app [installedPackage] finds here, with that package;
     *  the service Kodi's [addon] plays first (AddonLinks.sameService); an add-on channel (in
     *  Prime Video, Apple TV) only when nothing else is here. */
    fun choose(offers: List<JustWatch.Offer>, addon: String? = null,
               installedPackage: (JustWatch.Offer) -> String?): Pair<JustWatch.Offer, String>? {
        val ordered = offers.sortedWith(compareBy({ !AddonLinks.sameService(it.link, addon) }, { JustWatch.isChannel(it) }))
        for (o in ordered) installedPackage(o)?.let { return o to it }
        return null
    }

    /** What to call the title in notes: "Thunderbolts*", "Andor S1 E1". */
    fun name(identity: Identity, label: String): String =
        label.ifBlank { if (identity.type == "movie") identity.title ?: "" else "${identity.show} S${identity.season} E${identity.episode}" }
}
