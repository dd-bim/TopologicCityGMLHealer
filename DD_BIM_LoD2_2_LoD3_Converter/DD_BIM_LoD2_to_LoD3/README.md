# LoD2 → LoD3 Konvertierungspipeline

Konvertiert CityGML-Gebäude von **LoD2 auf LoD3** – mit Keller, Geschossen, Türen und Fenstern.
Eingabe: CityGML-Datei + optionales DGM. Ausgabe: CityGML 1.0 (LoD3).

> **Vollständige technische Dokumentation:** [Doku.md](Doku.md)

---

## Voraussetzungen

| Komponente | Version |
|---|---|
| Java | 21+ |
| Maven | 3.6+ |
| citygml4j | 3.2.7 |

---

## Build

```sh
mvn clean package -DskipTests
```

---

## Schnellstart

### Vollständige Pipeline

```sh
java -jar target/lod2-zu-lod3-pipeline.jar  input.gml  Baukörpermodule_json/  output/  [dgm-pfad]
```

Oder mit Maven direkt aus dem Quellcode:

```sh
mvn exec:java \
  -Dexec.mainClass=de.mpsc.lod2tolod3.Lod2ToLod3Pipeline \
  -Dexec.args="input.gml Baukörpermodule_json/ output/"
```

### Einzelne Schritte

Jeder Schritt kann auch standalone aufgerufen werden:

```sh
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.Lod2ToLod3Promoter   input.gml  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.BasementGenerator    input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.StoreyGenerator      input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.DoorGenerator        input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.WindowGenerator      input.gml  jsonDir/  [output.gml]
```

---

## Pipeline-Ablauf

```
CityGML LoD2
    │
    ▼  Schritt 1 – Lod2ToLod3Promoter
    │  LoD2-Geometrie auf LoD3 hochstufen
    │  (WallSurface / RoofSurface / GroundSurface aus Solid extrahieren)
    │
    ▼  Schritt 2 – BasementGenerator
    │  Keller unterhalb der Geländeoberfläche modellieren (DGM-gestützt)
    │  Schnittlinie Gebäudehülle ↔ Geländefläche per Sutherland-Hodgman
    │
    ▼  Schritt 3 – StoreyGenerator
    │  Wände in Geschosse aufteilen (EG / OG / DG / UG)
    │  Horizontales Clipping mit automatischer Geschosshöhenberechnung
    │
    ▼  Schritt 4 – DoorGenerator
    │  Türen auf EG-Außenwände platzieren (TIC-basierte Positionierung)
    │
    ▼  Schritt 5 – WindowGenerator
    │  Fenster auf alle Geschoss-Außenwände platzieren (TIC-Methode)
    │
    ▼
CityGML LoD3
```

**Single-Pass-Architektur:** Die Eingabedatei wird genau einmal gelesen, alle
Schritte werden pro Gebäude im Speicher ausgeführt, das Ergebnis einmal geschrieben –
keine Zwischendateien.

---

## Baukörpermodule (JSON)

Für jedes Gebäude wird eine JSON-Datei (`{gml:id}.json`) oder ein Fallback
(`_default.json`) aus dem `jsonDir`-Verzeichnis geladen:

```json
{
  "GF": { "roomHeight": 2.8, "windowRatio": 0.25, "doorCount": 1 },
  "OG": { "roomHeight": 2.6, "windowRatio": 0.30 },
  "DG": { "roomHeight": 2.4, "windowRatio": 0.15 },
  "BA": { "height":    2.5, "windowRatio": 0.0  }
}
```

| Schlüssel | Bedeutung |
|---|---|
| `GF` | Erdgeschoss |
| `OG` | Obergeschoss(e) – wird bei mehreren Vollgeschossen wiederholt |
| `DG` | Dachgeschoss (wenn unter Traufe Restfläche vorhanden) |
| `BA` | Keller (Basement) |

---

## DGM-Unterstützung

| Format | Beschreibung |
|---|---|
| `.asc` | ESRI ASCII Grid (einzelne Kachel) |
| `.tif` / `.tiff` | GeoTIFF (javax.imageio) |
| `.zip` | ZIP-Archiv mit `.asc`-Dateien |
| Verzeichnis | Automatisches Mosaik aus mehreren Kacheln |

Format wird automatisch erkannt (`DgmLoader`-Factory).

---

## Implementierungsstatus

| # | Schritt | Klasse | Status |
|---|---|---|---|
| 1 | LoD2→LoD3 Promotion | `Lod2ToLod3Promoter` | ✅ Fertig |
| 2 | Keller | `BasementGenerator` | ✅ Fertig |
| 3 | Geschosse | `StoreyGenerator` | ✅ Fertig |
| 4 | Türen | `DoorGenerator` | ✅ Fertig |
| 5 | Fenster | `WindowGenerator` | ✅ Fertig |
| 6 | Dachfenster | – | 📋 TODO |
| 7 | Balkone | – | 🗓 Geplant |

Getestet mit **3 801 Gebäuden** (Testdatensatz Sachsen LoD2), Laufzeit ca. **20 s**.

---

## Projektstruktur

```
src/main/java/de/mpsc/lod2tolod3/
├── Lod2ToLod3Pipeline.java         Haupt-Pipeline (Single-Pass)
├── Lod2ToLod3Promoter.java         Schritt 1: Geometrie-Promotion
├── BasementGenerator.java          Schritt 2: Keller
├── StoreyGenerator.java            Schritt 3: Geschosse
├── DoorGenerator.java              Schritt 4: Türen
├── WindowGenerator.java            Schritt 5: Fenster
├── util/
│   ├── CityGmlUtils.java           Shared Utilities (rebuildSolidShell, …)
│   ├── DgmLoader.java              DGM-Format-Erkennung (Factory)
│   ├── DgmReader.java              ESRI ASCII Grid Parser
│   ├── GeoTiffReader.java          GeoTIFF Parser
│   ├── DgmMosaic.java              Mosaik-Kombinator (mehrere Kacheln)
│   ├── DgmProvider.java            Interface (getHeight, contains, describe)
│   └── ModuleParametersLoader.java JSON-Parameter-Loader mit Cache
├── model/
│   └── ModuleParameters.java       Datenklasse für JSON-Baukörpermodule
└── tools/
    ├── WallAnalyzer.java           Wand-Analyse und Flächenberechnung
    └── MixedRoofAnalyzer.java      Dachtyp-Erkennung (Flach-/Sattel-/Walmdach)
```

---

## CRS

`urn:adv:crs:ETRS89_UTM33*DE_DHHN2016_NH` (Koordinatenreferenzsystem der Testdaten)

---

## Lizenz

Siehe [LICENSE](../LICENSE) im Repository-Root.