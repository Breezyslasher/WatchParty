#!/usr/bin/env python3
"""Do Paramount+ and Apple TV web links work without the title's name?

For Watch Party's Kodi bridge. Kodi's addons play these titles by id alone
(slyguy.paramount.plus: video_id=<contentId>; plugin.video.appletv:
item_id=<umc id>), but the web links JustWatch gives name the title too:
paramountplus.com/movies/<name>/<id>, /shows/<show>/video/<id>/<name>,
tv.apple.com/us/movie/<name>/<umc id>, /us/episode/<name>/<umc id>?showId=….
This takes those links from JustWatch, rebuilds each without the name (and
for Apple TV episodes, without showId), fetches every form signed out and
prints what the site does: status, where it redirects, the page's canonical
link and title, and whether the page mentions the id.

    python3 nameless_links.py "Top Gun: Maverick:MOVIE:paramount" "Severance:SHOW:apple"

Result (2026-11-16, signed out): neither needs the name. Paramount+'s own
links have none: JustWatch's /movies/<name>/<id> and
/shows/<show>/video/<id>/<name> 301 to /movies/video/<id>/ and
/shows/video/<id>/ (canonical and og:url too), and /shows/video/<a movie's
id>/ 301s on to /movies/video/<id>/; /movies/<id>/ and /shows/x/video/<id>/
are 404s. Apple TV's tv.apple.com/[us/]movie|episode/<id> (and /x/<id>) 301
to the full link, an episode's with its showId (playableId only if given);
the wrong kind (/episode/<a movie's id>) is a 404. (Top Gun: Maverick, Tulsa
King S1 E1-2, Wolfs, Severance S1 E1-2.)
"""
import os
import re
import sys
from urllib.parse import urlsplit, parse_qsl, urlencode

import requests

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'justwatch'))
import jw  # noqa: E402  (tools/justwatch/jw.py: the same JustWatch queries)

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36')
SITES = {'paramount': 'paramountplus.com', 'apple': 'tv.apple.com'}


def links(title, kind, site):
    """The site's web links: a movie's, or a show's first season's first two episodes'."""
    host = SITES[site]
    data = jw.post(jw.SEARCH, {'f': {'searchQuery': title, 'objectTypes': [kind]}, 'c': 'US', 'l': 'en', 'first': 3})
    edges = ((data.get('data') or {}).get('popularTitles') or {}).get('edges') or []
    if not edges:
        return []
    node = edges[0]['node']
    if kind == 'MOVIE':
        return list(dict.fromkeys(o['standardWebURL'] for o in node['offers']
                                  if host in (o.get('standardWebURL') or '')))[:1]
    seasons = jw.post(jw.SEASONS, {'id': node['id'], 'c': 'US', 'l': 'en'})
    season = (((seasons.get('data') or {}).get('node') or {}).get('seasons') or [None])[0]
    if not season:
        return []
    eps = jw.post(jw.SEASON_EPISODES, {'id': season['id'], 'c': 'US', 'l': 'en', 'filter': {'bestOnly': True}})
    out = []
    for ep in (((eps.get('data') or {}).get('node') or {}).get('episodes') or [])[:2]:
        for o in ep.get('web') or []:
            if host in (o.get('standardWebURL') or ''):
                out.append(o['standardWebURL'])
                break
    return out


def variants(link):
    """(what, link, id) for the link and its nameless forms."""
    u = urlsplit(link)
    base = '%s://%s' % (u.scheme, u.netloc)
    p = u.path
    m = re.match(r'^(/(?:[a-z]{2}/)?)movies/([^/]+)/([A-Za-z0-9_-]+)/?$', p)
    if 'paramountplus' in u.netloc and m:
        pre, _, cid = m.groups()
        return cid, [('as given', link),
                     ('movies/video/<id>', base + pre + 'movies/video/%s/' % cid),
                     ('movies/<id>', base + pre + 'movies/%s/' % cid),
                     ('movies/x/<id>', base + pre + 'movies/x/%s/' % cid),
                     ('shows/video/<id> (a movie as an episode)', base + pre + 'shows/video/%s/' % cid)]
    m = re.match(r'^(/(?:[a-z]{2}/)?)shows/([^/]+)/video/([A-Za-z0-9_-]+)(/.*)?$', p)
    if 'paramountplus' in u.netloc and m:
        pre, _, cid, _ = m.groups()
        return cid, [('as given', link),
                     ('shows/<show>/video/<id>/ (no episode name)', base + pre + 'shows/%s/video/%s/' % (m.group(2), cid)),
                     ('shows/video/<id>', base + pre + 'shows/video/%s/' % cid),
                     ('shows/x/video/<id>', base + pre + 'shows/x/video/%s/' % cid),
                     ('movies/video/<id>', base + pre + 'movies/video/%s/' % cid)]
    m = re.match(r'^/([a-z]{2})/(movie|episode)/([^/]+)/(umc\.[A-Za-z0-9.]+)/?$', p)
    if u.netloc == 'tv.apple.com' and m:
        cc, kind, _, umc = m.groups()
        q = parse_qsl(u.query)
        keep = urlencode([(k, v) for k, v in q if k in ('showId', 'playableId')])
        out = [('as given', link),
               ('<kind>/<id>', '%s/%s/%s/%s' % (base, cc, kind, umc)),
               ('<kind>/x/<id>', '%s/%s/%s/x/%s' % (base, cc, kind, umc)),
               ('no country', '%s/%s/%s' % (base, kind, umc)),
               ('other kind', '%s/%s/%s/%s' % (base, cc, 'episode' if kind == 'movie' else 'movie', umc))]
        if kind == 'episode':
            out.insert(1, ('name kept, no showId', '%s/%s/%s/%s/%s' % (base, cc, kind, m.group(3), umc)))
            if keep:
                out.insert(2, ('no name, showId kept', '%s/%s/%s/%s?%s' % (base, cc, kind, umc, keep)))
        return umc, out
    return None, [('as given (no rule)', link)]


def fetch(link, cid):
    try:
        r = requests.get(link, headers={'User-Agent': UA, 'Accept-Language': 'en-US'}, timeout=20, allow_redirects=True)
    except Exception as e:
        return 'failed: %r' % e
    html = r.text
    canon = re.search(r'<link[^>]+rel="canonical"[^>]+href="([^"]+)"', html) or \
        re.search(r'<link[^>]+href="([^"]+)"[^>]+rel="canonical"', html)
    og = re.search(r'<meta[^>]+property="og:url"[^>]+content="([^"]+)"', html)
    title = re.search(r'<title[^>]*>([^<]*)</title>', html)
    hops = ' -> '.join('%d %s' % (h.status_code, h.headers.get('location', '')) for h in r.history)
    return '%d%s | final %s | canonical %s | og:url %s | title %r | mentions id: %s | %d bytes' % (
        r.status_code, (' (via %s)' % hops) if hops else '', r.url,
        canon.group(1) if canon else '-', og.group(1) if og else '-',
        (title.group(1).strip()[:80] if title else '-'), cid in html if cid else '-', len(html))


def main():
    for entry in sys.argv[1:]:
        title, kind, site = entry.rsplit(':', 2)
        print('\n### %s (%s, %s)' % (title, kind, site))
        found = links(title, kind, site)
        if not found:
            print(' no %s link on JustWatch' % SITES[site])
        for link in found:
            cid, forms = variants(link)
            print(' JustWatch link:', link)
            for what, url in forms:
                print('   %-45s %s\n     %s' % (what, url, fetch(url, cid)))


if __name__ == '__main__':
    main()
