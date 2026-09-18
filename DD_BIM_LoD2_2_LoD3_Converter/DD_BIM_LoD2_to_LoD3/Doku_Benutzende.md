# LoD2 → LoD3 Pipeline — Doku für Benutzende

Was diese Pipeline aus einem Gebäudemodell macht und wie die Ausgabe fachlich einzuordnen ist. Für Aufruf/Konfiguration siehe [Doku_Administrierende.md](Doku_Administrierende.md).

---

## Was wird erzeugt

Aus einer einfachen Gebäudehülle (Wände, Dach, Grundfläche) wird pro Gebäude ein detailliertes Modell mit folgenden Elementen, jeweils gesteuert über das Baukörpermodul, das dem Gebäude anhand seines `sst`-Attributs zugeordnet ist (siehe Admin-Doku):

- **Keller** — unterhalb der Geländeoberfläche, inkl. Kellerwänden und -boden, wenn das Modul einen Keller vorsieht.
- **Geschosse** — die Gebäudehülle wird in Erdgeschoss, Obergeschosse und ggf. Dachgeschoss unterteilt, mit eigenen Böden und Decken je Geschoss.
- **Türen** — mindestens die Eingangstür im Erdgeschoss.
- **Fenster** — auf allen Geschossen inkl. Keller, nach den im Modul hinterlegten Abständen und Maßen; auf verdeckten Wandabschnitten (hinter einem Anbau) werden bewusst keine platziert. Kellerfenster werden links und rechts unter jeder Tür ausgespart (0,40 m Puffer je Seite) — dort liegt in der Realität die kleine Außentreppe zur Tür.
- **Balkone** — wenn das Modul ein Balkon-Muster vorsieht, inkl. Balkontür.
- **Dachfenster** — auf geneigten Dachflächen, wenn das Modul welche vorsieht.

Ein Gebäude kann je nach zugeordnetem Modul auch nur einen Teil dieser Elemente bekommen (z.B. kein Keller, wenn keiner vorgesehen ist) — das ist beabsichtigtes Verhalten, kein Fehler.

---

## Datenqualität einordnen

Jeder Lauf wird gegen zwei unabhängige Prüfwerkzeuge verifiziert:

- **val3dity** prüft, ob jedes Gebäude ein geometrisch sauberes, wasserdichtes 3D-Volumen ist (ISO-19107-Solid-Validität) — z.B. keine offenen Lücken in der Hülle, keine Selbstüberschneidungen.
- **CityDoctor2** prüft zusätzlich semantische Regeln (z.B. zeigt ein Kellerboden wirklich nach unten, ist eine Fläche wirklich planar).

