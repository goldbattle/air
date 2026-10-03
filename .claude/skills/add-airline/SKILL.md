---
name: add-airline
description: Add or update a bundled airline guide in the Air app (brand color, logo, alliance, hubs, check-in and baggage rules, tips, links). Use when the user says "add airline XX", "add United", "add a logo for Southwest", "update the baggage rules for ...", or when an airline shows as a plain colored monogram and they want it to look right.
---

# Add an airline guide

Guides live in `app/src/main/assets/guides/airlines/<IATA>.json`; an icon goes in `app/src/main/assets/airline-icons/<IATA>.png`.
The badge on every flight card shows that icon, else a monogram on the guide's `color`; the rest shows on the airline page
(`AirlineScreen` in `ui/screens/AirportScreen.kt`). Everything is bundled; nothing is fetched at runtime.

## Steps

1. **Check the airline exists** in `app/src/main/assets/airlines.tsv` (`IATA<TAB>ICAO<TAB>name`). The ICAO code is what
   live tracking needs (UA -> UAL), so if it's missing or wrong, fix that row first.
2. **Research** with WebSearch/WebFetch from the airline's own site: alliance, hubs (IATA codes), when online check-in
   opens, carry-on and first checked bag rules for the basic economy / main cabin fares, and 2–5 practical tips.
   Only write what you found a source for.
3. **Icon** (optional): the airline's emblem, not its wordmark, from Wikimedia Commons only (public domain / PD-textlogo /
   CC; skip fair-use files). Crop the symbol from Commons' PNG rendering, trim, center it on a 96×96 transparent PNG with
   16% padding per side. It sits on light gray unless drawn in white (then add its brand color to `Guides.airlineIconBg`).
   Add the source URL, author and license to `airline-icons/CREDITS.md`. No emblem on Commons: leave it out.
4. **Write the JSON** (all fields optional except `iata`):

```json
{
  "iata": "UA",
  "color": "#0033A0",
  "alliance": "Star Alliance",
  "hubs": ["SFO", "ORD", "DEN", "IAH", "EWR", "IAD", "LAX"],
  "summary": "One sentence.",
  "checkin": "Online check-in opens 24 hours before departure.",
  "baggage": "Carry-on and personal item included except Basic Economy on some routes...",
  "tips": ["..."],
  "links": [{ "title": "Baggage rules", "url": "https://www.united.com/..." }]
}
```

5. **Validate** the JSON (`python -c "import json,sys; json.load(open(sys.argv[1], encoding='utf-8'))" <file>`) and
   build with `./gradlew assembleRelease`. Tell the user what was added and what couldn't be confirmed.
