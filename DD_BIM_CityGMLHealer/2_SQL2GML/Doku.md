# sql2gml - Dokumentation

## Übersicht

**sql2gml** ist ein Java-Tool, das CityGML-Dateien auf Basis einer SQLite-Datenbank aktualisiert. Es liest validierte und ggf. korrigierte Geometriedaten aus der Datenbank und schreibt sie zurück in die CityGML-Datei.

### Kernfunktionen
- **Koordinaten-Updates**: Übernimmt korrigierte Polygon-Koordinaten aus der `LinearRings`-Tabelle (bei `IsValid=1`)
- **IsValid-Kaskade**: Top-down-Prüfung — Building → BuildingPart → Surface. Ist eine Ebene `IsValid=0`, wird die gesamte Unterhierarchie übersprungen (Original-Geometrie bleibt erhalten)
- **Polygon-Splitting**: Neue Polygone aus der DB (Log enthält "NewPolygon") werden als neue GML-Polygone in der `MultiSurface` erstellt und im `CompositeSurface` (lod2Solid) per `xlink:href` referenziert
- **Validierungs-Log**: Schreibt das Log aus allen Hierarchie-Ebenen (Building, BuildingPart, Surface, Polygon, LinearRing) als CityGML-Attribute
- **Attribut-Übertragung**: Übernimmt berechnete Attribute (FACEAREA, NORMAL_AZI, NORMAL_H, Z_Max, Z_Min, Z_MAX_ASL, Z_MIN_ASL) aus der Datenbank
- **Duplikat-sichere Attribute**: Existierende Attribute werden aktualisiert statt dupliziert
- **Batch-Verarbeitung**: Auto-Modus liest Dateiliste aus DB und verarbeitet nur Dateien mit Modifikationen

---

## Technologien

| Technologie | Version | Zweck |
|-------------|---------|-------|
| **Java** | 21 LTS | Programmiersprache |
| **Maven** | 3.x | Build- und Dependency-Management |
| **citygml4j** | 3.2.7 | CityGML-Bibliothek (Lesen/Schreiben) |
| **SQLite JDBC** | 3.47.x | Datenbankzugriff |
| **Gson** | 2.11.x | JSON-Parsing für Attribute |
| **SLF4J Simple** | 2.0.x | Logging |

---

## Ausführung

### Voraussetzungen
- Java 21 installiert (JAVA_HOME gesetzt)
- Maven installiert
- SQLite-Datenbank mit validierten Daten vorhanden

### Kompilieren (Fat-JAR)
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
cd sql2gml_neu
mvn clean package -q
```

Erzeugt `target/sql2gml-complete.jar` (Fat-JAR mit allen Abhängigkeiten).

### Modus 1: Einzelne Datei
```powershell
java -jar target/sql2gml-complete.jar <input.gml> <database.db> <output.gml>
```

**Beispiel:**
```powershell
java -jar target/sql2gml-complete.jar D:\data\LoD2_33_416_5656.gml D:\data\BuildingParts.db D:\output\LoD2_33_416_5656_updated.gml
```

### Modus 2: Batch-Verarbeitung (Ordner)
Verarbeitet alle `.gml` Dateien in einem Ordner:

```powershell
java -jar target/sql2gml-complete.jar <inputFolder> <database.db> <outputFolder>
```

### Modus 3: Auto-Batch (empfohlen für Kacheln)
Liest die Dateiliste aus der `CityGmlFiles`-Tabelle und verarbeitet nur Dateien mit Modifikationen:

```powershell
java -jar target/sql2gml-complete.jar <database.db> <inputFolder> <outputFolder> --auto
```

**Vorteile des Auto-Modus:**
- Liest Dateiliste direkt aus der Datenbank (`CityGmlFiles`-Tabelle)
- Überspringt Dateien ohne Modifikationen automatisch
- Ideal für große Kachel-Datensätze (z.B. Dresden mit 100+ Kacheln)

---

## Datenbankstruktur

Die SQLite-Datenbank enthält die hierarchische Gebäudestruktur. Jede Tabelle hat `IsValid` (0/1) und `Log` Spalten:

```
CityGmlFiles
    └── Buildings (n)              IsValid, Log, Attributes (JSON)
            └── BuildingParts (n)  IsValid, Log, Attributes (JSON)
                    └── Surfaces (n)       IsValid, Log, Attributes (JSON: FACEAREA, NORMAL_AZI, ...)
                            └── Polygons (n)       IsValid, Log
                                    └── LinearRings (n)    IsValid, Log, PosList
```

### IsValid-Semantik

| IsValid | Bedeutung | Verarbeitung im Tool |
|---------|-----------|---------------------|
| `1` | Element oder Kind wurde korrigiert | Koordinaten aus DB übernehmen |
| `0` | Unheilbarer Fehler | Original-CityGML beibehalten, Log als Attribut hinzufügen |

**Log wird immer übertragen**, unabhängig von IsValid.

### IsValid-Kaskade

Die IsValid-Prüfung erfolgt **top-down** beim Aufbau des Polygon-Index:

```
Building (IsValid=0?) → Gesamtes Building überspringen (Original-GML beibehalten)
  └── BuildingPart (IsValid=0?) → Part und alle Surfaces/Polygone überspringen
        └── Surface (IsValid=0?) → Surface und alle Polygone überspringen
              └── Polygon → Koordinaten aus DB übernehmen / neue Polygone erstellen
