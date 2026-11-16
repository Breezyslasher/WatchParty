#!/usr/bin/env python3
"""Which streaming services JustWatch offers, and the link each one gets.

For finding the services Watch Party hasn't tried yet: looks up movies and
shows (a show's first episodes too), collects every streaming offer (the
monetization types Watch Party offers: FLATRATE, FREE, ADS, FAST) and prints
one line per service — its technical name, how many titles it had, its
monetization types, and a sample WEB link (the link Watch Party shares), for
a movie and for an episode. Same queries as jw.py.

    python3 services.py --movies "Oppenheimer|Barbie" --shows "The Office|Bluey"
"""
import argparse
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(__file__))
import jw  # noqa: E402

TITLE_OFFERS = """query T($f: TitleFilter!, $c: Country!, $l: Language!, $first: Int!) {
  popularTitles(country: $c, filter: $f, first: $first, sortBy: POPULAR, sortRandomSeed: 0) {
    edges { node { id objectType content(country: $c, language: $l) { title }
      offers(country: $c, platform: WEB) {
        monetizationType deeplinkURL(platform: WEB) standardWebURL
        package { clearName technicalName }
      } } }
  }
}"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--movies", default="")
    ap.add_argument("--shows", default="")
    ap.add_argument("--country", default="US")
    args = ap.parse_args()

    seen = defaultdict(lambda: {"name": "", "titles": set(), "types": set(), "movie": None, "episode": None})

    def add(o, title, kind):
        if o["monetizationType"] not in jw.STREAMING:
            return
        s = seen[o["package"]["technicalName"]]
        s["name"] = o["package"]["clearName"]
        s["titles"].add(title)
        s["types"].add(o["monetizationType"])
        link = o.get("deeplinkURL") or o.get("standardWebURL")
        if s[kind] is None and link:
            s[kind] = link

    for kind, raw in (("MOVIE", args.movies), ("SHOW", args.shows)):
        for title in [t.strip() for t in raw.split("|") if t.strip()]:
            data = jw.post(TITLE_OFFERS, {"f": {"searchQuery": title, "objectTypes": [kind]},
                                          "c": args.country, "l": "en", "first": 1})
            edges = ((data.get("data") or {}).get("popularTitles") or {}).get("edges") or []
            if not edges:
                print(f"(nothing for {title!r})")
                continue
            node = edges[0]["node"]
            name = (node.get("content") or {}).get("title") or title
            if kind == "MOVIE":
                for o in node.get("offers") or []:
                    add(o, name, "movie")
                continue
            seasons = jw.post(jw.SEASONS, {"id": node["id"], "c": args.country, "l": "en"})
            season = ((((seasons.get("data") or {}).get("node") or {}).get("seasons")) or [None])[0]
            if not season:
                continue
            eps = jw.post(jw.SEASON_EPISODES, {"id": season["id"], "c": args.country, "l": "en",
                                               "filter": {"bestOnly": True}})
            for ep in ((((eps.get("data") or {}).get("node") or {}).get("episodes")) or [])[:2]:
                for o in ep.get("web") or []:
                    add(o, name, "episode")

    print(f"\n{len(seen)} services, by how many titles had them:")
    for tech, s in sorted(seen.items(), key=lambda kv: (-len(kv[1]["titles"]), kv[0])):
        print(f"\n{s['name']} [{tech}] titles={len(s['titles'])} types={','.join(sorted(s['types']))}")
        if s["movie"]:
            print(f"    movie:   {s['movie']}")
        if s["episode"]:
            print(f"    episode: {s['episode']}")


if __name__ == "__main__":
    main()
