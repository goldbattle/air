---
name: add-airport
description: Add or update a bundled airport guide in the Air app (terminals, which airlines use which terminal, gate ranges, terminal maps, lounges, getting there, tips). Use when the user says "add airport XXX", "add a guide for SFO", "I'm flying into ...", "update the PIT guide", or names an airport they want info for in the app.
---

# Add an airport guide

Guides live in `app/src/main/assets/guides/airports/<IATA>.json`, with images in
`app/src/main/assets/guides/airports/<IATA>/`. The app reads them offline (`Guides` in `data/Guides.kt`, shown by `ui/screens/AirportScreen.kt`);
nothing is fetched at runtime, so everything the page shows must be in these files.

## Steps

1. **Check the code exists** in `app/src/main/assets/airports.tsv` (`grep -P "^SFO\t"`). If it's missing, add a row in
   the same tab-separated format (`IATA ICAO name city ISO2 lat lon tz region rank`), sorted by IATA. Or refresh
   everything with `tools/make_data.py` (download commands are in its docstring).
2. **Research** with WebSearch/WebFetch, preferring the airport's official site, then the airlines' own pages:
   - terminals / concourses, the airlines in each, gate ranges
   - an official terminal map (image or PDF)
   - lounges (name, where airside, who gets in)
   - getting to the city (train, bus, rideshare pickup area) and 3–6 practical tips (security wait, walking times
     between terminals, AirTrain, where rideshare pickup is)
   Only write facts you found a source for. Leave a field out rather than guess.
3. **Maps**: download to `guides/airports/<IATA>/`. PDFs: `magick -density 150 "map.pdf[0]" -resize "2400x2400>" map.png`
   (ImageMagick is installed). Keep each image under ~1.5 MB (`-quality 85` or convert to `.jpg`). Also keep the
   source URL in `url` so the user can open the live version. These are for personal use; don't republish.
4. **Write the JSON** (all fields optional except `iata`):

```json
{
  "iata": "SFO",
  "updated": "2026-09-26",
  "summary": "One or two sentences: layout and what matters most.",
  "terminals": [
    { "name": "Terminal 3", "airlines": ["UA"], "gates": "E1–E13, F1–F22", "notes": "United's hub. F gates are a long walk." }
  ],
  "maps": [
    { "title": "Terminal 3", "image": "SFO/t3.png", "url": "https://www.flysfo.com/..." }
  ],
  "lounges": [
    { "name": "United Club", "where": "Terminal 3, near F9", "access": "Club members, Star Gold" }
  ],
  "transport": ["BART from the International Terminal to downtown in ~30 min."],
  "tips": ["AirTrain connects all terminals and the rental car center, runs 24/7."],
  "links": [{ "title": "Official site", "url": "https://www.flysfo.com" }]
}
```
   `terminals[].airlines` are IATA airline codes. `maps[].image` is relative to `guides/airports/`.

5. **Validate**: `python -c "import json,sys; json.load(open(sys.argv[1], encoding='utf-8'))" <file>` and check every
   `image` path exists.
6. **Build**: `./gradlew assembleRelease` (Aria picks up `app/build/outputs/apk/release/app-release.apk`).
   Tell the user what was added and which facts you couldn't confirm.