```

- Buildings mit `IsValid=0` werden im ObjectWalker komplett übersprungen: `visit(Building)` kehrt ohne `super.visit()` zurück, die Original-Geometrie bleibt vollständig erhalten.
- Logs werden immer übertragen — auch bei ungültigen Hierarchie-Ebenen.

### Tabellen

#### CityGmlFiles
| Spalte | Typ | Beschreibung |
|--------|-----|--------------|
| Id | INTEGER | Primary Key |
| Filename | TEXT | Dateiname der CityGML-Kachel |

#### Buildings
| Spalte | Typ | Beschreibung |
|--------|-----|--------------|
| Id | INTEGER | Primary Key |
| BuildingIdGml | TEXT | gml:id des Buildings |
| FileId | INTEGER | Referenz auf CityGmlFiles |
| Attributes | TEXT | JSON mit Attributen |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |

#### BuildingParts
| Spalte | Typ | Beschreibung |
|--------|-----|--------------|
| Id | INTEGER | Primary Key |
| PartIdGml | TEXT | gml:id des BuildingParts |
| BuildingId | INTEGER | Referenz auf Buildings |
| Attributes | TEXT | JSON mit Attributen |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |

#### Surfaces
| Spalte | Typ | Beschreibung |
|--------|-----|--------------|
| Id | INTEGER | Primary Key |
| SurfaceIdGml | TEXT | gml:id der Surface |
| SurfaceTypeId | INTEGER | Typ (WallSurface, RoofSurface, etc.) |
| BuildingPartId | INTEGER | Referenz auf BuildingParts |
| Attributes | TEXT | JSON mit berechneten Attributen (FACEAREA, NORMAL_AZI, NORMAL_H, Z_Max, Z_Min, Z_MAX_ASL, Z_MIN_ASL) |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |

#### Polygons
| Spalte | Typ | Beschreibung |
|--------|-----|--------------|
| Id | INTEGER | Primary Key |
| PolygonIdGml | TEXT | gml:id des Polygons |
| SurfaceId | INTEGER | Referenz auf Surfaces |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |

#### LinearRings
| Spalte | Typ | Beschreibung |
|--------|-----|--------------|
| PolygonId | INTEGER | Referenz auf Polygons |
| RingIndex | INTEGER | 0 = Exterior, 1+ = Interior |
| PosList | TEXT | Koordinaten als Leerzeichen-getrennte Liste (x y z x y z ...) |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |

---

## Projektstruktur

```
sql2gml_neu/
├── pom.xml                          # Maven-Konfiguration (inkl. maven-shade-plugin für Fat-JAR)
├── Doku.md                          # Diese Dokumentation
├── README.md                        # Kurzanleitung
└── src/main/java/de/mpsc/sql2gml/
    ├── CompleteWorkflow.java        # Hauptklasse (Workflow)
    ├── DatabaseReader.java          # Datenbankzugriff
    ├── ExtractSst.java              # Gebäude mit sst-Attribut extrahieren
    ├── ExtractBuildings.java        # Gebäude nach gml:id extrahieren
    └── model/
        ├── Building.java            # Gebäude-Modell
        ├── BuildingPart.java        # Gebäudeteil-Modell
        ├── Surface.java             # Oberflächen-Modell
        ├── Polygon.java             # Polygon-Modell
        └── LinearRing.java          # Ring-Modell
```

---

## Hilfs-Tools

### ExtractSst — Gebäude mit sst-Attribut extrahieren

Extrahiert nur Gebäude, die das generische Attribut `name="sst"` besitzen, aus einer CityGML-Datei. Arbeitet textstrom-basiert (`BufferedReader`/`BufferedWriter`) und kann daher auch sehr große Kacheln effizient verarbeiten, ohne den gesamten CityGML-Objektbaum im Speicher aufzubauen.

#### Verwendung

```powershell
# Einzelne Datei — Output wird als _sst.gml neben der Eingabe erzeugt
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <input.gml>

# Einzelne Datei mit explizitem Ausgabepfad
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <input.gml> <output.gml>

# Einzelne Datei mit Ausgabeordner (Dateiname wird automatisch erzeugt)
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <input.gml> <outputFolder>

# Ordner-Modus — alle .gml Dateien im Ordner verarbeiten
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <folder>

# Ordner-Modus mit Ausgabeordner
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <folder> <outputFolder>
```

#### Funktionsweise

1. Liest die CityGML-Datei zeilenweise
2. Sammelt jeden `<core:cityObjectMember>`-Block
3. Prüft ob der Block `name="sst"` enthält
4. Schreibt nur passende Blöcke in die Ausgabe (Header/Footer werden immer übernommen)

#### Beispiel

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst `
    "D:\data\LoD2_33_416_5656_2_SN.gml" `
    "D:\output\LoD2_33_416_5656_2_SN_sst.gml"
```

```
--- LoD2_33_416_5656_2_SN.gml ---
  Total buildings: 3801
  SST buildings:   1452
  Output: D:\output\LoD2_33_416_5656_2_SN_sst.gml
```

