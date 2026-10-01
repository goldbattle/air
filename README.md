# Air

A private flight tracker and travel passport for Android. Track your own flights and friends' live, see your travel
history on a map, and import confirmations by just sharing them to the app. Everything stays on the phone.

<!-- screenshots:start -->
## Screenshots

Made-up example data (the two flights in the air are real flights at the time of capture). Regenerate with `python tools/screenshots.py`.

| **Flights** | **In flight** | **Passport** |
|---|---|---|
| <img src="docs/screenshots/flights.png" width="260"> | <img src="docs/screenshots/flight_live.png" width="260"> | <img src="docs/screenshots/passport.png" width="260"> |
| Upcoming trips; a flight in the air shows live progress, speed and altitude. | Route map with the live plane and the phase bar: pre-travel, departure, in the air, arrival. | Your flights on a map with miles, time in the air, airports and countries. |
<!-- screenshots:end -->

## Features

- Live tracking for your own flights and friends', with gate, delay, and takeoff/landing notifications.
- A flight page with a route map, schedule, weather at both ends, and (while airborne) speed, altitude and heading.
- Import by sharing a confirmation email, PDF, or screenshot to the app; OCR and PDF text extraction run on-device.
- A passport view: every past flight on a map, with miles, time in the air, and top airports/airlines/routes.
- A home screen widget with the route map and a countdown to your next flight.
- Bundled guides for airports (terminals, maps, lounges) and airlines (check-in, baggage, alliance).

## Install

Download the latest APK from [Releases](https://github.com/goldbattle/air/releases) and sideload it. Air checks
GitHub for new releases itself (Settings > About > Check for updates, with an optional daily background check) and
installs them in place.

## Privacy

Air talks to [adsb.lol](https://adsb.lol) for routes and live positions (sends a flight's callsign) and, if you add
your own key, [FlightAware AeroAPI](https://www.flightaware.com/commercial/aeroapi/) for schedules and gates (sends
the flight number). Nothing else leaves the phone, there's no account and no analytics, and backups are a JSON file
you export yourself.

## Building from source

Requires Android Studio or the command line with `ANDROID_SDK_ROOT` set.

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk, debug-signed for sideloading
./gradlew testDebugUnitTest
```

---

Copyright (C) 2026 Patrick Geneva. Licensed under the [GPL-3.0](LICENSE).
