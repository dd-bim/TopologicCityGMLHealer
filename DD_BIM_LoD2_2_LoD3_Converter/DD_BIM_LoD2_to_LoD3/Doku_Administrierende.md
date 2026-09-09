# LoD2 → LoD3 Pipeline — Doku für Administrierende

Installation, Konfiguration, Ausführung, Verifikation. Für Architektur/Algorithmen siehe [Doku_Programmierende.md](Doku_Programmierende.md), für fachliche Einordnung der Ausgabe [Doku_Benutzende.md](Doku_Benutzende.md).

---

## Voraussetzungen

| Komponente | Version |
|---|---|
| Java | 21+ |
| Maven | 3.6+ |
| citygml4j | 3.2.7 (wird automatisch heruntergeladen) |

## Build

```sh
mvn clean package
```

Ergebnis: `target/lod2-zu-lod3-pipeline.jar` (Main-Class `Lod2ToLod3Pipeline`), plus JARs für jeden Einzelschritt.

---

## Ausführung

### Einzelne Datei

```sh
java -jar target/lod2-zu-lod3-pipeline.jar  input.gml  Baukörpermodule_json/  output/  [dgm-pfad]
```

Schreibt eine Ausgabedatei nach `output/`, dabei `LoD2_...` → `LoD3_...` umbenannt (und `_BuildingPreferences` entfernt).

### Ganzer Ordner (Batch-Modus)

Wird automatisch erkannt, wenn das erste Argument ein Ordner statt einer Datei ist:

```sh
java -jar target/lod2-zu-lod3-pipeline.jar  inputFolder/  Baukörpermodule_json/  output/  [dgm-pfad]
```

Verarbeitet alle `.gml`-Dateien im Ordner nacheinander (sequenziell, siehe Programmierenden-Doku für den Grund). Legt unter `output/` einen neuen Unterordner an, dessen Name wie bei den Dateien `LoD2_...`→`LoD3_...` umbenannt ist (enthält der Ordnername kein "LoD2", wird `_LoD3` angehängt). Bricht eine Kachel ab, läuft der Rest weiter — am Ende: Liste der fehlgeschlagenen Dateien + aufsummierte Gesamtstatistik über alle erfolgreichen Kacheln.

**Beispiel-Log (Ausschnitt eines Batch-Laufs):**
```
[1/98] Verarbeite LoD2_33_400_5654_2_SN_BuildingPreferences.gml ...
[1/98] fertig: 215 Gebaeude, 2.260 s -> LoD3_33_400_5654_2_SN.gml
...
Batch abgeschlossen: 98 von 98 Kacheln erfolgreich
Gesamtzeit: 962.674 s
Schritt 1 — Promotion:  141670 Gebaeude, 1946543 Geometrien hochgestuft
Schritt 2 — Keller:     63878 Keller
...
```

### Einzelne Schritte standalone

```sh
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.Lod2ToLod3Promoter   input.gml  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.BasementGenerator    input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.StoreyGenerator      input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.DoorGenerator        input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.WindowGenerator      input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.BalconyGenerator     input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.RoofWindowGenerator  input.gml  jsonDir/  [output.gml]
```

Nützlich zum isolierten Testen eines Schritts — der Junction-Conforming-/Pinch-Split-Schritt (6+7) läuft nur im Pipeline-Modus.

---

## GUI (Desktop-Anwendung)

Für Nutzende ohne CMD/PowerShell/IDE — z. B. Projektpartner — gibt es eine eigenständige `.exe` mit grafischer Oberfläche, die exakt dieselbe, bereits verifizierte Pipeline-Logik aufruft wie die Kommandozeile (kein separater Nachbau, gleiches Ergebnis).

### Herunterladen/Starten

`LoD2zuLoD3.zip` entpacken, `LoD2zuLoD3.exe` im entpackten Ordner doppelklicken. Kein Java, kein CMD nötig — die Laufzeit ist eingebettet. Der allererste Start kann etwas dauern (Windows prüft eine neue, unsignierte `.exe` einmalig), danach startet es normal schnell.