### ExtractBuildings — Gebäude nach gml:id extrahieren

Extrahiert bestimmte Gebäude anhand ihrer `gml:id` aus einer CityGML-Datei. Ebenfalls textstrom-basiert.

#### Verwendung

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractBuildings <input.gml> <output.gml> <ID1> [ID2] ...
```

#### Beispiel

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractBuildings `
    "D:\data\LoD2_33_416_5656_2_SN.gml" `
    "D:\output\7buildings.gml" `
    DESNALK0pF001g4s DESNALK0pF001gGp DESNALK0pF001gjj
```

---

## Klassen im Detail

### CompleteWorkflow.java

Die Hauptklasse, die den gesamten Workflow steuert.

#### RunMode Enum

Erkennt den Ausführungsmodus anhand der Kommandozeilen-Argumente:

| Wert | Erkennung | Bedeutung |
|------|-----------|-----------|
| `SINGLE_FILE` | `args.length >= 2` und `args[0]` ist eine Datei | Einzelne GML-Datei verarbeiten |
| `BATCH_FOLDER` | `args.length >= 2` und `args[0]` ist ein Ordner | Alle GML-Dateien in einem Ordner |
| `AUTO_BATCH` | `args.length >= 4` und `args[3] == "--auto"` | Dateiliste aus DB, nur geänderte Kacheln |

Die Erkennung erfolgt in `RunMode.detect(String[] args)` — AUTO_BATCH hat Vorrang vor BATCH_FOLDER.

#### Ablauf

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CompleteWorkflow                              │
├─────────────────────────────────────────────────────────────────────┤
│  Step 1: Datenbank lesen                                            │
│    → DatabaseReader lädt alle Buildings hierarchisch                │
│    → Erstellt Polygon-Index nach gml:id (Map<String, Polygon>)      │
│    → Erstellt Surface-Index (Map<Polygon, Surface>) für O(1)-Lookup │
│    → Zählt: valid / invalid                                         │
├─────────────────────────────────────────────────────────────────────┤
│  Step 2: CityGML verarbeiten                                        │
│    → Liest original boundedBy vom CityModel (ohne Chunking)         │
│    → Iteriert über alle Features mit CityGMLReader (mit Chunking)   │
│    → ObjectWalker besucht jedes Polygon:                            │
│       • Nicht in DB: Keine Änderung                                 │
│       • IsValid=0: Original beibehalten + Log als Attribut          │
│       • IsValid=1: Koordinaten aus DB übernehmen +                  │
│                     Surface-Attribute aktualisieren                  │
│       • Splitting: Neue Polygone in MultiSurface einfügen           │
│       • Log wird immer übertragen (Polygon, Ring, Surface-Ebene)    │
│    → ObjectWalker besucht jedes Building:                           │
│       • Building-Log + DB-Attribute hinzufügen                      │
│       • BuildingPart-Logs hinzufügen                                │
│       • IsValid=0 → kein super.visit, Original-Geometrie bleibt    │
│    → 2. Pass: CompositeSurface-Referenzen für neue Polygone         │
│       • Programmatische citygml4j-Navigation (nicht ObjectWalker)   │
│       • Building.getLod2Solid() + Building.getBuildingParts()        │
│       • Fügt xlink:href für jedes neue Polygon in die Shell ein     │
│    → 3. Pass: Entfernung ungültiger Polygone und Surfaces           │
│       • Polygone mit IsValid=0 (z.B. Tesselated) aus MultiSurface  │
│       • Surfaces mit IsValid=0 als ganze BoundarySurface            │
│       • Leer gewordene BoundarySurfaces (alle Polygone entfernt)    │
│       • xlink:href-Bereinigung im CompositeSurface (lod2Solid)      │
│       • Removal-Log als Attribute am Building (Removal_1, ...)      │
│    → fixHeader: Original-Header wird beibehalten (FME-Kompatibilität)│
│    → Schreibt Feature in Output-GML                                 │
├─────────────────────────────────────────────────────────────────────┤
│  Output: Neue CityGML 1.0 mit aktualisierten Daten                  │
└─────────────────────────────────────────────────────────────────────┘
```

#### Wichtige Methoden

