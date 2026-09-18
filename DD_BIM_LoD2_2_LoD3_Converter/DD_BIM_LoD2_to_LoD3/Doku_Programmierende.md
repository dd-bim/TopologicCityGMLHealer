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

**Keller bei mehreren GroundSurfaces:** Ein `Building` ohne BuildingParts kann mehrere aneinandergrenzende `GroundSurface`-Polygone haben. Kellerwände entstehen pro Grundriss-Kante — ohne Sonderbehandlung stünden auf der gemeinsamen Innengrenze zweier Polygone zwei deckungsgleiche, gegenläufige Wände mitten im Keller (`GE_S_NON_MANIFOLD_EDGE`), und ein Digitalisierungs-Sliver zwischen den Polygonen (bei `gJv` 9,6 mm Überlapp) würde als Kellerwände ohne Gegenstück darüber repliziert (`GE_S_NOT_CLOSED`). Deshalb werden die terrain-nahen Grundriss-Polygone eines Targets zuerst per JTS vereinigt (`SlabClippingUtils.unionFootprints`); Kellerboden, -wände, -decke und TIC entstehen aus der Außenkontur der Vereinigung (`footprintsMerged`, im Schritt-2-Summary als „Keller aus vereinigten Grundrissen"). Innengrenzen und Sliver verschwinden damit an der Wurzel. Übernommen wird die Vereinigung nur, wenn sie die Polygonanzahl reduziert und kein Loch entsteht. Bei Innenhöfen (Vereinigung mit Loch) oder einem JTS-Fehler bleiben die Einzelpolygone; exakt geteilte Innenkanten erkennt dann ein ungerichteter, mm-gerundeter Kantenschlüssel (`interiorEdgeKey`), dort entsteht keine Kellerwand (`interiorEdgesSkipped`, „Innenkanten ohne Kellerwand"). Testkachel: 4 Keller vereinigt, 22 Innenkanten bei drei Innenhof-Gebäuden übersprungen. Vollkachel-A/B: `gJv` in CityDoctor2 und val3dity vollständig valide, `GE_S_NOT_CLOSED` 5 → 4, `GE_S_NON_MANIFOLD_EDGE` 3 → 2, `GE_P_NON_PLANAR` 512 → 511, val3dity `303` 13 → 12; kein Gebäude verschlechtert, alle Fenster-, Tür- und Balkonzahlen identisch. Nebeneffekt: keine Phantom-Trennwände im Keller, die im Starkregen-Modell den Wasserfluss blockieren würden. Seit 2026-09-17 wird die Vereinigung zusätzlich verworfen, wenn ihr Ring im mm-Raster sich selbst berührt (`slabRingTouchesItself`, 1 mm): JTS-gültige Vereinigungen mit Sub-mm-Zacken meldete CityDoctor2 (rundet auf mm) Dresden-weit 87× als `GE_R_SELF_INTERSECTION`. An allen 1.100 vereinigten Kellern Dresdens: im gepushten Stand war keines dieser Gebäude fehlerfrei, jetzt 559 (ohne Vereinigung wären es 517); val3dity-`303`-Einzelmeldungen 30.355 → 7.612.

### Schritt 3 — StoreyGenerator

