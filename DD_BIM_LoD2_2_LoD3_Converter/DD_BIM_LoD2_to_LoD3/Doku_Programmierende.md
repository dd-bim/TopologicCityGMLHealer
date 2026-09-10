# LoD2 → LoD3 Pipeline — Doku für Programmierende

Architektur, Algorithmen, Code-Organisation, offene Punkte. Für Aufruf/Konfiguration siehe [Doku_Administrierende.md](Doku_Administrierende.md), für fachliche Einordnung der Ausgabe [Doku_Benutzende.md](Doku_Benutzende.md).

---

## Architektur

**Single-Pass:** Eingabedatei wird einmal gelesen, pro Gebäude laufen alle Schritte im Speicher, Ergebnis wird einmal geschrieben — keine Zwischendateien (`CityGmlUtils.processGmlFile`).

```
input.gml
    │
[CityGMLReader — 1x lesen]
    │
    ├─ pro Building: ────────────────────────────────────┐
    │  1  Lod2ToLod3Promoter    LoD2 → LoD3 Geometrie-Slot │
    │  2  BasementGenerator     Keller                     │
    │  3  StoreyGenerator       Geschosse                  │
    │  4  DoorGenerator         Türen                      │
    │  5a BalconyGenerator      Balkone, führender Lauf    │
    │  5b WindowGenerator       Fenster                    │
    │  5c BalconyGenerator      Balkone, Rest-Läufe        │
    │  5d RoofWindowGenerator   Dachflächenfenster          │
    │  4d DoorGenerator         Fallback-Tür (kein Fenster ohne Tür-Gebäude)│
    │  6  JunctionConformingUtils.conformJunctions    T-Naht-Vertices       │
    │  7  JunctionConformingUtils.splitSelfTouchingRings  Pinch-Point-Split │
    └────────────────────────────────────────────────────┘
    │
[CityGMLChunkWriter — 1x schreiben]
    │
output.gml
```

Schritte 6+7 sind **streng formneutral**: kein bestehender Vertex wird verschoben, nur fehlende Punkte auf bestehende Kanten eingefügt bzw. selbstberührende Ringe sauber aufgetrennt.

**Batch-Modus:** `Lod2ToLod3Pipeline.main()` erkennt automatisch, ob `args[0]` eine Datei oder ein Ordner ist. Bei einem Ordner läuft `runBatch()` — sequenziell, ein `processSingleFile()`-Aufruf je Kachel mit frischen Generator-Instanzen (kein Shared State zwischen Kacheln). Bewusst sequenziell: `ModuleParametersLoader` cached in einer nicht-thread-sicheren `HashMap`.

### AbstractGenerator (Template-Method)

Alle JSON-parametrisierten Generatoren (`BasementGenerator`, `StoreyGenerator`, `DoorGenerator`, `WindowGenerator`, `BalconyGenerator`, `RoofWindowGenerator`) erben von `AbstractGenerator<S>`:

- `runCli`: Arg-Parsing, Verzeichnis-Erstellung, Logging
- `process`: Lese-/Schreibschleife über `CityGmlUtils.processGmlFile`
- `BaseStats`: gemeinsame Statistik-Basis, jeder Generator erweitert sie
- `resolveParams`: `sst`-Attribut → passendes Baukörpermodul
- Hook `onConfigure(args)` für Zusatz-Argumente (z.B. DGM bei `BasementGenerator`)

Eine konkrete Unterklasse implementiert nur `outputSuffix()`, `displayName()`, `newStats()`, `processBuilding(...)`, `logResult(...)`. `Lod2ToLod3Promoter` (Schritt 1) fällt bewusst aus diesem Schema (kein JSON, keine `sst`-Auflösung).

---

## Projektstruktur