| Methode | Beschreibung |
|---------|--------------|
| `main(String[] args)` | Einstiegspunkt, parst Argumente, wählt Modus (Single/Batch/Auto) |
| `runSingleFile()` | Verarbeitet eine einzelne GML-Datei |
| `runBatchMode()` | Verarbeitet alle GML-Dateien in einem Ordner |
| `runBatchFromDatabase()` | Liest Dateiliste aus DB, verarbeitet nur Dateien mit Modifikationen |
| `configureWriter()` | Konfiguriert `CityGMLChunkWriter` mit Namespace-Prefixes, Schema-Locations und `boundedBy` |
| `findParentSurface()` | Navigiert via `Child`-Interface zum Parent-Surface (WallSurface, RoofSurface, etc.) |
| `addLogToParentSurface()` | Sammelt Logs von Polygon-, Ring- und Surface-Ebene und schreibt sie als Attribute |
| `updateLinearRingCoordinates()` | Setzt neue Koordinaten in einem GML-LinearRing |
| `addGenericAttributes()` | Fügt Attribute hinzu oder aktualisiert bestehende (duplikat-sicher) |
| `findRingByIndex()` | Findet LinearRing nach Index in der DB-Polygon-Liste |
| `addNewRefsToSolid()` | Fügt xlink:href-Referenzen für neue Polygone in die Shell des lod2Solid ein |
| `findParentMultiSurface()` | Navigiert via `Child`-Interface aufwärts zur parent MultiSurface |
| `addNewPolygonToMultiSurface()` | Erzeugt neues GML-Polygon aus DB-Daten und fügt es in die MultiSurface ein |
| `createGmlLinearRing()` | Erzeugt einen GML-LinearRing aus DB-Daten (mit posList, srsDimension=3) |
| `removeSurfacesFromBuilding()` | Entfernt BoundarySurfaces mit IsValid=0 (inkl. xlink:href-Bereinigung) |
| `removeEmptySurfaces()` | Entfernt BoundarySurfaces, deren MultiSurface nach Polygon-Entfernung leer ist |
| `fixHeader()` | Ersetzt den citygml4j-generierten Header durch den Original-Header (FME-Kompatibilität) |

#### Log-Übertragung

Logs werden auf der Parent-Surface (WallSurface/RoofSurface/GroundSurface) als `gen:stringAttribute` geschrieben:

| Log-Quelle | Attribut-Name | Häufigkeit |
|------------|---------------|------------|
| Polygon | `Log_<PolygonIdGml>` | Pro Polygon |
| LinearRing | `Log_<PolygonIdGml>_exterior` / `Log_<PolygonIdGml>_interior_1` | Pro Ring |
| Surface | `Log_Surface_<SurfaceIdGml>` | Einmal pro Surface (dedupliziert) |

Building-Ebene:

| Log-Quelle | Attribut-Name |
|------------|---------------|
| Building | `Log` |
| BuildingPart | `Log_<PartIdGml>` |

#### Surface-Attribut-Aktualisierung

Für `IsValid=1`-Polygone werden folgende Attribute aus der DB auf der Parent-Surface aktualisiert:

```
FACEAREA, NORMAL_AZI, NORMAL_H, Z_Max, Z_Min, Z_MAX_ASL, Z_MIN_ASL
```

Dabei werden existierende Attribute mit gleichem Namen **aktualisiert** (nicht dupliziert).

#### Polygon-/Surface-Entfernung

Polygone und Surfaces mit `IsValid=0` werden aus der GML entfernt. Der Ablauf ist dreistufig:

1. **Polygon-Entfernung**: Polygone mit `IsValid=0` (z.B. Log enthält "Tesselated") werden aus ihrer `MultiSurface` entfernt (das `surfaceMember`-Element wird gelöscht).

2. **Surface-Entfernung**: Surfaces mit `IsValid=0` werden als ganze `BoundarySurface` (WallSurface, RoofSurface, etc.) aus dem Building/BuildingPart entfernt.

3. **Leere-Surface-Bereinigung**: BoundarySurfaces, deren `MultiSurface` nach individueller Polygon-Entfernung leer wurde (alle Polygone einzeln entfernt), werden ebenfalls entfernt.

In allen drei Fällen werden die zugehörigen `xlink:href`-Referenzen im `CompositeSurface` (`lod2Solid`) automatisch bereinigt.

#### Removal-Log

Entfernungen werden als `gen:stringAttribute` am **Building** protokolliert:

| Attribut-Name | Inhalt | Beispiel |
|---------------|--------|----------|
| `Removal_1` | Surface/Polygon + Grund | `Surface UUID_abc entfernt: Tesselated` |
| `Removal_2` | nächste Entfernung | `Polygon UUID_xyz entfernt: Tesselated` |
| `Removal_3` | LinearRing-Log | `LinearRing UUID_xyz_exterior entfernt: Polygon nicht mehr vorhanden` |
| … | fortlaufend nummeriert | |

#### Anchor-lose Merged Surfaces

Beim Polygon-Splitting können neue Surfaces entstehen, die **kein** Original-Polygon als Anker besitzen (alle Polygone der Surface sind neu, z.B. durch Zusammenführung). In diesem Fall wird kein bestehendes Polygon im ObjectWalker besucht, an dem die neuen Polygone angefügt werden könnten.

**Lösung**: Beim Index-Aufbau werden solche "anchor-losen" Surfaces identifiziert. Wenn ein beliebiges Polygon desselben Buildings/BuildingParts im ObjectWalker besucht wird, werden die neuen Polygone der anchor-losen Surface in dessen `MultiSurface` eingefügt (da sie zum gleichen Parent-Building gehören).

#### Header-Korrektur (fixHeader)

citygml4j schreibt beim Output einen eigenen XML-Header mit reduzierten Namespace-Deklarationen. FME und andere Werkzeuge erwarten jedoch den vollständigen Original-Header (inkl. `tex:`, `sch:`, `gml:boundedBy`, etc.).

`fixHeader()` ersetzt nach dem Schreiben den generierten Header durch den Original-Header der Eingabedatei. Alles vor dem ersten `<core:cityObjectMember>` wird 1:1 aus der Originaldatei übernommen.

