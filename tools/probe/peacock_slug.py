#!/usr/bin/env python3
"""Does a Peacock web link name the slug Paco8's plugin.video.skyott plays?

For Watch Party's Kodi bridge. The addon plays an on-demand title as
plugin://plugin.video.skyott/?action=play&slug=<slug>, looking the slug up
with Peacock's catalogue API (adapter-calypso/v3/query/node?slug=). Peacock's
web links are peacocktv.com/watch/asset/<path>. This takes Peacock's links
from JustWatch (what Watch Party shares), tries <path> as a slug with the
addon's own SkyShowtime client (signed out), and prints the slugs the
addon's search returns for comparison.

    python3 peacock_slug.py --addon plugin.video.skyott "The Grinch:MOVIE" "The Office:SHOW"
"""
import argparse
import json
import os
import re
import sys
import tempfile
import types

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'justwatch'))
import jw  # noqa: E402  (tools/justwatch/jw.py: the same JustWatch queries)


def stub_kodi():
    xbmc = types.ModuleType('xbmc')
    xbmc.LOGDEBUG, xbmc.LOGINFO, xbmc.LOGWARNING = 0, 1, 2
    xbmc.log = lambda *a, **k: None
    xbmcaddon = types.ModuleType('xbmcaddon')

    class Addon:
        def __init__(self, *a):
            pass

        def getAddonInfo(self, key):
            return 'plugin.video.skyott'

        def getSetting(self, key):
            return ''

    xbmcaddon.Addon = Addon
    sys.modules['xbmc'] = xbmc
    sys.modules['xbmcaddon'] = xbmcaddon


def peacock_links(title, kind):
    """Peacock's web links for a movie, or a show's first season's first episodes."""
    data = jw.post(jw.SEARCH, {'f': {'searchQuery': title, 'objectTypes': [kind]}, 'c': 'US', 'l': 'en', 'first': 3})
    edges = ((data.get('data') or {}).get('popularTitles') or {}).get('edges') or []
    if not edges:
        return []
    node = edges[0]['node']
    if kind == 'MOVIE':
        return [o['standardWebURL'] for o in node['offers']
                if 'peacock' in o['package']['technicalName'] and o.get('standardWebURL')][:1]
    seasons = jw.post(jw.SEASONS, {'id': node['id'], 'c': 'US', 'l': 'en'})
    season = (((seasons.get('data') or {}).get('node') or {}).get('seasons') or [None])[0]
    if not season:
        return []
    eps = jw.post(jw.SEASON_EPISODES, {'id': season['id'], 'c': 'US', 'l': 'en', 'filter': {'bestOnly': True}})
    out = []
    for ep in (((eps.get('data') or {}).get('node') or {}).get('episodes') or [])[:2]:
        for o in ep.get('web') or []:
            if 'peacock' in o['package']['technicalName'] and o.get('standardWebURL'):
                out.append(o['standardWebURL'])
                break
    return out


def show(label, item):
    keep = {k: item.get(k) for k in ('type', 'title', 'slug', 'content_id', 'provider_variant_id', 'id')
            if item and k in item}
    print('   %s: %s' % (label, json.dumps(keep, ensure_ascii=False)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('titles', nargs='+', help='"Title:MOVIE" or "Title:SHOW"')
    ap.add_argument('--addon', required=True, help='path to plugin.video.skyott')
    args = ap.parse_args()

    stub_kodi()
    sys.path.insert(0, args.addon)
    from resources.lib.sky import SkyShowtime

    sky = SkyShowtime(tempfile.mkdtemp() + '/', platform='peacocktv')
    for entry in args.titles:
        title, _, kind = entry.rpartition(':')
        print('\n### %s (%s)' % (title, kind))
        for link in peacock_links(title, kind):
            m = re.match(r'^https?://(?:www\.)?peacocktv\.com/watch/asset(/[^?#]+)', link)
            print(' JustWatch link:', link)
            if not m:
                print('   (not a /watch/asset/ link)')
                continue
            candidate = m.group(1)
            try:
                info = sky.get_video_info(candidate)
                show('get_video_info(%r)' % candidate, info)
            except Exception as e:
                print('   get_video_info(%r) failed: %r' % (candidate, e))
            # what the apps share is Peacock's play deeplink: only this id (pvid)
            pvid = candidate.rstrip('/').split('/')[-1]
            try:
                show('get_video_info_uuid(%r) [the pvid alone]' % pvid, sky.get_video_info_uuid(pvid))
            except Exception as e:
                print('   get_video_info_uuid(%r) failed: %r' % (pvid, e))
        try:
            found = sky.search(title) or []
            for item in found[:4]:
                show('search', item)
        except Exception as e:
            print('   search failed: %r' % e)


if __name__ == '__main__':
    main()