### Bedienung

1. **Modus**: Einzelne Datei oder Ordner (Batch).
2. **CityGML-Datei/Ordner**: die Eingabe (LoD2).
3. **Baukörpermodule (JSON-Ordner)**: Pflichtfeld, siehe Abschnitt „JSON-Baukörpermodule" unten.
4. **DGM** (optional): Datei oder Ordner, siehe „DGM-Einrichtung" unten — ohne Angabe automatisch flache TerrainIntersectionCurve.
5. **Ausgabeordner**: Pflichtfeld.
6. **Erweiterte Optionen** (aufklappbar): einzelne Pipeline-Schritte abwählen, siehe unten.
7. **Konvertierung starten** — Fortschrittsbalken (bei Batch mit echtem „Datei X von Y", bei Einzeldatei mit echtem „Gebäude X von Y" nach kurzem Vorab-Zähllauf) und Live-Log. Am Ende Option, den Ausgabeordner direkt zu öffnen.

### Erweiterte Optionen: einzelne Schritte abwählen

Alle sechs optionalen Schritte (Keller, Geschosse, Türen, Fenster, Balkone, Dachfenster) sind per Häkchen einzeln abwählbar, Standard ist „alle an" (= identisch zum normalen Pipeline-Lauf, ungeöffnet verhält sich die GUI exakt wie ohne dieses Menü). Die Häkchen bilden eine kleine Hierarchie, kein Satz von sechs unabhängigen Schaltern:

- **Türen, Fenster und Balkone brauchen „Geschosse"** — sie platzieren ihre Elemente auf Wandabschnitten, die nur der Geschosse-Schritt erzeugt (eigene Erdgeschoss-/Obergeschoss-Wand mit eigener Boden-/Deckenhöhe). Ohne Geschosse gibt es dafür keine sinnvolle Grundlage, das kann die Pipeline aktuell nicht ersatzweise auf der rohen LoD2-Volltonnen-Wand nachbilden. Deshalb: wird „Geschosse" abgewählt, werden diese drei Häkchen automatisch mit abgewählt **und gesperrt** (ausgegraut, mit Tooltip) — sie lassen sich erst wieder anklicken, sobald „Geschosse" erneut angehakt ist.
- **Keller und Dachfenster sind unabhängig davon** — Keller braucht nur `H_DGM`, Dachfenster arbeiten direkt auf den Dachflächen. Beide bleiben unabhängig vom Geschosse-Häkchen frei wählbar, z. B. für einen schnellen Testlauf „nur Keller + Dachfenster".
- Die strukturellen Schritte 1 (LoD2→LoD3-Umstufung) und 6+7 (T-Naht-Konformierung / Pinch-Point-Aufspaltung) laufen immer und sind nicht abwählbar — sie sind Voraussetzung für ein gültiges LoD3-Ergebnis, unabhängig davon, welche optionalen Schritte liefen.

Technische Details zur GUI (Bibliotheken, Architektur, warum das Abwählen gefahrlos möglich ist): siehe [Doku_Programmierende.md](Doku_Programmierende.md).

---

## JSON-Baukörpermodule

Jedes Gebäude wird über sein `sst`-Attribut einem Modul (`{sst}.json` im JSON-Ordner) zugeordnet. `ModuleParametersLoader` matcht dabei auch ohne `_4`-Suffix (`MRG3_4.json` ist unter `MRG3` UND `MRG3_4` auffindbar). Fehlt eine passende Datei, wird `_default.json` verwendet.

### Kategorien im Überblick