#### Polygon-Splitting (NewPolygon)

Wenn ein Polygon in der DB geteilt wurde (Log enthält `"NewPolygon"`), werden die neuen Polygone:

1. **In der MultiSurface erstellt**: Neues `gml:Polygon` mit `gml:id`, Exterior-Ring und ggf. Interior-Rings aus der DB. Wird als neues `surfaceMember` in die bestehende `MultiSurface` der Surface eingefügt.

2. **Im CompositeSurface referenziert**: Im `lod2Solid` des Buildings (bzw. BuildingParts) wird ein `xlink:href` auf das neue Polygon eingefügt. Dazu wird programmatisch navigiert:

```
Building.getLod2Solid() → SolidProperty → Solid → Shell → surfaceMembers
Building.getBuildingParts() → BuildingPart.getLod2Solid() → ... (gleicher Pfad)
```

**Wichtig**: Der `ObjectWalker` traversiert die `lod2Solid`-Geometrie bei CityGML 1.0 Daten **nicht**. Daher erfolgt die CompositeSurface-Aktualisierung in einem separaten programmatischen Schritt nach dem ObjectWalker-Durchlauf.

**Wichtig**: BuildingParts werden über `Building.getBuildingParts()` erreicht (direkte citygml4j API), **nicht** über `DeprecatedPropertiesOfAbstractBuilding.getConsistsOfBuildingParts()`. Letzteres wird von citygml4j beim Lesen von CityGML 1.0 nicht befüllt.

---

### Innere Klassen in CompleteWorkflow

#### ProcessingStats

Mutable Counter-Klasse, die während der Entfernungsphase aggregierte Zahlen hält.

| Feld | Typ | Bedeutung |
|------|-----|-----------|
| `removedPolygons` | `int` | Anzahl bisher entfernter Polygone (IsValid=0 oder leer) |
| `removedSurfaces` | `int` | Anzahl bisher entfernter BoundarySurfaces |

Wird als Parameter an `removeSurfacesFromBuilding()` und `removeEmptySurfaces()` übergeben, damit beide Methoden gemeinsam auf denselben Zähler addieren können.

---

#### PolygonIndex

Baut beim Start aus der hierarchischen DB-Struktur (Liste von `Building`-Objekten) mehrere Maps auf, die während des ObjectWalker-Durchlaufs O(1)-Zugriff ermöglichen.

##### Felder

| Feld | Typ | Inhalt |
|------|-----|--------|
| `polygonIndex` | `Map<String, Polygon>` | Polygon-gml:id → DB-Polygon |
| `surfaceByPolygon` | `Map<Polygon, Surface>` | DB-Polygon → DB-Surface |
| `buildingByPolygon` | `Map<Polygon, Building>` | DB-Polygon → DB-Building |
| `newPolygonsByParentPolygon` | `Map<String, List<Polygon>>` | Anker-Polygon-gml:id → neue Split-Polygone |
| `newPolygonsByBuilding` | `Map<String, List<Polygon>>` | Building-gml:id → anchor-lose neue Polygone |
| `polygonsToRemove` | `Set<String>` | gml:ids von Polygonen mit IsValid=0 |
| `surfacesToRemove` | `Set<String>` | gml:ids von Surfaces mit IsValid=0 |
| `removedPolygonIds` | `Set<String>` | gml:ids erfolgreich entfernter Polygone (für xlink-Bereinigung) |
| `surfaceAttributesByPolygon` | `Map<String, Map<String, Object>>` | Polygon-gml:id → Surface-Attribute (FACEAREA, etc.) |
| `surfaceIdByPolygon` | `Map<String, String>` | Polygon-gml:id → Surface-gml:id |
| `anchorlessNewPolygonBuildings` | `Set<String>` | Building-gml:ids mit anchor-losen neuen Polygonen |

##### buildFromDatabase()

Iteriert über alle Buildings/BuildingParts/Surfaces/Polygons mit IsValid-Kaskade:

1. Überspringt Building, BuildingPart oder Surface wenn `IsValid=0`
2. Ruft `categorizePolygon()` für jedes Polygon auf

##### categorizePolygon(Polygon, Surface, String buildingGmlId)

Drei-Wege-Klassifizierung mit Guard Clauses:

| Bedingung | Aktion |
|-----------|--------|
| `polygon.getLog()` enthält `"NewPolygon"` | Neues Split-Polygon — in `newPolygonsByParentPolygon` oder `newPolygonsByBuilding` (anchor-los) |
| `!polygon.isValid()` | Ungültiges Polygon — in `polygonsToRemove` eintragen |
| sonst | Gültiges Polygon — in `polygonIndex`, `surfaceByPolygon`, `surfaceAttributesByPolygon` usw. eintragen |

Bei anchor-losen neuen Polygonen (kein gültig zugehöriges Original-Polygon existiert) wird der Building-gml:id in `anchorlessNewPolygonBuildings` eingetragen.

---

#### GmlUpdateWalker

`ObjectWalker`-Implementierung. Besucht alle `Polygon`- und `Building`-Objekte im CityGML-Objektbaum.

##### Felder

