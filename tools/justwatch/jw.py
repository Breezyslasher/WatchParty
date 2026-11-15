#!/usr/bin/env python3
"""Print each streaming service's JustWatch link for some titles.

Used to find which link format JustWatch gives for each service, so Watch
Party can turn a title (from a media session) into a link that starts it.
Same unofficial GraphQL API justwatch.com uses (and Yamtrack's
feat/justwatch-links branch). Sends only the titles.

    python3 jw.py [--country US] "Thunderbolts*" "Iron Man"
"""
import argparse
import json
import urllib.error
import urllib.request

API = "https://apis.justwatch.com/graphql"
HEADERS = {
    "Content-Type": "application/json",
    # JustWatch rejects default library user agents
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0",
    "Origin": "https://www.justwatch.com",
    "Referer": "https://www.justwatch.com/",
}

SEARCH = """query S($f: TitleFilter!, $c: Country!, $l: Language!, $first: Int!) {
  popularTitles(country: $c, filter: $f, first: $first, sortBy: POPULAR, sortRandomSeed: 0) {
    edges { node {
      id objectType
      content(country: $c, language: $l) { title originalReleaseYear runtime externalIds { tmdbId imdbId } }
      offers(country: $c, platform: WEB) {
        monetizationType presentationType standardWebURL
        package { packageId clearName technicalName }
      }
    } }
  }
}"""

# Not sure these fields exist; asked separately so a schema error can't hide
# the standard links.
DEEPLINKS = """query D($id: ID!, $c: Country!) {
  node(id: $id) { ... on MovieOrShowOrSeasonOrEpisode {
    offers(country: $c, platform: ANDROID_TV) {
      monetizationType standardWebURL deeplinkURL(platform: ANDROID_TV)
      package { clearName technicalName }
    }
  } }
}"""


