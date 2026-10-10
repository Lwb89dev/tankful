# Changelog

## 0.1.0

First build of Tankful, a local-first Android logbook for a vehicle. The record stays on the phone.

- Refuels, odometer readings, expenses, and maintenance are stored in Room. A bi-fuel visit records petrol with LPG, or petrol with CNG, each with its own quantity and unit price. The total is the quantity times the unit price. A saved refuel can be corrected from Insights. The odometer is required on that form.
- Insights draws consumption from one full tank to the next full tank of the same fuel, and distance against spend. Units follow the phone: metric litres and kilometres, or imperial gallons and miles. Twenty currencies, with no exchange rates.
- Garage names the vehicle from the bundled official catalogue. The comparison uses the published EPA, ADEME, and EEA figures. Those tables keep their publishers' terms. The Tankful code is MIT.
- The station map uses MapLibre and the OpenFreeMap Liberty style. Search reads OpenStreetMap fuel and charging nodes from Overpass, up to the area on screen. Posted prices are the Italian MIMIT board when a station is within 300 metres. Stations outside Italy are shown without an invented price. A station can be handed to Roadstr as a destination.
- Optional Nostr sync encrypts the logbook to your own key with NIP-44 before a relay sees it. Amber is the signer. A pasted nsec stays sealed in the Android Keystore and is not published. The on-device logbook file is SQLCipher. Sync stays off until you turn it on. Photos are not synced.
- Settings exports and imports a backup, chooses the theme, edits the relay list, and opens the Lightning address `lwb89@blink.sv`, the same address Roadstr uses. If no wallet is installed, the address is copied.
- English is the source language. The app also ships Bulgarian, Croatian, Czech, Danish, Dutch, Estonian, Finnish, French, German, Greek, Hungarian, Irish, Italian, Latvian, Lithuanian, Maltese, Polish, Portuguese, Romanian, Slovak, Slovenian, Spanish, Swedish, Chinese, Japanese, and Russian.