```
src/main/java/de/mpsc/lod2tolod3/
├── Lod2ToLod3Pipeline.java         Haupt-Pipeline (Single-Pass + Batch-Modus)
├── Lod2ToLod3Promoter.java         Schritt 1
├── AbstractGenerator.java          Template-Method-Basis
├── BasementGenerator.java          Schritt 2
├── StoreyGenerator.java            Schritt 3
├── DoorGenerator.java              Schritt 4 + 4d (inkl. Fallback-Tür)
├── WindowGenerator.java            Schritt 5b
├── BalconyGenerator.java           Schritt 5a/5c
├── RoofWindowGenerator.java        Schritt 5d
├── util/
│   ├── CityGmlUtils.java           Attribut-Helfer, GML-I/O, SRS
│   ├── GeometryUtils.java          Geometrie-Grundlagen (Basis-Klasse der meisten anderen)
│   ├── BuildingQueryUtils.java     Boundary-/Target-Sammlung, Dach-Z-Bereich
│   ├── WallCuttingUtils.java       Sutherland-Hodgman + JTS-Bandschnitt
│   ├── SlabClippingUtils.java      Geschossflächen-Zuschnitt bei Anbauten (JTS)
│   ├── SolidShellUtils.java        TerrainIntersectionCurve + Solid-Shell-Neuaufbau
│   ├── JunctionConformingUtils.java T-Naht-Konformierung + Pinch-Point-Aufspaltung
│   ├── OpeningUtils.java           Fenster-/Tür-Platzierungsprüfung und -Erzeugung
│   ├── PartyWallCoverageUtils.java Wand-Deckung durch Nachbarbauteile (Anbau)
│   ├── Point3D.java                3D-Punkt-Klasse
│   ├── DgmLoader.java / DgmReader.java / GeoTiffReader.java / DgmMosaic.java / DgmProvider.java
│   └── ModuleParametersLoader.java JSON-Parameter laden + cachen
└── model/
    ├── ModuleParameters.java       Datenklasse für JSON-Baukörpermodule (10 Kategorien, siehe Admin-Doku)
    └── WindowPreference.java       Enum: NONE/NORMAL/ABOVE_NEIGHBOR
```

**Code-Organisation (Stand 2026-09-01):** `CityGmlUtils.java` war auf 2.186 Zeilen mit 13 Themenblöcken angewachsen und wurde in die obigen 9 `util/`-Klassen aufgeteilt (reine Verschiebung, per Vollkachel-Diff als verhaltensneutral verifiziert). Package-private Sichtbarkeit reicht für Cross-Klassen-Aufrufe, `import static` bewusst nicht verwendet (Herkunft am Aufrufort bleibt sichtbar, z.B. `GeometryUtils.createPolygon(...)`).

---

## Pipeline-Schritte: Algorithmen

### Schritt 1 — Lod2ToLod3Promoter

Nutzt citygml4j-Basisklassen (`AbstractThematicSurface`, `AbstractSpace`) generisch — erfasst automatisch jede LoD2-Geometrie (`lod2MultiSurface`→`lod3MultiSurface`, `lod2Solid`→`lod3Solid`, `lod2MultiCurve`→`lod3MultiCurve`) über alle Boundary-Klassen (Wall/Roof/Ground/Ceiling/Floor/Interior/Closure), ohne die Geometrie selbst zu verändern. Benennt `LOD2_*`-Attribute/-Namen auf `LOD3_*` um.

### Schritt 2 — BasementGenerator

Ersetzt die originale `GroundSurface` durch die physische Bodenplatte + Kellerwände, dokumentiert den ursprünglichen Geländeschnitt als `TerrainIntersectionCurve` (siehe TIC-Abschnitt unten). Kelleroberkante = `H_DGM + heightGr` (oberirdischer Kelleranteil aus dem JSON-Modul). `H_DGM` ist ein **generisches Attribut pro Gebäude** aus den LoD2-Quelldaten, keine Laufzeitberechnung — siehe Admin-Doku, Abschnitt Troubleshooting.

### Schritt 3 — StoreyGenerator

