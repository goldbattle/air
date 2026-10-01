#!/usr/bin/env python3
"""Screenshots of Air with made-up example data, and a Screenshots section in the README built from them.

    python tools/screenshots.py              # build, install, capture, update README
    python tools/screenshots.py --no-build   # reuse the last APK
    python tools/screenshots.py --avd air37  # which emulator to boot if none is running

Needs an Android 16+ emulator (the app's minSdk is 36) and ImageMagick (`magick`) to shrink the images; without it
the full-size PNGs are kept. It WIPES the app's data on the device it runs on, so point it at an emulator, not your
phone. Everything shown is invented (traveler "Alex Rivera", confirmation codes like DEMO42) except two flights that
are really in the air when you run it, picked from adsb.lol so the live screens show real speed and altitude.
"""
import argparse
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "screenshots"
PKG = "com.aria.air"
EXE = ".exe" if os.name == "nt" else ""


def sdk_dir() -> Path:
    props = ROOT / "local.properties"
    if props.exists():
        for line in props.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].replace("\\\\", "\\").replace("\\:", ":"))
    env = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if env:
        return Path(env)
    sys.exit("Android SDK not found: set sdk.dir in local.properties or ANDROID_SDK_ROOT")


SDK = sdk_dir()
ADB = str(SDK / "platform-tools" / f"adb{EXE}")


def adb(*args: str, check: bool = True) -> str:
    r = subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout


def shell(cmd: str, check: bool = True) -> str:
    return adb("shell", cmd, check=check)


# ---------- Device ----------

