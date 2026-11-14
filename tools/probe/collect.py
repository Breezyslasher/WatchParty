#!/usr/bin/env python3
"""Find out whether streaming apps expose what they're playing.

Run on a computer with adb connected to the TV. Play titles in as many apps
as you like; for each one this captures what Android exposes about it —
media session, that app's own log lines, screen text, launch intents,
notifications, "Continue watching" rows — asks JustWatch for the title's ids
in each service, and reports where (if anywhere) those ids appear.

    python3 collect.py watch          # capture every new title you play
    python3 collect.py once           # capture what's playing now

Options: -s SERIAL (several devices), --country US, --settle 12 (seconds to
let a title load before capturing), --out probe-reports.

Only the playing app's data is collected; tokens, signatures and keys in
URLs are redacted. Only the title is sent to JustWatch.
"""
import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# ── adb ─────────────────────────────────────────────────────────────────

ADB = ["adb"]


def adb(*args, timeout=60):
    """stdout of an adb command ('' on failure)."""
    try:
        out = subprocess.run(ADB + list(args), capture_output=True, timeout=timeout)
        return out.stdout.decode("utf-8", "replace")
    except (subprocess.SubprocessError, OSError) as e:
        return f"<adb failed: {e}>"


def shell(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


# ── media sessions ──────────────────────────────────────────────────────

def parse_media_sessions(dump):
    """[{package, state, title, subtitle}] from `dumpsys media_session`."""
    sessions = []
    current = None
    for line in dump.splitlines():
        s = line.strip()
        m = re.match(r"package=(\S+)", s)
        if m:
            current = {"package": m.group(1), "state": None, "title": None, "subtitle": None}
            sessions.append(current)
            continue
        if current is None:
            continue
        m = re.search(r"state=PlaybackState \{state=(\d+)", s)
        if m:
            current["state"] = int(m.group(1))
        m = re.match(r"metadata:\s*size=\d+, description=(.*)$", s)
        if m:
            parts = [p.strip() for p in m.group(1).split(", ")]
            current["title"] = None if not parts or parts[0] == "null" else parts[0]
            if len(parts) > 1 and parts[1] != "null":
                current["subtitle"] = parts[1]
    return sessions


PLAYING = 3


def parse_probe_status(reply):
    """Session Probe's `status` reply: data="pkg "title": PLAYING at 1:23"."""
    m = re.search(r'data="(\S+) "(.*)": (\w+) at', reply)
    if not m:
        return None
    title = None if m.group(2) == "(no title)" else m.group(2)
    return {"package": m.group(1), "state": PLAYING if m.group(3) == "PLAYING" else 0,
            "title": title, "subtitle": None}


def playing_session():
    """The session that's playing (state 3), skipping our own apps. Falls
    back to Session Probe if this device's dumpsys format isn't understood."""
    for s in parse_media_sessions(shell("dumpsys media_session")):
        if s["state"] == PLAYING and not s["package"].startswith("dev.watchparty"):
            return s
    s = parse_probe_status(adb("shell", "am", "broadcast", "-n",
                               "dev.watchparty.sessionprobe/.ControlReceiver", "--es", "cmd", "status"))
    return s if s and s["state"] == PLAYING else None


# ── what to capture ─────────────────────────────────────────────────────

SECRET_PARAMS = re.compile(
    r"([?&;](?:[a-z0-9_.-]*(?:token|sig|signature|auth|key|secret|session|password|pwd|jwt|"
    r"policy|credential|hmac|access)[a-z0-9_.-]*)=)[^&;\s\"')]+", re.I)
BEARER = re.compile(r"(?i)(bearer\s+|authorization[\"':=\s]+)[a-z0-9._~+/=-]{8,}")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")


def redact(text):
    text = SECRET_PARAMS.sub(r"\1<redacted>", text)
    text = BEARER.sub(r"\1<redacted>", text)
    return EMAIL.sub("<email>", text)


def notifications_of(dump, pkg):
    """Only the notification blocks posted by pkg."""
    out, keep = [], False
    for line in dump.splitlines():
        if "NotificationRecord(" in line or "StatusBarNotification(" in line:
            keep = f"pkg={pkg} " in line
        if keep:
            out.append(line)
    return "\n".join(out)


def ui_text(xml):
    attrs = re.findall(r'(?:text|content-desc|resource-id)="([^"]+)"', xml)
    return "\n".join(sorted(set(attrs)))


def capture(pkg):
    pid = shell(f"pidof {pkg}").strip().split()
    logs = shell(f"logcat -d --pid={pid[0]}", timeout=120) if pid else "<app not running>"
    activities = "\n".join(l for l in shell("dumpsys activity activities").splitlines()
                           if pkg in l and ("dat=" in l or "cmp=" in l or "Intent" in l))
    shell("uiautomator dump /sdcard/wp_ui.xml", timeout=60)
    screen = ui_text(shell("cat /sdcard/wp_ui.xml"))
    shell("rm /sdcard/wp_ui.xml")
    return {
        "media_session": "\n".join(l for l in shell("dumpsys media_session").splitlines()),
        "probe_meta": adb("shell", "am", "broadcast", "-n", "dev.watchparty.sessionprobe/.ControlReceiver",
                          "--es", "cmd", "meta", "--es", "pkg", pkg),
        "app_log": logs,
        "activity_intents": activities,
        "screen_text": screen,
        "notifications": notifications_of(shell("dumpsys notification --noredact"), pkg),
        "watch_next": shell("content query --uri content://android.media.tv/watch_next_program"),
    }


# ── JustWatch: the ids to look for ──────────────────────────────────────

JW_API = "https://apis.justwatch.com/graphql"
JW_HEADERS = {
    "Content-Type": "application/json",
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0",
    "Origin": "https://www.justwatch.com",
    "Referer": "https://www.justwatch.com/",
}
JW_SEARCH = """query S($f: TitleFilter!, $c: Country!, $l: Language!) {
  popularTitles(country: $c, filter: $f, first: 3, sortBy: POPULAR, sortRandomSeed: 0) {
    edges { node { id content(country: $c, language: $l) { title originalReleaseYear } } } } }"""
JW_OFFERS = """query D($id: ID!, $c: Country!) { node(id: $id) { ... on MovieOrShowOrSeasonOrEpisode {
  web: offers(country: $c, platform: WEB) { standardWebURL package { clearName technicalName } }
  atv: offers(country: $c, platform: ANDROID_TV) { deeplinkURL(platform: ANDROID_TV) package { clearName technicalName } }
} } }"""


def jw_post(query, variables):
    req = urllib.request.Request(JW_API, data=json.dumps({"query": query, "variables": variables}).encode(),
                                 headers=JW_HEADERS)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.load(resp)


def simplify(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower().replace("+", "plus"))


def justwatch_links(title, country):
    """[(service, url)] for titles named like `title` (top matches)."""
    data = jw_post(JW_SEARCH, {"f": {"searchQuery": title, "objectTypes": ["MOVIE", "SHOW"]}, "c": country, "l": "en"})
    links = []
    for edge in (((data.get("data") or {}).get("popularTitles") or {}).get("edges") or []):
        node = edge["node"]
        if simplify(node["content"]["title"]) != simplify(title):
            continue
        offers = jw_post(JW_OFFERS, {"id": node["id"], "c": country})
        n = (offers.get("data") or {}).get("node") or {}
        for o in (n.get("web") or []):
            links.append((o["package"]["clearName"], o.get("standardWebURL")))
        for o in (n.get("atv") or []):
            links.append((o["package"]["clearName"], o.get("deeplinkURL")))
    return [(svc, url) for svc, url in links if url]


ID_PATTERNS = [
    re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.I),  # UUIDs (Disney+, HBO)
    re.compile(r"umc\.cmc\.[a-z0-9]+", re.I),                                         # Apple TV
    re.compile(r"amzn1\.dv\.gti\.[0-9a-f-]+", re.I),                                  # Amazon
    re.compile(r"(?<=asin=)[A-Z0-9]{10}"),                                            # Amazon
    re.compile(r"(?<=[/=])[0-9]{5,}(?=[/?#&;]|$)"),                                   # numeric (Netflix, Starz, Tubi)
    re.compile(r"(?<=/watch/)[A-Z0-9]{8,}(?=[/?#]|$)"),                               # Crunchyroll
    re.compile(r"(?<=[?&]v=)[A-Za-z0-9_-]{11}"),                                      # YouTube
]


