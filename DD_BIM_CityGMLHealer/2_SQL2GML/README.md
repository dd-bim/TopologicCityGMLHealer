# sql2gml

Java-Tool zum Zurückschreiben von validierten und reparierten CityGML-Geometrien aus einer SQLite-Datenbank in CityGML 1.0 Dateien.

Die Datenbank enthält eine hierarchische Gebäudestruktur (Buildings → BuildingParts → Surfaces → Polygons → LinearRings) mit korrigierten Koordinaten und Validierungsprotokollen. `sql2gml` liest diese Daten und schreibt sie zurück in die Original-CityGML-Datei — dabei werden Koordinaten aktualisiert, neue Polygone erzeugt, ungültige Geometrien entfernt und Logs als generische Attribute hinzugefügt.

---

## Voraussetzungen

| Werkzeug | Mindestversion | Download |
|----------|---------------|----------|
| **Java JDK** | 21 LTS | [Adoptium / Eclipse Temurin](https://adoptium.net/de/temurin/releases/?version=21) |
| **Apache Maven** | 3.6 | [maven.apache.org/download](https://maven.apache.org/download.cgi) |

Nach der Installation prüfen:

```powershell
java -version    # muss 21.x.x zeigen
mvn -version     # muss 3.6+ zeigen
```

---

## Bibliotheken

Alle Abhängigkeiten werden **automatisch von Maven heruntergeladen** — kein manuelles Installieren notwendig (Maven ist das Java-Äquivalent zu `pip` oder `npm`). Sie sind in `pom.xml` deklariert und werden beim ersten Build aus dem [Maven Central Repository](https://central.sonatype.com/) bezogen.

| Bibliothek | Version | Zweck |
|------------|---------|-------|
| **citygml4j-xml** | 3.2.7 | CityGML-Dateien lesen und schreiben (CityGML 1.0 / 2.0 / 3.0); stellt das vollständige CityGML-Objektmodell bereit (`Building`, `WallSurface`, `Polygon`, `LinearRing`, …) sowie `CityGMLReader`, `CityGMLChunkWriter` und `ObjectWalker` |
| **sqlite-jdbc** | 3.47.1.0 | JDBC-Treiber für SQLite-Datenbanken; ermöglicht den direkten Zugriff auf `.db`-Dateien ohne Datenbank-Server |
| **gson** | 2.11.0 | JSON-Parsing; liest die `Attributes`-Spalten (Building, Surface, …) als `Map<String, Object>` |
| **slf4j-simple** | 2.0.16 | Einfache Logging-Ausgabe auf der Konsole (kein Konfigurationsfile notwendig) |

---

## Installation und Build

```powershell
# 1. Repository klonen
git clone https://github.com/dd-bim/gml2sql2gml.git
cd gml2sql2gml/sql2gml_neu

# 2. JAVA_HOME setzen (Pfad ggf. anpassen)
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"

# 3. Fat-JAR bauen — Maven lädt dabei alle Bibliotheken automatisch herunter
mvn clean package -q
```

Ergebnis: `target/sql2gml-complete.jar` — ein eigenständiges JAR mit allen Abhängigkeiten.

---

## Verwendung

### Modus 1 — Einzelne Datei

```powershell
java -jar target/sql2gml-complete.jar <input.gml> <database.db> <output.gml>
```

Beispiel:

```powershell
java -jar target/sql2gml-complete.jar `
    D:\data\LoD2_33_416_5656.gml `
    D:\data\BuildingParts.db `
    D:\output\LoD2_33_416_5656_updated.gml
```

### Modus 2 — Batch (Ordner)

Verarbeitet alle `.gml`-Dateien in einem Ordner:

```powershell
java -jar target/sql2gml-complete.jar <inputFolder> <database.db> [<outputFolder>]
```

### Modus 3 — Auto-Batch (empfohlen für große Datensätze)

Liest die Dateiliste direkt aus der `CityGmlFiles`-Tabelle der Datenbank. Überspringt automatisch Kacheln ohne Modifikationen:

```powershell
java -jar target/sql2gml-complete.jar <database.db> <inputFolder> <outputFolder> --auto
```

---

## Hilfs-Tools

### ExtractSst — Gebäude mit `sst`-Attribut extrahieren

Extrahiert nur Gebäude mit dem generischen Attribut `name="sst"`. Textstrom-basiert — effizient auch für sehr große Kacheln.

```powershell
# Einzelne Datei (Output neben der Eingabe als _sst.gml)
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <input.gml>

# Expliziter Ausgabepfad
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <input.gml> <output.gml>

# Gesamten Ordner verarbeiten
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <folder> [<outputFolder>]
```

### ExtractBuildings — Gebäude nach `gml:id` extrahieren

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractBuildings <input.gml> <output.gml> <ID1> [ID2] ...
```

---

## Ausführliche Dokumentation

**→ [Doku.md](Doku.md)** — Vollständige Klassen-Dokumentation, Datenbankschema, Algorithmen, innere Klassen (`PolygonIndex`, `GmlUpdateWalker`, `ProcessingStats`) und citygml4j-Codebeispiele.


## Voraussetzungen

- Java 21+
- Maven 3.6+

## Dokumentation

**→ Ausführliche Dokumentation siehe [Doku.md](Doku.md)**

Enthält:
- Vollständige Datenbankstruktur mit IsValid/Log-Semantik
- Alle Ausführungsmodi (Single/Batch/Auto)
- Klassen-Dokumentation (CompleteWorkflow, DatabaseReader, Model-Klassen)
- citygml4j-Architektur und Code-Beispiele
- Beispiel-Workflows

## Lizenz

Projekt: `dd-bim/gml2sql2gml`
