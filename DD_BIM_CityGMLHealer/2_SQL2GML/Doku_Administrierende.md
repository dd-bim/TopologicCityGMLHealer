# sql2gml — Doku für Administrierende

Installation, Ausführung, Hilfs-Tools, Troubleshooting. Für die fachliche Einordnung (was liefert der Healer, wie wird entschieden) siehe [Doku_Benutzende.md](Doku_Benutzende.md), für Architektur/Klassen [Doku_Programmierende.md](Doku_Programmierende.md).

---

## Voraussetzungen

| Werkzeug | Mindestversion |
|---|---|
| Java JDK | 21 LTS |
| Apache Maven | 3.6 |

```powershell
java -version    # muss 21.x.x zeigen
mvn -version     # muss 3.6+ zeigen
```

Alle Bibliotheken (citygml4j 3.2.7, sqlite-jdbc 3.47.x, gson 2.11.x, slf4j-simple 2.0.x) werden automatisch von Maven aus `pom.xml` geladen — kein manuelles Installieren nötig.

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
cd sql2gml_neu
mvn clean package -q
```

Ergebnis: `target/sql2gml-complete.jar` (Fat-JAR mit allen Abhängigkeiten). Main-Class ist `HealedReplaceWorkflow` — reines CMD-Tool, `java -jar` genügt.

---

## Ausführung

### Modus 1 — Einzelne Datei

```powershell
java -jar target/sql2gml-complete.jar <input.gml> <database.db> [<output.gml>]
```

Ohne expliziten Output wird `<input>_new.gml` neben der Eingabe erzeugt.

### Modus 2 — Batch (ganzer Ordner)

```powershell
java -jar target/sql2gml-complete.jar <inputFolder> <database.db> [<outputFolder>]
```

Verarbeitet alle `.gml`-Dateien im Ordner (Output-Dateien mit Suffix `_new`).

### Modus 3 — Auto-Batch (empfohlen für Kacheln)

```powershell
java -jar target/sql2gml-complete.jar <database.db> <inputFolder> <outputFolder> --auto
```

Liest die Dateiliste direkt aus der `CityGmlFiles`-Tabelle der Datenbank und überspringt Kacheln ohne jede Modifikation automatisch — DB-Index und CityGML-Kontext werden nur einmal pro Lauf geladen. Ideal für große Kachel-Datensätze (z. B. Dresden mit 100+ Kacheln).

### PolygonOnlyReplaceWorkflow (keine TriangulatedSurface in der Ausgabe)

Identischer Aufruf zu allen drei Modi oben, andere Main-Class:

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.PolygonOnlyReplaceWorkflow <Argumente>
```

Einsetzen, wenn die Ausgabe mit CityDoctor 3.18.2 geprüft werden soll (siehe [Doku_Benutzende.md](Doku_Benutzende.md)).

### Legacy-Workflows (altes DB-Schema, nicht weiterentwickelt)

Nur verwenden, wenn die Datenbank noch das alte Schema (`Polygons`+`LinearRings` statt `SurfaceGeometries`+`PosLists`) hat:

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.legacy.ReplaceWorkflow <gleiche Argumente>
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.legacy.CompleteWorkflow <gleiche Argumente>
```

---

## GUI (Desktop-Anwendung)

Für Nutzende ohne CMD/PowerShell/IDE — z. B. Projektpartner — gibt es eine eigenständige `.exe` mit grafischer Oberfläche, die exakt dieselbe, bereits verifizierte Workflow-Logik aufruft wie die Kommandozeile (kein separater Nachbau, gleiches Ergebnis).

### Herunterladen/Starten

`sql2gml.zip` entpacken, `sql2gml.exe` im entpackten Ordner doppelklicken. Kein Java, kein CMD nötig — die Laufzeit ist eingebettet. Der allererste Start kann etwas dauern (Windows prüft eine neue, unsignierte `.exe` einmalig), danach startet es normal schnell.

### Bedienung

1. **Modus**: Einzelne Datei oder Ordner (Batch).
2. **CityGML-Datei/Ordner**: die Eingabe.
3. **Healer-Datenbank (.db)**: Pflichtfeld.
4. **Ausgabedatei/-ordner** (optional): ohne Angabe gelten dieselben Standardwerte wie beim CLI-Aufruf (`<input>_new.gml` bzw. Suffix `_new`).
5. **Checkbox „Ohne TriangulatedSurface schreiben"**: entspricht `PolygonOnlyReplaceWorkflow`, siehe Benutzenden-Doku.
6. **Konvertierung starten** — Fortschrittsbalken (bei Batch mit echtem „Datei X von Y", bei Einzeldatei mit echtem „Feature X von Y" nach kurzem Vorab-Zähllauf) und Live-Log. Am Ende Option, den Ausgabeordner direkt zu öffnen.

Technische Details zur GUI (Bibliotheken, Architektur): siehe [Doku_Programmierende.md](Doku_Programmierende.md).

---

## Hilfs-Tools

### ExtractSst — Gebäude mit `sst`-Attribut extrahieren

Extrahiert nur Gebäude mit dem generischen Attribut `name="sst"` (textstrom-basiert, effizient auch für sehr große Kacheln):

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <input.gml>                  # Output: _sst.gml
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <input.gml> <output.gml>     # expliziter Pfad
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <folder> [<outputFolder>]    # ganzer Ordner
```

### ExtractBuildings — Gebäude nach `gml:id` extrahieren

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractBuildings <input.gml> <output.gml> <ID1> [ID2] ...
```

Beispiel:

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractBuildings `
    "D:\data\LoD2_33_416_5656_2_SN.gml" `
    "D:\output\7buildings.gml" `
    DESNALK0pF001g4s DESNALK0pF001gGp DESNALK0pF001gjj
```

---

## Troubleshooting

### Geometrie wird beim Lesen `null`, obwohl das XML korrekt aussieht

Build-Problem, kein Laufzeitfehler: das Fat-JAR muss mit dem `ServicesResourceTransformer` des `maven-shade-plugin` gebaut sein (bereits in `pom.xml` konfiguriert). Fehlt er, werden die `META-INF/services`-Dateien beim Zusammenführen nicht korrekt kombiniert, citygml4j registriert dann die GML-3.1.1-Adapter nicht und parst Geometrie-Elemente als `null`. Bei einem eigenen Build-Setup: sicherstellen, dass dieser Transformer aktiv bleibt.

### Wie finde ich Gebäude mit triangulierten Wänden?

`HealedReplaceWorkflow` gibt am Ende eines Laufs eine gesonderte Statistik/Liste betroffener Gebäude aus (`buildingsWithTinWall`) — diese Wände sind für nachgelagerte Bearbeitung (z. B. Fenster-/Türeinbau) ungünstig, siehe [Doku_Benutzende.md](Doku_Benutzende.md).

### CityDoctor 3.18.2 meldet `GE_S_NOT_CLOSED` auf einer sichtbar geschlossenen Fläche

Bekannter Mapper-Bug des Prüfwerkzeugs bei per xlink referenzierten TriangulatedSurfaces, keine echte Geometrie-Lücke. Workaround: mit `PolygonOnlyReplaceWorkflow` erzeugen statt mit dem Standard-Workflow (siehe oben).

### Kompletter Ordner statt einzelner Kacheln — welcher Modus?

Für einen einmaligen Testlauf reicht Modus 2 (Batch, ganzer Ordner). Für wiederholte Läufe auf demselben großen Datensatz (Stadt-weite Kachelmengen) Modus 3 (Auto-Batch) verwenden — er überspringt unveränderte Kacheln automatisch und spart dadurch spürbar Zeit.
