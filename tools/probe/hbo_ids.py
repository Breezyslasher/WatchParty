#!/usr/bin/env python3
"""Do HBO Max's public pages give the ids SlyGuy's slyguy.max plays?

For Watch Party's Kodi bridge. slyguy.max plays ?_=play&edit_id=<edit id>
(episodes, from its own listings) or ?_=play&id=<show id> (movies: the
addon asks the API for the show's active video). Watch Party's links are
play.hbomax.com/video/watch/<video id>, from JustWatch. This takes
JustWatch's HBO Max links, fetches HBO Max's public pages for them (signed
out) and prints every edit / show / movie id they mention, with context.

    python3 hbo_ids.py "Dune: Part Two:MOVIE" "The White Lotus:SHOW"
"""
import os
import re
import sys

import requests

sys.path.insert(0, os.path.dirname(__file__))
from nameless_links import UA  # noqa: E402

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'justwatch'))
import jw  # noqa: E402

UUID = r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'


def hbo_links(title, kind):
    data = jw.post(jw.SEARCH, {'f': {'searchQuery': title, 'objectTypes': [kind]}, 'c': 'US', 'l': 'en', 'first': 3})
    edges = ((data.get('data') or {}).get('popularTitles') or {}).get('edges') or []
    if not edges:
        return []
    node = edges[0]['node']
    pick = lambda offers: [o['standardWebURL'] for o in offers if re.search(r'(hbo)?max\.com', o.get('standardWebURL') or '')]
    if kind == 'MOVIE':
        return list(dict.fromkeys(pick(node['offers'])))[:1]
    seasons = jw.post(jw.SEASONS, {'id': node['id'], 'c': 'US', 'l': 'en'})
    season = (((seasons.get('data') or {}).get('node') or {}).get('seasons') or [None])[0]
    eps = jw.post(jw.SEASON_EPISODES, {'id': season['id'], 'c': 'US', 'l': 'en', 'filter': {'bestOnly': True}})
    out = []
    for ep in (((eps.get('data') or {}).get('node') or {}).get('episodes') or [])[:2]:
        out += pick(ep.get('web') or [])[:1]
    return out


def get(url):
    try:
        r = requests.get(url, headers={'User-Agent': UA, 'Accept-Language': 'en-US'}, timeout=20)
        return r.status_code, r.url, r.text
    except Exception as e:
        return 0, url, repr(e)


def report(url, video_id):
    status, final, html = get(url)
    print('   GET %s\n     %d, final %s, %d chars' % (url, status, final, len(html)))
    keys = {}
    for m in re.finditer(r'"(\w*(?:edit|Edit)\w*)"\s*:\s*("[^"]{0,80}"|\{[^{}]{0,200}\}|[\w.]+)', html):
        keys.setdefault(m.group(1), set()).add(m.group(2)[:120])
    for k, vals in sorted(keys.items()):
        print('     key %s: %s' % (k, ' | '.join(sorted(vals)[:4])))
    paths = sorted(set(re.findall(r'/(?:show|movies?|video/watch|feature|standalone)/[^"\s\\]{0,80}', html)))
    print('     paths: %s' % ' '.join(paths[:15]))
    for i, m in enumerate(re.finditer(re.escape(video_id), html)):
        if i >= 3:
            break
        print('     near id: ...%s...' % html[max(0, m.start() - 200):m.end() + 200].replace('\n', ' '))


def main():
    for entry in sys.argv[1:]:
        title, kind = entry.rsplit(':', 1)
        print('\n### %s (%s)' % (title, kind))
        for link in hbo_links(title, kind):
            print(' JustWatch link:', link)
            m = re.search(r'/video/watch/(%s)' % UUID, link)
            vid = m.group(1) if m else link.rstrip('/').split('/')[-1]
            for url in (link, 'https://www.hbomax.com/video/watch/' + vid, 'https://play.hbomax.com/video/watch/' + vid):
                report(url, vid)


if __name__ == '__main__':
    main()