Kernstück der Pipeline. Pro Geschoss:
- **Wandschnitt:** `WallCuttingUtils.cutWallAtMultipleZJTS` — JTS-Bandschnitt im wandeigenen (u,v)-Profil, robust auch bei konkaven Profilen (Wand mit Anbau-Kerbe), löste den älteren Sutherland-Hodgman-Einzelschnitt (`cutWallPolygonAtZ`, noch für Basement-Schnitte verwendet) ab. LoD2-Splitterwände, deren Unterkante nur aus zwei lagegleichen Punkten besteht (keine Wandrichtung), bleiben ungeschnitten.
- **Boden-/Deckenzuschnitt bei Anbauten:** `SlabClippingUtils.clipSlabAtZ` — echte 2D-Polygon-Differenz (JTS `Polygon.difference()`): `slab(z) = Grundriss − (Dachanteile ≤ z)`. Liefert eine Liste von `SlabPiece`s (mehrere Teilstücke möglich, wenn ein Anbau das Gebäude in Flügel teilt). Ersetzt ein älteres Kanten-Matching-Verfahren vollständig (drei Vorgänger-Patches waren strukturell nicht robust genug für beliebige Anbau-Konturen).
- **Flachdach-Erkennung:** First-/Traufhöhe aus allen `RoofSurface`-Punkten; Differenz < 0,30 m → keine `CeilingSurface` am obersten Geschoss (Dach = Decke).
- **Mischdach-Erkennung:** bei gemischt geneigten/flachen Dachflächen wird die oberste Decke NUR aus den auf `traufeZ` projizierten geneigten Dachpolygonen erzeugt, nicht aus dem vollen Grundriss — verhindert eine Decke, die über einem niedrigeren Anbau-Flachdach schwebt.
- **Geschosse oberhalb der Traufe:** `calculateStoreys` teilt nur bis zur gebäudeweiten Traufhöhe. `appendUpperStoreys` erweitert den Stapel, wenn eine Wand mit zusammenhängender waagerechter Oberkante (≥ 1 m, `topEdgeWidth` — einzelne Spitzen wie Giebel oder Sägezahn-/Zwerchgiebelreihen zählen nicht) mindestens 0,75 × OG-Höhe über die Traufe ragt — Flügel, Turm oder Treppenhauskopf ohne eigenen BuildingPart. Die Höhe bis zur höchsten solchen Oberkante wird gleichmäßig auf ganze Geschosse verteilt (Anzahl ≈ Höhe / OG-Höhe, jedes Geschoss ≥ 2,0 m). Ein volles bisheriges Top-Geschoss endet knapp über der Traufe; ist es nur ein Restgeschoss oder blieben darüber weniger als 2,0 m, wird ab seinem Boden neu aufgeteilt — bringt das kein zusätzliches Geschoss, bleibt es beim bisherigen Stand. Neue Grenzhöhen liegen auf einem 1-cm-Raster und ≥ 1 cm neben allen Wand- und Dach-Eckpunkten **aller Gebäudeteile** (`buildingVertexZ`); Teile mit mm-verschiedener Traufe erzeugten sonst 3-mm-Stummelkanten an gemeinsamen Wänden (offene Hülle). Die neuen Grenzen (`newCutZ`) schneiden eine Wand nur, wenn keine nicht senkrechte Konturkante sie kreuzt oder weniger als 0,5 m darüber beginnt (`slopedEdgeNear`): ein Schnitt durch einen Giebel setzte T-Naht-Punkte in die Dachkanten (Planarität, Ring-Spitzen) und Fenster in Giebel-Trapeze, einer knapp darunter erzeugte Kamm-Segmente (val3dity `204`). Solche Wände erhalten ihr Geschoss aus den Geschossen unterhalb der Traufe — das Giebeldreieck bleibt Dachraum. Ausgelassen werden Teile mit Slab-Begrenzung durch ein niedrigeres Anbaudach (sonst neue Wände ohne passende Böden) und Teile ohne substanziellen Bereich: `SlabClippingUtils.hasSubstantialRegion` verlangt in Mindesthöhe über der Traufe einen zusammenhängenden Bereich unter dem Dach von ≥ 8 m² und ≥ 2 m Breite; dieselbe Bedingung gilt für jedes Deckenstück des obersten neuen Geschosses. Bei erweiterten Teilen entfällt die Mischdach-Projektion, das neue Top-Geschoss wird regulär auf seiner Oberkante zugeschnitten; Böden referenzieren die Decke darunter wie überall per XLink (`OrientableSurface`). Summary-Zeile „Geschosse oberhalb der Traufe“ (erweitert, Kandidaten, ausgelassen: Slab-Begrenzung, Fitzelchen-Bereich, kein Zusatzgeschoss). Testkachel: 89 Teile erweitert (159 Kandidaten; 40 / 19 / 11 ausgelassen). Dresden: 2.924 Teile erweitert (5.743 Kandidaten; 2.016 / 425 / 378 ausgelassen). Validierung siehe „Dresden-A/B-Validierung“ unter „Bekannte offene Punkte“.
- **Bereinigung selbstberührender Deckenringe:** Zuschnitte knapp neben Dach-Eckpunkten können Rückwärts-Spitzen und mm-Zacken erzeugen (Eckpunkt wenige mm neben einer nicht benachbarten Kante — JTS-valide, CityDoctor2 rundet auf mm und meldet `GE_R_SELF_INTERSECTION`). `clipSlabAtZ` prüft jedes Stück mit `SLAB_SPIKE_TOL` = 5 mm (`slabRingTouchesItself`), entfernt die Spitzen (`removeSlabSpikes`), fällt notfalls auf `GeometryFixer` zurück und verwirft ein unrettbares Stück mit Warnung; saubere Stücke bleiben unverändert. Decken gehören nicht zur geprüften Hülle, die großzügigere Toleranz ist dort unkritisch.
- **Robuste Dach-Vereinigung (`roofAreaBelowZ`):** Schnitthöhen oberhalb der Traufe schneiden viele Dachflächen nur teilweise; die klassische JTS-Vereinigung dieser Stücke scheiterte Dresden-weit 954× („non-noded intersection“). Früher wurde das Dachstück dann übersprungen — die oberste Decke ragte über tiefer liegende Dachteile hinaus (Testkachel: 11 Decken, von Validatoren nicht erkannt, weil Decken nicht zur Hülle gehören). Jetzt folgt im Fehlerfall `OverlayNGRobust` mit bereinigten Eingaben (`polygonal`: `GeometryFixer`, nur Flächenanteile — ohne Bereinigung scheiterte auch die robuste Variante an gemischten Eingaben). Dresden: keine Fehlschläge mehr, kein Gebäude mit zusätzlicher herausragender Decke (geprüft mit einem Abgleich jeder Decke gegen die Dachhöhe an ihrer Lage).

