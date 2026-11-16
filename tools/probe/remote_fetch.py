#!/usr/bin/env python3
"""Does the relay's remote page search JustWatch through the relay?

For the remote (/remote, served by the relay, watchparty-relay/): a web page can't call JustWatch's API itself, so it
posts the request to the relay's /remote/fetch, which makes it. This runs
against a relay started from that code: joins a room, sends the remote's
search query (the extension's SEARCH) and HBO Max's ASL page through
/remote/fetch, and prints what came back.

    python3 remote_fetch.py http://127.0.0.1:8799
"""
import json
import sys
import urllib.request

SEARCH = """query S($f: TitleFilter!, $c: Country!, $l: Language!, $first: Int!) {
  popularTitles(country: $c, filter: $f, first: $first, sortBy: POPULAR, sortRandomSeed: 0) {
    edges { node { id objectType content(country: $c, language: $l) { title originalReleaseYear } } }
  }
}"""


def post(base, path, body):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return json.load(e)


def main():
    base = sys.argv[1]
    member = post(base, '/join', {'room': 'PROBE', 'name': 'probe'})['member_id']
    query = json.dumps({'query': SEARCH, 'variables': {
        'f': {'searchQuery': 'Thunderbolts', 'objectTypes': ['MOVIE']}, 'c': 'US', 'l': 'en', 'first': 5}})
    r = post(base, '/remote/fetch', {'room': 'PROBE', 'member_id': member, 'method': 'POST',
                                     'url': 'https://apis.justwatch.com/graphql', 'body': query})
    print('JustWatch via the relay: ok=%s status=%s' % (r.get('ok'), r.get('status')), r.get('error') or '')
    if r.get('ok'):
        data = json.loads(r['body'])
        for e in ((data.get('data') or {}).get('popularTitles') or {}).get('edges') or []:
            c = e['node']['content']
            print('   %s (%s)' % (c['title'], c['originalReleaseYear']))
        if data.get('errors'):
            print('   errors:', data['errors'])
    r = post(base, '/remote/fetch', {'room': 'PROBE', 'member_id': member, 'method': 'GET',
                                     'url': 'https://www.hbomax.com/accessibility/asl'})
    print('HBO Max ASL page via the relay: ok=%s status=%s, %d chars, mentions "(with ASL)": %s' % (
        r.get('ok'), r.get('status'), len(r.get('body') or ''), '(with ASL)' in (r.get('body') or '')))
    r = post(base, '/remote/fetch', {'room': 'PROBE', 'member_id': 'nobody', 'method': 'POST',
                                     'url': 'https://apis.justwatch.com/graphql', 'body': query})
    print('not a member:', r.get('error'))


if __name__ == '__main__':
    main()
