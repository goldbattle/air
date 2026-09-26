# Air

Android flight tracker and travel passport (Jetpack Compose, iOS-style UI). Private by design: everything stays on the phone.

## Tabs

- **Flights**: upcoming and past flights, grouped by day. Tap **+** to search a flight number and date, or import.
- **Friends**: the same, for other people's flights, filterable by person. They get the same live progress and alerts.
- **Passport**: your finished flights on a world map, with miles, time in the air, airports, airlines and countries, by year or all time.
- **Settings**: About (name, privacy), Notifications, Data (FlightAware key), Backup (export/restore JSON).

**Flight page**: route map with the live plane, then a phase bar like the airline apps (Pre-travel · Departure · PIT → SFO · Arrival). Each phase has its own stats: countdown, boarding, seat, confirmation; terminal, gate, delay; speed, altitude, heading, time and distance left; arrival gate, baggage belt, time zone change.

**Live Updates**: at the gate and in the air, a flight shows an ongoing notification with a progress bar, a countdown chip in the status bar, and speed and altitude. Alerts cover takeoff, landing, delays, gate changes, cancellations, and a reminder before your own flights.

## Data sources

| What | Source | Sent |
|---|---|---|
| Airports, airlines, coastlines | Bundled (OurAirports, OpenFlights, Natural Earth), rebuilt with `tools/make_data.py` | nothing |
| Route for a flight number, live position/speed/altitude | [adsb.lol](https://adsb.lol) (free, open data) | callsign |
| Schedule, delays, gates, baggage | [FlightAware AeroAPI](https://www.flightaware.com/commercial/aeroapi/) (optional, your key) | flight number |
| Import from PDF / screenshot / email | Android PDF text layer and ML Kit OCR, on-device | nothing |

Share an email, PDF or screenshot from any app (for example Gmail) to Air to import its flights.

## Airport and airline guides

Guides for airports (terminals, maps, lounges, tips) and airlines (color, logo, rules) are JSON files in `app/src/main/assets/guides/`. In Claude Code, use `/add-airport SFO` or `/add-airline UA` to research and add one.

## Build

`./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (signed with the debug key for sideloading). Tests: `./gradlew testDebugUnitTest`.