### Schritt 4/4d — DoorGenerator

Platziert Türen auf EG-Außenwänden (TIC-basierte Positionierung, `HDistDoWa`-Anker). Schritt 4d (`processFallbackDoors`, läuft NACH 5b/5d) ergänzt genau eine Tür für Gebäude mit Fenstern aber `DoorCount=0` überall (bestätigte Quelldatenlücke in der Adresspunkt-Zuordnung) — Wandauswahl: breiteste EG-Wand zuerst (FACEAREA-sortiert), Kollision mit vorhandenen Fenstern per `computeExistingWindowSpans` ausgeschlossen.

### Schritt 5a/5c — BalconyGenerator

Zweiphasig um Schritt 5b herum: Phase 1 platziert den führenden `Ga`-Lauf pro Wand unabhängig (`HDistWaGa`-Anker), reserviert die belegte Wandspanne. Phase 2 platziert restliche `Ga`-Token eines Musters (z.B. `"GaWiWiGa"`) nach den echten Fenstern (`HDistWiGa`-Anker). Deck+Brüstung als `BuildingInstallation` (`STRUKTUR=Balkondecke`/`Balkonbruestung`), nicht als Roof-/WallSurface — schema-konform ohne `boundedBy`-Ausschluss-Hack. Balkon-Normalen aus der wand-eigenen Umlaufrichtung abgeleitet (nicht aus einer globalen Schwerpunkt-Heuristik — die versagt bei nicht-konvexen Grundrissen/Innenhöfen).

### Schritt 5b — WindowGenerator

Fenster auf Geschoss-Außenwänden (TIC-Methode), max. 1 Reihe pro Wand und Geschoss. Enthält u.a. `openingInsideWallTopClearance2D`/`openingInsideWallSideTopClearance2D` (2 cm Sicherheitsabstand oben bzw. an beiden Seitenkanten — Ray-Casting ist an Randpunkten mehrdeutig) und `wallContourEntersOpening` (erkennt eine "M"-förmige Wandkontur, die mitten in eine sonst gültige Öffnung hineinragt). Beide Prüfungen fasst `fitsInWall` zusammen; seit 2026-09-17 gilt der Kontur-Check auch für Fenster und Balkon-Zugangstüren (vorher nur Türen und Dachfenster) — Fenster überspannten sonst Kerben und Täler gezackter Wandoberkanten (`GE_P_INTERSECTING_RINGS`, val3dity `201`), obwohl alle vier Ecken innerhalb der Wand lagen.