| Kürzel | Bedeutung | Steuert |
|---|---|---|
| `BA` | Basement (Keller) | Kellerhöhe, Kellerfenster, Kellertür |
| `GF` | Ground Floor (EG) | Geschosshöhe, EG-Fenster, Eingangstür |
| `UF` | Upper Floor (OG) | Geschosshöhe, OG-Fenster (wird pro OG wiederholt) |
| `RO` | Roof (Dach) | Dachfenster, Dachform |
| `UT` | Utilities | Versorgungsschächte |
| `GA` | Gallery | Balkone/Terrassen |
| `IN` | Interior | Innenraum-Aufteilung |
| `FL` | Stairwell | Treppenhaus |
| `BU` | Building | Allgemeine Gebäudedaten (Maße, Keller-Flag, Dachform) |
| `FD` | Facade Details | Materialien/Vulnerabilität (nur Metadaten, keine Geometrie) |

### Felder

**`BA` / `GF` / `UF` (Geschosse):**

| Feld | JSON-Key | Bedeutung |
|---|---|---|
| Höhe | `height` | Geschosshöhe in m |
| Deckendicke | `CeHe` | m |
| Höhe über Gelände (nur GF) | `heightGr` | oberirdischer Kelleranteil |
| Polygon-Höhe erweitern (nur UF) | `extPolyHeight` | bool |
| Fenster | `window` | siehe `WindowParams` unten |
| Tür (nur BA/GF) | `door` | siehe `DoorParams` unten |

**`WindowParams`** (in `BA.window`/`GF.window`/`UF.window`/`RO.window`):

| Feld | JSON-Key | Bedeutung |
|---|---|---|
| Abstand Wandecke → 1. Fenster | `HDistWaWi` | m |
| Brüstungshöhe | `VDistFlWi` | Boden → Fensterunterkante |
| Lichter Abstand Fenster-Fenster | `HDistWiWi` | m |
| Abstand Tür-Fenster | `HDistDoWi` | m |
| Fensterbreite/-höhe | `WiLen`/`WiHe` | m |
| Mindestabstand Wandecke-Fensterkante | `HDistMinWaWi` | m |
| Max. Fenster pro Reihe | `MaxWiPerRow` | leer = kein Limit |

**`DoorParams`** (in `BA.door`/`GF.door`):

| Feld | JSON-Key | Bedeutung |
|---|---|---|
| Türhöhe/-breite | `DoHe`/`DoLen` | m |
| Abstand 1. Tür vom Wandrand | `HDistDoWa` | m |

**`RO` (Dach):** `window` (s.o.) + `shape`: `Typ` (Dachtyp, 1=Satteldach etc.), `RiHe` (Firsthöhe), `HSiTi`/`VSiTi`/`HFrTi`/`VFrTi` (Neigungsparameter, nur für die Ausgangs-Erzeugung im Quellwerkzeug relevant — die Pipeline übernimmt die reale LoD2-Dachgeometrie, konstruiert kein Dach neu).

**`UT` (Versorgungsschächte):** `HDistWaPo`/`HDistWaPo2` (horizontaler Abstand Wand-Schacht), `VDistFlPo` (vertikaler Abstand), `SiX`/`SiY`/`SiZ` (Schachtgröße), `DoUF`/`DoGF`/`DoBA` (in OG/EG/Keller?), `NrUt` (Anzahl).

**`GA` (Balkon/Galerie):**

| Feld | JSON-Key | Bedeutung |
|---|---|---|
| Balkonlänge/-breite | `GaLen`/`GaWid` | m |
| Brüstungshöhe | `GaHe` | m |
| Abstand Wand-Balkon | `HDistWaGa` | m, Anker für den führenden Lauf |
| Abstand Balkon-Balkon | `HDistGaGa` | m, innerhalb eines zusammenhängenden Laufs |
| Mindestabstand Wand-Balkon | `HDistMinWaGa` | m |
| Abstand Fenster-Balkon | `HDistWiGa` | m, Anker für Folge-Läufe nach Fenstern |
| Balkontür-Breite/-Höhe | `WiLen`/`WiHe` | m |
| Abstand Wand-Balkontür | `HDistWaWi` | m |
| Abstand Balkontür-Balkon | `DistWiGa` | m |
| Muster | `GaPa` | z.B. `"GaWiWiGa"` — Reihenfolge von Balkon-/Fenster-Slots |

