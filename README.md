# Tankful

Local-first vehicle logbook for Android. Money and fuel math live in `:domain`. Room, providers, and backup live in `:data`. The NIP-55 signer and relays live in `:sync`. The Compose UI lives in `:app`.

## Requirements

- JDK 17 with `javac`. On this machine the OpenJDK 21 package is a JRE without `javac`, so the project compiles to bytecode 17.
- Android SDK with compileSdk 36 and build-tools 36.0.0. `local.properties` sets `sdk.dir`.
- minSdk 26. No Google Play services.

## Commands

```bash
./gradlew :domain:test :data:testDebugUnitTest :sync:testDebugUnitTest :app:testDebugUnitTest
```

Use the Gradle wrapper, not the system Gradle. The wrapper is Gradle 8.13.

Domain tests do not need an emulator. Room tests use Robolectric. The app has not been checked on a device or an emulator.

## Language

English in `app/src/main/res/values/strings.xml` is the source catalog. Code comments and this file stay in English. Other languages are resource translations and follow the device language. On Android 13 and later, the system per-app language list comes from `locales_config.xml`.

Home, Timeline, Insights, Garage, the phrases "above official", "below official", and "matches official", and "Spend ÷ odometer span" stay in English in every language. So do the words odometer, signer, sync, and relay, and the labels Ownership, Correction, and Replacement.

Shipped translations, besides English: Bulgarian, Croatian, Czech, Danish, Dutch, Estonian, Finnish, French, German, Greek, Hungarian, Irish, Italian, Latvian, Lithuanian, Maltese, Polish, Portuguese (European), Romanian, Slovak, Slovenian, Spanish, Swedish, Chinese (Simplified, `values-zh`; the per-app locale is `zh-CN`), Japanese, and Russian. There is no in-app language picker.

## Map

The stations screen uses MapLibre Android 11.11.0 with the OpenFreeMap Liberty style. MapLibre Compose 0.18.0 is not used: it wants compileSdk 37. Stations come from Overpass only when you ask, with a 7-day cache. Italian board prices come from the MIMIT open data when a pump is within 300 m. Attribution: OpenMapTiles, OpenStreetMap, OpenFreeMap. The logbook works without that screen.

## Sync

Provisional kind 8472, unregistered, to be checked again before any publish. Sync stays off until you turn it on. Amber keeps the key. A pasted nsec is encrypted with the Android Keystore on this phone and is not sent to a relay.

## Official catalogue

The vehicle picker unpacks `app/src/main/assets/official_catalogue.pack` into a read-only SQLite database. That file is gzip. The name is not `.gz` because the Android packager would unpack it and put the 132 MB database back into the APK. `tools/catalogue/build_catalogue.py` writes the pack. The raw database is above GitHub's file limit, so it is not committed. It is not the Room logbook. Raw downloads under `catalog/raw/` are not shipped. The picker starts with an alphabetical make list, then the models of that make. After that it offers only the years, fuels, displacements, and power values present on those rows. Electric, mild hybrid, and hydrogen vehicles can be saved without an official figure. Displacement is read from the published variant text. Power is the published kilowatt figure, with metric horsepower beside it. EPA rows have displacement and no power figure.

This is not every car on the road. Three official tables are included. Each figure is copied as published:

- US EPA Fuel Economy, public domain, model years from 2003. Combined economy is miles per gallon and CO2 is grams per mile. https://www.fueleconomy.gov/feg/download.shtml
- ADEME car labelling, Licence Ouverte, editions 2003–2015, for cars commercialised in France. Combined consumption is NEDC litres per 100 km. The year is the labelling edition. https://www.data.gouv.fr/datasets/emissions-de-co2-et-de-polluants-des-vehicules-commercialises-en-france
- European Environment Agency CO2 monitoring of new passenger cars, registration years 2021–2025. Consumption is WLTP litres per 100 km. The year is the registration year. Rows are distinct published figures, not an average. Source: European Environment Agency (EEA). https://www.eea.europa.eu/en/about/contact-us/faqs/where-can-i-find-the-co2-emission-levels-of-new-passenger-cars-and-vans-in-europe

There is no compact official European consumption table in this database for 2016–2020. The current ADEME extract is a min/max band without a year and is not imported. Natural gas, E85, electricity, hydrogen, and miles-per-gallon-equivalent figures are skipped rather than converted. A real full-tank average is compared with the official combined figure when that figure is liquid fuel, including a non-plug-in hybrid. Weighted and charge-sustaining plug-in figures are stored and are not turned into a percentage.

The phone unpacks the gzip asset into app files when the SQLite user version changes. Rebuild with `catalog/.venv/bin/python tools/catalogue/build_catalogue.py` after `python3 tools/catalogue/fetch_eea.py`.

## License

The Tankful code is under the MIT license. See `LICENSE`. The bundled official figures stay under the terms of their publishers, named above.