**Kellerfenster-Freihaltung unter Türen:** Im `BA`-Geschoss werden die verfügbaren Wandabschnitte um jede Tür herum um `BASEMENT_STAIR_CLEARANCE` (0,40 m je Seite) beschnitten — Platz für die kleine Außentreppe, die den Höhensprung vom herausragenden Kellerdeckel zur Tür überbrückt. Türen liegen im GF-Segment, Kellerfenster im BA-Segment; da beide keine gemeinsame ID tragen (`BasementGenerator` erzeugt Kellerwände pro Grundriss-Kante, `StoreyGenerator` schneidet die Originalwände), erfolgt die Zuordnung geometrisch über die gemeinsame Grundriss-Linie (`baSectionsExcludingDoorZones`, gleiches Prinzip wie `BasementGenerator.findWindowPreferenceForEdge`). Rein subtraktiv — GF/UF/Dach unberührt, val3dity und CityDoctor2 exakt unverändert. Bekannte Grenze: Fallback-Türen (Schritt 4d) laufen nach den Fenstern, für die betroffenen Gebäude bleibt der Keller darunter unbeschnitten.

### Schritt 5d — RoofWindowGenerator

Eigenständiger Generator (nicht in `WindowGenerator` integriert — fundamental andere Geometrie: geneigte statt senkrechte Fläche). Nutzt dieselbe `WindowParams`-Struktur wie Wandfenster (`RO.window`-Block). Kernentscheidung: die Wand-Konvention "u = entlang Unterkante, v = Z" wird auf "u = entlang Traufe, v = entlang der Dachschräge (Traufe→First)" generalisiert (`computeUpSlopeVector`, `projectPlaneTo2D` statt `projectWallTo2D`).

**Dachfenster exakt in der Dachebene:** `findBottomEdge` lässt zwischen den beiden Traufpunkten bis 1 cm Höhenunterschied zu. Wird die u-Achse rein horizontal angesetzt (ohne den `dz`-Anteil der Traufe), liegt sie bei einer leicht aus der Waage geratenen Traufe nicht in der Dachebene — die Fensterecken wandern proportional zu ihrer Traufposition aus der Ebene heraus (gemessen bis 8 mm, typisch 2–4 mm, bei 1,41 mm CityDoctor2-Schwelle). Genau das war die Ursache der meisten „neuen" `GE_P_NON_PLANAR`-Meldungen (30 Dachflächen, bei denen die Trägerfläche selbst plan ist). Seit 2026-09-11 ist die Traufrichtung ein echter 3D-Vektor (`dirX, dirY, dirZ`), `up = n × dir3`; beide Achsen liegen in der Dachebene, die Ecken exakt darin (Restabweichung nur mm-Rundung der Ausgabe, < 0,5 mm). Vollkachel-A/B: `GE_P_NON_PLANAR` 538 → 512, alle anderen Kategorien und alle Fenster-/Tür-/Balkonzahlen identisch, kein Gebäude verschlechtert. Bei Wandfenstern gibt es den analogen Effekt in schwächerer Form (v-Achse lotrecht, Wand minimal geneigt) — dort sind die Trägerwände aber fast immer ohnehin schon als verdreht geflaggt, deshalb bewusst nicht geändert.

**Traufkanten-Toleranz und Verdrehungsschutz:** `findBottomEdge` verlangte auch bei Dachflächen zwei Punkte innerhalb 1 cm von zMin — auf der Testkachel sind aber ~800 von 12.400 geneigten Dachflächen um 1–5 cm aus der Waage digitalisiert (`noBottom` war mit 359 der häufigste Skip-Grund, z. B. `imX`: eine 51-m²-Fläche mit 2 cm Traufversatz blieb ohne Fenster, die Nachbarfläche mit 5 mm bekam eines). Dachflächen nutzen deshalb eine eigene Toleranz `ROOF_EAVE_Z_TOL = 2 cm` (Wände bleiben bei 1 cm). 5 cm wurden getestet und verworfen: ein zusätzliches Fenster auf einer 18-eckigen, gekerbten Fläche erzeugte einen weiteren CityDoctor2-Trianguations-Fehlalarm (`GE_S_SELF_INTERSECTION`, Klasse `hGc`/`j0t`). Gleichzeitig neuer Schutz `ROOF_MAX_PLANE_DEVIATION = 5 mm` (Skip-Grund `warped`): in Dachflächen, die selbst stärker aus der Ebene sind, werden keine Fensterlöcher geschnitten — sie sind schon in den Quelldaten defekt (CityDoctor2 flaggt ab 1,4 mm), und mehr Fenster auf einer 8 mm verdrehten Fläche (`gY3`) erzeugten sonst einen val3dity-203-Fehler. Ergebnis Vollkachel: Dachfenster 802 → 857 auf 384 → 400 Flächen, CityDoctor2 und val3dity tile-weit identisch; 7 Flächen wegen Verdrehung ausgelassen, 4 davon hatten vorher ein Fenster (alle bereits als nicht-planar geflaggt).