def ids_in(url):
    found = []
    for p in ID_PATTERNS:
        for m in p.findall(url):
            if m not in found:
                found.append(m)
    return found


def find_ids(ids_by_service, captured):
    """{id: [(source, line)]} for every id seen in the captured data."""
    hits = {}
    for svc, ids in ids_by_service.items():
        for i in ids:
            for source, text in captured.items():
                for line in text.splitlines():
                    if i.lower() in line.lower():
                        hits.setdefault(i, []).append((source, svc, redact(line.strip())[:300]))
                        break
    return hits


# ── a capture, end to end ───────────────────────────────────────────────

def run_capture(session, args):
    pkg, title = session["package"], session.get("title")
    print(f"\n▶ {pkg} — {title or '(no title published)'}")
    print(f"  letting it load for {args.settle}s …")
    time.sleep(args.settle)
    captured = {k: redact(v) for k, v in capture(pkg).items()}

    ids_by_service = {}
    jw_error = None
    if title:
        try:
            for svc, url in justwatch_links(title, args.country):
                ids_by_service.setdefault(svc, [])
                for i in ids_in(url):
                    if i not in ids_by_service[svc]:
                        ids_by_service[svc].append(i)
        except (urllib.error.URLError, OSError, ValueError, KeyError) as e:
            jw_error = str(e)
    hits = find_ids(ids_by_service, captured)

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    folder = os.path.join(args.out, f"{stamp}_{pkg}")
    os.makedirs(folder, exist_ok=True)
    for name, text in captured.items():
        with open(os.path.join(folder, f"{name}.txt"), "w") as f:
            f.write(text)
    report = {
        "package": pkg, "title": title, "subtitle": session.get("subtitle"),
        "justwatch_ids": ids_by_service, "justwatch_error": jw_error,
        "found": {i: [{"source": s, "service": svc, "line": l} for s, svc, l in v] for i, v in hits.items()},
        "log_lines": len(captured["app_log"].splitlines()),
    }
    with open(os.path.join(folder, "report.json"), "w") as f:
        json.dump(report, f, indent=2)

    if not title:
        print("  ✗ the app publishes no title — nothing to look up")
    elif jw_error:
        print(f"  ? JustWatch lookup failed: {jw_error}")
    elif not ids_by_service:
        print("  ? JustWatch has no match for this title")
    elif hits:
        for i, where in hits.items():
            for source, svc, line in where:
                print(f"  ✓ {svc} id {i} found in {source}: {line[:160]}")
    else:
        print(f"  ✗ none of JustWatch's ids for it ({sum(len(v) for v in ids_by_service.values())}) appear anywhere")
    print(f"  saved {folder}")
    return report