def post(query, variables):
    body = json.dumps({"query": query, "variables": variables}).encode()
    req = urllib.request.Request(API, data=body, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        return {"errors": [{"message": f"HTTP {e.code}: {e.read()[:300]!r}"}]}


STREAMING = {"FLATRATE", "FREE", "ADS", "FAST"}

INTROSPECT = """{ platform: __type(name: "Platform") { enumValues { name } }
  offer: __type(name: "Offer") { fields { name args { name } } } }"""

PER_PLATFORM = """query P($id: ID!, $c: Country!, $p: Platform!) {
  node(id: $id) { ... on MovieOrShowOrSeasonOrEpisode {
    offers(country: $c, platform: $p) {
      monetizationType standardWebURL deeplinkURL(platform: $p)
      package { clearName technicalName }
    }
  } }
}"""


# Platform values the API accepted (US, 2026-11-14). Introspection is
# disabled, so "all" uses these. Rejected: TV_OS, TIZEN, TIZEN_OS, WEB_OS,
# SAMSUNG(_TV), LG(_TV), VIZIO, VIZIOOS, SMARTCAST, VIDAA, VIDAAOS, TITANOS,
# FOXXUM, ZEASN, WHALEOS, CHROMECAST, CHROMECASTOS, GOOGLE_TV, GOOGLETV,
# APPLE_TV, APPLETV, FIRE_OS, AMAZON_FIRE_TV, ROKU, IPHONE, IPAD, IPADOS,
# MAC_OS, MACOS, WINDOWS, WINDOWS10, LINUX, KAIOS, MOBILE, TV, SMART_TV,
# PS, PS4, PS5, PLAYSTATION4, XBOX_ONE, XBOXONE.
KNOWN_PLATFORMS = ["WEB", "ANDROID", "IOS", "ANDROID_TV", "FIRE_TV", "TVOS",
                   "TIZENOS", "WEBOS", "ROKU_OS", "XBOX", "PLAYSTATION"]


# Episode links, in two steps like Watch Party TV: a show's seasons, then one
# season's episodes with their offers. JustWatch's schema isn't published
# (introspection is off), so GraphQL errors are printed in full.
SEASONS = """query S($id: ID!, $c: Country!, $l: Language!) {
  node(id: $id) { ... on Show {
    seasons(sortDirection: ASC) { id content(country: $c, language: $l) { seasonNumber title } }
  } }
}"""

SEASON_EPISODES = """query E($id: ID!, $c: Country!, $l: Language!, $filter: OfferFilter!) {
  node(id: $id) { ... on Season {
    episodes(sortDirection: ASC) {
      id
      content(country: $c, language: $l) { episodeNumber title }
      web: offers(country: $c, platform: WEB, filter: $filter) {
        monetizationType presentationType standardWebURL deeplinkURL(platform: WEB)
        retailPrice(language: $l)
        package { packageId clearName technicalName icon(profile: S100, format: PNG) }
      }
      atv: offers(country: $c, platform: ANDROID_TV, filter: $filter) {
        monetizationType presentationType deeplinkURL(platform: ANDROID_TV)
        package { packageId clearName technicalName }
      }
    }
  } }
}"""


def _errors(data, what):
    if data.get("errors"):
        for err in data["errors"][:5]:
            print(f"   {what} error:", err.get("message", "")[:400])
        return True
    return False


def episodes(node_id, country, max_seasons, max_episodes):
    data = post(SEASONS, {"id": node_id, "c": country, "l": "en"})
    if _errors(data, "seasons"):
        return
    show = (data.get("data") or {}).get("node") or {}
    for season in (show.get("seasons") or [])[:max_seasons]:
        sc = season.get("content") or {}
        print(f"   -- season {sc.get('seasonNumber')} ({season.get('id')})")
        eps = post(SEASON_EPISODES, {"id": season["id"], "c": country, "l": "en",
                                     "filter": {"bestOnly": True}})
        if _errors(eps, "episodes"):
            continue
        for ep in (((eps.get("data") or {}).get("node") or {}).get("episodes") or [])[:max_episodes]:
            ec = ep.get("content") or {}
            print(f"      E{ec.get('episodeNumber')} {ec.get('title')!r} ({ep.get('id')})")
            seen = set()
            for kind in ("web", "atv"):
                for o in ep.get(kind) or []:
                    if o["monetizationType"] not in STREAMING:
                        continue
                    link = o.get("deeplinkURL")
                    key = (kind, o["package"]["technicalName"], link)
                    if key in seen:
                        continue
                    seen.add(key)
                    extra = (f" web={o.get('standardWebURL')} pkg={o['package'].get('packageId')}"
                             f" {o.get('presentationType')} price={o.get('retailPrice')}" if kind == "web" else "")
                    print(f"        {kind.upper():<3} {o['package']['clearName']:<22} deeplink={link}{extra}")


# Title page fields, for Watch Party's search without Yamtrack. Each part is
# its own query, so a field the schema doesn't have can't hide the others.
DETAIL_PARTS = {
    "core": "content(country: $c, language: $l) { title originalReleaseYear originalReleaseDate runtime shortDescription posterUrl }",
    "genres": "content(country: $c, language: $l) { genres { shortName translation(language: $l) } }",
    "scoring": "content(country: $c, language: $l) { scoring { imdbScore imdbVotes tmdbScore tmdbPopularity } }",
    "credits": "content(country: $c, language: $l) { credits { role name characterName personId } }",
    "certification": "content(country: $c, language: $l) { ageCertification }",
    "seasons": "... on Show { totalSeasonCount }",
    "similar": "similarTitlesV2(country: $c, first: 5) { edges { node { id objectType content(country: $c, language: $l) { title originalReleaseYear posterUrl externalIds { tmdbId } } } } }",
}


def detail(node_id, country):
    for name, fields in DETAIL_PARTS.items():
        query = ("query T($id: ID!, $c: Country!, $l: Language!) { node(id: $id) { "
                 "... on MovieOrShow { " + fields + " } } }")
        data = post(query, {"id": node_id, "c": country, "l": "en"})
        if _errors(data, name):
            continue
        print(f"   [{name}]", json.dumps((data.get("data") or {}).get("node"), ensure_ascii=False)[:1500])


def introspect():
    data = post(INTROSPECT, {})
    if data.get("errors"):
        print("introspection:", data["errors"][0].get("message"), "- using", ", ".join(KNOWN_PLATFORMS))
        return KNOWN_PLATFORMS
    plats = [v["name"] for v in (data["data"]["platform"] or {}).get("enumValues") or []]
    print("Platform values:", ", ".join(plats))
    for f in (data["data"]["offer"] or {}).get("fields") or []:
        if "link" in f["name"].lower() or "url" in f["name"].lower():
            print(f"Offer field: {f['name']}({', '.join(a['name'] for a in f['args'])})")
    return plats


def per_platform(node_id, country, platforms):
    for plat in platforms:
        data = post(PER_PLATFORM, {"id": node_id, "c": country, "p": plat})
        if data.get("errors"):
            print(f"   [{plat}] error: {data['errors'][0].get('message', '')[:140]}")
            continue
        offers = ((data.get("data") or {}).get("node") or {}).get("offers") or []
        seen = set()
        shown = 0
        for o in offers:
            if o["monetizationType"] not in STREAMING:
                continue
            key = (o["package"]["technicalName"], o.get("deeplinkURL"))
            if key in seen:
                continue
            seen.add(key)
            shown += 1
            print(f"   [{plat}] {o['package']['clearName']:<24} deeplink={o.get('deeplinkURL')}")
        if not shown:
            print(f"   [{plat}] (no streaming offers)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("titles", nargs="+")
    ap.add_argument("--country", default="US")
    ap.add_argument("--kind", default="MOVIE", choices=["MOVIE", "SHOW"])
    ap.add_argument("--episodes", default="",
                    help='for shows: "SEASONS,EPISODES" to list, e.g. "1,2" (season 1, first 2 episodes)')
    ap.add_argument("--detail", action="store_true", help="print title page fields (description, genres, ...)")
    ap.add_argument("--platforms", default="",
                    help='comma list, or "all" to try every Platform value the API reports')
    args = ap.parse_args()

    platforms = [p for p in args.platforms.split(",") if p]
    if platforms == ["all"]:
        platforms = introspect()

    for title in args.titles:
        print(f"\n### search: {title!r} ({args.kind}, {args.country})")
        data = post(SEARCH, {"f": {"searchQuery": title, "objectTypes": [args.kind]},
                             "c": args.country, "l": "en", "first": 3})
        if data.get("errors"):
            print("  error:", data["errors"][0].get("message"))
            continue
        edges = data["data"]["popularTitles"]["edges"]
        if not edges:
            print("  no results")
            continue
        for edge in edges[:2]:
            node = edge["node"]
            c = node["content"]
            ids = c.get("externalIds") or {}
            print(f"== {c['title']} ({c.get('originalReleaseYear')}) runtime={c.get('runtime')}min "
                  f"tmdb={ids.get('tmdbId')} imdb={ids.get('imdbId')} jw={node['id']}")
            if args.detail:
                detail(node["id"], args.country)
                continue
            seen = set()
            for o in node["offers"]:
                if o["monetizationType"] not in STREAMING:
                    continue
                key = (o["package"]["technicalName"], o["standardWebURL"])
                if key in seen:
                    continue
                seen.add(key)
                print(f"   WEB  {o['package']['clearName']:<26} [{o['package']['technicalName']}] "
                      f"{o['monetizationType']:<8} {o['standardWebURL']}")
            if args.episodes and node.get("objectType") == "SHOW":
                seasons_n, episodes_n = (int(x) for x in args.episodes.split(","))
                episodes(node["id"], args.country, seasons_n, episodes_n)
                continue
            if platforms:
                per_platform(node["id"], args.country, platforms)
                continue
            deep = post(DEEPLINKS, {"id": node["id"], "c": args.country})
            if deep.get("errors"):
                print("   ANDROID_TV deeplinks: n/a —", deep["errors"][0].get("message", "")[:160])
                continue
            for o in ((deep.get("data") or {}).get("node") or {}).get("offers") or []:
                if o["monetizationType"] in STREAMING:
                    print(f"   ATV  {o['package']['clearName']:<26} deeplink={o.get('deeplinkURL')} "
                          f"web={o.get('standardWebURL')}")


if __name__ == "__main__":
    main()
