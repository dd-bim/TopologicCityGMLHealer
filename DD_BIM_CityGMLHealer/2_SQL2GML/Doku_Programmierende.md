# sql2gml — Doku für Programmierende

Architektur, Datenbankschema, Klassen, citygml4j-Muster, offene Punkte. Für Aufruf/Konfiguration siehe [Doku_Administrierende.md](Doku_Administrierende.md), für die fachliche Einordnung [Doku_Benutzende.md](Doku_Benutzende.md).

---

## Übersicht: zwei DB-Schema-Generationen, vier Workflows

| Workflow | DB-Schema | Strategie | Status |
|---|---|---|---|
| `HealedReplaceWorkflow` | neu (`SurfaceGeometries`+`PosLists`) | Kompletter Geometrie-Ersatz auf Building-Ebene, inkl. Solid+XLinks; neue/überholte BuildingParts werden automatisch angelegt/entfernt | **Haupt-Workflow** (Main-Class des JARs) |
| `PolygonOnlyReplaceWorkflow` | neu | Identisch zu `HealedReplaceWorkflow`, schreibt aber niemals `gml:TriangulatedSurface` (zerlegt in Einzel-Polygone) | Variante für Validatoren ohne TIN-xlink-Unterstützung (CityDoctor 3.18.2) |
| `legacy.ReplaceWorkflow` | alt (`Polygons`+`LinearRings`) | Kompletter Geometrie-Ersatz, Vorgänger von `HealedReplaceWorkflow` | Legacy, nicht weiterentwickelt |
| `legacy.CompleteWorkflow` | alt | Selektives Koordinaten-Update einzelner Polygone mit IsValid-Kaskade und Polygon-Splitting | Legacy, nicht weiterentwickelt |

---

## Datenbankschema (neues Healer-Schema)

```
CityGmlFiles
    └── Buildings (n)              IsValid, Log, Attributes (JSON)
            └── BuildingParts (n)  IsValid, Log, Attributes (JSON)
                    └── Surfaces (n)             IsValid, Log, Attributes (JSON: FACEAREA, NORMAL_AZI, ...)
                            └── SurfaceGeometries (genau 1 je Surface)  IsValid, Log, GeometryTypeId
                                    └── PosLists (n)   IsValid, Log, PosList, PosListIndex
```

