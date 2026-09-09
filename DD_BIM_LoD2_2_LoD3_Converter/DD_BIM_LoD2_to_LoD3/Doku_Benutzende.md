# LoD2 → LoD3 Pipeline — Doku für Benutzende

Was diese Pipeline aus einem Gebäudemodell macht und wie die Ausgabe fachlich einzuordnen ist. Für Aufruf/Konfiguration siehe [Doku_Administrierende.md](Doku_Administrierende.md).

---

## Was wird erzeugt

Aus einer einfachen Gebäudehülle (Wände, Dach, Grundfläche) wird pro Gebäude ein detailliertes Modell mit folgenden Elementen, jeweils gesteuert über das Baukörpermodul, das dem Gebäude anhand seines `sst`-Attributs zugeordnet ist (siehe Admin-Doku):

- **Keller** — unterhalb der Geländeoberfläche, inkl. Kellerwänden und -boden, wenn das Modul einen Keller vorsieht.
- **Geschosse** — die Gebäudehülle wird in Erdgeschoss, Obergeschosse und ggf. Dachgeschoss unterteilt, mit eigenen Böden und Decken je Geschoss.
- **Türen** — mindestens die Eingangstür im Erdgeschoss.
- **Fenster** — auf allen Geschossen inkl. Keller, nach den im Modul hinterlegten Abständen und Maßen; auf verdeckten Wandabschnitten (hinter einem Anbau) werden bewusst keine platziert.
- **Balkone** — wenn das Modul ein Balkon-Muster vorsieht, inkl. Balkontür.
- **Dachfenster** — auf geneigten Dachflächen, wenn das Modul welche vorsieht.

Ein Gebäude kann je nach zugeordnetem Modul auch nur einen Teil dieser Elemente bekommen (z.B. kein Keller, wenn keiner vorgesehen ist) — das ist beabsichtigtes Verhalten, kein Fehler.

---

## Datenqualität einordnen

Jeder Lauf wird gegen zwei unabhängige Prüfwerkzeuge verifiziert:

- **val3dity** prüft, ob jedes Gebäude ein geometrisch sauberes, wasserdichtes 3D-Volumen ist (ISO-19107-Solid-Validität) — z.B. keine offenen Lücken in der Hülle, keine Selbstüberschneidungen.
- **CityDoctor2** prüft zusätzlich semantische Regeln (z.B. zeigt ein Kellerboden wirklich nach unten, ist eine Fläche wirklich planar).

**Letzter kompletter Lauf über ganz Dresden** (98 Kacheln, 141.670 Gebäude): **98,36 %** der Gebäude-Flächen sind bei val3dity valide, **92,24 %** der Gebäude sind bei CityDoctor2 komplett fehlerfrei. Das heißt nicht, dass die restlichen Gebäude unbrauchbar sind — die allermeisten Meldungen sind kleine, lokal begrenzte Abweichungen (z.B. eine leicht nicht-plane Fläche), keine grob falschen Gebäude. Ein Teil der gemeldeten Fehler stammt außerdem nachweislich aus den LoD2-Ausgangsdaten selbst (Digitalisierungsungenauigkeit) oder ist ein bekanntes Artefakt der Prüfwerkzeuge, kein echter Mangel der erzeugten Geometrie (Details: Programmierenden-Doku, Abschnitt „Bekannte offene Punkte").

Die letzten mm-genauen Nähte zwischen einzelnen Flächen werden bewusst nicht von dieser Pipeline geschlossen, sondern bleiben Aufgabe des nachgelagerten Healers.

---

## Bekannte fachliche Einschränkungen

- **Manche Gebäude bekommen keine Tür.** Wenn die Quelldaten für ein Gebäude an keiner Wand eine Tür-Information liefern (eine bekannte Lücke in der vorgelagerten Adresspunkt-Zuordnung), setzt die Pipeline automatisch eine Ersatztür an der breitesten Erdgeschosswand, sofern das Gebäude mindestens ein Fenster hat — ein Gebäude mit Fenstern aber ganz ohne Tür wäre unrealistisch für ein bewohntes Haus. Bleibt auch das erfolglos (sehr seltener Randfall), bleibt das Gebäude ohne Tür.
- **Fenster hinter einem Anbau werden ausgelassen.** Wenn ein angebauter Gebäudeteil eine Wandfläche komplett verdeckt, wird dort kein Fenster platziert — auch wenn das Baukörpermodul eines vorsähe.
- **Nicht jedes Baukörpermodul deckt jedes Element ab.** Ob ein Gebäude einen Keller, Balkone oder Dachfenster bekommt, hängt einzig vom zugeordneten Modul ab (siehe oben) — fehlt ein Element in der Ausgabe, ist meist zuerst zu prüfen, ob es im Modul überhaupt vorgesehen war.
