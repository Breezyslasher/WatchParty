#!/usr/bin/env python3
"""What Tubi's content API says about a single episode.

For Watch Party's Kodi bridge: a Tubi episode link (tubitv.com/tv-shows/<id>)
carries only the episode's id, while plugin.video.tubitv plays an episode as
<episode>|<series>|<season>. This checks whether /api/v3/content for an
episode id names its series and season (and whether it already has its
streams), using the addon's own TubiAuth and TubiApi - the same anonymous
device token and the same requests - with stand-ins for Kodi's modules.

    python3 tubi_episode.py --addon kodi-addons/plugin.video.tubitv "Forensic Files"

Result (2026-11-15, anonymous token): Forensic Files S1 E1, id 438034, asked
for alone returns detailed_type "episode", series_id "2914", episode_number
"1" and its own video_resources (hlsv6, hlsv3) - so an episode id alone is
playable; there's no season number field (the title reads "S01:E01 - ...").
"""
import argparse
import json
import os
import sys
import tempfile
import types


def install_kodi_stand_ins(profile):
    xbmc = types.ModuleType('xbmc')
    xbmc.LOGDEBUG, xbmc.LOGINFO, xbmc.LOGWARNING, xbmc.LOGERROR = 0, 1, 2, 3
    xbmc.log = lambda msg='', level=0: print('   [log]', msg) if level >= 2 else None
    xbmcvfs = types.ModuleType('xbmcvfs')
    xbmcvfs.translatePath = lambda p: p
    xbmcvfs.exists = os.path.exists
    xbmcvfs.mkdirs = lambda p: os.makedirs(p, exist_ok=True)
    sys.modules['xbmc'] = xbmc
    sys.modules['xbmcvfs'] = xbmcvfs

    class Addon:
        def getAddonInfo(self, key):
            return {'name': 'Tubi (probe)', 'profile': profile}.get(key, '')

        def getSetting(self, key):
            return ''   # no account: the anonymous device token

    return Addon()


def summary(item, keys):
    """The fields that matter, and every top-level key (values trimmed)."""
    out = {k: item.get(k) for k in keys if k in item}
    out['video_resources'] = [r.get('type') for r in item.get('video_resources') or []]
    out['all_keys'] = sorted(item.keys())
    return out


ID_KEYS = ['id', 'type', 'title', 'series_id', 'parent_id', 'season_id', 'season', 'season_number',
           'episode_number', 'episode', 'valid_series_id', 'detailed_type', 'needs_login', 'duration']


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('query', nargs='?', default='Forensic Files')
    ap.add_argument('--addon', required=True, help='path to plugin.video.tubitv')
    args = ap.parse_args()

    addon = install_kodi_stand_ins(tempfile.mkdtemp())
    sys.path.insert(0, os.path.join(args.addon, 'resources', 'lib'))
    from tubi_auth import TubiAuth
    from tubi_api import TubiApi

    auth = TubiAuth(addon)
    headers = {'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0',
               'Accept-Language': 'en-US'}
    auth.apply(headers)
    print('anonymous token:', 'yes' if headers.get('Authorization', '').startswith('Bearer ') else 'NO')
    api = TubiApi(headers, auth.deviceId)

    found = api.search(args.query)
    series = [c for c in found if c.get('type') in ('s', 'series')]
    print('search %r: %d results, %d series: %s' % (args.query, len(found), len(series),
          [(c.get('id'), c.get('title'), c.get('type')) for c in found[:6]]))
    if not series:
        return
    show = series[0]
    seasons = api.seasons(show['id'])
    print('series %s %r: seasons %s' % (show['id'], show.get('title'),
          [(s.get('season'), len(s.get('episodes') or [])) for s in seasons][:8]))
    first = next((s for s in seasons if s.get('episodes')), None)
    if not first:
        print('no season with episodes listed')
        return
    number = first.get('season')
    episodes = api.episodes(show['id'], number)
    print('season %s: %d episodes, first ids %s' % (number, len(episodes), [e.get('id') for e in episodes[:3]]))
    if not episodes:
        return
    episode = episodes[0]
    print('\n== the episode in its season payload (how the addon plays it now):')
    print(json.dumps(summary(episode, ID_KEYS), indent=1))

    print('\n== /api/v3/content for the episode id alone (what a tubitv.com/tv-shows/<id> link gives):')
    alone = api.content(episode['id'])
    print(json.dumps(summary(alone, ID_KEYS), indent=1))
    for key in ('series_id', 'parent_id', 'valid_series_id'):
        if alone.get(key):
            print('-> names its series as %s = %s' % (key, alone[key]))
    print('-> has streams of its own:', bool(alone.get('video_resources') or alone.get('url')))


if __name__ == '__main__':
    main()
