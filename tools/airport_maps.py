"""Downloads indoor maps of the big US airports from OpenStreetMap into the app, so they open instantly and offline.

  python tools/airport_maps.py            # every airport in AIRPORTS that isn't bundled yet
  python tools/airport_maps.py SFO BWI    # just these (re-downloads them)

Writes app/src/main/assets/airport-maps/<IATA>.json.gz: the Overpass answer with only the tags the app reads, coordinates
rounded to ~10 cm. The query is the app's own (api/Overpass.kt; IndoorMapTest checks they match). The public Overpass
servers are shared and often busy, so this waits between airports and retries busy servers. Data is © OpenStreetMap
contributors, under the ODbL.
"""
import gzip, json, sys, time, urllib.error, urllib.parse, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "main" / "assets" / "airport-maps"

# The largest US airports with international flights, by passengers.
AIRPORTS = """ATL LAX ORD DFW DEN JFK SFO SEA LAS MCO EWR CLT PHX IAH MIA BOS MSP FLL DTW PHL LGA BWI SLC SAN IAD DCA TPA
PDX HNL AUS""".split()

SERVERS = ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
           "https://overpass.kumi.systems/api/interpreter"]

KEEP = {"aeroway", "amenity", "shop", "indoor", "building", "highway", "tourism", "emergency", "name", "brand", "ref",
        "local_ref", "level", "opening_hours"}

# Keep in step with Overpass.wanted() in the app.
WANTED = """(
  nwr["indoor"~"^(room|area|corridor|yes)$"]{a};
  nwr["aeroway"~"^(gate|terminal|apron|baggage_claim|baggage_reclaim|check-in)$"]{a};
  nwr["building"="terminal"]{a};
  nwr["amenity"~"^(restaurant|fast_food|food_court|ice_cream|cafe|bar|pub|toilets|drinking_water|lounge|atm|bank|bureau_de_change|pharmacy|clinic|doctors|charging_station|information|place_of_worship)$"]{a};
  nwr["shop"]{a};
  nwr["highway"~"^(elevator|steps|escalator)$"]["indoor"]{a};
  nwr["highway"="elevator"]{a};
  nwr["tourism"="information"]{a};
);
out geom qt;"""


def airports():
    rows = (line.split("\t") for line in (ROOT / "app" / "src" / "main" / "assets" / "airports.tsv").read_text(encoding="utf-8").splitlines())
    return {r[0]: r for r in rows}


def query(iata, icao):
    return (f'[out:json][timeout:90];\n(nwr["aeroway"="aerodrome"]["icao"="{icao}"];nwr["aeroway"="aerodrome"]["iata"="{iata}"];)->.a;\n'
            ".a map_to_area->.ap;\n" + WANTED.replace("{a}", "(area.ap)"))


def nearby(lat, lon):
    """Within 2.5 km of the airport, for airports whose boundary isn't mapped (the app does the same)."""
    return "[out:json][timeout:90];\n" + WANTED.replace("{a}", f"(around:2500,{lat},{lon})")


def run(q):
    """The first server's answer; busy servers are retried for up to ~10 minutes."""
    for attempt in range(8):
        for s in SERVERS:
            req = urllib.request.Request(s + "?data=" + urllib.parse.quote(q), headers={"User-Agent": "Air-Android/tools (airport maps)"})
            try:
                with urllib.request.urlopen(req, timeout=120) as r:
                    return json.loads(r.read().decode("utf-8"))
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
                print(f"    {urllib.parse.urlparse(s).netloc}: {getattr(e, 'code', '') or e}")
        time.sleep(30 * (attempt + 1))
    raise RuntimeError("no Overpass server answered")


def slim(d):
    def pts(g):
        return [{"lat": round(p["lat"], 6), "lon": round(p["lon"], 6)} for p in g or []]
    out = []
    for e in d.get("elements", []):
        x = {"type": e["type"], "tags": {k: v for k, v in e.get("tags", {}).items() if k in KEEP}}
        if e["type"] == "node":
            x["lat"], x["lon"] = round(e["lat"], 6), round(e["lon"], 6)
        elif e["type"] == "way":
            x["geometry"] = pts(e.get("geometry"))
        else:
            x["members"] = [{"type": "way", "role": m.get("role", ""), "geometry": pts(m.get("geometry"))}
                            for m in e.get("members", []) if m["type"] == "way"]
        out.append(x)
    return out


def main():
    db = airports()
    codes = [c.upper() for c in sys.argv[1:]] or [c for c in AIRPORTS if not (OUT / f"{c}.json.gz").exists()]
    OUT.mkdir(parents=True, exist_ok=True)
    for i, code in enumerate(codes):
        if i:
            time.sleep(10)  # be gentle with the shared servers
        print(f"{code}...")
        els = slim(run(query(code, db[code][1])))
        if not any(e["tags"].get("aeroway") == "terminal" or e["tags"].get("building") == "terminal" or "indoor" in e["tags"] for e in els):
            print("    boundary not mapped, trying the area around it")
            els = slim(run(nearby(db[code][5], db[code][6])))
        if not els:
            print("    nothing mapped, skipped")
            continue
        data = json.dumps({"generated": int(time.time()), "elements": els}, separators=(",", ":")).encode("utf-8")
        (OUT / f"{code}.json.gz").write_bytes(gzip.compress(data, 9))
        print(f"    {len(els)} features, {len(data) // 1024} KB -> {(OUT / f'{code}.json.gz').stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