Kernstück der Pipeline. Pro Geschoss:
- **Wandschnitt:** `WallCuttingUtils.cutWallAtMultipleZJTS` — JTS-Bandschnitt im wandeigenen (u,v)-Profil, robust auch bei konkaven Profilen (Wand mit Anbau-Kerbe), löste den älteren Sutherland-Hodgman-Einzelschnitt (`cutWallPolygonAtZ`, noch für Basement-Schnitte verwendet) ab.
- **Boden-/Deckenzuschnitt bei Anbauten:** `SlabClippingUtils.clipSlabAtZ` — echte 2D-Polygon-Differenz (JTS `Polygon.difference()`): `slab(z) = Grundriss − (Dachanteile ≤ z)`. Liefert eine Liste von `SlabPiece`s (mehrere Teilstücke möglich, wenn ein Anbau das Gebäude in Flügel teilt). Ersetzt ein älteres Kanten-Matching-Verfahren vollständig (drei Vorgänger-Patches waren strukturell nicht robust genug für beliebige Anbau-Konturen).
- **Flachdach-Erkennung:** First-/Traufhöhe aus allen `RoofSurface`-Punkten; Differenz < 0,30 m → keine `CeilingSurface` am obersten Geschoss (Dach = Decke).
- **Mischdach-Erkennung:** bei gemischt geneigten/flachen Dachflächen wird die oberste Decke NUR aus den auf `traufeZ` projizierten geneigten Dachpolygonen erzeugt, nicht aus dem vollen Grundriss — verhindert eine Decke, die über einem niedrigeren Anbau-Flachdach schwebt.

### Schritt 4/4d — DoorGenerator

Platziert Türen auf EG-Außenwänden (TIC-basierte Positionierung, `HDistDoWa`-Anker). Schritt 4d (`processFallbackDoors`, läuft NACH 5b/5d) ergänzt genau eine Tür für Gebäude mit Fenstern aber `DoorCount=0` überall (bestätigte Quelldatenlücke in der Adresspunkt-Zuordnung) — Wandauswahl: breiteste EG-Wand zuerst (FACEAREA-sortiert), Kollision mit vorhandenen Fenstern per `computeExistingWindowSpans` ausgeschlossen.

### Schritt 5a/5c — BalconyGenerator

Zweiphasig um Schritt 5b herum: Phase 1 platziert den führenden `Ga`-Lauf pro Wand unabhängig (`HDistWaGa`-Anker), reserviert die belegte Wandspanne. Phase 2 platziert restliche `Ga`-Token eines Musters (z.B. `"GaWiWiGa"`) nach den echten Fenstern (`HDistWiGa`-Anker). Deck+Brüstung als `BuildingInstallation` (`STRUKTUR=Balkondecke`/`Balkonbruestung`), nicht als Roof-/WallSurface — schema-konform ohne `boundedBy`-Ausschluss-Hack. Balkon-Normalen aus der wand-eigenen Umlaufrichtung abgeleitet (nicht aus einer globalen Schwerpunkt-Heuristik — die versagt bei nicht-konvexen Grundrissen/Innenhöfen).

### Schritt 5b — WindowGenerator

Fenster auf Geschoss-Außenwänden (TIC-Methode), max. 1 Reihe pro Wand und Geschoss. Enthält u.a. `openingInsideWallTopClearance2D`/`openingInsideWallSideTopClearance2D` (2 cm Sicherheitsabstand oben bzw. an beiden Seitenkanten — Ray-Casting ist an Randpunkten mehrdeutig) und `wallContourEntersOpening` (erkennt eine "M"-förmige Wandkontur, die mitten in eine sonst gültige Öffnung hineinragt).

**Kellerfenster-Freihaltung unter Türen:** Im `BA`-Geschoss werden die verfügbaren Wandabschnitte um jede Tür herum um `BASEMENT_STAIR_CLEARANCE` (0,40 m je Seite) beschnitten — Platz für die kleine Außentreppe, die den Höhensprung vom herausragenden Kellerdeckel zur Tür überbrückt. Türen liegen im GF-Segment, Kellerfenster im BA-Segment; da beide keine gemeinsame ID tragen (`BasementGenerator` erzeugt Kellerwände pro Grundriss-Kante, `StoreyGenerator` schneidet die Originalwände), erfolgt die Zuordnung geometrisch über die gemeinsame Grundriss-Linie (`baSectionsExcludingDoorZones`, gleiches Prinzip wie `BasementGenerator.findWindowPreferenceForEdge`). Rein subtraktiv — GF/UF/Dach unberührt, val3dity und CityDoctor2 exakt unverändert. Bekannte Grenze: Fallback-Türen (Schritt 4d) laufen nach den Fenstern, für die betroffenen Gebäude bleibt der Keller darunter unbeschnitten.