**Letzter kompletter Lauf über ganz Dresden** (98 Kacheln, 141.670 Gebäude, September 2026): **98,52 %** der Gebäude-Objekte sind bei val3dity valide, **94,63 %** der Gebäude sind bei CityDoctor2 komplett fehlerfrei. Das heißt nicht, dass die restlichen Gebäude unbrauchbar sind — die allermeisten Meldungen sind kleine, lokal begrenzte Abweichungen (z.B. eine leicht nicht-plane Fläche), keine grob falschen Gebäude. Ein Teil der gemeldeten Fehler stammt außerdem nachweislich aus den LoD2-Ausgangsdaten selbst (Digitalisierungsungenauigkeit) oder ist ein bekanntes Artefakt der Prüfwerkzeuge, kein echter Mangel der erzeugten Geometrie (Details: Programmierenden-Doku, Abschnitt „Bekannte offene Punkte").

Mängel, die schon in den LoD2-Ausgangsdaten stecken (z. B. mm-Nähte zwischen Flächen, leicht verdrehte Flächen), repariert diese Pipeline bewusst nicht. Dafür ist der Healer zuständig, der im Projektablauf **vor** dieser Pipeline über die LoD2-Daten läuft. Nach der LoD3-Aufwertung wird nicht mehr geheilt.

**Fehlercodes der Prüfwerkzeuge:** Was die einzelnen Codes bedeuten, erklären die Werkzeuge selbst: [val3dity-Fehlerliste](https://val3dity.readthedocs.io/2.7.0/errors/) und [CityDoctor2-Prüfungen](https://transfer.hft-stuttgart.de/pages/citydoctor/citydoctorhomepage/de/requirements/). Für diese Pipeline sind zwei Punkte wichtig:

- Beide Werkzeuge prüfen die Hülle eines Gebäudes erst, wenn seine Flächen fehlerfrei sind. Behebt eine neue Version einen Flächenfehler, kann deshalb ein schon vorhandener Hüllenfehler zum ersten Mal gemeldet werden. Läufe deshalb Gebäude für Gebäude und gegen das LoD2-Original vergleichen, nicht nur die Gesamtzahlen.
- Bekannte Fehlalarme: `SE_POLYGON_WITHOUT_SURFACE` (CityDoctor2) an Fenstern, Türen und Balkonen; `GE_S_SELF_INTERSECTION`, wenn nur CityDoctor2 sie meldet und val3dity dieselbe Hülle als gültig bewertet.

---

## Meldungen des Programms

Die GUI meldet Probleme auf drei Wegen: als Dialog vor dem Start, als Fehlerdialog bei einem Abbruch und als Zeile im Log-Fenster. Log-Zeilen tragen eine Stufe: `ERROR` heißt, dass etwas nicht verarbeitet wurde; `WARN` ist ein Hinweis, der Lauf geht normal weiter. Beim Aufruf über die Kommandozeile erscheinen dieselben Meldungen in der Konsole. Die Häufigkeiten stammen aus dem Dresden-Lauf vom September 2026 (141.670 Gebäude).

### Vor dem Start

Die GUI prüft die Eingaben, bevor sie etwas verarbeitet. Es wird noch nichts geschrieben.

| Meldung | Ursache und Abhilfe |
|---|---|
| „Bitte Eingabe, JSON-Modulordner und Ausgabeordner auswählen.“ | Ein Pflichtfeld ist leer. |
| „Eingabedatei nicht gefunden.“ / „Eingabeordner nicht gefunden.“ | Der Pfad existiert nicht oder passt nicht zum Modus: Bei „Einzelne Datei“ muss eine Datei gewählt sein, bei „Ordner“ ein Ordner. |
| „JSON-Modulordner nicht gefunden.“ | Der Pfad existiert nicht. |
| „Im JSON-Modulordner „…“ wurden keine .json-Dateien gefunden.“ | Meist wurde der übergeordnete Ordner statt des Ordners mit den Moduldateien (`ER7_4.json` usw.) gewählt. |
| „DGM-Pfad nicht gefunden.“ | Der Pfad existiert nicht. Das Feld darf auch leer bleiben. |
| „Die gewählte Datei „…“ ist keine .gml-Datei.“ | Verarbeitet werden nur `.gml`-Dateien. |
| „Im Ordner „…“ wurden keine .gml-Dateien gefunden.“ | Der Ordner enthält keine `.gml`-Datei. Unterordner werden nicht durchsucht. |
| Log: „Hinweis: „…“ ist keine .gml-Datei und wird übersprungen.“ | Nur zur Information: Andere Dateien im Eingabeordner werden ignoriert. |

### Abbruch: „Die Verarbeitung ist fehlgeschlagen“

Der Lauf bricht ab, und der Dialog nennt die Ursache. Ein unerwarteter Fehler an einem einzelnen Gebäude stoppt die ganze Datei. Hatte das Programm schon mit dem Schreiben begonnen, nennt der Dialog zusätzlich die unvollständige Ausgabedatei. Sie sieht gültig aus, enthält aber nur die Gebäude bis zur Fehlerstelle und darf nicht verwendet werden.

| Meldung (Beispiel) | Bedeutung und Abhilfe |
|---|---|
| `ParseError at [row,col]:[18044,24]` `Message: XML-Dokumentstrukturen müssen innerhalb derselben Entity beginnen und enden.` | Die GML-Datei ist unvollständig, etwa nach einem abgebrochenen Download oder Kopiervorgang. Zeile und Spalte zeigen die Stelle. Datei neu beschaffen. |
| `ParseError at [row,col]:[1,1]` `Message: Vorzeitiges Dateiende.` | Die Datei ist leer. |
| `ParseError at [row,col]:[1,1]` `Message: Content ist nicht zulässig in Prolog.` | Die Datei ist kein XML, z. B. eine umbenannte Text- oder CSV-Datei. |
| `Unbekanntes DGM-Format: …` | Als DGM ist eine Datei mit anderer Endung als `.asc`, `.tif`, `.tiff` oder `.zip` angegeben. |
| `Keine DGM-Dateien (.asc, .tif, .tiff, .zip) gefunden in: …` | Der DGM-Ordner enthält auch in seinen Unterordnern keine DGM-Datei. |
| `Keine DGM-Tiles erfolgreich geladen aus: …` | Keine einzige DGM-Datei im Ordner war lesbar. Die Gründe stehen darüber im Log (`Fehler beim Laden von …`). |
| `DGM: ncols/nrows fehlt oder ungueltig …`, `DGM: xllcorner/yllcorner/cellsize fehlt`, `DGM: Unvollstaendige Daten …`, `DGM: Ungueltiger Header: …` | Die ASCII-Grid-Datei (`.asc`) ist beschädigt oder hat keinen gültigen Dateikopf. |
| `GeoTIFF: Keine Georeferenzierung gefunden in …` (auch `GeoTIFF: Unvollstaendige …`) | Das GeoTIFF enthält keine eingebettete Georeferenzierung. Eine separate World-Datei (`.tfw`) wird nicht ausgewertet. |
| `Kein DGM (.asc/.tif/.tiff) in ZIP gefunden: …` | Das ZIP-Archiv enthält kein DGM. |
| `Zugriff verweigert: …` | Für den Ausgabeordner fehlen Schreibrechte. Einen anderen Ordner wählen. |
| `…: Der Prozess kann nicht auf die Datei zugreifen, da sie von einem anderen Prozess verwendet wird` | Die Ausgabedatei ist in einem anderen Programm geöffnet (z. B. FME, QGIS, Editor). Dort schließen und neu starten. |
| `…: Unable to determine if root directory exists` | Das Laufwerk des Ausgabeordners ist nicht erreichbar, etwa ein getrenntes Netzlaufwerk. |
| Andere Meldung, z. B. `NullPointerException` | Interner Fehler bei einem Gebäude. Log-Inhalt und Eingabedatei an die Entwicklung geben. |

### „Fertig mit Fehlern“

Treten während des Laufs `ERROR`-Zeilen auf, meldet die GUI statt „Erfolgreich abgeschlossen“ den Hinweis „Fertig, aber das Log enthält N Fehlermeldung(en)“. Alles, was die Fehlerzeilen nicht nennen, ist normal verarbeitet. Im Dresden-Lauf trat keine `ERROR`-Zeile auf.

| Log-Zeile | Bedeutung und Abhilfe |
|---|---|
| `[3/98] FEHLER bei <Datei>: …`, am Ende `Fehlgeschlagene Kacheln (n): […]` | Im Ordner-Modus ist eine Kachel gescheitert, die übrigen wurden normal verarbeitet. Die Ausgabe dieser Kachel fehlt oder ist unvollständig. Die Ursache steht in den Zeilen darunter (`Caused by: …`). Am einfachsten die Kachel im Modus „Einzelne Datei“ erneut starten; dann zeigt der Fehlerdialog die Ursache direkt. |
| `Fehler beim Laden von …\ER7_4.json: …MalformedJsonException: … at line 90 column 23 …` | Eine Moduldatei ist fehlerhaft. Alle Gebäude mit diesem `sst` bleiben ohne Keller, Geschosse, Türen, Fenster, Balkone und Dachfenster. Die Datei an der genannten Stelle reparieren und neu starten. |
| `Fehler beim Scannen des JSON-Verzeichnisses: …` | Der Modulordner ist nicht lesbar, kein Gebäude bekommt LoD3-Details. |

### Warnungen im Log

Der Lauf geht normal weiter. Die Zahl in Klammern gibt die Häufigkeit im Dresden-Lauf an.

| Log-Zeile | Bedeutung |
|---|---|
| `Weniger als 2 Punkte an Unterkante fuer Wand …` (22.044) | Ein Wandstück hat keine waagerechte Unterkante, es läuft z. B. nach unten spitz zu. Es bekommt keine Fenster. Unbedenklich. |
| `Tuer passt nicht in Wand …` (8.112), `Wand … zu schmal fuer n Tuer(en) …` (354), `Keine Unterkante gefunden fuer Wand …` (54), `Zu wenig Abstand zwischen Tueren in Wand …` (27) | An dieser Wand ist keine Tür möglich, meist weil sie zu niedrig oder zu schmal ist. Hat das Gebäude danach gar keine Tür, setzt die Pipeline eine Ersatztür (siehe „Bekannte fachliche Einschränkungen“). |
| `WWR=… > 0.6 an Wand …` (847), `Dachflaeche …: WWR … ueberschreitet 0.6 …` | Die Fenster bedecken mehr als 60 % der Wand- bzw. Dachfläche. Nur ein Hinweis, die Fenster bleiben erhalten. |
| `Keine terrain-nahen GroundSurface-Polygone fuer sst=… (gml:id=…)` (18) | Keine Grundfläche dieses Gebäudeteils liegt höchstens 0,5 m über der Geländehöhe `H_DGM`, z. B. bei aufgeständerten Bauteilen oder falschem `H_DGM`. Der Gebäudeteil bekommt keinen Keller. |
| `Deckenstueck bei z=… beruehrt sich selbst und ist nicht reparierbar — verworfen` (6) | Ein Stück Geschossdecke ließ sich nicht fehlerfrei bilden und entfällt. Gebäude im Validator prüfen. |
| `Traufe (…) <= EG-Floor (…) fuer sst=…, ueberspringe` (3) | Die Traufe liegt nicht über dem Erdgeschossboden (`H_DGM` plus Sockelhöhe laut Modul), typisch bei sehr niedrigen Bauten oder falschem `H_DGM`. Der Gebäudeteil bekommt keine Geschosse und damit keine Türen, Fenster und Balkone. |
| `Wand … reicht unter egFloorZ (…), Einzelschnitt dort …` (1) | Eine Wand ließ sich am Erdgeschossboden nicht schneiden und bleibt unverändert. Sie kann sich mit der Kellerwand überlappen; Gebäude im Validator prüfen. |
| `GF.height fehlt/ungueltig fuer sst=…, ueberspringe Geschossteilung` (0) | Im Modul fehlt die Erdgeschosshöhe. Keine Geschosse und damit keine Türen, Fenster und Balkone. |
| `clipSlabAtZ: …`, `roofAreaBelowZ: …`, `unionFootprints: …`, `hasSubstantialRegion: …`, `cutWallAtMultipleZJTS: …` (jeweils 0) | Eine geometrische Berechnung an einem Bauteil ist gescheitert. Das Bauteil bleibt unverändert oder wird vereinfacht gebildet. Gebäude im Validator prüfen. |
| `Fehler beim Laden von <DGM-Datei>: …` | Eine Datei im DGM-Ordner war nicht lesbar, die übrigen werden genutzt. Gebäude in diesem Bereich erhalten eine flache Geländelinie auf Höhe `H_DGM`. |
| `GeoTIFF: GDAL_NODATA-Tag nicht parsbar in …, verwende -9999` | Der Kein-Wert-Eintrag des GeoTIFF war nicht lesbar, angenommen wird -9999. |

### Ohne Meldung

Folgende Fälle erzeugen keine Warnung. Die Zusammenfassung am Ende des Logs (Zeilen `Schritt 1 …` bis `Schritt 7 …`) zeigt aber, ob die Zahlen plausibel sind.

- **Gebäude ohne `sst`-Attribut oder ohne passende Moduldatei** (`{sst}.json` bzw. `{sst}_4.json`): Das Gebäude wird nur auf LoD3 hochgestuft und bekommt keine Keller, Geschosse, Türen, Fenster, Balkone oder Dachfenster. Eine Ersatz-Moduldatei gibt es nicht.
- **Gebäude ohne `H_DGM`-Attribut:** kein Keller und keine Geschosse, damit auch keine Türen, Fenster und Balkone. Dachfenster sind weiterhin möglich.
- **Gebäude außerhalb des DGM:** Die Geländelinie (TerrainIntersectionCurve) liegt flach auf Höhe `H_DGM`.
- **Datei ohne Gebäude**, z. B. ein XML ohne CityGML-Gebäude: Die Ausgabe enthält keine Gebäude, die Zusammenfassung zeigt `0 Gebaeude`.

---

## Bekannte fachliche Einschränkungen

- **Manche Gebäude bekommen keine Tür.** Wenn die Quelldaten für ein Gebäude an keiner Wand eine Tür-Information liefern (eine bekannte Lücke in der vorgelagerten Adresspunkt-Zuordnung), setzt die Pipeline automatisch eine Ersatztür an der breitesten Erdgeschosswand, sofern das Gebäude mindestens ein Fenster hat — ein Gebäude mit Fenstern aber ganz ohne Tür wäre unrealistisch für ein bewohntes Haus. Bleibt auch das erfolglos (sehr seltener Randfall), bleibt das Gebäude ohne Tür.
- **Fenster hinter einem Anbau werden ausgelassen.** Wenn ein angebauter Gebäudeteil eine Wandfläche komplett verdeckt, wird dort kein Fenster platziert — auch wenn das Baukörpermodul eines vorsähe.
- **Nicht jedes Baukörpermodul deckt jedes Element ab.** Ob ein Gebäude einen Keller, Balkone oder Dachfenster bekommt, hängt einzig vom zugeordneten Modul ab (siehe oben) — fehlt ein Element in der Ausgabe, ist meist zuerst zu prüfen, ob es im Modul überhaupt vorgesehen war.
- **Geschosse bei Flügeln und Türmen.** Ragt ein Flügel oder Turm, der in den Quelldaten kein eigener Gebäudeteil ist, über die Haupttraufe hinaus, wird dieser Bereich in weitere Geschosse von mindestens 2 m Höhe unterteilt. Ausgenommen sind sehr kleine oder schmale Aufbauten und Gebäude, deren Geschossdecken durch ein niedrigeres Anbaudach begrenzt sind — dort bleibt das oberste Geschoss höher und hat nur eine Fensterreihe.
- **Stark verdrehte Dachflächen bekommen keine Dachfenster.** Ist eine Dachfläche schon in den Quelldaten spürbar aus der Ebene (mehr als 5 mm), wird dort kein Dachfenster eingesetzt — sonst entstünden Geometriefehler. Betrifft nur sehr wenige Flächen.