**`IN` (Innenraum):** `HIn`/`VIn` (horizontale/vertikale Raumteilung, Anzahl), `MinInSi` (Mindest-Raumgröße m²) — aktuell nicht in Geometrie umgesetzt (kein Pipeline-Schritt dafür).

**`FL` (Treppenhaus):** `IDistStWa` (innerer Abstand Treppe-Wand), `StLen`/`StWid` (Treppenhaus-Maße) — aktuell nicht in Geometrie umgesetzt.

**`BU` (Gebäude-Metadaten):** `BA` (hat Keller? bool — zusammen mit `BA.height>0` Voraussetzung für Schritt 2), `GFRo` (EG bis Dach durchgehend?), `BuLen`/`BuWid`/`BuHe` (Gebäudemaße), `RoSh` (Dachform 1–4), `BaDo` (hat Kellertür?), `EnDi` (Eingangsrichtungen, 1–4 — Seitennummerierung des idealisierten Rechtecks, NICHT verlässlich auf reale Wand-IDs übertragbar, wird daher aktuell nicht zur Wandauswahl genutzt), `SID` (Struktur-ID, entspricht meist dem Dateinamen/`sst`).

**`FD` (Fassaden-Materialien):** je Bauteil (`WallAttr`, `CeilingAttr`, `Window`, `FacadeAttr`, `Groundsurface`, `FoundationAttr`, `Interiorwallsurface`, `Door`, `GalleryDoor`, `Closuresurface`) ein Objekt mit `typ` (Kürzel), `description`, `vulnerability` (Zahl) — reine Metadaten, fließen nicht in die Geometrie ein.

### Vollständiges Test-Modul

Für Tests, die alle Features gleichzeitig auslösen sollen (Keller, Kellerfenster, EG/OG-Fenster, Dachfenster, Balkon, Tür), eignet sich `MRG3_4.json` (bzw. `MRO7_4`/`MRO3_4`/`MR6_4`/`MR5_4`) — diese 5 der 32 mitgelieferten Module decken als einzige alle 7 Kernfeatures gleichzeitig ab.

---

## DGM-Einrichtung

| Format | Dateiendung |
|---|---|
| ESRI ASCII Grid | `.asc` |
| GeoTIFF | `.tif`, `.tiff` |
| ZIP-Archiv (enthält `.asc`/`.tif`, wird on-the-fly gelesen) | `.zip` |
| Verzeichnis (rekursiv gescannt, als Mosaik kombiniert) | Ordnerpfad |

Format wird automatisch anhand der Datei-/Pfadendung erkannt. Ein Verzeichnis mit mehreren Kacheln wird zu einem virtuellen Mosaik kombiniert — z.B. alle 117 DGM1-Kacheln für Dresden als ein durchgehendes Geländemodell:

```
DGM/Dresden/
├── dgm1_33_400_5654_2_sn_tiff.zip
├── ...                              (117 Kacheln)

java -jar target/lod2-zu-lod3-pipeline.jar input.gml jsonDir/ output/ DGM/Dresden/
```

**Ohne DGM-Argument** wird automatisch eine flache TerrainIntersectionCurve bei `Z = H_DGM` erzeugt (siehe Troubleshooting unten — kein Schalter nötig, das ist der Default-Fallback).

---

## Ausgabe

Ausgabedatei/-ordner: `LoD2_...` → `LoD3_...` umbenannt (Dateien zusätzlich ohne `_BuildingPreferences`-Suffix). Jedes hochgestufte Gebäude bekommt Metadaten-Attribute, u.a.:

```xml
<gen:stringAttribute name="lod2ToLod3Promotion">
  <gen:value>promoted=true; count=13; types=[...]; timestamp=...</gen:value>
</gen:stringAttribute>
<gen:stringAttribute name="storeysGenerated"><gen:value>3</gen:value></gen:stringAttribute>
```