**Vorhandene Löcher und Ausgleichsebene (2026-09-17):** Die Platzierung prüfte nur den Außenring der Dachfläche. Hat die Fläche schon in den Quelldaten ein Loch (Gauben- oder Schornsteinausschnitt), landete das Fenster teils darin oder daran (`GE_P_INNER_RINGS_NESTED`/`INTERSECTING_RINGS`, val3dity `207`/`201`) — ein alter Fehler, der bisher von der Planaritätsmeldung derselben Fläche verdeckt war. `OpeningUtils.projectInteriorRings2D` + `openingTouchesHoles2D` lehnen jetzt jedes Fenster ab, das ein vorhandenes Loch mit 2 cm Sicherheitsabstand berührt, schneidet oder darin liegt. Außerdem werden die Fensterecken zusätzlich auf die Ausgleichsebene der Dachfläche projiziert (`GeometryUtils.newellPlane`/`projectOntoPlane`): bei leicht verzogenen Dächern (Außenring 1–1,6 mm aus der Ebene, CityDoctor2-Schwelle 1,41 mm) weicht die Traufebene minimal ab, die Löcher schoben die Fläche sonst über die Schwelle. Dresden: `INNER_RINGS_NESTED` 66 → 0, `INTERSECTING_RINGS` 122 → 3.

### Schritt 6 — JunctionConformingUtils.conformJunctions (Toleranz 5 mm)

An T-Stößen (eine Wand endet mitten auf der Kante einer anderen) fehlt dem längeren Nachbarn der Zwischenpunkt, die Hülle schließt formal nicht. Fügt den fehlenden Vertex **auf** die bestehende Kante ein (liegt exakt auf der Geraden → keine Formänderung). Näht ausschließlich die eigenen, unabhängig erzeugten Zusatzflächen zusammen (Geschosse/Keller/Slabs) — die Quellgeometrie selbst (mm-Nähte, Planarität) bleibt bewusst unverändert, das ist Aufgabe des vorgelagerten Healers (läuft nur auf den LoD2-Daten; nach der Pipeline wird nicht mehr geheilt, von der Pipeline erzeugte Mängel muss sie also selbst vermeiden). Enthält ein eng begrenztes Vertex-Welding (bis 2 mm) VOR der Einfügung, um zwei unabhängig digitalisierte, nur mm-genau abweichende Eckpunkte zu verschmelzen (verhindert doppelte T-Naht-Einfügung an derselben Stelle).

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

### Zuletzt benutzter Ordner

Alle Auswahldialoge laufen über `newChooser(JTextField)`: Startordner ist der Elternordner des Feldeintrags, sonst der gemerkte Ordner. Nach jeder Auswahl speichert `rememberFolder` den Elternordner der gewählten Datei bzw. des gewählten Ordners (bei Ordnern der übergeordnete, damit Nachbarordner sichtbar sind) über `java.util.prefs.Preferences` (Schlüssel `letzterOrdner`, unter Windows in der Registry des angemeldeten Benutzers). Ein nicht mehr vorhandener Ordner wird ignoriert; ist die Registry nicht beschreibbar, bleibt es beim Standardverhalten. Identisch in `Sql2GmlGui`.

### Fehleranzeige

