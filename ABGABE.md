# OffGrid – Abgabe Zentral Hack 2026

Challenge «Bring your own challenge by SUVA», Kategorie Young Talents. Team: Nils Utiger, Artur Ferreira,
Gianluca Barbieri, Jonathan Wirz, Pascal Hocher. Ausführliche Dokumentation im [README](README.md).

## Problem

In den Bergen und im Gelände gibt es oft keinen Empfang – genau dort passieren Unfälle. Wer allein
unterwegs ist, erreicht dann niemanden. Beispiel für die Suva-Challenge: Eine Forstwartin verletzt sich
ausser Rufweite. Während des Hackathons fiel in Rotkreuz der Strom aus: Strom und WLAN waren weg,
OffGrid lief weiter.

## Zielgruppe

Wandernde und Bergsteiger ohne Empfang (Kernzielgruppe), Menschen, die allein im Gelände arbeiten (Forst,
Alp), Krisenvorsorge (Hütten, Vereine, Gemeinden).

## Lösung

OffGrid ist ein Messenger über LoRa-Funk (868 MHz) ohne Mobilnetz und Internet. Eine kleine Antenne
(Heltec V3) steckt per USB-C am Android-Handy. Drei Kernfunktionen:

- **Ende-zu-Ende verschlüsselt, von Antenne zu Antenne:** Kontakte koppeln per gegenseitigem QR-Scan
  (X25519), Nachrichten laufen AES-128-CCM-verschlüsselt über Funk. Kein Server, kein Anbieter.
- **Mesh:** Jedes Gerät leitet weiter (höchstens 3 Hops) und vergrössert so die Reichweite. Weiterleitende
  Nodes sehen nur den Kopf des Pakets, nicht den Inhalt.
- **Secure Vault:** Identität und Kontaktschlüssel liegen AES-256-GCM-verschlüsselt in der Antenne und
  lassen sich nur mit dem gekoppelten Handy entsperren.

Eine Quittung zeigt in der App «zugestellt».

## Vor dem Hackathon und während des Hackathons

Vor dem Event gab es die Idee, Figma-Designs, die Planung und die Spezifikationsentwürfe für Funkprotokoll
und USB-Schnittstelle. In den 24 Stunden entstanden die Firmware mit echtem Funk, Weiterleiten,
Verschlüsselung und Secure Vault, die native Android-App (0.1 bis 0.3.2), Tests mit drei Boards (direkt
0,6–1,1 s, über einen Node 2,9–6,9 s bis zur Quittung, 135/135 Prüfungen), ein Demo-Video, die Website,
ein Film und der Pitch. Die Reichweite im Gelände ist noch nicht gemessen, Gruppenchats sind auf den
Boards noch nicht getestet.

## Geschäftsmodell (Konzept, Preise ungeprüft)

- **Produkt verkaufen:** Antenne als Kit, App gratis.
- **OffGrid-Standard lizenzieren:** das Kommunikationsprotokoll für Geräte und Produkte anderer Firmen.
- **Netz und Betriebe:** Nodes für Hütten und Vereine, Geräte und Betreuung für Betriebe.

## Offenlegung

Open Source und KI-Werkzeuge sind im [README](README.md#verwendete-open-source-software) aufgeführt
(RadioLib, Arduino-ESP32 mit mbedTLS, ArduinoJson, U8g2, usb-serial-for-android, Bouncy Castle, ZXing,
Jetpack Compose; Claude Code, Codex, Kilo Code).

## Links

- Repository: https://github.com/arturict/offgrid-app
- Website mit Simulator und APK: https://offgrid-mesh.ch
- App-Release 0.3.2: https://github.com/arturict/offgrid-app/releases/tag/v0.3.2
- Firmware: https://github.com/OffGridCH/firmware
