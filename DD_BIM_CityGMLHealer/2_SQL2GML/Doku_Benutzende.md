# sql2gml — Doku für Benutzende

Was `sql2gml` fachlich macht: was der Healer in der Datenbank zurückliefert und nach welchen Regeln daraus die CityGML-Datei aktualisiert wird. Für Aufruf/Konfiguration siehe [Doku_Administrierende.md](Doku_Administrierende.md), für Klassen/Algorithmen [Doku_Programmierende.md](Doku_Programmierende.md).

---

## Was macht sql2gml

Der Healer prüft und korrigiert Gebäudegeometrien und legt das Ergebnis in einer SQLite-Datenbank ab. `sql2gml` liest diese Datenbank und schreibt die geprüften Geometrien zurück in die CityGML-Datei — pro Gebäude entweder **komplett** (Solid samt allen Flächen neu aufgebaut) oder **gar nicht** (Original unverändert). Ein Mittelweg — nur einzelne Flächen ersetzen, den Rest original lassen — wird bewusst vermieden, siehe Valid-Gate unten.

---

## Was kommt vom Healer zurück

Die Healer-Datenbank liefert eine hierarchische Struktur, die die gleiche Verschachtelung wie CityGML selbst hat:

```
CityGmlFiles
  └─ Buildings           ein Gebäude
       └─ BuildingParts    seine Gebäudeteile (Anbauten etc.)
            └─ Surfaces      seine Flächen (Wand/Dach/Grundfläche)
                 └─ Geometrie   Polygon ODER Dreiecksvermaschung (TIN)
```

Jede Ebene trägt zwei Dinge: ob sie **valide** ist (geprüft/korrigiert, verwendbar) und ein **Log**, das erklärt warum nicht, falls nicht. Bei den Flächen-Geometrien gibt es zusätzlich zwei mögliche Formen:

- **Polygon** — der Normalfall, ein sauberer, ebener Umring (plus Löcher, falls vorhanden).
- **TriangulatedSurface (TIN)** — eine Notlösung des Healers für Flächen, die er nicht in eine einzelne ebene Fläche zwingen konnte, deshalb stattdessen in viele kleine Dreiecke zerlegt. Geometrisch weiterhin korrekt, aber für nachgelagerte Schritte unpraktischer (siehe „Bekannte fachliche Einschränkungen" unten).

---

## Wie wir entscheiden, was wir übernehmen (Valid-Gate)

Ein Gebäude wird nur ersetzt, wenn **wirklich jede Ebene** seiner gesamten Hierarchie valide ist — nicht nur das Gebäude selbst, sondern jeder Gebäudeteil, jede Fläche, jede Geometrie und jeder Koordinatenring einzeln:

<div align="center">

```
Gebäude valide?
  └─ JA → jeder Gebäudeteil valide?
             └─ JA → jede Fläche valide?
                        └─ JA → jede Geometrie + jeder Ring valide?
                                   └─ JA → Solid-Merge-Gate besteht? (siehe unten)
                                              └─ JA → ✅ ersetzen
                                              └─ NEIN → ⛔ Original bleibt
                        └─ NEIN → ⛔ Original bleibt
             └─ NEIN → ⛔ Original bleibt
  └─ NEIN → ⛔ Original bleibt
```

</div>

Reißt die Kette an irgendeiner Stelle ab, bleibt das **komplette** Gebäude so, wie es vorher war. Grund: ein Teil-Ersatz könnte ausgerechnet die eine Fläche fehlen lassen, die die Hülle schließt, und ein Loch in einem sonst plausibel aussehenden Gebäude ist schwerer zu entdecken als ein Gebäude, das gar nicht erst angefasst wurde.

---

## Solid-Merge-Gate: wenn der Healer Gebäudeteile zusammenführt

Ein Sonderfall betrifft **Party-Wall-Fälle** — zwei oder mehr Gebäudeteile, die eine gemeinsame Trennwand teilen (z. B. eine Doppelhaushälfte oder ein späterer Anbau). Der Healer kann solche Teile zu einem gemeinsamen Volumenkörper verschmelzen oder umgekehrt einen Teil in mehrere aufspalten. Das Ergebnis ist ein neuer Solid, der zu keinem der ursprünglichen Gebäudeteile mehr eindeutig passt:

<div align="center">

```
┌──────────┬──────────┐        ┌ ─ ─ ─ ─ ─ ─ ─ ┐         ┌──────────┬──────────┐
│  Teil 1  │  Teil 2  │  ───▶   Healer-Merge      ───▶    │  Teil 1  │  Teil 2  │
│ (Original)│(Original)│        (neuer Solid,             │ (Original)│(Original)│
└──────────┴──────────┘        noch nicht reif)          └──────────┴──────────┘
   im Original-GML             ┌ ─ ─ ─ ─ ─ ─ ─ ┘             bleibt unverändert
                                Solid-Merge-Gate greift
```

</div>

**Diese Funktion im Healer ist noch nicht ausgereift genug**, um sie zu übernehmen — in beobachteten Fällen fehlten nach dem Merge Wände oder das Dach schwebte ohne tragende Wände in der Luft. Deshalb wird das bewusst zurückgehalten: Erkennt `sql2gml` einen solchen Fall, bleibt das **gesamte** betroffene Gebäude unverändert, genau wie bei jedem anderen Valid-Gate-Fehler — lieber ein Gebäude, das (noch) nicht vom Healer profitiert, als eines mit sichtbar kaputter Geometrie.

Das gilt in **beide Richtungen**: auch wenn umgekehrt ein bestehender Gebäudeteil in der Healer-Datenbank plötzlich gar keine Zeile mehr hat — ohne Löschungs-Log, ohne Fehlermeldung, einfach weg — wird das genauso behandelt. Ein Gebäudeteil verschwindet dann nicht kommentarlos aus der Ausgabe, sondern das gesamte Gebäude bleibt wie es im Original war. Sobald der Healer-Code für Party-Wall-Fälle zuverlässig funktioniert, kann diese Sperre aufgehoben werden.

---

## Zwei Ausgabe-Varianten

- **Standard** (`HealedReplaceWorkflow`) — schreibt Dreiecksvermaschungen so, wie der Healer sie abgelegt hat (`gml:TriangulatedSurface`). Regulärer, schema-konformer CityGML-Bestandteil.
- **PolygonOnly** (`PolygonOnlyReplaceWorkflow`) — identische Geometrie, aber jedes Dreieck als eigenes `gml:Polygon` statt als TIN. Grund: ein verbreitetes Prüfwerkzeug (CityDoctor 3.18.2) kann eine per Verweis (xlink) eingebundene TIN nicht auflösen und meldet dann fälschlich einen Fehler — ein Werkzeug-Problem, kein echter Mangel der Geometrie.

---

## Bekannte fachliche Einschränkungen

- **Triangulierte Wände erschweren nachgelagerte Bearbeitung.** Konnte der Healer eine Wand nicht eben bekommen und sie deshalb als TIN abgelegt, lässt sich dort später kein sauberes Fenster/Tür mehr einschneiden (z. B. in der nachgelagerten LoD2→LoD3-Pipeline) — die Fläche ist kein einzelnes Polygon mehr, sondern viele kleine Dreiecke.
- **CityDoctor 3.18.2 meldet bei TINs einen falschen Fehler** (`GE_S_NOT_CLOSED`), obwohl die Geometrie tatsächlich geschlossen ist — ein bestätigter Mapper-Bug des Prüfwerkzeugs, nicht unserer Ausgabe. Workaround: die PolygonOnly-Variante verwenden.
- **Nur CityGML 1.0** wird gelesen und geschrieben.
- **Interior Rings** (Löcher in Flächen) werden unterstützt, sind aber weniger getestet als der Normalfall (Außenring ohne Löcher).