def ensure_device(avd: str) -> None:
    if re.search(r"\tdevice$", adb("devices"), re.M):
        return
    print(f"Booting emulator {avd}...")
    env = dict(os.environ, ANDROID_SDK_ROOT=str(SDK))
    subprocess.Popen(
        [str(SDK / "emulator" / f"emulator{EXE}"), "-avd", avd, "-no-snapshot-save", "-no-audio", "-no-boot-anim"],
        env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    for _ in range(120):
        time.sleep(5)
        if shell("getprop sys.boot_completed", check=False).strip() == "1":
            time.sleep(10)  # let the launcher settle
            return
    sys.exit("Emulator didn't boot in 10 minutes")


def build_and_install(build: bool) -> None:
    if build:
        gradlew = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        print("Building release APK...")
        subprocess.run([str(gradlew), "assembleRelease", "-q"], cwd=ROOT, check=True)
    apk = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    print("Installing...")
    adb("install", "-r", str(apk))


# ---------- UI driving ----------

def nodes() -> list[tuple[str, int, int]]:
    """Visible texts (and content descriptions) with their center points."""
    shell("uiautomator dump /sdcard/ui.xml", check=False)
    xml = shell("cat /sdcard/ui.xml", check=False)
    out = []
    for m in re.finditer(r'(?:text|content-desc)="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        out.append((m.group(1), (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def tap_text(text: str, prefix: bool = False, last: bool = False, timeout: float = 10) -> bool:
    """Taps the element showing [text]. [last] picks the lowest match (the tab bar, not the page title)."""
    end = time.time() + timeout
    while time.time() < end:
        hits = [n for n in nodes() if (n[0].startswith(text) if prefix else n[0] == text)]
        if hits:
            _, x, y = max(hits, key=lambda n: n[2]) if last else hits[0]
            shell(f"input tap {x} {y}")
            time.sleep(1.5)
            return True
        time.sleep(1)
    print(f"  ! couldn't find '{text}'")
    return False


def tab(name: str) -> None:
    tap_text(name, last=True)


def back() -> None:
    shell("input keyevent 4")
    time.sleep(1.5)


def scroll(dy: int = 900) -> None:
    shell(f"input swipe 540 1700 540 {1700 - dy} 500")
    time.sleep(1.2)


def pull_to_refresh() -> None:
    shell("input swipe 540 600 540 1500 600")


def shot(name: str) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{name}.png"
    with open(path, "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, check=True)
    magick = shutil.which("magick")
    if magick:  # README-sized: about 430 px wide
        subprocess.run([magick, str(path), "-resize", "40%", "-strip", str(path)], check=False)
    print(f"  saved {path.relative_to(ROOT)}")


# ---------- Example data ----------

# Rough UTC offsets for the demo airports (fall, with daylight saving) to turn local times into instants.
OFFSET = {
    "SFO": -7, "OAK": -7, "LAX": -7, "LAS": -7, "SEA": -7, "DEN": -6, "MDW": -5, "ORD": -5,
    "JFK": -4, "BOS": -4, "ATL": -4, "LHR": 1,
}
ICAO_TO_IATA = {"UAL": "UA", "DAL": "DL", "AAL": "AA", "SWA": "WN", "ASA": "AS", "JBU": "B6"}


def fetch(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": "air-screenshots"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())


def flight(airline, number, day: dt.date, frm, to, dep=None, arr=None, **extra) -> dict:
    f = {"id": str(uuid.uuid4()), "airline": airline, "number": str(number), "date": day.isoformat(),
         "from": frm, "to": to, "source": "demo", "cancelled": False, "diverted": False}
    if dep and arr:
        def at(t, code):
            h, m = map(int, t.split(":"))
            local = dt.datetime.combine(day, dt.time(h, m))
            return local - dt.timedelta(hours=OFFSET.get(code, 0))
        d, a = at(dep, frm), at(arr, to)
        while a < d:
            a += dt.timedelta(days=1)
        epoch = dt.datetime(1970, 1, 1)
        f["depSched"] = int((d - epoch).total_seconds() * 1000)
        f["arrSched"] = int((a - epoch).total_seconds() * 1000)
    f.update(extra)
    return f


def airborne(n: int) -> list[tuple[str, str, str, str]]:
    """Up to [n] (airline, number, from, to) of US airline jets in cruise right now, with a known route."""
    found = []
    for lat, lon in [(39, -98), (40, -85), (37, -110), (42, -75)]:
        try:
            acs = fetch(f"https://api.adsb.lol/v2/point/{lat}/{lon}/250")["ac"]
        except Exception as e:
            print(f"  adsb.lol unavailable ({e})")
            return found
        for ac in acs:
            cs = ac.get("flight", "").strip()
            alt = ac.get("alt_baro")
            if cs[:3] not in ICAO_TO_IATA or not cs[3:].isdigit() or not isinstance(alt, int) or alt < 28000:
                continue
            try:
                codes = fetch(f"https://vrs-standing-data.adsb.lol/routes/{cs[:2]}/{cs}.json").get("_airport_codes_iata", "").split("-")
            except Exception:
                continue
            if len(codes) == 2 and all(len(c) == 3 for c in codes) and cs not in [f[4] for f in found]:
                found.append((ICAO_TO_IATA[cs[:3]], cs[3:], codes[0], codes[1], cs))
                if len(found) >= n:
                    return found
    return found


def demo_backup() -> tuple[dict, list[str]]:
    """The example history, plus the labels of the flights in the air (for tapping)."""
    today = dt.date.today()
    d = lambda days: today + dt.timedelta(days=days)
    live = airborne(2)
    flights, live_labels = [], []
    for i, (al, num, frm, to, _) in enumerate(live):
        # No times: the app finds the plane on adsb.lol and estimates takeoff and landing from its position.
        flights.append(flight(al, num, today, frm, to, **({} if i == 0 else {"friend": "Sam Lee"})))
        live_labels.append(f"{al} {num}")
    if not live:  # offline fallback: a flight that's two hours into five, by the clock
        now = dt.datetime.now()
        dep, arr = (now - dt.timedelta(hours=2)).strftime("%H:%M"), (now + dt.timedelta(hours=3)).strftime("%H:%M")
        flights.append(flight("UA", 1000, today, "SFO", "BOS", dep, arr))
        live_labels.append("UA 1000")
    flights += [
        # Mine, upcoming
        flight("UA", 1234, d(1), "BOS", "SFO", "07:00", "10:35", seat="21C", booking="DEMO42"),
        flight("DL", 1105, d(18), "ATL", "BOS", "09:15", "11:58", booking="DEMO77"),
        # Mine, past (a year and a half of trips, two of them for work)
        flight("UA", 951, d(-60), "LHR", "SFO", "12:35", "15:40", seat="31A"),
        flight("UA", 950, d(-70), "SFO", "LHR", "17:00", "11:30", seat="31C"),
        flight("WN", 500, d(-80), "OAK", "LAS", "07:15", "08:45"),
        flight("WN", 501, d(-80), "LAS", "OAK", "20:30", "22:00"),
        flight("UA", 300, d(-150), "SFO", "ORD", "06:00", "12:15"),
        flight("UA", 301, d(-147), "ORD", "SFO", "17:30", "20:10"),
        flight("WN", 1200, d(-300), "OAK", "LAS", "18:35", "20:05", work=True, booking="WORK01"),
        flight("WN", 1201, d(-298), "LAS", "OAK", "20:20", "21:50", work=True, booking="WORK01"),
        flight("DL", 2100, d(-400), "LAX", "JFK", "22:30", "06:55"),
        flight("DL", 2101, d(-395), "JFK", "LAX", "08:00", "11:25"),
        flight("AS", 10, d(-500), "SEA", "BOS", "09:30", "17:45"),
        # Friends
        flight("UA", 455, d(5), "SFO", "BOS", "07:15", "15:40", friend="Jordan Park", seat="22C"),
        flight("WN", 2200, d(-40), "MDW", "DEN", "10:05", "11:35", friend="Jordan Park"),
    ]
    return {"app": "air", "version": 1, "name": "Alex Rivera", "flights": flights}, live_labels


def share_text(text: str) -> None:
    """Shares [text] into the app, as another app's share sheet would."""
    tmp = ROOT / "app" / "build" / "share.txt"
    tmp.parent.mkdir(parents=True, exist_ok=True)
    tmp.write_text(text, encoding="utf-8")
    adb("push", str(tmp), "/data/local/tmp/share.txt")
    shell(f'am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "$(cat /data/local/tmp/share.txt)" -n {PKG}/.MainActivity')
    time.sleep(4)


# ---------- README ----------

CAPTIONS = [
    ("flights", "Flights", "Upcoming trips; a flight in the air shows live progress, speed and altitude."),
    ("flight_live", "In flight", "Route map with the live plane and the phase bar: pre-travel, departure, in the air, arrival."),
    ("flight_pretravel", "Before a trip", "Countdown, estimated boarding, seat and confirmation, time zone change."),
    ("flight_details", "Schedule & weather", "Times, distance, and the weather at takeoff and landing (Open-Meteo)."),
    ("past", "History", "Every past flight, imported from byAir, PDFs, screenshots or emails."),
    ("friends", "Friends", "Friends' flights, tracked live like your own, with alerts when they land."),
    ("passport", "Passport", "Your flights on a map with miles, time in the air, airports and countries."),
    ("passport_lists", "Passport lists", "Top airports, airlines and routes; personal and work trips counted separately."),
    ("import", "Import", "Confirmations read on the phone: traveler, flights, seats and confirmation codes."),
    ("notifications", "Notifications", "Live countdown before departure, then flight progress, as ongoing notifications."),
    ("widget", "Widget", "Home screen widget with the route map and a big countdown."),
    ("settings", "Settings", "Privacy, notifications, data sources, duplicates and backup."),
]


# Only these make the README; the rest are still captured into docs/screenshots for reference.
README_SELECT = ["flights", "flight_live", "passport"]


def update_readme() -> None:
    have = [(n, t, c) for n, t, c in CAPTIONS if n in README_SELECT and (OUT / f"{n}.png").exists()]
    rows = []
    for i in range(0, len(have), 3):
        chunk = have[i:i + 3]
        rows.append("| " + " | ".join(f"**{t}**" for _, t, _ in chunk) + " |")
        if i == 0:
            rows.append("|" + "---|" * len(chunk))
        rows.append("| " + " | ".join(f'<img src="docs/screenshots/{n}.png" width="260">' for n, _, _ in chunk) + " |")
        rows.append("| " + " | ".join(c for _, _, c in chunk) + " |")
    section = "\n".join([
        "<!-- screenshots:start -->",
        "## Screenshots",
        "",
        "Made-up example data (the two flights in the air are real flights at the time of capture). "
        "Regenerate with `python tools/screenshots.py`.",
        "",
        *rows,
        "<!-- screenshots:end -->",
    ])
    readme = ROOT / "README.md"
    text = readme.read_text(encoding="utf-8")
    if "<!-- screenshots:start -->" in text:
        text = re.sub(r"<!-- screenshots:start -->.*?<!-- screenshots:end -->", lambda _: section, text, flags=re.S)
    else:  # after the intro paragraph
        head, sep, rest = text.partition("\n## ")
        text = head.rstrip() + "\n\n" + section + "\n\n" + sep.lstrip("\n") + rest if sep else text + "\n\n" + section + "\n"
    readme.write_text(text, encoding="utf-8")
    print("README.md updated")


# ---------- Tour ----------

def add_widget() -> None:
    """Puts the widget on the home screen through the launcher's widget picker (Pixel launcher)."""
    shell("input keyevent KEYCODE_HOME")
    time.sleep(2)
    if any("Departs in" in t or "Lands in" in t or "Boarding in" in t for t, _, _ in nodes()):
        return  # already there
    shell("input swipe 540 1150 540 1150 1500")  # long-press an empty spot
    time.sleep(2)
    if not tap_text("Widgets"):
        return
    time.sleep(2)
    if not tap_text("Air"):
        back(); return
    time.sleep(2)
    label = [n for n in nodes() if n[0] == "Next flight"]
    if not label:
        back(); return
    _, x, y = label[0]
    shell(f"input tap {x} {y - 250}")  # the preview sits above its label
    time.sleep(2)
    tap_text("Add")
    time.sleep(4)


def tour(live_labels: list[str]) -> None:
    today = dt.date.today()
    fly = today + dt.timedelta(days=24)
    email = (
        "Your trip confirmation: DEMO99\n"
        "Passenger: ALEX RIVERA\n"
        f"{fly.strftime('%a, %b %d, %Y')}\n"
        "UA 700  San Francisco (SFO) to Boston (BOS)\n"
        "Departs 8:30 AM  Arrives 4:55 PM  Seat 22C\n"
        f"{(fly + dt.timedelta(days=8)).strftime('%a, %b %d, %Y')}\n"
        "UA 701  Boston (BOS) to San Francisco (SFO)\n"
        "Departs 7:50 AM  Arrives 11:20 AM  Seat 14A\n"
    )

    print("Flights")
    tab("Flights")
    pull_to_refresh()
    time.sleep(10)  # live positions and weather
    shot("flights")

    if tap_text(live_labels[0]):
        time.sleep(6)
        shot("flight_live")
        scroll(1500)
        shot("flight_details")
        back()

    if tap_text("UA 1234"):
        time.sleep(3)
        shot("flight_pretravel")
        back()

    if tap_text("Past", prefix=True):
        time.sleep(2)
        shot("past")
        tap_text("Upcoming", prefix=True)

    print("Friends")
    tab("Friends")
    time.sleep(2)
    shot("friends")

    print("Passport")
    tab("Passport")
    time.sleep(3)
    shot("passport")
    scroll(1400)
    shot("passport_lists")

    print("Import")
    share_text(email)
    shot("import")
    back()

    print("Settings")
    tab("Settings")
    time.sleep(2)
    shot("settings")

    print("Notifications")
    shell("cmd statusbar expand-notifications")
    time.sleep(3)
    shot("notifications")
    shell("cmd statusbar collapse")
    time.sleep(1)

    print("Widget")
    add_widget()
    shell("input keyevent KEYCODE_HOME")
    time.sleep(3)
    shot("widget")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--avd", default="air37", help="emulator to boot if no device is connected")
    ap.add_argument("--no-build", action="store_true", help="install the existing release APK")
    ap.add_argument("--no-readme", action="store_true", help="don't touch README.md")
    args = ap.parse_args()

    ensure_device(args.avd)
    build_and_install(not args.no_build)

    print("Resetting the app and loading example data...")
    shell(f"pm clear {PKG}", check=False)
    shell(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS", check=False)
    shell(f"am start -n {PKG}/.MainActivity")
    time.sleep(4)
    backup, live_labels = demo_backup()
    print(f"  in the air now: {', '.join(live_labels)}")
    share_text(json.dumps(backup))
    time.sleep(8)  # first background check: live positions, weather backfill

    tour(live_labels)
    if not args.no_readme:
        update_readme()


if __name__ == "__main__":
    main()
