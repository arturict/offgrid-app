# OffGrid

Messenger über LoRa-Funk, ohne Mobilnetz und ohne Internet. Eine kleine Antenne am USB-C-Anschluss
des Android-Handys funkt verschlüsselte Kurznachrichten an gekoppelte Kontakte, direkt oder über
einen Node.

Projekt von Team OffGrid am **Zentral Hack 2026** (23. bis 24. Oktober 2026, HSLU Rotkreuz),
Challenge «Bring your own challenge» von Suva, Kategorie Young Talents. Stand dieses Dokuments:
24. Oktober 2026, App-Version 0.3.2.

> **Wo die eingereichte App liegt:** Die App ist die native Android-App im Ordner
> [`native-android/`](native-android/) (Kotlin, Jetpack Compose). Die übrigen Dateien im
> Wurzelverzeichnis (`src/`, `android/`, `package.json`, `tickets/`, `.kilo/` usw.) gehören zur
> Ionic-/Angular-App, mit der das Team gestartet hat. Sie bleiben zur Nachvollziehbarkeit im Repo,
> sind aber **nicht** die eingereichte App.

| | Link |
|---|---|
| Website mit Simulator und Download | https://offgrid-mesh.ch |
| App-Release 0.3.2 (APK) | https://github.com/arturict/offgrid-app/releases/tag/v0.3.2 |
| Firmware der Antenne | https://github.com/OffGridCH/firmware |
| Team-Repository der App | https://github.com/OffGridCH/app |
| Dieses Repository (App) | https://github.com/arturict/offgrid-app |

## Problem

Wer allein im Funkloch arbeitet, erreicht im Notfall niemanden. Unser Beispiel für die
Suva-Challenge: Eine Forstwartin verletzt sich ausser Rufweite, ihr Handy hat keinen Empfang. Sie
soll einem vorbereiteten Kontakt in der Nähe schreiben können, was passiert ist und wo sie ist.

Während des Hackathons fiel in Rotkreuz der Strom aus. Strom und WLAN waren weg, OffGrid lief
weiter, weil die Antennen am Akku der Handys hängen. Mobile Daten funktionierten in dieser Zeit
noch; einen Ausfall des Mobilnetzes haben wir nicht erlebt.

OffGrid ergänzt die Alarmierung, ersetzt aber keinen Notruf. Wer Empfang hat, alarmiert zuerst
112, 144 oder 1414 (Rega).

## Zielgruppe

- Wandernde und Bergsportler, die in den Bergen keinen Empfang haben
- Menschen, die allein im Gelände arbeiten: Forst, Alp, Unterhalt im Gebirge (der Suva-Fall)
- Helferinnen und Helfer vor Ort, wenn bei einem Unwetter oder Stromausfall Netze ausfallen
- Leute in Berg und Hütte, die hinter dem Grat kein Netz mehr haben

## Lösung und Funktionsweise

```
Handy A ──USB-C── Antenne A ~~~ LoRa 868 MHz ~~~ [Node] ~~~ Antenne B ──USB-C── Handy B
         Klartext     (AES-128-CCM, Schlüssel nur auf den Antennen)      Klartext
```