| Feld | Typ | Bedeutung |
|------|-----|-----------|
| `idx` | `PolygonIndex` | Lookup-Strukturen aus der DB |
| `updatedPolygons` | `int` | Zähler: aktualisierte Polygone |
| `updatedRings` | `int` | Zähler: aktualisierte Ringe |
| `processedBuildings` | `int` | Zähler: verarbeitete Buildings |
| `visitedPolygons` | `int` | Zähler: alle besuchten Polygone |
| `skippedBuildings` | `int` | Zähler: übersprungene Buildings (IsValid=0) |
| `createdPolygons` | `int` | Zähler: neu erzeugte Split-Polygone |
| `currentBuildingInDb` | `boolean` | `true` wenn das aktuell besuchte Building in der DB vorkommt |
| `writtenSurfaceLogs` | `Set<String>` | dedupliziert Log-Writes pro Surface-gml:id |
| `processedSurfacesForNewPolygons` | `Set<String>` | verhindert doppeltes Einfügen anchor-loser Polygone |
| `polygonsToRemove` | `Map<String, List<String>>` | Building-gml:id → Liste zu entfernender Polygon-gml:ids |
| `removedPolygonIdsForXlink` | `Set<String>` | gml:ids bereits entfernter Polygone (für xlink-Bereinigung) |
| `SURFACE_ATTRIBUTE_KEYS` | `static final String[]` | `{"FACEAREA","NORMAL_AZI","NORMAL_H","Z_Max","Z_Min","Z_MAX_ASL","Z_MIN_ASL"}` |

##### visit(Polygon)

5-Zeilen-Dispatcher:

```java
if (!idx.polygonIndex.containsKey(gmlId)) {
    handleUnmatchedPolygon(polygon, gmlId);
    return;
}
handleMatchedPolygon(polygon, gmlId);
```

##### handleUnmatchedPolygon(Polygon, String gmlId)

Wird für Polygone aufgerufen, die **nicht** im `polygonIndex` sind (also kein gültiges Update aus der DB haben).

Logik mit Guard Clauses:

1. Falls das Polygon-gml:id in `polygonsToRemove` des Walker: merkt das Polygon zur Entfernung vor
2. Falls das Building (`currentBuildingInDb`) anchor-lose neue Polygone hat und diese Surface noch nicht verarbeitet wurde: fügt die neuen Polygone in die MultiSurface des aktuellen Polygons ein (`addNewPolygonToMultiSurface`), setzt `processedSurfacesForNewPolygons`
3. Falls `currentBuildingInDb = false`: kein DB-Eintrag für dieses Building, Polygon wird unverändert durchgelassen

##### handleMatchedPolygon(Polygon, String gmlId)

Wird für Polygone aufgerufen, die im `polygonIndex` gefunden wurden.

1. **Koordinaten-Update**: Ruft `updateLinearRingCoordinates()` für jeden Ring auf
2. **Attribute-Update**: Iteriert `SURFACE_ATTRIBUTE_KEYS` und aktualisiert Attribute auf der Parent-Surface (duplikat-sicher via `addGenericAttributes()`)
3. **Log-Übertragung**: Ruft `addLogToParentSurface()` auf
4. **Split-Polygone**: Falls das Polygon als Anker für neue Polygone dient (`newPolygonsByParentPolygon`), fügt diese in die MultiSurface ein

##### visit(Building)

- Schlägt das Building anhand seiner `gml:id` in der DB nach, setzt `currentBuildingInDb`
- Wenn `IsValid=0`: schreibt Building-Log als Attribut, kehrt **ohne** `super.visit()` zurück (Original-Geometrie bleibt)
- Wenn `IsValid=1`: schreibt Building-Log, BuildingPart-Logs und Building-Attribute aus JSON, ruft `super.visit()` für Kinder auf

---

### DatabaseReader.java

Liest die hierarchische Datenstruktur aus der SQLite-Datenbank.

#### Wichtige Methoden

| Methode | Beschreibung |
|---------|--------------|
| `readAllBuildings()` | Liest komplette Hierarchie: Buildings → BuildingParts → Surfaces → Polygons → LinearRings |
| `getCityGmlFiles()` | Liest alle Dateinamen aus `CityGmlFiles`-Tabelle (Map: FileId → Filename) |
| `hasModificationsForFile(fileId)` | Prüft ob mindestens ein Polygon mit `IsValid=1` für eine Datei existiert |
| `readBuildingPartsForBuilding()` | Liest BuildingParts für ein spezifisches Building |
| `readSurfacesForBuildingPart()` | Liest Surfaces für einen BuildingPart (inkl. JSON-Attribute) |
| `readPolygonsForSurface()` | Liest Polygons mit IsValid und Log |
| `readLinearRingsForPolygon()` | Liest LinearRings mit PosList, IsValid und Log |

#### JSON-Attribute

Die `Attributes`-Spalte in Buildings, BuildingParts und Surfaces enthält JSON:

```json
{
    "FACEAREA": 12.345,
    "NORMAL_AZI": 180.0,
    "NORMAL_H": 0.0,
    "Z_Max": 125.5,
    "Z_Min": 120.0,
    "Z_MAX_ASL": 225.5,
    "Z_MIN_ASL": 220.0
}
```

