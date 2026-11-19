#!/usr/bin/env python3
"""Do JustWatch's Plex links carry the id Plex's free catalogue plays by?

For Watch Party's Kodi bridge. Breezyslasher's Kodi addon for Plex's free
on-demand catalogue (Kodi-Addons branch plugin.video.plexondemand) plays
?action=play&rk=<ratingKey>, looked up at vod.provider.plex.tv/library/
metadata/<ratingKey>. JustWatch's Plex links name the title by slug and carry
a 24-hex id in utm_content:

  watch.plex.tv/show/forensic-files/season/1/episode/1?autoplay=1&utm_content=5d9c0c4a02391c001f5addaa&…

This gets an anonymous Plex token as the addon does (POST plex.tv/api/v2/
users/anonymous as Plex Web) and asks the catalogue for each link's id.

    python3 plex_vod.py "https://watch.plex.tv/show/…?utm_content=…" …
"""
import sys
import uuid
from urllib.parse import parse_qs, urlsplit

import requests

HEADERS = {
    'Accept': 'application/json',
    'X-Plex-Client-Identifier': str(uuid.uuid4()),
    'X-Plex-Product': 'Plex Web',          # the addon's lib/auth.py: other products get 404
    'X-Plex-Version': '4.145.1',
    'X-Plex-Platform': 'Chrome',
    'X-Plex-Platform-Version': '131.0',
    'X-Plex-Device': 'Linux',
    'X-Plex-Device-Name': 'Watch Party probe',
    'X-Plex-Language': 'en',
}


def main(links):
    r = requests.post('https://plex.tv/api/v2/users/anonymous', headers=HEADERS, timeout=20)
    print('anonymous token:', r.status_code)
    token = r.json().get('authToken')
    if not token:
        print(r.text[:300])
        return
    h = dict(HEADERS, **{'X-Plex-Token': token})
    for link in links:
        print('\n###', link)
        rk = (parse_qs(urlsplit(link).query).get('utm_content') or [None])[0]
        print('   utm_content:', rk, '| path:', urlsplit(link).path)
        if not rk:
            continue
        r = requests.get('https://vod.provider.plex.tv/library/metadata/%s' % rk, headers=h, timeout=25)
        print('   metadata:', r.status_code)
        if r.status_code != 200:
            print('  ', r.text[:300])
            continue
        for m in (r.json().get('MediaContainer') or {}).get('Metadata') or []:
            print('   ratingKey=%s type=%s title=%r show=%r season=%s episode=%s year=%s slug=%s media=%s drm=%s' % (
                m.get('ratingKey'), m.get('type'), m.get('title'), m.get('grandparentTitle'),
                m.get('parentIndex'), m.get('index'), m.get('year'), m.get('slug'),
                len(m.get('Media') or []), [x.get('drm') for x in m.get('Media') or []]))


if __name__ == '__main__':
    main(sys.argv[1:])
