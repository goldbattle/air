# Air

A private flight tracker and travel passport for Android. Track your own flights and friends' live, see your travel
history on a map, and import confirmations by just sharing them to the app. Everything stays on the phone.

<!-- screenshots:start -->
<p align="center"><img src="docs/screenshots/flights.png" width="200" alt="flights"> <img src="docs/screenshots/flight_live.png" width="200" alt="flight_live"> <img src="docs/screenshots/notifications.png" width="200" alt="notifications"> <img src="docs/screenshots/passport.png" width="200" alt="passport"></p>
<!-- screenshots:end -->

## Features

- Live tracking for your own flights and friends', with gate, delay, and takeoff/landing notifications.
- A live notification that follows each flight: a countdown with the time to leave, boarding and the gate at the
  airport, then progress, speed and landing time in the air.
- A flight page with a route map, schedule, weather at both ends, your plane's previous flight, and (while airborne)
  speed, altitude and heading. Connecting flights show as one journey.
- Map layers: radar, forecast radar for US takeoffs, weather along the route, and the paths earlier flights took.
- Import by sharing a confirmation email, PDF, or screenshot to the app, or by printing an email to "Save to Air".
  OCR and PDF text extraction run on-device.
- Optional Gmail scan: link one or more mailboxes with an app password and Air finds past and new bookings for you
  to pick from. Manual, or every few weeks in the background.
- A passport view: every past flight on a map, with miles, time in the air, and top airports/airlines/routes.
- A home screen widget with the route map, radar and a countdown to your next flight.

## Install

Download the latest APK from [Releases](https://github.com/goldbattle/air/releases) and sideload it. Air checks
GitHub for new releases itself (Settings > About > Check for updates, with an optional daily background check) and
installs them in place.

## Data sources

There's no account and no analytics. Your flights, emails and backups stay on the phone; these services only see
what's listed. Layers marked *off by default* make no requests until you turn them on.

| Source | Used for | What it's sent | How often |
|---|---|---|---|
| [adsb.lol](https://adsb.lol) | Live positions, routes, the plane's previous flight, the path a flight actually flew | Callsign, tail number or aircraft hex | Every 1–10 min around and during a flight; a flight's full path once after landing |
| [Open-Meteo](https://open-meteo.com) | Weather at takeoff and landing; weather along the route (*off by default*) | Airport or route coordinates and times | Daily when far out, every 6 h in the last 3 days, every 3 h on the day |
| [RainViewer](https://www.rainviewer.com/api.html) | Radar on the map and widget | Map tile coordinates | Every 10 min while shown, from 6 h before departure until the flight's rain map is out |
| [NASA GIBS (IMERG)](https://gibs.earthdata.nasa.gov) | Rain during past flights, worldwide | A time | Once per 3-hour slot, about 7 h after the flight; kept on the phone and in exports |
| [NOAA HRRR via Iowa State](https://mesonet.agron.iastate.edu) | Forecast radar for US takeoffs (*off by default*) | Nothing but the forecast step | Hourly while shown, up to 48 h before takeoff |
| [AeroDataBox](https://aerodatabox.com) (your key) | Status, delays, gates, aircraft; past routes (*off by default*) | Flight number and date | Weekly far out down to every 20 min near departure; past routes one day per request, at most once a day |
| [FlightAware AeroAPI](https://www.flightaware.com/commercial/aeroapi/) (your key) | Gates, tail number and inbound plane when AeroDataBox lacks them; past routes fallback | Flight number | Only when something's missing in the last 12 h, on a gate change, or on pull to refresh |
| GitHub | Update check | Nothing | Daily (can be turned off) |
| Your mail server | Gmail scan (optional) | Your address and app password, encrypted on the phone | Manual, or every 1–4 weeks |

Backups are a JSON file you export yourself.

## Code layout

One Android module, `app`, under `app/src/main/java/com/aria/air/`:

| Package | What lives there |
|---|---|
| `com.aria.air` | `App`, `MainActivity`, navigation, and the workers, receivers and widget class whose names Android stores |
| `util` | Clock, geo math, formatting |
| `data` | The flight model and its status logic, `Store` (flights.json, backups), `Prefs`, bundled airports and guides |
| `api` | One client per service in the table above, over a shared `Http` helper |
| `sync` | Deciding what to ask which service and when, and merging the answers |
| `background` | The background flight check, notifications, mail scans, self-update |
| `importer` | Reading flights out of documents, byAir exports and email |
| `ui` | Shared components, the maps, each screen, the home screen widget |
| `debug` | The developer request log and reports |

---

Copyright (C) 2026 Patrick Geneva. Licensed under the [GPL-3.0](LICENSE).