### Schritt 5d — RoofWindowGenerator

Eigenständiger Generator (nicht in `WindowGenerator` integriert — fundamental andere Geometrie: geneigte statt senkrechte Fläche). Nutzt dieselbe `WindowParams`-Struktur wie Wandfenster (`RO.window`-Block). Kernentscheidung: die Wand-Konvention "u = entlang Unterkante, v = Z" wird auf "u = entlang Traufe, v = entlang der Dachschräge (Traufe→First)" generalisiert (`computeUpSlopeVector`, `projectPlaneTo2D` statt `projectWallTo2D`).

### Schritt 6 — JunctionConformingUtils.conformJunctions (Toleranz 5 mm)

An T-Stößen (eine Wand endet mitten auf der Kante einer anderen) fehlt dem längeren Nachbarn der Zwischenpunkt, die Hülle schließt formal nicht. Fügt den fehlenden Vertex **auf** die bestehende Kante ein (liegt exakt auf der Geraden → keine Formänderung). Näht ausschließlich die eigenen, unabhängig erzeugten Zusatzflächen zusammen (Geschosse/Keller/Slabs) — die Quellgeometrie selbst (mm-Nähte, Planarität) bleibt bewusst unverändert, das ist Aufgabe des nachgelagerten Healers. Enthält ein eng begrenztes Vertex-Welding (bis 2 mm) VOR der Einfügung, um zwei unabhängig digitalisierte, nur mm-genau abweichende Eckpunkte zu verschmelzen (verhindert doppelte T-Naht-Einfügung an derselben Stelle).

### Schritt 7 — JunctionConformingUtils.splitSelfTouchingRings

Spaltet Ringe, die durch Schritt 6 an einer Stelle einen "Pinch Point" (denselben 3D-Punkt an zwei nicht benachbarten Stellen) bekommen haben, in zwei einfache Teilringe auf. Sicherheitsnetz: committet nur, wenn ALLE resultierenden Teilstücke sauber sind (`ringSelfIntersects`-Check) — sonst bleibt das Original unverändert. Grenzfall "Spike" (Weg geht zu einem Punkt raus und exakt zurück) lässt sich damit strukturell NICHT lösen, siehe „Bekannte offene Punkte" unten.

---

## Algorithmen-Referenz

**Sutherland-Hodgman (`cutWallPolygonAtZ`):** klassischer Polygon-Clip an einer horizontalen Ebene. Sicherheitsmerkmale: 5 cm Fitzelchen-Toleranz (kein Schnitt zu nah an einer Kante), 1 mm Klassifikations-Epsilon, mm-Rundung der Schnittpunkte.

**Newell's Method (`calculateWallArea`):** Flächenberechnung eines beliebigen planaren 3D-Polygons über die Kreuzprodukt-Summe aller Kantenpaare (`N = Σ P_i × P_{i+1}`, `A = 0,5·|N|`) — funktioniert für beliebige Punktzahl, nicht nur Rechtecke.

**Wandnormale-Azimut (`calculateWallNormalAzimuthFromPolygon`):** dreistufig — (1) Senkrechte auf die Unterkante (zwei Punkte bei minZ), (2) Fallback: längste horizontale Kante, (3) letzter Fallback: erste Kante.

---

## TerrainIntersectionCurve (TIC)

Nach Kolbe (2009): Interface-Objekt zwischen 3D-Gebäudemodell und Geländemodell, dokumentiert wo ein Gebäude das Gelände schneidet. Zwei Erzeugungsvarianten:
- **Flach** (kein DGM): `Z = H_DGM` für jeden TIC-Vertex.
- **Interpoliert** (mit DGM): `Z = DGM(x,y)`, bilinear aus den 4 umgebenden Rasterzellen.

**Validierung gegen offizielle Dresden-TICs** (Geoportal Dresden, 1.720 gematchte Gebäude): mittlere Differenz **−0,001 m**, 90,8 % innerhalb ±0,5 m. Abweichungen v.a. durch Vertex-Dichte (Dresden Ø 52 Vertices/TIC auf 1m-Raster verdichtet, wir Ø 14 — die originalen Eckpunkte).