`startConversion` prüft vorab denselben Dateifilter wie `ModuleParametersLoader` (`*.json`); ohne Module liefe die Pipeline sonst „erfolgreich“ ohne ein einziges LoD3-Element. Bei einem Abbruch zeigt `describeError` die tiefste aussagekräftige Meldung der Ursachenkette: citygml4j verpackt XML-Lesefehler als `CityGMLReadException` mit der Meldung „Caused by:“, `AccessDeniedException`/`NoSuchFileException` liefern nur den Pfad und bekommen einen Klartext-Präfix. `filesModifiedSince` nennt Dateien im Ausgabeordner, die seit Laufbeginn geschrieben wurden (siehe „Kein Fehlerschutz je Gebäude“ unter „Bekannte offene Punkte“). Der Batch-Modus fängt Kachel-Fehler selbst ab und kehrt normal zurück; deshalb zählt `appendLog` alle Logzeilen mit „ ERROR “ (SLF4J-Simple-Format), und die GUI meldet dann „Fertig mit Fehlern“. `WARN`-Zeilen zählen bewusst nicht mit (Dresden-Lauf: rund 31.500 unbedenkliche Warnungen, keine `ERROR`-Zeile). Die Meldungstexte samt Abhilfe stehen in Doku_Benutzende.md, „Meldungen des Programms“.

### Log-Anzeige

`System.out`/`System.err` werden beim GUI-Start (vor jedem Zugriff auf `Lod2ToLod3Pipeline`, da SLF4J-Simple den Ziel-Stream beim ersten `LoggerFactory.getLogger(...)`-Aufruf fest bindet) durch einen zeilenweise puffernden `OutputStream` ersetzt, der jede Zeile zusätzlich an die Swing-Textfläche weiterreicht — ohne den ursprünglichen Stream zu ersetzen (bei Start aus einem Terminal bleibt die Konsolenausgabe zusätzlich erhalten).

---

## Bekannte offene Punkte

Diese Liste enthält bewusst nur **noch nicht vollständig gelöste** oder **strukturell akzeptierte** Fälle — abgeschlossene Bugfixes sind über die Git-Historie nachvollziehbar, nicht hier dupliziert.

