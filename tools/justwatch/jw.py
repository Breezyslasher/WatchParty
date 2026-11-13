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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("titles", nargs="+")
    ap.add_argument("--country", default="US")
    ap.add_argument("--kind", default="MOVIE", choices=["MOVIE", "SHOW"])
    args = ap.parse_args()

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
