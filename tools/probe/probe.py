#!/usr/bin/env python3
"""Fetch public pages and print what matches a pattern, with context.

For checking what a streaming service's public pages say about a link
(e.g. which HBO Max edition a JustWatch link opens) from GitHub Actions,
when the dev sandbox can't reach the site. Sends no account details.

    python3 probe.py --pattern 'ASL|video/watch' URL [URL ...]
"""
import argparse
import re
import urllib.error
import urllib.request

UA = "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.8"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.geturl(), resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, url, e.read().decode("utf-8", "replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("urls", nargs="+")
    ap.add_argument("--pattern", required=True, help="regular expression, case-insensitive")
    ap.add_argument("--context", type=int, default=120, help="characters around each match")
    ap.add_argument("--max", type=int, default=60, help="matches per page")
    args = ap.parse_args()
    rx = re.compile(args.pattern, re.I)
    for url in args.urls:
        status, final, body = fetch(url)
        print(f"\n### {url}\n    status {status}, final {final}, {len(body)} chars")
        seen = set()
        shown = 0
        for m in rx.finditer(body):
            a, b = max(0, m.start() - args.context), min(len(body), m.end() + args.context)
            snippet = " ".join(body[a:b].split())
            if snippet in seen:
                continue
            seen.add(snippet)
            print(f"  [{m.group(0)}] …{snippet}…")
            shown += 1
            if shown >= args.max:
                print("  (more matches cut)")
                break
        if not shown:
            print("  no matches")


if __name__ == "__main__":
    main()