CityGML 1.0 kennt kein natives `BuildingStorey`-Element — Geschosse sind `FloorSurface`/`CeilingSurface` mit `Geschoss`-Attribut (`GF`, `UF_1`, `UF_2`, ..., `BA`).

---

## Verifikationswerkzeuge

### Schema-Validierung

```sh
D:\Tools\citygml-tools-2.5.0\citygml-tools validate output.gml
```

### val3dity (geometrische Solid-Validität)

Erst nach CityJSON konvertieren, dann prüfen (funktioniert auch für ganze Ordner in einem Aufruf):

```sh
D:\Tools\citygml-tools-2.5.0\citygml-tools to-cityjson -o cityjson_out/ output.gml
D:\Tools\val3dity-win64\val3dity.exe cityjson_out/output.json
```

Ohne `--overlap_tol`-Option ausführen — die Option ist bei Mehrteil-Gebäuden auf größeren Kacheln instabil (siehe Programmierenden-Doku). Ergebnis-Interpretation: siehe Benutzenden-Doku.

### CityDoctor2 (semantische Prüfung)

**Immer mit der Projekt-Konfiguration aufrufen** — ohne `-c` nutzt die CLI einen unvollständigen Default-Prüfplan und übersieht echte Fehler:

```sh
java -cp "D:\Tools\CityDoctorGUI-3.18.2-win\app\*" de.hft.stuttgart.citydoctor2.CityDoctorValidationCLI ^
  -i output.gml -x report.xml -c "<Pfad zur Projekt-config.yml>"
```

**Wichtig:** aus dem Verzeichnis `D:\Tools\CityDoctorGUI-3.18.2-win\` heraus starten — die Konfiguration referenziert `schematronPath: checkForSolid.xml` relativ, die Datei liegt nur dort. Enthält der Konfigurationspfad Umlaute, kann das bei Aufruf aus einem PowerShell-Subprozess zu `FileNotFoundException` führen (Windows-Codepage-Problem über mehrere Prozessebenen) — im Zweifel einen umlautfreien Kopie-Pfad verwenden.

---

## Troubleshooting

### Gebäude bekommt keinen Keller / keine Geschossunterteilung

`BasementGenerator` und `StoreyGenerator` lesen `H_DGM` als **generisches Attribut direkt vom Gebäude** — keine Laufzeitberechnung, auch nicht aus einem übergebenen DGM. Fehlt dieses Attribut, überspringen beide Schritte das Gebäude komplett (kein Keller, keine Geschossunterteilung, dadurch vermutlich auch keine Türen/Fenster/Balkone, da diese Schritte auf Geschossen aufbauen).

**Betrifft insbesondere Testläufe mit Daten anderer Städte/Bundesländer:** `H_DGM` ist in den Dresdner Testdaten Standard, aber nicht garantiert Teil jedes LoD2-Exports. Vor einem Testlauf prüfen, ob das Attribut vorhanden ist — falls nicht, z.B. aus dem Minimum der eigenen `GroundSurface`-Z-Werte des Gebäudes ableiten und ergänzen (die GroundSurface-Geometrie ist in jeder Standard-LoD2-Datei vorhanden).

### Batch-Modus: eine Kachel schlägt fehl, der Rest läuft trotzdem weiter

Erwartetes Verhalten — der Fehler wird geloggt, die betroffene Kachel fehlt in der Ausgabe, alle anderen werden trotzdem verarbeitet. Am Ende steht eine Liste der fehlgeschlagenen Dateinamen im Log. Bei einem `TopologyException`-artigen JTS-Fehler: einzeln reproduzieren (Einzeldatei-Modus) und den vollen Stack-Trace prüfen, meist eine geometrische Randbedingung (siehe Programmierenden-Doku, „Bekannte offene Punkte").