Unterschied zum alten Schema (siehe „Legacy" unten): `Polygons`+`LinearRings` wurden durch `SurfaceGeometries`+`PosLists` ersetzt — eine Surface hat jetzt genau EINE Geometrie (nicht mehr potenziell mehrere Polygone), die per `GeometryTypeId` entweder ein klassisches Polygon (`0`) oder eine Dreiecksvermaschung/TIN (`1`) ist.

### Tabellen

<details><summary><strong>CityGmlFiles</strong></summary>

| Spalte | Typ | Beschreibung |
|---|---|---|
| Id | INTEGER | Primary Key |
| Filename | TEXT | Dateiname der CityGML-Kachel |
</details>

<details><summary><strong>Buildings</strong></summary>

| Spalte | Typ | Beschreibung |
|---|---|---|
| Id | INTEGER | Primary Key |
| BuildingIdGml | TEXT | gml:id des Buildings |
| FileId | INTEGER | Referenz auf CityGmlFiles |
| Attributes | TEXT | JSON mit Attributen |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |
</details>

<details><summary><strong>BuildingParts</strong></summary>

| Spalte | Typ | Beschreibung |
|---|---|---|
| Id | INTEGER | Primary Key |
| PartIdGml | TEXT | `null`/leer → Geometrie gehört zum Building; existierende GML-Part-ID → In-place-Ersatz; sonst → Solid-Merge-Gate greift (s. u.) |
| BuildingId | INTEGER | Referenz auf Buildings |
| Attributes | TEXT | JSON mit Attributen |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |
</details>

<details><summary><strong>Surfaces</strong></summary>

| Spalte | Typ | Beschreibung |
|---|---|---|
| Id | INTEGER | Primary Key |
| SurfaceIdGml | TEXT | gml:id der Surface |
| SurfaceTypeId | INTEGER | 0=None (→WallSurface-Fallback), 1=Ground, 2=Wall, 3=Roof |
| BuildingPartId | INTEGER | Referenz auf BuildingParts |
| Attributes | TEXT | JSON: FACEAREA, NORMAL_AZI, NORMAL_H, Z_Max, Z_Min, Z_MAX_ASL, Z_MIN_ASL |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |
</details>

<details><summary><strong>SurfaceGeometries</strong> — genau 1 je Surface (mehrere werden geloggt, alle bis auf die erste verworfen)</summary>

| Spalte | Typ | Beschreibung |
|---|---|---|
| Id | INTEGER | Primary Key |
| SurfaceId | INTEGER | Referenz auf Surfaces |
| GeometryIdGml | TEXT | gml:id der Geometrie (Polygon bzw. TriangulatedSurface) |
| GeometryTypeId | INTEGER | 0 = Polygon, 1 = TriangulatedSurface (TIN) |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |
</details>

<details><summary><strong>PosLists</strong></summary>

| Spalte | Typ | Beschreibung |
|---|---|---|
| SurfaceGeometryId | INTEGER | Referenz auf SurfaceGeometries |
| PosListIndex | INTEGER | Bei Polygon: 0 = Außenring, 1+ = Löcher. Bei TIN: jede PosList ein unabhängiges Dreieck (4 Punkte, geschlossen) |
| PosList | TEXT | Koordinaten als Leerzeichen-getrennte Liste (x y z x y z ...), geschlossener Ring |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |
</details>

### IsValid-Semantik

| IsValid | Bedeutung | Verarbeitung im Tool |
|---|---|---|
| `1` | Element (beim Building: die GESAMTE Unterhierarchie) korrekt/valide | Geometrie aus DB übernehmen |
| `0` | Unheilbarer Fehler irgendwo in der Hierarchie | Original-CityGML für das GANZE Building beibehalten |

**Log wird immer in die DB geschrieben**, aber `HealedReplaceWorkflow` liest/schreibt nur den Building- und BuildingPart-Log als generisches Attribut (`Log`/`Log_Part`) — anders als beim alten, feingranularen Legacy-Log pro Polygon/Ring (siehe „Legacy" unten).

### Valid-Gate (`isFullyValid`, striktes All-or-Nothing)

```
Building.isValid()
  UND für JEDEN BuildingPart:   part.isValid()
  UND für JEDE Surface:         surface.isValid()
  UND deren SurfaceGeometry:    geometry != null, geometry.isValid(), mind. 1 PosList
  UND für JEDE PosList:         posList.isValid()
  UND mindestens 1 BuildingPart vorhanden
  UND Solid-Merge-Gate:         JEDER DB-Part mit gesetzter PartIdGml referenziert ein
                                 BEREITS bestehendes GML-BuildingPart (1:1) — keine vom
                                 Healer neu erzeugten Party-Wall-Merge/Split-Solids
```

Ist irgendein Teil invalide, bleibt das gesamte Building unverändert — kein Teil-Ersatz, keine Hüllen-Lücke.

### Solid-Merge-Gate — technisch

Der Healer erzeugt bei einem Party-Wall-Merge (mehrere Original-BuildingParts zu einem Solid verschmolzen) oder -Split (ein Teil in mehrere Solids aufgespalten) eine `PartIdGml`, die zu keinem bestehenden GML-BuildingPart passt. Früher wurde dafür automatisch ein neues BuildingPart angelegt (siehe Git-Historie). **Das ist bewusst deaktiviert:** der Healer-Code für diesen Fall ist noch nicht ausgereift genug und hat sichtbar kaputte Geometrie produziert (fehlende Wände, schwebendes Dach). Bis der Healer-Code dafür reif ist, disqualifiziert jede solche `PartIdGml` das gesamte Building — Original-Geometrie bleibt vollständig erhalten, wie bei jedem anderen Valid-Gate-Fehlschlag. Fachliche Einordnung siehe [Doku_Benutzende.md](Doku_Benutzende.md).

**Erweiterung auf die Rückrichtung:** die ursprüngliche Fassung prüfte nur DB→GML (jede vom Healer vergebene `PartIdGml` muss ein bestehendes GML-Part treffen), nicht aber GML→DB (jeder bestehende GML-Part muss einen DB-Gegenpart haben). Ein GML-BuildingPart, für den die Healer-DB überhaupt keine Zeile mehr liefert, wurde bisher klaglos vom „überholte-Parts-entfernen"-Schritt gelöscht statt das Building wie jeden anderen Merge/Split-Fall unangetastet zu lassen. Das Gate prüft jetzt beide Richtungen; fehlt auch nur ein GML-Part komplett in der DB, bleibt das gesamte Building unverändert.

---

## Klassen im Detail

### HealedReplaceWorkflow.java — Haupt-Workflow

```
┌─────────────────────────────────────────────────────────────────────┐
│  Step 1: Datenbank lesen (EINMAL pro Lauf)                          │
│    → DbReader lädt alle Buildings hierarchisch                      │
│    → Index: BuildingIdGml → DB-Building                             │
├─────────────────────────────────────────────────────────────────────┤
│  Step 2: CityGML verarbeiten (Streaming, Chunk-weise, KEIN          │
│          ObjectWalker — einfache while-Schleife mit instanceof)     │
│    → boundedBy-Envelope aus dem Original übernehmen                 │
│    → Pro Building:                                                  │
│       • Nicht in DB: unverändert durchreichen                       │
│       • In DB, aber NICHT vollständig valide (Valid-Gate): Original │
│         komplett erhalten, Warnung loggen                           │
│       • In DB UND vollständig valide (inkl. Solid-Merge-Gate):       │
│           1. DB-Attribute + Log als generische Attribute schreiben  │
│           2. Je DB-BuildingPart Ziel bestimmen (s. u.) und dessen   │
│              Boundaries neu aufbauen (Surfaces → SurfaceGeometry →   │
│              Polygon ODER TIN)                                      │
│           3. Ueberholte GML-Parts entfernen (Healer hat sie in der   │
│              DB weggelassen, ohne sie in etwas Neues zu mergen)      │
│           4. lod2Solid JE ZIEL komplett neu aus Geometrie-IDs        │
│              (xlink:href) — fehlt Geometrie, wird das Solid entfernt│
│    → Header-Fix (Namespaces/schemaLocation aus der Eingabe)         │
└─────────────────────────────────────────────────────────────────────┘
```

**Was erhalten bleibt:** Building-eigene CityGML-Attribute (measuredHeight, function, Adresse, …) — nur die Geometrie und die DB-Attribute werden ersetzt.

**Ergebnis-Statistik:** Features read, Buildings replaced/unchanged/kept-invalid, Superseded parts removed, Surfaces written (davon TIN), sowie eine Aufschlüsselung der Triangulierung je Flächentyp (Ground/Wall/Roof) — triangulierte Wände werden gesondert als Building-Liste ausgegeben.

#### BuildingPart-Zielbestimmung

| `PartIdGml` | Ziel | Bedeutung |
|---|---|---|
| `null`/leer | das Building selbst | Geometrie gehört direkt zum Building |
| existierende GML-Part-ID | das bestehende `BuildingPart` (in-place) | 1:1-Fall, keine Aufspaltung/Merge für dieses Teil |

Jede andere ID darf diesen Punkt gar nicht erst erreichen — das Solid-Merge-Gate in `isFullyValid` sortiert das gesamte Building vorher aus. Erreicht der Code diesen Zweig trotzdem (Gate/Logik-Widerspruch), wird der betroffene DB-Part defensiv übersprungen und ein Fehler geloggt, statt einen unreifen Healer-Merge-Solid zu schreiben.

#### GeometryMode (`buildGeometries`)

| Modus | GeometryTypeId=1 (TIN) wird geschrieben als | Verwendet von |
|---|---|---|
| `AS_IN_DATABASE` (Standard) | `gml:TriangulatedSurface`, 1:1 zur DB | `HealedReplaceWorkflow` |
| `ALWAYS_POLYGON` | N einzelne `gml:Polygon` (ein Dreieck je Polygon, eigene `gml:id`, alle einzeln im Solid referenziert) | `PolygonOnlyReplaceWorkflow` |

Die DB-Spalte `GeometryTypeId` bleibt in beiden Fällen unangetastet — der Modus steuert ausschließlich die GML-Repräsentation. `setGeometryMode()` ist ein globaler, statischer Schalter, der VOR `main()` gesetzt werden muss.

#### RunMode Enum

| Wert | Erkennung | Bedeutung |
|---|---|---|
| `SINGLE_FILE` | `args.length >= 2` und `args[0]` ist eine Datei | Einzelne GML-Datei verarbeiten |
| `BATCH_FOLDER` | `args.length >= 2` und `args[0]` ist ein Ordner | Alle GML-Dateien in einem Ordner |
| `AUTO_BATCH` | `args.length >= 4` und `args[3] == "--auto"` | Dateiliste aus DB, nur geänderte Kacheln |

Erkennung in `RunMode.detect(String[] args)` — AUTO_BATCH hat Vorrang vor BATCH_FOLDER.

### PolygonOnlyReplaceWorkflow.java

30-Zeilen-Wrapper: ruft `HealedReplaceWorkflow.setGeometryMode(ALWAYS_POLYGON)` und delegiert dann an `HealedReplaceWorkflow.main(args)`.

### DbReader.java

Liest die hierarchische Datenstruktur aus der SQLite-Datenbank. Jede Tabelle wird genau EINMAL komplett gelesen und im Speicher über die Fremdschlüssel zusammengesetzt (kein N+1-Query-Muster).

| Methode | Beschreibung |
|---|---|
| `readAllBuildings()` | Liest komplette Hierarchie: Buildings → BuildingParts → Surfaces → SurfaceGeometries (genau 1 je Surface) → PosLists |
| `getCityGmlFiles()` | Liest alle Dateinamen aus `CityGmlFiles`-Tabelle (Map: FileId → Filename) |
| `hasModificationsForFile(fileId)` | Prüft per JOIN über die ganze Hierarchie, ob mindestens eine `SurfaceGeometry` mit `IsValid=1` für eine Datei existiert |

### GmlMemberFilter.java

Gemeinsamer, zeilenbasierter Streaming-Filter — Basis für `ExtractSst` und `ExtractBuildings`. Kopiert den Datei-Rahmen (alles außerhalb eines `cityObjectMember`) unverändert und schreibt jeden `<core:cityObjectMember>…</core:cityObjectMember>`-Block nur, wenn ein übergebenes `Predicate<String>` (erhält den vollständigen Blocktext) `true` liefert. Setzt voraus, dass öffnendes und schließendes Member-Tag je auf einer eigenen Zeile stehen (passt zum GDI-DE-/FME-Format dieses Projekts).

### Model-Klassen (neues Schema)

Alle Model-Klassen haben `valid` (boolean) und `log` (String) Felder.

| Klasse | Felder | Beschreibung |
|---|---|---|
| `Building` | id, buildingIdGml, fileId, attributes, valid, log, buildingParts | Gebäude mit Referenz auf CityGML-Datei |
| `BuildingPart` | id, buildingId, partIdGml, attributes, valid, log, surfaces | Gebäudeteil |
| `Surface` | id, surfaceIdGml, surfaceTypeId, attributes, valid, log, geometry | Oberfläche; **genau EINE** `SurfaceGeometry` (nicht mehr eine Liste von Polygonen) |
| `SurfaceGeometry` | id, surfaceId, geometryIdGml, geometryTypeId, valid, log, posLists | Ersetzt `Polygon`. `geometryTypeId`: `TYPE_POLYGON=0`, `TYPE_TRIANGULATED_SURFACE=1`; `isTriangulatedSurface()` |
| `PosList` | surfaceGeometryId, posListIndex, posList, valid, log | Ersetzt `LinearRing`. Bei Polygon: Index 0=Außenring, >0=Loch. Bei TIN: jede PosList ein unabhängiges Dreieck |

---

## citygml4j — Architektur und Muster

```
CityGMLContext
  ├── createCityGMLInputFactory()  → CityGMLReader
  └── createCityGMLOutputFactory() → CityGMLChunkWriter

Building
  ├── boundedBy → WallSurface / RoofSurface / GroundSurface
  │                 └── lod2MultiSurface → MultiSurface → surfaceMember → Polygon
  │                                                          └── exterior/interior → LinearRing → posList
  ├── lod2Solid → SolidProperty → Solid → Shell (CompositeSurface) → surfaceMember → xlink:href
  └── getBuildingParts() → BuildingPart (gleiche Struktur)
```

**ObjectWalker-Verzicht im Haupt-Workflow:** `HealedReplaceWorkflow` liest mit einer einfachen `while (reader.hasNext())`-Schleife über die Chunk-Features und unterscheidet Feature-Typen per `instanceof Building` — kein `ObjectWalker`, kein Visitor. Grund: der Replace-Ansatz ersetzt beim Treffer die komplette Boundary/Solid eines Buildings auf einmal, es muss also nicht mehr in die Tiefe (Polygon-Ebene) traversiert werden. Das `ObjectWalker`-Pattern wird nur noch von den Legacy-Workflows verwendet (siehe unten).

**Parent-Navigation via `Child`-Interface:**
```java
Object current = polygon;
while (current instanceof Child child) {
    current = child.getParent();
    if (current instanceof AbstractCityObject cityObject) { /* WallSurface, RoofSurface, ... gefunden */ }
}
```

**lod2Solid / CompositeSurface navigieren:** der `ObjectWalker` traversiert bei CityGML 1.0 die `lod2Solid`-Geometrie nicht (kein `visit(Solid)`/`visit(CompositeSurface)`). Der Zugriff erfolgt programmatisch — `HealedReplaceWorkflow.rebuildSolid()` baut den Solid für jedes Ziel exakt nach diesem Schema neu auf:
```java
SolidProperty solidProp = building.getLod2Solid();
Solid solid = (Solid) solidProp.getObject();
Shell shell = solid.getExterior().getObject();
List<SurfaceProperty> refs = shell.getSurfaceMembers();   // xlink:href-Einträge

SurfaceProperty newRef = new SurfaceProperty();
newRef.setHref("#neuePolygonId");
refs.add(newRef);

// BuildingParts über Building.getBuildingParts() (NICHT DeprecatedPropertiesOfAbstractBuilding!)
for (var partProp : building.getBuildingParts()) {
    BuildingPart part = partProp.getObject();
    SolidProperty partSolid = part.getLod2Solid();   // eigener lod2Solid, gleiche Navigation
}
```

**Attribute duplikat-sicher hinzufügen:**
```java
for (AbstractGenericAttributeProperty prop : cityObject.getGenericAttributes()) {
    if (prop.getObject() instanceof StringAttribute existing && name.equals(existing.getName())) {
        existing.setValue(newValue);
        return;
    }
}
cityObject.getGenericAttributes().add(new AbstractGenericAttributeProperty(new StringAttribute(name, value)));
```

---

## Technische Hinweise

**maven-shade-plugin: ServicesResourceTransformer** — kritisch für das Fat-JAR:
```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
```
Ohne diesen Transformer werden `META-INF/services`-Dateien nicht korrekt zusammengeführt, citygml4j registriert dann die GML-3.1.1-Adapter nicht und Geometrie-Elemente werden `null` — obwohl das XML korrekt geladen wird.

**`DefaultReferenceResolver` nicht verwenden** — bricht inline-Geometrie. Der `ObjectWalker` (dort, wo er noch verwendet wird) greift direkt auf die Geometrie zu, ohne Resolver.

**CityGML 1.0 Output-Konfiguration:** der Writer wird mit den Schema-Locations des GDI-DE Repository konfiguriert und verwendet die Namespace-Prefixes der Original-Datei (`core:`, `tex:`, `sch:`, etc.).

**TIN/TriangulatedSurface — Schema-Konformität vs. Werkzeug-Kompatibilität:** `gml:TriangulatedSurface` ist über die Substitutionskette `TriangulatedSurface → Surface → _Surface` regulärer Bestandteil von GML 3.1.1/CityGML 1.0 (bestätigt durch `citygml-tools`, das solche Dateien klaglos zu CityJSON konvertiert). `HealedReplaceWorkflow` schreibt sie im `AS_IN_DATABASE`-Modus 1:1 so, wie der Healer sie abgelegt hat — direkt per xlink aus der Shell referenziert, exakt wie ein `gml:Polygon`. CityDoctor 3.18.2 kann eine per xlink referenzierte TIN allerdings nicht auflösen und meldet fälschlich `GE_S_NOT_CLOSED` — dafür existiert `PolygonOnlyReplaceWorkflow` als reine Ausgabe-Variante.

---

## Legacy (altes DB-Schema, nicht weiterentwickelt)

Die Klassen im Package `de.mpsc.sql2gml.legacy` (`ReplaceWorkflow`, `CompleteWorkflow`, `DatabaseReader`, `legacy.model.*`) arbeiten auf dem alten DB-Schema (`Polygons`+`LinearRings` statt `SurfaceGeometries`+`PosLists`) und werden nicht mehr weiterentwickelt — sie bleiben nur als Fallback/Referenz erhalten. Wesentliche Unterschiede zum aktuellen Schema:

- Eine Surface konnte MEHRERE Polygone tragen (neu: genau eine SurfaceGeometry je Surface).
- **IsValid-Kaskade statt All-or-Nothing:** die alte Prüfung lief top-down und konnte einzelne Ebenen überspringen (`Building.IsValid=0` → ganzes Gebäude überspringen; sonst pro BuildingPart/Surface/Polygon einzeln entscheiden) statt wie im neuen Schema das gesamte Gebäude bei irgendeinem invaliden Teil zu verwerfen.
- `CompleteWorkflow` patcht zusätzlich **einzelne Polygone selektiv** (inkl. Polygon-Splitting und feingranularer Log-Übertragung je Ring/Polygon/Surface) statt komplette Gebäude zu ersetzen — der älteste, granularste Ansatz in diesem Projekt.

Vollständige Klassen-/Methodendokumentation (u. a. `PolygonIndex`, `GmlUpdateWalker`, Log-Übertragungsschema, Header-Korrektur, Anchor-lose Merged Surfaces): siehe [Doku_Legacy.md](Doku_Legacy.md), Abschnitt „Legacy".

---

## Projektstruktur

```
sql2gml_neu/
├── pom.xml                          Maven-Konfiguration (inkl. maven-shade-plugin für Fat-JAR)
├── Doku.md / Doku_*.md              Dokumentation
├── README.md                        Kurzanleitung
└── src/main/java/de/mpsc/sql2gml/
    ├── HealedReplaceWorkflow.java   HAUPT-Workflow, neues Schema (Main-Class)
    ├── PolygonOnlyReplaceWorkflow.java  Variante ohne TriangulatedSurface (CityDoctor-Workaround)
    ├── DbReader.java                Datenbankzugriff, neues Schema
    ├── GmlMemberFilter.java         Gemeinsamer Streaming-Filter (Basis für ExtractSst/ExtractBuildings)
    ├── ExtractSst.java              Gebäude mit sst-Attribut extrahieren
    ├── ExtractBuildings.java        Gebäude nach gml:id extrahieren
    ├── gui/
    │   └── Sql2GmlGui.java          Desktop-Oberfläche (siehe Abschnitt „GUI-Anwendung" unten)
    ├── model/                       Datenmodell, neues Schema
    │   ├── Building.java / BuildingPart.java / Surface.java
    │   ├── SurfaceGeometry.java     Ersetzt Polygon.java (1 Geometrie je Surface, Polygon ODER TIN)
    │   └── PosList.java             Ersetzt LinearRing.java
    └── legacy/                      Altes Schema, nicht weiterentwickelt
        ├── ReplaceWorkflow.java / CompleteWorkflow.java / DatabaseReader.java
        └── model/                   Building/BuildingPart/Surface/Polygon/LinearRing
```

---

## GUI-Anwendung

`de.mpsc.sql2gml.gui.Sql2GmlGui` — eigenständige Desktop-Oberfläche für Nutzende ohne CMD/PowerShell (siehe Doku_Administrierende.md, Abschnitt „GUI"). Zusätzliche Main-Class im selben Fat-JAR, keine eigene Kopie der Workflow-Logik.

### Verwendete Bibliotheken

| Zweck | Bibliothek | Warum |
|---|---|---|
| UI-Toolkit | Swing (JDK-Bestandteil) | Kein Zusatz-Download, läuft mit der eingebetteten JDK-Laufzeit |
| Look-and-Feel | [FlatLaf](https://www.formdev.com/flatlaf/) 3.7.2 | Modernes, flaches Erscheinungsbild statt Standard-Swing-„Metal"; Hell/Dunkel als fertige Themes, per Button umschaltbar (nicht an das Windows-Theme gekoppelt — das bräuchte zusätzlich eine Registry-Abfrage) |
| Paketierung | `jpackage` (JDK-Bestandteil, seit JDK 14) | Baut aus dem fertigen Fat-JAR eine eigenständige `.exe` samt eingebetteter Java-Laufzeit — `--type app-image`, kein Installer/WiX nötig |

### Architektur-Prinzip: dünne UI-Schicht, keine Logik-Duplikation

Die GUI ruft `HealedReplaceWorkflow.run(String[] args)` direkt auf — denselben Code-Pfad, den auch `main()` (CLI) nutzt. Verifiziert per Diff: CLI-Aufruf und GUI-Aufruf (`run()`) erzeugen auf den 14 Testgebäuden byte-identische Ausgabe.

`run()` ist eine reine Ergänzung zu `main()`: der bisherige `main()`-Rumpf (Argument-Auflösung, Modus-Dispatch) wurde nach `run()` verschoben, das Exceptions wirft statt `System.exit()` aufzurufen — damit die GUI einen Fehler in einem Dialog anzeigen kann, statt dass der ganze Prozess (inkl. Fenster) beendet wird. `main()` ruft nur noch `run()` auf und behält das bisherige `System.exit(1)`-Verhalten bei Fehlern. CLI-Verhalten bleibt unverändert.

### Fortschritts-Hook und Live-Zählung

`HealedReplaceWorkflow.ProgressListener` (statisches, per `setProgressListener()` gesetztes Interface, `null` im CLI-Betrieb = No-Op) meldet `onFileStart(fileIndex, totalFiles, filename)` im Batch-Modus (echte Werte aus der bereits vorhandenen Dateiliste) und `onFeature(featuresRead)` nach jedem gelesenen CityGML-Feature. Für einen echten X/Y-Fortschrittsbalken im Einzeldatei-Modus zählt die GUI vorab die Anzahl `<core:cityObjectMember>`-Zeilen in der Eingabedatei (reines Zeilenlesen, kein XML-Parsing, daher schnell auch bei großen Kacheln) — dasselbe Zeilen-Prinzip wie bereits in `GmlMemberFilter` verwendet.

### Log-Anzeige

`System.out`/`System.err` werden beim GUI-Start (vor jedem Zugriff auf `HealedReplaceWorkflow`, da SLF4J-Simple den Ziel-Stream beim ersten `LoggerFactory.getLogger(...)`-Aufruf fest bindet) durch einen zeilenweise puffernden `OutputStream` ersetzt, der jede Zeile zusätzlich an die Swing-Textfläche weiterreicht — ohne den ursprünglichen Stream zu ersetzen (bei Start aus einem Terminal bleibt die Konsolenausgabe zusätzlich erhalten).

---

## Bekannte offene Punkte

- **Solid-Merge-Gate bleibt vorerst aktiv (kein Bug, bewusste Sperre):** solange der Healer-Code für Party-Wall-Merge/-Split nicht ausgereift ist, werden betroffene Gebäude weiterhin komplett unangetastet gelassen statt teilweise verbessert — siehe oben.
- **Triangulierte Wände blockieren nachgelagerte LoD3-Bearbeitung:** die Wandfläche ist dann kein einzelnes Polygon mehr, sondern N Dreiecke — betroffene Buildings über `Stats.buildingsWithTinWall` identifizierbar.
- **CityDoctor 3.18.2 TIN-xlink-Limitation:** führt zu falsch-positiven `GE_S_NOT_CLOSED`-Meldungen bei per xlink referenzierten `TriangulatedSurface`-Elementen — Werkzeug-Bug, kein Mangel unserer Ausgabe. Workaround: `PolygonOnlyReplaceWorkflow`.
- **`ObjectWalker` und `lod2Solid`:** traversiert bei CityGML 1.0 die `lod2Solid`-Geometrie nicht — CompositeSurface-Änderungen müssen weiterhin programmatisch erfolgen (s. o.).
- **`BuildingParts`-Zugriff:** `Building.getBuildingParts()` ist die korrekte API. `DeprecatedPropertiesOfAbstractBuilding.getConsistsOfBuildingParts()` wird bei CityGML 1.0 von citygml4j nicht befüllt — leicht zu verwechseln.
- **Interior Rings** werden unterstützt, sind aber weniger getestet als Exterior Rings.

## Bugfixes (2026-09-02, abgeschlossen)

- **`gml:name` ging bei jeder zurückgeschriebenen Fläche verloren** — die DB kennt keine Name-Spalte; `gml:name` wird jetzt deterministisch aus `SurfaceTypeId` abgeleitet (`LOD2_Wall`/`_Roof`/`_Ground`), an ~110.000 echten Flächen als 1:1 bestätigt.
- **BuildingPart (Anbau) verschwand komplett aus der Ausgabe** — behoben durch die Erweiterung des Solid-Merge-Gates auf die Rückrichtung (siehe oben).

---

## Weiterführende Links

- [citygml4j GitHub](https://github.com/citygml4j/citygml4j)
- [CityGML Standard](https://www.citygml.org/)
- [OGC CityGML 2.0](https://www.ogc.org/standards/citygml)
