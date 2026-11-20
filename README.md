# Shield Gallery – Kunstrahmen-Bildschirmschoner für NVIDIA Shield / Android TV

> 🤖 **Hinweis:** Dieser Code wurde mit KI erstellt (Claude Code). Details: [KI-HINWEIS.md](KI-HINWEIS.md)

Zeigt Gemälde wie ein gerahmtes Bild an der Wand: Passepartout mit Schrägschnitt,
Papierstruktur, weiches Raumlicht, sanfte Überblendungen.

## Installation auf dem Shield
1. Shield: Einstellungen → Geräteeinstellungen → Info → 7× auf „Build“ klicken (Entwickleroptionen),
   dann Entwickleroptionen → „Netzwerk-Debugging“ einschalten. IP notieren.
2. Am PC: `adb connect <SHIELD-IP>:5555` (am TV bestätigen), dann `adb install -r apk/shield-gallery.apk`
   (Alternativ: APK per „Send files to TV“ übertragen und mit einem Dateimanager installieren.)
3. Als Bildschirmschoner setzen: Einstellungen → Geräteeinstellungen → Bildschirmschoner →
   Bildschirmschoner → **Galerie**. Dort auch „Startzeitpunkt“ wählen.
   Oder per adb:
   `adb shell settings put secure screensaver_components app.galerie.tv/.ArtDreamService`

## Modi
- **Art**: Bild im Rahmen-Stil mit Passepartout, Leinwand-Effekt; auf Wunsch mit Uhr und Wetter als dezente Zeile auf dem Passepartout
- **Wetter**: Vollbild 16:9 mit Uhr, Datum und Wetter; Position wählbar (oben/unten × links/mittig/rechts)
- **Galerie**: Diashow im Vollbild ohne Effekte

## Bedienung
- App „Galerie“ starten = Kunstmodus dauerhaft (◀ ▶ Bild wechseln, ▼ Modus wechseln, OK Info, ▲ Einstellungen)
- Einstellungen: Quellen, Passepartout-Stil/-Farbe, Bildgröße, Helligkeit, Wechselintervall

## Eigene Bilder
- Einstellungen → „Bilder vom Handy oder PC hinzufügen“ → QR-Code scannen → Bilder im Browser hochladen
  (Port 8765, nur solange dieser Bildschirm offen ist)
- oder Ordner **Galerie** auf einem USB-Stick, dann „Auch Ordner vom USB-Stick“ aktivieren

**Formate:** JPG, PNG, WebP und **HEIC/HEIF** (iPhone). HEIC wird beim Hochladen schon im Browser
in JPEG umgewandelt (Safari nativ, sonst mit dem mitgelieferten heic2any), notfalls auf dem Shield.
Hochkant aufgenommene Fotos werden dabei richtig gedreht. HEIC-Dateien vom USB-Stick dekodiert das Shield
selbst (Android-ImageDecoder, ab Android 9).

## Bildformat
Standard: randlos, auf 16:9 zugeschnitten, Mindestauflösung Full HD (wahlweise 4K).
Ausgabe in der physischen Auflösung des Fernsehers (bis 3840×2160) über eine SurfaceView.

## Wetter
Open-Meteo (ohne API-Schlüssel). Ort in den Einstellungen eintragen.

## Bauen
`./gradlew assembleRelease` (JDK 17, Android SDK 34) → `app/build/outputs/apk/release/app-release.apk`
Vorschau-Renderings ohne Gerät: `./gradlew testReleaseUnitTest` → `app/build/previews/`

## Signatur
Die fertige APK in `apk/` ist mit einem privaten Schlüssel signiert, der nicht im Repo liegt.
Wer selbst baut, bekommt ohne eigenen Schlüssel eine Debug-Signatur
(eigenen Schlüssel als `app/galerie-release.jks` ablegen, Passwörter per
`GALERIE_STORE_PASSWORD` / `GALERIE_KEY_PASSWORD` setzen).

## Bildquellen
Museumsbilder: [The Metropolitan Museum of Art Open Access](https://www.metmuseum.org/about-the-met/policies-and-procedures/image-resources) (gemeinfrei, CC0).
Wetter: [Open-Meteo](https://open-meteo.com/) (CC BY 4.0).

## Lizenz
**Nicht kommerziell** – [PolyForm Noncommercial 1.0.0](LICENSE.md).

- ✅ Erlaubt: privat nutzen, ändern, weitergeben – auch durch gemeinnützige Organisationen, Schulen, Forschung
- ❌ Nicht erlaubt: jede kommerzielle Nutzung (z. B. verkaufen, in kostenpflichtige Produkte oder Dienste einbauen, im Firmeneinsatz)
- 📌 Pflicht: Wer den Code weitergibt, muss die Lizenz und diesen Hinweis mitgeben:
  `Required Notice: Copyright (c) 2026 Goeste (https://github.com/Goeste)`

Für eine kommerzielle Nutzung bitte [Goeste](https://github.com/Goeste) fragen.

Drittanbieter-Komponenten behalten ihre eigenen Lizenzen: siehe [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