| Baustein | Wie es funktioniert |
|---|---|
| Antenne | Heltec WiFi LoRa 32 V3 (ESP32-S3 und SX1262), rohes LoRa auf 868 MHz, am Handy über USB-C. App und Antenne sprechen JSON Lines über die serielle Schnittstelle («Host Interface v1»). |
| Kopplung | Beide Handys scannen gegenseitig den QR-Code des anderen (X25519-Schlüsseltausch). Die App führt Schritt für Schritt durch die Kopplung. |
| Verschlüsselung | Nachrichten sind von Antenne zu Antenne mit AES-128-CCM verschlüsselt. Die Schlüssel bleiben auf der Antenne, das Handy entsperrt sie mit einem Token. Zwischen Handy und Antenne läuft der Text unverschlüsselt über das USB-Kabel. |
| Weiterleiten | Ein Node (eine Antenne ohne Handy) leitet Pakete weiter, höchstens 3 Hops, mit Erkennung doppelter Pakete. |
| Quittung | Der Empfänger schickt eine Empfangsbestätigung (ACK) zurück. Bleibt sie aus, sendet die Antenne erneut. |
| Status in der App | «wartet auf Funk» → «gesendet» → «zugestellt» |
| Sendezeit | Das 868-MHz-Band erlaubt je nach Teilband nur einen kleinen Anteil Sendezeit (oft 1 %). Die Labor-Firmware hält ein Sendebudget ein; die Team-Firmware auf `main` hat die feste Begrenzung für den Laborbetrieb entfernt (OffGridCH/firmware#15). Vor einem Einsatz draussen muss sie wieder rein. |
| Text | Bis 83 Byte pro Funkspruch (ein Umlaut zählt doppelt). Längere Texte teilt die App in mehrere Nachrichten. |

Weitere Funktionen der App: Standort senden und ansehen mit Kompasspfeil, der sich mit dem Handy
dreht, antippbare Links, Benachrichtigungen ohne Inhalt auf dem Sperrbildschirm, verschlüsseltes
Backup, Zurücksetzen der Antenne.

## Vor dem Hackathon und während des Hackathons

Bestehendes Projekt: Idee, Designs und Spezifikationen gab es schon vor dem Event. Gebaut wurde in
den 24 Stunden.

| Schon vor dem Event (bis 23.10.2026, 18:00) | Neu während der 24 Stunden |
|---|---|
| Projektbeschreibung «OffGrid» (Idee, Nodeless- und Node-Setup, Einsatzfälle), Anfang Oktober | Firmware mit echtem LoRa-Funk, Weiterleiten und Verschlüsselung (Nils Utiger, [OffGridCH/firmware](https://github.com/OffGridCH/firmware), erster Commit 23.10. 19:19) |
| App-Designs und Logo in Figma | Native Android-App von 0.1 bis 0.3.2 (erster Commit 24.10. 00:06): Einrichtung der Antenne, geführte Kopplung, Chat mit Zustellstatus, Standort mit Kompasspfeil, Links, Zurücksetzen der Antenne, Backup |
| Planung: UI-Mockup, Datenmodell (ERM), Moduldiagramm, Ablauf von Verschlüsselung und Handshake | Gruppenchats in App und Firmware gebaut, auf den Boards noch nicht getestet |
| Spezifikationsentwürfe «Offgrid Protocol v1» (Funkprotokoll) und «Offgrid Host Interface v1» (USB-Schnittstelle), am 23.10. um 20:48 ins Firmware-Repo hochgeladen | Tests mit drei Heltec-V3-Boards über die serielle Schnittstelle, vier Firmware-Korrekturen als PRs (OffGridCH/firmware #11 bis #14) |
| Ionic-App-Repository mit Ticket-Workflow (T00 bis T22) und Kilo-Agenten, angelegt am 20. und 22.10. | Website https://offgrid-mesh.ch mit Simulator und App-Download, Film, Pitch |

Die Ionic-App im Wurzelverzeichnis ist während des Events weiterentwickelt worden, ist aber nicht
die eingereichte App (siehe oben). Die Git-Historie dieses Repositorys zeigt beides: die Ionic-Commits
ab 20.10. und die native App unter `native-android/` ab 24.10.

## Ergebnisse und Stand

Getestet am Morgen des 24. Oktober mit drei Heltec-V3-Boards in einem Raum, gesteuert über die
serielle Schnittstelle, mit der Labor-Firmware (Team-Firmware plus die Korrekturen aus #11 bis #14).

| Was | Ergebnis |
|---|---|
| Senden per LoRa | Funktioniert echt, ohne Simulation. RSSI etwa −20 dBm, SNR etwa 11 dB im Raum |
| Direkt, bis zur Quittung | 0,6 bis 1,1 s |
| Über einen Node (A → B → C), bis zur Quittung | 2,9 bis 6,9 s, ohne doppelte Nachrichten. A und C ignorieren sich per Liste, damit sie sich im selben Raum nicht direkt hören |
| Mesh-Testlauf (Hop-Limit, Node aus und wieder an, gesperrter und abwesender Empfänger, Neustart) | 135 von 135 Prüfungen bestanden |
| Kompression OGZ1 (nur Labor-Firmware) | rund 27 % weniger Sendezeit bei 150 synthetischen Testnachrichten |
| Reichweite im Gelände | **nicht gemessen** |

Offen:

- Gruppenchats sind gebaut, aber nicht auf den Boards getestet. Die App zeigt sie nur, wenn die
  Antenne die Gruppen-Firmware aus OffGridCH/firmware#16 hat.
- Reichweite im Gelände ist nicht gemessen; wir nennen keine Zahl.
- Weiterleiten-Steuerung und OGZ1 sind in der Labor-Firmware, noch nicht in der Hauptversion.
- Die native App liegt in diesem Fork; die PRs ins Team-Repository (OffGridCH/app #22 bis #27) sind
  offen.

## Nächste Schritte

1. Reichweite im Gelände messen, mit Distanz, Gelände und Funkparametern.
2. Gruppenchats auf den Boards testen.
3. Native App ins Team-Repository OffGridCH/app übernehmen und die Ionic-App ablösen.
4. Sendezeitbegrenzung in der Team-Firmware wieder einbauen.

## Geschäftsmodell (Konzept)

Ein erster Entwurf. Preise und Zahlungsbereitschaft haben wir noch mit niemandem geprüft.

| Angebot | Idee |
|---|---|
| Hardware-Kit | Die Antenne zum Einstecken, die App gratis dazu |
| Nodes für Vereine und Hütten | Wer einen Node aufstellt, erweitert die Abdeckung für alle in der Umgebung. Als Bausatz oder fertig montiert |
| Betriebe | Geräte, Einrichtung und Betreuung lokaler Nodes, zum Beispiel für ein Forstteam |

## Installation und Demo

Ohne Hardware: Der Simulator auf https://offgrid-mesh.ch zeigt den Ablauf mit zwei Handys im Browser.

Mit Hardware (Android-Handy mit USB-C-OTG und ein Heltec WiFi LoRa 32 V3 pro Person):

1. APK 0.3.2 laden, von https://offgrid-mesh.ch/app.html oder vom
   [GitHub-Release v0.3.2](https://github.com/arturict/offgrid-app/releases/tag/v0.3.2).
   SHA-256: `ee3ea73416a7d77447783feb7edb0a7b936c044583fa1d5f12784c5011547385`
2. Firmware auf die Antenne flashen, aus https://github.com/OffGridCH/firmware mit PlatformIO:
   `pio run -e heltec_wifi_lora_32_V3 -t upload`
3. App installieren, Antenne einstecken, Namen eingeben, Kontakt per gegenseitigem QR-Scan koppeln,
   schreiben.

Selbst bauen (JDK 17 oder neuer, Android SDK 35, `sdk.dir` in `native-android/local.properties`):

```sh
cd native-android
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # Unit-Tests
```

Im Emulator nutzt die App eine simulierte Antenne. Details zu Build, Signierung und Architektur
stehen in [`native-android/README.md`](native-android/README.md).

## Verwendete Open-Source-Software

Android-App (`native-android/gradle/libs.versions.toml`):

| Komponente | Version | Lizenz |
|---|---|---|
| Kotlin, kotlinx.coroutines, kotlinx.serialization | 2.1.0, 1.9.0, 1.7.3 | Apache 2.0 |
| Jetpack Compose (BOM), Material 3, Navigation, Lifecycle | 2024.12.01, 2.8.5, 2.8.7 | Apache 2.0 |
| AndroidX Room, CameraX, Core, Activity, SplashScreen | 2.6.1, 1.4.1, 1.15.0, 1.9.3, 1.0.1 | Apache 2.0 |
| ZXing (QR-Codes) | 3.5.3 | Apache 2.0 |
| usb-serial-for-android (mik3y) | 3.8.1 | MIT |
| Bouncy Castle (bcprov-jdk18on, X25519/HKDF) | 1.79 | Bouncy Castle Licence (MIT-artig) |
| JUnit, sqlite-jdbc (nur Tests) | 4.13.2, 3.41.2.2 | EPL 1.0, Apache 2.0 |
| Schriften Schibsted Grotesk, Geist Mono | | SIL Open Font License |

Firmware (`platformio.ini` in OffGridCH/firmware):

| Komponente | Version | Lizenz |
|---|---|---|
| PlatformIO espressif32 mit Arduino-ESP32 | 6.12.0 | Apache 2.0 / LGPL 2.1 |
| mbedTLS (Teil von ESP-IDF; AES-CCM, Zufall) | | Apache 2.0 |
| RadioLib (SX1262) | 7.8.1 | MIT |
| ArduinoJson | 6.21.5 | MIT |
| U8g2 (OLED) | 2.36.15 | BSD 2-Clause |
| Adafruit NeoPixel | 1.12.5 | LGPL 3.0 |

## Einsatz von KI

| Werkzeug | Wofür |
|---|---|
| Claude Code (Claude Opus 5.5, Anthropic) | Code der nativen Android-App, Website mit Simulator, Testskripte für die Boards, Firmware-Korrekturen, Film, Pitch-Unterlagen, dieses README |
| Codex (GPT-6.1 Sol, OpenAI) | Code, Website, Film und Pitch zusammen mit Claude Code; Reviews von Pull Requests und der Pitch-Unterlagen |
| Kilo Code | Agenten-Workflow im Team-Repository der Ionic-App (`kilo.jsonc`, `.kilo/`: Planner, Coder, Reviewer, Tester, Documenter); gemergt haben nur Menschen |
| ElevenLabs | Nur für eine optionale Version des Films mit Sprecherstimme; im Pitch nicht verwendet |

Vom Team stammen: Idee und Einsatzfälle, die App-Designs und das Logo in Figma, Architektur und
Spezifikationen von Firmware, Funkprotokoll und USB-Schnittstelle, die Firmware von Nils Utiger,
die Entscheidungen über Umfang, App-Version und Pitch. Die Testläufe mit den drei Boards hat ein
Agent auf Anweisung des Teams ausgeführt und protokolliert. Die KI hat nach diesen
Vorgaben Code und Texte geschrieben; das Team hat die Ergebnisse geprüft und entschieden, was
übernommen wird.

## Team

| Name | Arbeitgeber |
|---|---|
| Nils Utiger | Suva |
| Artur Ferreira | CKW |
| Gianluca Barbieri | Suva |
| Jonathan Wirz | Suva |
| Pascal Hocher | Suva |