def summary(reports):
    if not reports:
        return
    print("\n" + "=" * 78)
    print(f"{'app':<36} {'title':<24} id exposed in")
    for r in reports:
        sources = sorted({h["source"] for v in r["found"].values() for h in v})
        verdict = ", ".join(sources) if sources else ("no title" if not r["title"] else
                                                       "JustWatch: no match" if not r["justwatch_ids"] else "nowhere")
        print(f"{r['package'][:35]:<36} {(r['title'] or '-')[:23]:<24} {verdict}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", choices=["watch", "once"])
    ap.add_argument("-s", "--serial")
    ap.add_argument("--country", default="US")
    ap.add_argument("--settle", type=int, default=12)
    ap.add_argument("--out", default="probe-reports")
    args = ap.parse_args()
    if args.serial:
        ADB.extend(["-s", args.serial])

    if args.mode == "once":
        s = playing_session()
        if not s:
            sys.exit("Nothing is playing. Start a title on the TV and try again.")
        summary([run_capture(s, args)])
        return

    print("Watching. Play titles in as many apps as you like; each new one is captured.")
    print("Press Ctrl+C when done for the summary.")
    reports, seen = [], set()
    try:
        while True:
            s = playing_session()
            if s and (s["package"], s.get("title")) not in seen:
                seen.add((s["package"], s.get("title")))
                reports.append(run_capture(s, args))
            time.sleep(3)
    except KeyboardInterrupt:
        pass
    summary(reports)


if __name__ == "__main__":
    main()
