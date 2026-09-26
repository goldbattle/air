"""Builds the offline datasets in app/src/main/assets from public sources.

  curl -o airports.csv https://davidmegginson.github.io/ourairports-data/airports.csv
  curl -o of_airports.dat https://raw.githubusercontent.com/jpatokal/openflights/master/data/airports.dat
  curl -o airlines.dat https://raw.githubusercontent.com/jpatokal/openflights/master/data/airlines.dat
  curl -o land.json https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_land.geojson
  python make_data.py <dir with those files> <assets dir>
"""
import csv, json, math, sys

src, out = sys.argv[1], sys.argv[2]

# Timezones come from OpenFlights (OurAirports has none); coverage/names from OurAirports.
tz = {}
for r in csv.reader(open(f"{src}/of_airports.dat", encoding="utf-8")):
    if len(r) > 11 and r[4] not in ("", "\\N") and r[11] not in ("", "\\N"):
        tz[r[4]] = r[11]

rows = []
for r in csv.DictReader(open(f"{src}/airports.csv", encoding="utf-8")):
    iata = r["iata_code"].strip()
    if len(iata) != 3 or r["type"] not in ("large_airport", "medium_airport", "small_airport"):
        continue
    if r["type"] == "small_airport" and r["scheduled_service"] != "yes":
        continue
    # Rank for picking "the" airport of a city: 0 large, 1 medium with airline service, 2 other medium, 3 small.
    rank = 0 if r["type"] == "large_airport" else 3 if r["type"] == "small_airport" else 1 if r["scheduled_service"] == "yes" else 2
    rows.append([iata, r["icao_code"] or r["ident"], r["name"], r["municipality"], r["iso_country"],
                 float(r["latitude_deg"]), float(r["longitude_deg"]), tz.get(iata, ""), r["iso_region"], str(rank)])

# Airports OpenFlights doesn't know borrow the timezone of the nearest one that has it.
known = [a for a in rows if a[7]]
for a in rows:
    if not a[7]:
        a[7] = min(known, key=lambda b: (b[5] - a[5]) ** 2 + ((b[6] - a[6]) * math.cos(math.radians(a[5]))) ** 2)[7]

with open(f"{out}/airports.tsv", "w", encoding="utf-8", newline="\n") as f:
    for a in sorted(rows, key=lambda a: a[0]):
        f.write("\t".join([a[0], a[1], a[2], a[3], a[4], f"{a[5]:.4f}", f"{a[6]:.4f}", a[7], a[8], a[9]]) + "\n")

seen = set()
with open(f"{out}/airlines.tsv", "w", encoding="utf-8", newline="\n") as f:
    for r in csv.reader(open(f"{src}/airlines.dat", encoding="utf-8")):
        iata, icao = r[3].strip(), r[4].strip()
        if r[7] == "Y" and len(iata) == 2 and len(icao) == 3 and iata not in seen and icao.isalpha():
            seen.add(iata)
            f.write(f"{iata}\t{icao}\t{r[1]}\n")

# One ring per line: "lon lat lon lat ...", rounded to 0.1 degree.
with open(f"{out}/land.txt", "w", encoding="utf-8", newline="\n") as f:
    for feat in json.load(open(f"{src}/land.json"))["features"]:
        g = feat["geometry"]
        polys = [g["coordinates"]] if g["type"] == "Polygon" else g["coordinates"]
        for p in polys:
            f.write(" ".join(f"{x:.1f} {y:.1f}" for x, y in p[0]) + "\n")

print(len(rows), "airports,", len(seen), "airlines")