- **GE_R_SELF_INTERSECTION „Spike"-Fälle:** eine Kachel-Kantenhöhe liegt gelegentlich 1–9 mm von einem originalen Wand-Stufen-Vertex entfernt; die 5-mm-T-Naht-Suche (Schritt 6) findet dann einen bereits im selben Ring vorhandenen Punkt erneut und erzeugt einen "Spike" (A→B→C→B). Schritt 7 löst echte zweiseitige Pinch-Points, kann aber Spikes strukturell nicht auflösen (beide Hälften würden auf <3 Punkte entarten). Betrifft 5 Gebäude der Testkachel (`gqs`, `h37`, `h55`, `hms`, `09Xf000Fn`). Lokale Fixes sind ausgeschöpft: zuletzt wurde 2026-09-11 ein fünfter Versuch (Kandidat überspringen, wenn er exakt einem anderen Punkt desselben Rings entspricht) per Vollkachel-A/B verworfen — der doppelte Punkt wird für die Wand-Dach-Naht tatsächlich gebraucht, ohne ihn meldet val3dity an denselben Gebäuden `303 NON_MANIFOLD_CASE` statt `104`. Ein echter Fix bräuchte einen Modellwechsel (Ring darf sich an einer Stelle berühren bzw. echte Zwei-Loop-Struktur).
- **Shell-Fehler `GE_S_NOT_CLOSED`/`GE_S_NON_MANIFOLD_EDGE` (Stand 2026-09-14, CityDoctor2 mit Projekt-Konfiguration):** tile-weit 4 bzw. 2 Instanzen, keine davon durch die Pipeline verursacht — `CpV`, `gGt`, `hjL` unverändert aus dem LoD2 geerbt; `gmt`, `hdQ`, `iMM` im LoD2 bereits defekt, nur mit anderem Fehlernamen. Der letzte pipeline-eigene Fall (`gJv`, Keller-Sliver) ist seit der Grundriss-Vereinigung behoben (siehe Schritt 2). Die frühere Angabe „~66 Gebäude, Ursache ungeklärt" stammte aus einer CityDoctor2-Prüfung ohne vollständige Konfiguration und ist überholt.
- **Geschosse oberhalb der Traufe — bewusst ausgelassene Fälle:** Überstände über der Haupttraufe werden in Geschosse unterteilt (siehe Schritt 3). Nicht erweitert werden weiterhin Teile mit Slab-Begrenzung durch ein niedrigeres Anbaudach (Dresden: 2.016 Kandidaten) — die Regel stammt aus der Zeit vor dem JTS-Deckenzuschnitt und ist vermutlich überholt, eine Lockerung wäre ein eigener A/B-Schritt. Mansard- oder Dachaufbau-Geschosse ohne entsprechend hohe Wand werden nicht erkannt.
- **val3dity `601 BUILDINGPARTS_OVERLAP`:** bei Mehrteil-Gebäuden (Standard-Muster: gemeinsame, xlink-referenzierte Party-Wall) meist ein CGAL-Nef-Erosions-Artefakt der Prüfkette (`citygml-tools`→CityJSON→val3dity), kein reales Volumen-Overlap — mechanistisch am val3dity-Quellcode verifiziert. `--overlap_tol` würde das sauber ausschließen, ist aber in der aktuellen val3dity-2.6.0b0-Windows-Beta auf großen Kacheln instabil (hängt/stürzt ab) — daher nur auf handhabbaren Teilmengen nutzbar. Die reine `601`-Zahl ist folglich kein verlässliches Qualitätsmaß für Mehrteil-Gebäude.
- **CityDoctor2 `SE_POLYGON_WITHOUT_SURFACE`:** meldet fälschlich auf jedem Fenster-/Tür-/Balkon-Polygon — bestätigter Mapper-Bug im Tool (Öffnungsgeometrie wird beim Einlesen nie mit ihrer Wand verknüpft), auch am externen FZK-Haus-Referenzdatensatz reproduziert (14.207 Treffer). Schema-Validität ist davon unberührt.
- **BalconyGenerator — Parameter-Interpretationen ohne volle Spec-Bestätigung:** einige `GA`-Block-Felder (`HDistWiGa`-Doppelrolle, `DistWiGa`, Zugangsöffnung als `DoorSurface` statt eigenem Element) sind aus wenigen realen Datenpunkten inferiert, nicht durch eine vollständige Spezifikation belegt — Geometrie-Validität ist davon unabhängig nachgewiesen. Details siehe Javadoc der Klasse.
- **Kein Fehlerschutz je Gebäude:** `CityGmlUtils.processGmlFile` fängt Ausnahmen der Generatoren nicht je Gebäude ab. Eine Ausnahme bricht die ganze Datei ab (Batch: nur diese Kachel) und hinterlässt ein formal gültiges, aber unvollständiges CityModel; getestet mit einer Ausnahme beim 3. von 14 Gebäuden → Ausgabe mit 2 Gebäuden. Die GUI nennt diese Datei im Fehlerdialog. Ein Abfangen je Gebäude müsste den Zustand vor den Generator-Schritten sichern, weil diese das Gebäude-Objekt direkt verändern. Im Dresden-Lauf trat kein solcher Fall auf.
- **Stille Auslassungen:** Gebäude ohne `sst`, ohne passende Moduldatei oder ohne `H_DGM` überspringen die Generatoren ohne Log-Zeile (`resolveParams` bzw. `parseDoubleAttribute` liefert leer, `processBuilding` kehrt zurück). Es gibt keine `_default.json`-Rückfallebene. Ein Zähler in der Zusammenfassung würde solche Fälle sichtbar machen.

**Aktuelle Baseline** (kompletter Dresden-Lauf, 98 Kacheln, 141.670 Gebäude, 2026-09-17): val3dity 98,52 % valide Features / 99,67 % valide Primitive; CityDoctor2 94,63 % fehlerfreie Gebäude (Projekt-Konfiguration). Dominante Fehlerkategorien: `601 BUILDINGPARTS_OVERLAP` (val3dity, s.o.) und `GE_P_NON_PLANAR_POLYGON_DISTANCE_PLANE` (CityDoctor2, überwiegend aus unabhängig digitalisierten LoD2-Quellflächen geerbtes Rauschen, nicht durch die Pipeline verursacht).