Diese werden mit Gson in `Map<String, Object>` geparst.

---

### Model-Klassen

Alle Model-Klassen haben `valid` (boolean) und `log` (String) Felder.

| Klasse | Felder | Beschreibung |
|--------|--------|--------------|
| `Building` | id, buildingIdGml, fileId, attributes, valid, log, buildingParts | Gebäude mit Referenz auf CityGML-Datei |
| `BuildingPart` | id, buildingId, partIdGml, attributes, valid, log, surfaces | Gebäudeteil |
| `Surface` | id, surfaceIdGml, attributes, valid, log, polygons | Oberfläche (Wall/Roof/Ground) |
| `Polygon` | id, surfaceId, polygonIdGml, valid, log, linearRings | Polygon |
| `LinearRing` | polygonId, ringIndex, posList, valid, log | Ring mit Koordinaten |

`LinearRing.getPosListAsArray()` konvertiert den PosList-String in ein `double[]` Array.

---

## citygml4j - Bibliothek

### Architektur

```
┌──────────────────────────────────────────────────────────────────────┐
│  CityGMLContext                                                      │
│    ├── createCityGMLInputFactory()  → CityGMLReader                  │
│    └── createCityGMLOutputFactory() → CityGMLChunkWriter             │
└──────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│  CityGML Object Model                                                │
│    Building                                                          │
│      ├── boundedBy → WallSurface / RoofSurface / GroundSurface       │
│      │                 └── lod2MultiSurface → MultiSurface           │
│      │                                          └── surfaceMember    │
│      │                                                └── Polygon    │
│      │                                                     └── exterior / interior
│      │                                                          └── LinearRing
│      │                                                                └── posList
│      ├── lod2Solid → SolidProperty → Solid → Shell (CompositeSurface)│
│      │                                         └── surfaceMember     │
│      │                                              └── xlink:href   │
│      └── getBuildingParts() → BuildingPart (gleiche Struktur)        │
└──────────────────────────────────────────────────────────────────────┘
```

### Wichtige Klassen

| Klasse | Beschreibung |
|--------|--------------|
| `CityGMLContext` | Factory für Reader/Writer |
| `CityGMLReader` | Liest CityGML-Dateien als Java-Objekte |
| `CityGMLChunkWriter` | Schreibt CityGML-Objekte in eine Datei |
| `ObjectWalker` | Visitor-Pattern zum Durchlaufen des Objektbaums |
| `AbstractFeature` | Basisklasse für alle CityGML-Features |
| `AbstractCityObject` | Basisklasse mit `getGenericAttributes()` |
| `Child` | Interface mit `getParent()` für Navigation im Objektbaum |
| `Building` | Gebäude-Objekt mit `getLod2Solid()`, `getBuildingParts()` |
| `BuildingPart` | Gebäudeteil mit eigenem `getLod2Solid()` |
| `WallSurface`, `RoofSurface`, `GroundSurface` | Surface-Typen |
| `MultiSurface` | Aggregat von Polygonen (lod2MultiSurface) |
| `SolidProperty` | Wrapper für Solid (lod2Solid) |
| `Solid` | Geometrie-Volumen mit `getExterior()` → Shell |
| `Shell` | CompositeSurface mit `getSurfaceMembers()` (xlink:href-Liste) |
| `SurfaceProperty` | Referenz auf Surface/Polygon, hat `setHref()` für xlink |
| `Polygon` | Einzelnes Polygon |
| `LinearRing` | Ring aus Koordinaten |
| `DirectPositionList` | Koordinaten-Liste |
| `StringAttribute` | Generisches String-Attribut |

### ObjectWalker Pattern

```java
feature.accept(new ObjectWalker() {
    @Override
    public void visit(Polygon polygon) {
        String gmlId = polygon.getId();
        // Polygon verarbeiten
        super.visit(polygon);  // Kinder besuchen
    }
    
    @Override
    public void visit(Building building) {
        // Building verarbeiten
        super.visit(building);
    }
});
```

### Parent-Navigation via Child-Interface

```java
// Vom Polygon zur Parent-Surface navigieren
Object current = polygon;
while (current instanceof Child child) {
    current = child.getParent();
    if (current instanceof AbstractCityObject cityObject) {
        // WallSurface, RoofSurface, etc. gefunden
    }
}
```

### Koordinaten setzen

```java
double[] dbCoords = dbRing.getPosListAsArray();
List<Double> coordList = new ArrayList<>(dbCoords.length);
for (double coord : dbCoords) {
    coordList.add(coord);
}
gmlRing.getControlPoints().getPosList().setValue(coordList);
```

### lod2Solid / CompositeSurface navigieren

Der `ObjectWalker` traversiert bei CityGML 1.0 die `lod2Solid`-Geometrie **nicht** (kein `visit(Solid)` oder `visit(CompositeSurface)` wird aufgerufen). Der Zugriff erfolgt programmatisch über die citygml4j API:

```java
// Building → lod2Solid → Solid → Shell → surfaceMembers
SolidProperty solidProp = building.getLod2Solid();
Solid solid = (Solid) solidProp.getObject();
Shell shell = solid.getExterior().getObject();
List<SurfaceProperty> refs = shell.getSurfaceMembers();  // xlink:href-Einträge

// Neue Referenz hinzufügen
SurfaceProperty newRef = new SurfaceProperty();
newRef.setHref("#neuePolygonId");
refs.add(newRef);

// BuildingParts über Building.getBuildingParts() (NICHT deprecated properties!)
for (var partProp : building.getBuildingParts()) {
    BuildingPart part = partProp.getObject();
    SolidProperty partSolid = part.getLod2Solid();  // eigener lod2Solid
    // ... gleiche Navigation wie oben
}
```

### Attribute duplikat-sicher hinzufügen

```java
List<AbstractGenericAttributeProperty> attrs = cityObject.getGenericAttributes();
for (AbstractGenericAttributeProperty prop : attrs) {
    if (prop.getObject() instanceof StringAttribute existing && name.equals(existing.getName())) {
        existing.setValue(newValue);  // Existierenden Wert aktualisieren
        return;
    }
}
// Neues Attribut hinzufügen
attrs.add(new AbstractGenericAttributeProperty(new StringAttribute(name, value)));
```

---

## Wichtige Hinweise

### maven-shade-plugin: ServicesResourceTransformer

Das Fat-JAR wird mit `maven-shade-plugin` gebaut. **Kritisch** ist der `ServicesResourceTransformer`:

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
```

Ohne diesen Transformer werden die `META-INF/services`-Dateien nicht korrekt zusammengeführt. Das führt dazu, dass citygml4j die GML 3.1.1 Adapter nicht registriert und Geometrie-Elemente (MultiSurface, Polygon) als `null` geparst werden — obwohl das XML korrekt geladen wird.

### DefaultReferenceResolver NICHT verwenden

```java
// FALSCH — bricht inline-Geometrie:
// DefaultReferenceResolver.resolveReferences(feature);

// RICHTIG — ObjectWalker greift direkt auf die Geometrie zu
feature.accept(new ObjectWalker() { ... });
```

### CityGML 1.0 Output-Konfiguration

Der Writer wird mit den Schema-Locations des GDI-DE Repository konfiguriert und verwendet die Namespace-Prefixes der Original-Datei (inkl. `core:`, `tex:`, `sch:`, etc.).

---

## Bekannte Einschränkungen

1. **Interior Rings**: Werden unterstützt, aber weniger getestet als Exterior Rings.
2. **CityGML-Version**: Nur CityGML 1.0 wird unterstützt (Lesen und Schreiben).
3. **Attribut-Typen**: Aus der DB werden alle Attribute als `gen:stringAttribute` geschrieben. Numerische Typen in der DB werden zu Strings konvertiert.
4. **ObjectWalker und lod2Solid**: Der `ObjectWalker` von citygml4j traversiert bei CityGML 1.0 die `lod2Solid`-Geometrie nicht. CompositeSurface-Änderungen (z.B. neue xlink:href) müssen programmatisch über `Building.getLod2Solid()` erfolgen.
5. **BuildingParts-Zugriff**: `Building.getBuildingParts()` ist die korrekte API. `DeprecatedPropertiesOfAbstractBuilding.getConsistsOfBuildingParts()` wird bei CityGML 1.0 von citygml4j nicht befüllt.

---

## Beispiel-Workflow

### Szenario: Einzelne Kachel mit validierten Geometrien

**Datenbank-Inhalt (BuildingParts.db):**
- 1.487 Buildings, 1.735 BuildingParts, 14.771 Surfaces, 14.755 Polygons, 14.761 LinearRings
- 12.801 Polygone mit `IsValid=1` (Koordinaten korrigiert)
- 1.954 Polygone mit `IsValid=0` (nicht reparierbar, Log wird übertragen)

**Ausführung:**
```powershell
java -jar target/sql2gml-complete.jar
```

**Log-Ausgabe:**
```
=== Complete Workflow: DB → CityGML ===
--- Step 1: Reading Database ---
Read 1487 buildings
Created polygon index with 14755 entries
Polygon status: 12801 valid (update from DB), 1954 invalid (keep original, add Log)

--- Step 2: Processing CityGML ---
Reading and updating features...

--- Results ---
Features read: 3801
Polygons visited by GeometryWalker: 61978
Buildings processed: 3801
Polygons updated (isValid=1): 12801
Polygons kept original + Log added (isValid=0): 1954
Linear Rings updated: 12807
Polygons not in DB (unchanged): 47223

✓ SUCCESS: CityGML file created with updated coordinates!
```

**Ergebnis (Output-GML):**
- 12.801 Polygone mit korrigierten Koordinaten aus der DB
- 12.807 LinearRings aktualisiert (einige Polygone haben Interior-Rings)
- 1.954 Polygone beibehalten + Log als Attribut
- Surface-Attribute (FACEAREA etc.) aktualisiert (existierende ersetzt, keine Duplikate)
- 47.223 Polygone unverändert (nicht in DB enthalten)

---

## Weiterführende Links

- [citygml4j GitHub](https://github.com/citygml4j/citygml4j)
- [CityGML Standard](https://www.citygml.org/)
- [OGC CityGML 2.0](https://www.ogc.org/standards/citygml)