Referenzen: Kolbe (2009), Kolbe & Czerwinski (2006), OGC 20-010 (CityGML 3.0 Conceptual Model), Gröger & Plümer (2012).

**Bilineare Interpolation** (DGM-Reader, `DgmReader`/`GeoTiffReader`):
```
col = (x - xllcorner) / cellsize - 0.5,  row = (nrows-1) - (y - yllcorner) / cellsize + 0.5
c0/r0 = floor(col/row), c1/r1 = c0/r0 + 1, fx/fy = Nachkommaanteil
Z = (1-fx)(1-fy)·Z[r0,c0] + fx(1-fy)·Z[r0,c1] + (1-fx)fy·Z[r1,c0] + fx·fy·Z[r1,c1]
```
GeoTIFF-Reader (`javax.imageio`, keine externen Abhängigkeiten) wertet `ModelPixelScaleTag` (33550), `ModelTiepointTag` (33922), `ModelTransformationTag` (34264), `GDAL_NODATA` (42113) aus. Robustheit: NODATA-Zellen → nächster gültiger Nachbar; Rasterrand → Nearest-Neighbor statt bilinear; `float[][]` statt `double[][]` (Speicher: ~16 MB/Tile bei 2000×2000).

---

## Technische Details

**citygml4j-Klassenhierarchie:**
```
AbstractCityObject
├─ AbstractSpace (Building, BuildingPart, ...) — getSolid(lod)/getMultiSurface(lod)/getMultiCurve(lod)
└─ AbstractSpaceBoundary
   └─ AbstractThematicSurface (WallSurface, RoofSurface, ...) — getMultiSurface(lod)
```

**CityGML-Version:** liest/schreibt CityGML 1.0. Kein natives `BuildingStorey`-Element in 1.0 — Geschosse werden als `FloorSurface`/`CeilingSurface` mit `Geschoss`-Attribut abgebildet, IDs nach Muster `{buildingId}_storey{level}_floor_{polygonIndex}`.

---

## GUI-Anwendung