**Dresden-A/B-Validierung vor der Auslieferung (2026-09-17):** alle 98 Kacheln einmal mit dem zuletzt gepushten Stand und einmal mit dem aktuellen Stand, identische Eingabe, DGM und Konfiguration, CityDoctor2 und val3dity (`--verbose` liefert die Fehler je Gebäude; `--report` bleibt in der Windows-Beta leer). Gebäude mit CityDoctor2-Fehlern 11.001 → 7.614, val3dity ungültige Features 8.621 → 7.789 und Primitive 3.966 → 3.110. Die 194 Gebäude mit mindestens einer neuen Meldung wurden einzeln nachgerechnet (Teilmenge liefert dieselben Gebäude wie die Vollkachel) und zusätzlich gegen das validierte LoD2-Original abgeglichen. Fast alle neuen Meldungen sind geerbt oder **verdeckt**: CityDoctor2 prüft die Hülle eines Gebäudes erst, wenn seine Flächen fehlerfrei sind, und Ring-Schnitte erst bei planaren Flächen; val3dity prüft `3xx` erst ohne `1xx`/`2xx`-Fehler und `306` erst ohne `303`. Behebt eine Änderung den vordergründigen Fehler, erscheint der dahinterliegende, schon vorhandene Defekt als „neu“ — bei A/B-Vergleichen deshalb immer gebäudeweise gegen den Vorzustand und das LoD2-Original prüfen. Echt neu blieben 14 Einzelmeldungen: CityDoctor2 `GE_S_SELF_INTERSECTION` ×6 (val3dity bewertet dieselben Hüllen als gültig — bekannte Triangulations-Fehlalarm-Kategorie), val3dity `204` ×5 (teils Artwechsel an derselben Fläche, teils Grenzfälle), CityDoctor2-Planarität ×2 (Grenzfälle an bereits nicht planaren Gebäuden) und val3dity `302` ×1 (`DESNALK0q5003M3r`, CityDoctor2 ohne Befund). Zusätzliche `601`-Meldungen verschwinden alle mit `--overlap_tol 0.001`. Unterwegs gefunden und behoben: aus dem Dach ragende Decken, Keller-Ringe mit mm-Zacken, Dachfenster in vorhandenen Dachlöchern, Wandfenster über Konturkerben, Geschossgrenzen durch Giebel, Sägezahn-Wände als Erweiterungskandidaten, mm-Stummel zwischen Gebäudeteilen.

---

## Unit-Tests

`src/test/java/de/mpsc/lod2tolod3/` und `.../util/` (spiegelt `src/main`-Paketstruktur). JUnit 5, von Maven Surefire automatisch erkannt. `mvn test` bzw. `mvn clean package` (Tests laufen automatisch mit).

Alle Testklassen sind Regressionstests für konkret gefundene Fehler (nicht abstrakte Coverage-Übung):

| Testklasse | Prüft |
|---|---|
| `CityGmlUtilsWallCutTest` | `cutWallAtMultipleZJTS` — Kerben-Zerlegung, Flächenbilanz |
| `CityGmlUtilsPartyWallTest` | `computeCoveredSpans`/`overlapsAnySpan`/`isFullyCovered` — Party-Wand-Erkennung |
| `CityGmlUtilsBottomEdgeTest` | `findBottomEdge` — Punktepaar mit größtem 2D-Abstand bei geknickter Basis |
| `CityGmlUtilsOpeningClearanceTest` | `openingInsideWallTopClearance2D` — Fenster-Traufe-Clearance; `openingTouchesHoles2D` — Dachfenster in/an vorhandenen Löchern |
| `CityGmlUtilsSlabClipTest` | `clipSlabAtZ` — Anbau-Zuschnitt |
| `CityGmlUtilsRoofPlaneTest` | Solid-Shell-Rebuild bei Dachflächen-FillingSurfaces |
| `CityGmlUtilsFootprintUnionTest` | `unionFootprints` — Keller-Grundriss-Vereinigung, Innenhof bleibt getrennt |
| `CityGmlUtilsSlabSpikeTest` | `slabRingTouchesItself`/`removeSlabSpikes` — Rückwärts-Spitze und mm-Zacke in Deckenringen |
| `StoreyGeneratorUpperStoreyTest` | `topEdgeWidth`/`slopedEdgeNear` — waagerechte Oberkante vs. Giebel/Sägezahn, Schnitt durch/knapp unter Giebel |

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