`de.mpsc.lod2tolod3.gui.Lod2Lod3Gui` — eigenständige Desktop-Oberfläche für Nutzende ohne CMD/PowerShell (siehe Doku_Administrierende.md, Abschnitt „GUI"). Zusätzliche Main-Class im selben Fat-JAR, keine eigene Kopie der Pipeline-Logik.

### Verwendete Bibliotheken

| Zweck | Bibliothek | Warum |
|---|---|---|
| UI-Toolkit | Swing (JDK-Bestandteil) | Kein Zusatz-Download, läuft mit der eingebetteten JDK-Laufzeit |
| Look-and-Feel | [FlatLaf](https://www.formdev.com/flatlaf/) 3.7.2 | Modernes, flaches Erscheinungsbild statt Standard-Swing-„Metal"; Hell/Dunkel als fertige Themes, per Button umschaltbar (nicht an das Windows-Theme gekoppelt — das bräuchte zusätzlich eine Registry-Abfrage) |
| Paketierung | `jpackage` (JDK-Bestandteil, seit JDK 14) | Baut aus dem fertigen Fat-JAR eine eigenständige `.exe` samt eingebetteter Java-Laufzeit — `--type app-image`, kein Installer/WiX nötig |

### Architektur-Prinzip: dünne UI-Schicht, keine Logik-Duplikation

Die GUI ruft `Lod2ToLod3Pipeline.run(String[] args, StepSelection selection)` direkt auf — denselben Code-Pfad, den auch `main()` (CLI) nutzt. Verifiziert per Diff: CLI-Aufruf und GUI-Aufruf (`run()` mit `StepSelection.all()`) erzeugen auf einer realen 3.801-Gebäude-Kachel byte-identische Ausgabe (einzige Abweichung: die eingebetteten Verarbeitungs-Zeitstempel).

`run()` ist eine reine Ergänzung zu `main()` — wirft Exceptions statt `System.exit()` aufzurufen, damit die GUI einen Fehler in einem Dialog anzeigen kann, statt dass der ganze Prozess (inkl. Fenster) beendet wird. CLI-Verhalten bleibt unverändert.

### StepSelection: Schritte gefahrlos abwählbar machen

`StepSelection` ist ein `record` mit sechs booleschen Feldern (`basement, storeys, doors, windows, balconies, roofWindows`), das durchgereicht wird und in `processSingleFile()` steuert, welche Generator-Schritte der `buildingSteps`-Liste hinzugefügt werden. Vor der Umsetzung wurde gezielt geprüft, ob das strukturell überhaupt sicher ist:

- `DoorGenerator`, `WindowGenerator` und `BalconyGenerator` wählen ihre Ziel-Wände über `CityGmlUtils.getStringAttribute(wall, "Geschoss")` — dieses Attribut wird **ausschließlich** von `StoreyGenerator` gesetzt. Ist „Geschosse" abgewählt, liefert die Abfrage für jede Wand `null`, die Eligibility-Prüfung (`isWindowEligibleGeschoss` bzw. Äquivalente) schlägt fehl, der Schritt erzeugt sauber 0 Elemente — kein Absturz, keine invalide Geometrie. Per Testlauf bestätigt (Fenster+Balkone abgewählt: 0 Fenster, 0 Balkone, alle anderen Zahlen exakt wie im Volllauf).
- `RoofWindowGenerator` liest kein `Geschoss`-Attribut (schreibt nur `"RO"` auf seine eigenen erzeugten Fenster) — vollständig unabhängig von `StoreyGenerator`.
- `StoreyGenerator` selbst liest `H_DGM` direkt vom Building (nicht über `BasementGenerator`) und unterscheidet im Code explizit zwischen „GroundSurface von BasementGenerator erzeugt" und „Original-GroundSurface" — funktioniert also bereits nachweislich unabhängig davon, ob Keller lief.
- Balkone (Phase 1 vor Fenstern, Phase 2 nach Fenstern) sind fest an eine gemeinsame `balconies`-Auswahl gekoppelt, nicht einzeln wählbar — die beiden Phasen sind architektonisch aneinander gekoppelt (Phase 2 braucht die reale Fensterposition aus Phase 1s Nachfolgeschritt).
- Fallback-Türen (Schritt 4d) sind an dieselbe `doors`-Auswahl gekoppelt wie der normale Tür-Schritt — sonst würde bei „Türen abgewählt" trotzdem an jedem Gebäude mit Fenstern automatisch eine Ersatztür eingefügt, was der erkennbaren Absicht widerspräche.

Schritte 1 (Promotion) und 6+7 (Junction-Conforming/Pinch-Split) sind nicht Teil von `StepSelection` — sie sind strukturell zwingend (jede Kombination optionaler Schritte erzeugt neue Berührflächen, die Schritt 6+7 nahtlos schließen muss).

**GUI-seitige Absicherung:** Obwohl die Pipeline die Kombination „Geschosse aus, Fenster an" bereits gefahrlos abfängt (0 Fenster statt Fehler), verhindert die GUI diese Kombination zusätzlich direkt in der Oberfläche — ein `ActionListener` auf der Geschosse-Checkbox (`onStoreysToggled()`) hakt Türen/Fenster/Balkone automatisch ab und sperrt sie (`setEnabled(false)`), sobald Geschosse abgewählt wird, und stellt beim erneuten Anhaken den vorherigen Auswahlzustand wieder her. Grund: eine still auf 0 laufende, aber weiterhin angehakte Checkbox wäre in der Praxis ein Footgun (z. B. ein langer Batch-Lauf, der am Ende ohne ein einziges Fenster fertig ist, obwohl „Fenster" angehakt war) — die Sperrung macht die Abhängigkeit in der Oberfläche selbst sichtbar, statt sie nur in einem Hinweistext zu erwähnen. Keller und Dachfenster sind von dieser Sperre nicht betroffen (siehe oben, beide unabhängig von Geschossen).

### Fortschritts-Hook und Live-Zählung

`Lod2ToLod3Pipeline.ProgressListener` (statisches, per `setProgressListener()` gesetztes Interface, `null` im CLI-Betrieb = No-Op) meldet `onFileStart(fileIndex, totalFiles, filename)` im Batch-Modus (echte Werte aus der bereits vorhandenen Dateiliste) und `onBuilding(count)` nach jedem verarbeiteten Gebäude. Für einen echten X/Y-Fortschrittsbalken im Einzeldatei-Modus zählt die GUI vorab die Anzahl `<core:cityObjectMember>`-Zeilen in der Eingabedatei (reines Zeilenlesen, kein XML-Parsing, daher schnell auch bei großen Kacheln) — dasselbe Zeilen-Prinzip wie bereits in `GmlMemberFilter` (sql2gml_neu) verwendet.

### Log-Anzeige

`System.out`/`System.err` werden beim GUI-Start (vor jedem Zugriff auf `Lod2ToLod3Pipeline`, da SLF4J-Simple den Ziel-Stream beim ersten `LoggerFactory.getLogger(...)`-Aufruf fest bindet) durch einen zeilenweise puffernden `OutputStream` ersetzt, der jede Zeile zusätzlich an die Swing-Textfläche weiterreicht — ohne den ursprünglichen Stream zu ersetzen (bei Start aus einem Terminal bleibt die Konsolenausgabe zusätzlich erhalten).

---

## Bekannte offene Punkte

Diese Liste enthält bewusst nur **noch nicht vollständig gelöste** oder **strukturell akzeptierte** Fälle — abgeschlossene Bugfixes sind über die Git-Historie nachvollziehbar, nicht hier dupliziert.

- **GE_R_SELF_INTERSECTION „Spike"-Fälle:** eine Kachel-Kantenhöhe liegt gelegentlich 1–9 mm von einem originalen Wand-Stufen-Vertex entfernt; die 5-mm-T-Naht-Suche (Schritt 6) findet dann einen bereits im selben Ring vorhandenen Punkt erneut und erzeugt einen "Spike" (A→B→C→B). Schritt 7 löst echte zweiseitige Pinch-Points, kann aber Spikes strukturell nicht auflösen (beide Hälften würden auf <3 Punkte entarten). Betrifft eine kleine, im Detail bekannte Menge an Gebäuden.
- **GE_S_NON_MANIFOLD_EDGE/GE_S_NOT_CLOSED bei ~66 Gebäuden:** offene Kanten auf einem Z-Niveau, das NICHT der Keller-/EG-Grenze entspricht (anders als der bereits behobene Keller-Überhang-Fall) — Ursache noch nicht diagnostiziert.
- **val3dity `601 BUILDINGPARTS_OVERLAP`:** bei Mehrteil-Gebäuden (Standard-Muster: gemeinsame, xlink-referenzierte Party-Wall) meist ein CGAL-Nef-Erosions-Artefakt der Prüfkette (`citygml-tools`→CityJSON→val3dity), kein reales Volumen-Overlap — mechanistisch am val3dity-Quellcode verifiziert. `--overlap_tol` würde das sauber ausschließen, ist aber in der aktuellen val3dity-2.6.0b0-Windows-Beta auf großen Kacheln instabil (hängt/stürzt ab) — daher nur auf handhabbaren Teilmengen nutzbar. Die reine `601`-Zahl ist folglich kein verlässliches Qualitätsmaß für Mehrteil-Gebäude.
- **CityDoctor2 `SE_POLYGON_WITHOUT_SURFACE`:** meldet fälschlich auf jedem Fenster-/Tür-/Balkon-Polygon — bestätigter Mapper-Bug im Tool (Öffnungsgeometrie wird beim Einlesen nie mit ihrer Wand verknüpft), auch am externen FZK-Haus-Referenzdatensatz reproduziert (14.207 Treffer). Schema-Validität ist davon unberührt.
- **BalconyGenerator — Parameter-Interpretationen ohne volle Spec-Bestätigung:** einige `GA`-Block-Felder (`HDistWiGa`-Doppelrolle, `DistWiGa`, Zugangsöffnung als `DoorSurface` statt eigenem Element) sind aus wenigen realen Datenpunkten inferiert, nicht durch eine vollständige Spezifikation belegt — Geometrie-Validität ist davon unabhängig nachgewiesen. Details siehe Javadoc der Klasse.

**Aktuelle Baseline** (kompletter Dresden-Lauf, 98 Kacheln, 141.670 Gebäude, 2026-09-02): val3dity 98,36 % valide Features / 99,58 % valide Primitive; CityDoctor2 92,24 % fehlerfreie Gebäude (Nutzer-Konfiguration). Dominante Fehlerkategorien: `601 BUILDINGPARTS_OVERLAP` (val3dity, s.o.) und `GE_P_NON_PLANAR_POLYGON_DISTANCE_PLANE` (CityDoctor2, überwiegend aus unabhängig digitalisierten LoD2-Quellflächen geerbtes Rauschen, nicht durch die Pipeline verursacht).

---

## Unit-Tests

`src/test/java/de/mpsc/lod2tolod3/util/` (spiegelt `src/main`-Paketstruktur). JUnit 5, von Maven Surefire automatisch erkannt. `mvn test` bzw. `mvn clean package` (Tests laufen automatisch mit).

Alle Testklassen sind Regressionstests für konkret gefundene Fehler (nicht abstrakte Coverage-Übung):

| Testklasse | Prüft |
|---|---|
| `CityGmlUtilsWallCutTest` | `cutWallAtMultipleZJTS` — Kerben-Zerlegung, Flächenbilanz |
| `CityGmlUtilsPartyWallTest` | `computeCoveredSpans`/`overlapsAnySpan`/`isFullyCovered` — Party-Wand-Erkennung |
| `CityGmlUtilsBottomEdgeTest` | `findBottomEdge` — Punktepaar mit größtem 2D-Abstand bei geknickter Basis |
| `CityGmlUtilsOpeningClearanceTest` | `openingInsideWallTopClearance2D` — Fenster-Traufe-Clearance |
| `CityGmlUtilsSlabClipTest` | `clipSlabAtZ` — Anbau-Zuschnitt |
| `CityGmlUtilsRoofPlaneTest` | Solid-Shell-Rebuild bei Dachflächen-FillingSurfaces |

Ergänzt die manuelle Verifikationskette (Einzelgebäude → 14er-Set → volle Kachel → `citygml-tools validate` → val3dity/CityDoctor2), ersetzt sie nicht: Shell-Geschlossenheit und Koordinaten-Präzision zwischen benachbarten Flächen zeigen sich erst beim echten Kachel-Zusammenbau.

---

## Ideen & Verbesserungspotenzial

### CityGML-Versions-Upgrade

Die Pipeline schreibt CityGML 1.0. citygml4j 3.2.7 nutzt intern ein CityGML-3.0-natives, versionsagnostisches Objektmodell — der Wechsel auf `CityGMLVersion.v2_0`/`v3_0` betrifft genau 2 Stellen (`CityGmlUtils.processGmlFile`, `Lod2ToLod3Pipeline`).

| Pfad | Aufwand | Mehrwert |
|---|---|---|
| 1.0 → 2.0 | 3 Zeilen | `relativeToTerrain`/`relativeToWater` standardisiert (aktuell nur über Generic Attributes abbildbar), `OuterFloorSurface`/`OuterCeilingSurface` für Balkone — sonst kein funktionaler Mehrwert |
| 1.0 → 3.0 (minimal) | 3 Zeilen | modernes Format; LoD4 entfällt (nicht genutzt), `Opening` → `FillingSurface`-Konzept (citygml4j mappt das beim 1.0-Export automatisch zurück) |
| 1.0 → 3.0 + `Storey` | 1–2 Tage | echte Geschoss-Objekte (`Storey`, `sortKey`) statt `FloorSurface`/`CeilingSurface` + Generic-Attribut-Workaround |
| 1.0 → 3.0 + `Storey` + `Elevation`/`storeyHeights*` | 2–3 Tage | `H_DGM`/Geschosshöhen als standardisierte Properties statt Generic Attributes |
| 1.0 → 3.0 + `Storey` + `BuildingRoom` | 4–6 Tage | Innenraum-Modellierung (nutzt bereits vorhandenen `IN`-JSON-Block) |

**Empfehlung:** direkt 1.0 → 3.0 (kein Umweg über 2.0). Abwärtskompatibel — CityGML-1.0-Input bleibt lesbar, nur die Ausgabe ändert sich.
