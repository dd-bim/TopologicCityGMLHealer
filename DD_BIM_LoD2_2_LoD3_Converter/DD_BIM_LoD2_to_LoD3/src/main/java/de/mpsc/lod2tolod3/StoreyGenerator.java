package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils.Point3D;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.CeilingSurface;
import org.citygml4j.core.model.construction.FloorSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.core.AbstractSpaceBoundaryProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Schritt 3: Geschoss-Unterteilung (StoreyGenerator).
 *
 * Teilt bestehende Waende in geschossweise Segmente und erzeugt
 * Floor-/CeilingSurface pro Geschoss (GF, UF_1, UF_2, ...).
 *
 * Arbeitet auf dem Output des BasementGenerators:
 * - Waende werden ab egFloorZ (H_DGM + heightGr) geschnitten
 * - Wandbereiche unterhalb egFloorZ werden verworfen (Keller-Overlap vermeiden)
 * - BA-Surfaces (Geschoss=BA) bleiben unveraendert
 * - Oberstes Geschoss endet exakt an der Traufe (= Z_MIN RoofSurface)
 * - Kein Deckendicken-Versatz: Ceiling[n].Z = Floor[n+1].Z (lueckenlos)
 *
 * Konventionen:
 * - storeysAboveGround wird IGNORIERT — UFs werden dynamisch berechnet
 * - Geschosstags: GF, UF_1, UF_2, UF_3, ...
 * - JSON-Werte als alleinige Quelle fuer Geschosshoehen
 * - DachTyp/DachName nur am obersten Geschoss
 * - Benennung: Face_{OrigPolyId}_{StoreyTag}_{LaufendeNummer}
 *
 * Fallunterscheidungen:
 * - ufHeight=0 oder fehlt: nur GF bis Traufe
 * - GF.height fehlt: Gebaeude wird uebersprungen
 * - UF-Ceiling >= Traufe: vorzeitig abbrechen, letztes UF bis Traufe
 * - Nicht-rechteckige Waende (5+ Punkte): werden geschnitten (Sutherland-Hodgman)
 * - Fitzelchen (&lt; 1.2m Resthoehe): ins vorherige Geschoss gemerged
 * - Flachdach + Fitzelchen-Merge &gt; 4m: Fitzelchen als eigenes kurzes Geschoss
 * - Flachdach: Keine CeilingSurface am obersten Geschoss (RoofSurface = Decke)
 * - Original-GroundSurface bleibt erhalten (markiert mit Original_GroundSurface=preserved),
 *   ausgenommen BA-Bodenplatten (STRUKTUR="Bodenplatte") vom BasementGenerator
 */
public class StoreyGenerator {

    private static final Logger log = LoggerFactory.getLogger(StoreyGenerator.class);

    /** Mindesthoehe fuer Schnitt-Ergebnisse (5cm) – verhindert degenerierte Geometrien. */
    private static final double CUT_TOLERANCE = 0.05;

    /** Mindesthoehe fuer Wand-Segmente nach Schnitt (50cm).
     *  Verhindert sichtbare Duennstreifen wenn eine Wand knapp ueber eine Geschossgrenze ragt. */
    private static final double MIN_WALL_SEGMENT_HEIGHT = 0.50;

    /** Toleranz fuer Flachdach-Erkennung (30cm) — wenn First-Traufe < Wert → Flachdach. */
    private static final double FLAT_ROOF_TOLERANCE = 0.30;

    /** XY-Toleranz fuer Kantenzuordnung von Wandsegmenten zu Grundriss-Polygonen (50cm). */
    private static final double XY_EDGE_TOLERANCE = 0.50;

    /** Mindest-Geschosshoehe (1.2m) – verhindert unrealistisch kurze Geschosse (Fitzelchen). */
    private static final double MIN_STOREY_HEIGHT = 1.20;

    /**
     * Max. Geschosshoehe bei Flachdach-Fitzelchen-Merge (4.0m).
     * Wenn Etagenhoehe + Fitzelchen diesen Wert uebersteigt, wird das Fitzelchen
     * als eigenes kurzes Geschoss beibehalten statt gemerged.
     * Gilt NUR fuer Flachdaecher.
     */
    private static final double MAX_STOREY_HEIGHT_FLACHDACH = 4.0;

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                System.err.println("Usage: StoreyGenerator <input.gml> <jsonDir> [output.gml]");
                System.exit(1);
            }

            Path inputPath = Paths.get(args[0]);
            Path jsonDir = Paths.get(args[1]);
            Path outputPath = CityGmlUtils.resolveOutputPath(inputPath, "_storeys",
                    args.length >= 3 ? Paths.get(args[2]) : null);

            Files.createDirectories(outputPath.getParent());

            log.info("=== Geschoss-Generator ===");
            log.info("Input:  {}", inputPath);
            log.info("JSON:   {}", jsonDir);
            log.info("Output: {}", outputPath);

            StoreyGenerator generator = new StoreyGenerator();
            ModuleParametersLoader loader = new ModuleParametersLoader(jsonDir);
            GenerationStats stats = generator.addStoreys(inputPath, outputPath, loader);

            log.info("=== Fertig ===");
            log.info("Gebaeude verarbeitet: {}", stats.buildingsProcessed);
            log.info("Geschosse erstellt: {}", stats.storeysCreated);
            log.info("Wandsegmente erstellt: {}", stats.wallSegmentsCreated);
            log.info("Boeden erstellt: {}", stats.floorsCreated);
            log.info("Decken erstellt: {}", stats.ceilingsCreated);

        } catch (Exception e) {
            log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    // ==================== Hauptverarbeitung ====================

    /**
     * Fuegt Geschoss-Unterteilungen zu allen Gebaeuden hinzu.
     */
    public GenerationStats addStoreys(Path inputGml, Path outputGml,
            ModuleParametersLoader paramLoader) throws Exception {

        GenerationStats stats = new GenerationStats();

        log.info("Starte Geschoss-Generierung...");
        log.info("  Input:  {}", inputGml);
        log.info("  Output: {}", outputGml);

        CityGmlUtils.processGmlFile(inputGml, outputGml, building -> {
            stats.buildingsProcessed++;
            processBuilding(building, paramLoader, stats);
        });

        log.info("Geschoss-Generierung abgeschlossen:");
        log.info("  {} Gebaeude verarbeitet", stats.buildingsProcessed);
        log.info("  {} Geschosse erstellt", stats.storeysCreated);
        log.info("  {} Wandsegmente erstellt", stats.wallSegmentsCreated);
        log.info("  {} Waende geschnitten", stats.wallsCut);
        log.info("  {} Boeden erstellt", stats.floorsCreated);
        log.info("  {} Decken erstellt", stats.ceilingsCreated);

        return stats;
    }

    // ==================== Gebaeude-Verarbeitung ====================

    /**
     * Verarbeitet ein einzelnes Gebaeude.
     * Liest Building-level Attribute (sst, H_DGM) und delegiert die Verarbeitung
     * an processAbstractBuilding() fuer das Building selbst und/oder seine BuildingParts.
     */
    void processBuilding(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {

        // --- Building-level Parameter ermitteln ---
        String sst = CityGmlUtils.getStringAttribute(building, "sst");
        if (sst == null || sst.isBlank()) return;

        Optional<ModuleParameters> paramsOpt = paramLoader.getParameters(sst);
        if (paramsOpt.isEmpty()) return;
        ModuleParameters params = paramsOpt.get();

        Double hDgm = CityGmlUtils.parseDoubleAttribute(building, "H_DGM");
        if (hDgm == null) return;

        // BuildingParts und Building selbst verarbeiten.
        // Solid-Shell immer neu aufbauen — auch wenn processAbstractBuilding
        // vorzeitig beendet wurde (z.B. fehlende Traufe). Der BasementGenerator
        // hat moeglicherweise bereits Boundaries geaendert.
        for (var target : CityGmlUtils.getBuildingTargets(building)) {
            processAbstractBuilding(target, sst, hDgm, params, stats);
            CityGmlUtils.rebuildSolidShell(target);
        }
    }

    /**
     * Verarbeitet ein AbstractBuilding (Building oder BuildingPart).
     *
     * Parameter sst und hDgm werden vom Parent-Building geerbt,
     * da diese Attribute nur auf Building-Ebene vorhanden sind.
     * Traufe wird vom target selbst gelesen (liegt bei BuildingParts auf Part-Ebene).
     * storeysAboveGround wird NICHT verwendet — UFs werden dynamisch berechnet.
     *
     * Ablauf:
     * 1. Hoehen-Parameter ermitteln (heightGr, gfHeight, ufHeight)
     * 2. Geschossgrenzen dynamisch berechnen (GF, UF_1, UF_2, ... → Traufe)
     * 3. Bestehende WallSurfaces an Geschossgrenzen schneiden (ab egFloorZ!)
     * 4. Floor-/CeilingSurface pro Geschoss erzeugen
     * 5. Original-GroundSurface markieren
     * 6. Metadaten aktualisieren
     */
    private void processAbstractBuilding(AbstractBuilding target, String sst, double hDgm,
            ModuleParameters params, GenerationStats stats) {

        double[] roofZRange = CityGmlUtils.getRoofZRange(target);
        if (roofZRange == null) {
            log.debug("Keine RoofSurface fuer Gebaeude/Part {}", target.getId());
            return;
        }
        double traufeZ = roofZRange[0];
        double firstZ = roofZRange[1];
        // rawMinRoofZ: globales Minimum-Z aller RoofSurface-Polygone
        // (inkl. moegliche Boden-Niveau-Artefakte und flache Teilflaechen)
        double rawMinRoofZ = roofZRange[2];
        // slopedRawMinRoofZ: Min-Z nur geneigter Dachflaechen (MAX_VALUE = kein Mischdach)
        final double slopedRawMinRoofZ = roofZRange.length > 3 ? roofZRange[3] : rawMinRoofZ;

        // --- Hoehen-Parameter ---
        double heightGr = params.getHeightGr();
        double gfHeight = params.getGroundFloor() != null ? params.getGroundFloor().getTotalHeight() : 0;
        double ufHeight = params.getUpperFloor() != null ? params.getUpperFloor().getTotalHeight() : 0;

        // storeysAboveGround wird IGNORIERT — UFs werden dynamisch berechnet
        // egFloorZ = Oberkante Sockel/Fundament (hDgm + heightGr).
        // Keller-Gebaeude: Waende werden an egFloorZ geschnitten (Keller-Overlap vermeiden).
        // Keller-lose Gebaeude: Waende werden NICHT an egFloorZ geschnitten, damit sie
        // bis zum Terrain (hDgm) reichen (kein sichtbarer Spalt zur GroundSurface).
        double egFloorZ = CityGmlUtils.roundZ(hDgm + heightGr);

        if (gfHeight <= 0) {
            log.warn("GF.height fehlt/ungueltig fuer sst={}, ueberspringe Geschossteilung", sst);
            return;
        }

        if (traufeZ <= egFloorZ + CUT_TOLERANCE) {
            log.warn("Traufe ({}) <= EG-Floor ({}) fuer sst={}, ueberspringe",
                    CityGmlUtils.formatNum(traufeZ), CityGmlUtils.formatNum(egFloorZ), sst);
            return;
        }

        // --- Flachdach-Erkennung (vor Geschossberechnung fuer Fitzelchen-Logik) ---
        boolean isFlachdach = (firstZ - traufeZ) < FLAT_ROOF_TOLERANCE;

        // --- Geschossgrenzen dynamisch berechnen ---
        List<StoreyInfo> storeys = calculateStoreys(
                egFloorZ, gfHeight, ufHeight, traufeZ, sst, isFlachdach);
        if (storeys.isEmpty()) return;

        String targetId = target.getId() != null ? target.getId() : "unknown";

        log.debug("Verarbeite sst={} (gml:id={}): {} Geschosse, EG-Floor={}, Traufe={}",
                sst, targetId, storeys.size(),
                CityGmlUtils.formatNum(egFloorZ), CityGmlUtils.formatNum(traufeZ));

        // --- Waende schneiden ---
        // Z-Werte der Geschoss-Grenzen:
        // 1. egFloorZ NUR bei Keller-Gebaeuden als erste Grenze
        //    (trimmt Wandbereiche unterhalb GF-Boden, die sonst mit Keller-Waenden
        //    ueberlappen wuerden). Keller-lose Gebaeude: kein Schnitt → Wand reicht
        //    bis hDgm, kein Spalt zur GroundSurface.
        // 2. Geschoss-Ceilings (zwischen GF/UF_1, UF_1/UF_2, ...)
        List<Double> cutZValues = new ArrayList<>();
        if (params.hasBasement()) {
            cutZValues.add(egFloorZ);
        }
        for (int i = 0; i < storeys.size() - 1; i++) {
            cutZValues.add(storeys.get(i).ceilingZ);
        }

        List<AbstractSpaceBoundaryProperty> toRemove = new ArrayList<>();
        List<AbstractSpaceBoundaryProperty> toAdd = new ArrayList<>();
        int wallsCut = 0;
        int segmentsCreated = 0;

        // Zaehler fuer laufende Nummern pro Geschoss-Tag (fuer Wand-Benennung)
        Map<String, Integer> wallCountPerStorey = new HashMap<>();

        for (var boundary : target.getBoundaries()) {
            if (!(boundary.getObject() instanceof WallSurface wall)) continue;

            // BA-Surfaces ueberspringen (vom BasementGenerator erzeugt)
            String geschoss = CityGmlUtils.getStringAttribute(wall, "Geschoss");
            if (geschoss != null) continue;

            Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
            if (wallPoly == null) continue;

            List<Point3D> wallPoints = CityGmlUtils.toPoints(wallPoly);
            if (wallPoints.size() < 3) continue;

            double[] zRange = CityGmlUtils.getZRange(wallPoints);
            double wallMinZ = zRange[0];
            double wallMaxZ = zRange[1];

            // Schnitt-Z-Werte filtern: nur Grenzen innerhalb des Wand-Z-Bereichs
            List<Double> applicableCuts = new ArrayList<>();
            boolean hasEgFloorCut = false;
            for (double cutZ : cutZValues) {
                if (cutZ > wallMinZ + CUT_TOLERANCE && cutZ < wallMaxZ - CUT_TOLERANCE) {
                    applicableCuts.add(cutZ);
                    // Merken ob egFloorZ als Schnitt verwendet wird
                    if (Math.abs(cutZ - egFloorZ) < 0.001) {
                        hasEgFloorCut = true;
                    }
                }
            }

            // Duennstreifen-Vermeidung: Wenn der letzte Schnitt ein zu duennes
            // oberstes Wandsegment erzeugen wuerde (z.B. Wand ragt nur 30cm
            // ueber die Geschossgrenze), den letzten Schnitt entfernen.
            // Ausnahme: egFloorZ-Schnitt (das untere Segment wird sowieso verworfen).
            if (!applicableCuts.isEmpty()) {
                double lastCut = applicableCuts.get(applicableCuts.size() - 1);
                if (wallMaxZ - lastCut < MIN_WALL_SEGMENT_HEIGHT
                        && Math.abs(lastCut - egFloorZ) > 0.001) {
                    applicableCuts.remove(applicableCuts.size() - 1);
                }
            }

            // Traufen-Schnitt: NUR bei Flachdach. Falls Wand geometrisch ueber die
            // Traufe eines Flachdach-Parts hinausragt, wird an traufeZ geschnitten.
            // Bei Schraegdach wird NICHT geschnitten: Giebelwaende sind reale Wandflaechen
            // oberhalb der Traufe und duerfen nicht entfernt werden.
            boolean hasTraufeCut = false;
            if (isFlachdach && wallMaxZ > traufeZ + CUT_TOLERANCE && traufeZ > wallMinZ + CUT_TOLERANCE) {
                applicableCuts.add(traufeZ);
                hasTraufeCut = true;
            }

            // Keine Schnitte noetig? → Geschoss-Tag zuweisen, Wand beibehalten
            if (applicableCuts.isEmpty()) {
                // Wand komplett unterhalb egFloorZ? → verwerfen (Keller-Bereich)
                if (wallMaxZ <= egFloorZ + CUT_TOLERANCE) {
                    toRemove.add(boundary);
                    continue;
                }
                // Wand komplett oberhalb Traufe (nur Flachdach)? → verwerfen
                if (isFlachdach && wallMinZ >= traufeZ - CUT_TOLERANCE) {
                    toRemove.add(boundary);
                    continue;
                }
                StoreyInfo storey = findStoreyForZ(storeys, (wallMinZ + wallMaxZ) / 2.0);
                if (storey != null) {
                    assignGeschossToExistingWall(wall, storey);
                    segmentsCreated++;
                }
                continue;
            }

            // Original-Eigenschaften sichern (fuer Uebernahme auf Segmente)
            String originalWallId = wall.getId();
            String originalFaceId = CityGmlUtils.getStringAttribute(wall, "BldgFaceID");
            String innenwand = CityGmlUtils.getStringAttribute(wall, "Innenwand");
            String dachTyp = CityGmlUtils.getStringAttribute(wall, "DachTyp_LOD3");
            String dachName = CityGmlUtils.getStringAttribute(wall, "DachName_LOD3");
            String doorCount = CityGmlUtils.getStringAttribute(wall, "DoorCount");
            String windowPref = CityGmlUtils.getStringAttribute(wall, "WindowPreference");
            // WP=2: absoluten Z_Fenster_ASL = Z_MIN_ASL + Z_Fenster vorberechnen
            String zDifferenzStr = CityGmlUtils.getStringAttribute(wall, "Z_Differenz");
            String zFensterAslStr = null;
            if ("2".equals(windowPref)) {
                String zFensterStr = CityGmlUtils.getStringAttribute(wall, "Z_Fenster");
                String origZMinAsl = CityGmlUtils.getStringAttribute(wall, "Z_MIN_ASL");
                if (zFensterStr != null && origZMinAsl != null) {
                    try {
                        double zFensterAsl = Double.parseDouble(origZMinAsl)
                                           + Double.parseDouble(zFensterStr);
                        zFensterAslStr = CityGmlUtils.formatNum(zFensterAsl);
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (innenwand == null) innenwand = "0";

            // Iteratives Schneiden von unten nach oben
            List<Polygon> segments = cutWallAtMultipleZ(wallPoly, applicableCuts);
            if (segments == null || segments.isEmpty()) {
                // Schnitt fehlgeschlagen → nur Tag setzen
                StoreyInfo storey = findStoreyForZ(storeys, (wallMinZ + wallMaxZ) / 2.0);
                if (storey != null) {
                    assignGeschossToExistingWall(wall, storey);
                    segmentsCreated++;
                }
                continue;
            }

            // Original-Wand zum Entfernen markieren
            toRemove.add(boundary);
            wallsCut++;

            // Erstes Segment verwerfen wenn egFloorZ-Schnitt angewendet wurde
            // (Bereich H_DGM → egFloorZ = Overlap-Zone mit Keller).
            // Letztes Segment verwerfen wenn traufeZ-Schnitt angewendet wurde
            // (Wand-Geometrie oberhalb Traufe des BuildingParts).
            int startIdx = hasEgFloorCut ? 1 : 0;
            int endIdx   = hasTraufeCut  ? segments.size() - 1 : segments.size();

            // Fuer jedes Segment: neues WallSurface mit Geschoss-Attributen
            for (int i = startIdx; i < endIdx; i++) {
                Polygon segPoly = segments.get(i);
                List<Point3D> segPoints = CityGmlUtils.toPoints(segPoly);
                double[] segZ = CityGmlUtils.getZRange(segPoints);
                double segMidZ = (segZ[0] + segZ[1]) / 2.0;

                StoreyInfo storey = findStoreyForZ(storeys, segMidZ);
                if (storey == null) storey = storeys.get(storeys.size() - 1);

                boolean isTopSegment = (i == endIdx - 1);

                // Laufende Nummer pro Geschoss
                int runNum = wallCountPerStorey.merge(storey.geschoss, 1, Integer::sum);

                // Neues WallSurface-Segment: {OrigPolyId}_{StoreyTag}_{RunNum}
                WallSurface segWall = new WallSurface();
                String baseFaceId = originalFaceId != null ? originalFaceId : targetId;
                String segFaceId = baseFaceId + "_" + storey.geschoss + "_" + runNum;
                segWall.setId("Face_" + segFaceId);
                CityGmlUtils.setGmlName(segWall, "LOD3_Wall");
                segWall.setLod3MultiSurface(
                        CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(segPoly));

                // Alle Standard-Wand-Attribute berechnen und setzen
                String struktur = "1".equals(innenwand) ? "Innenwand" : "Aussenwand";
                CityGmlUtils.addWallAttributes(segWall, segPoints,
                        segFaceId, hDgm, storey.geschoss, null, struktur,
                        originalWallId);

                // Geschossdecke-Z schreiben (fuer WindowGenerator: Obergrenze fuer Fensterplatzierung)
                CityGmlUtils.addStringAttribute(segWall, "GeschossDeckeZ",
                        CityGmlUtils.formatNum(storey.ceilingZ));

                // Innenwand-Wert vom Original uebernehmen (addWallAttributes setzt immer "0")
                if ("1".equals(innenwand)) {
                    CityGmlUtils.setStringAttribute(segWall, "Innenwand", "1");
                }

                // Zusatz-Attribute vom Original uebernehmen
                // DoorCount nur am GF-Geschoss (Tueren nur im Erdgeschoss)
                if (doorCount != null && "GF".equals(storey.geschoss)) {
                    CityGmlUtils.addStringAttribute(segWall, "DoorCount", doorCount);
                }
                if (windowPref != null) {
                    CityGmlUtils.addStringAttribute(segWall, "WindowPreference", windowPref);
                }
                // WP=2: Z_Differenz und absoluten Z_Fenster_ASL fuer den WindowGenerator mitgeben
                if ("2".equals(windowPref)) {
                    if (zDifferenzStr != null) {
                        CityGmlUtils.addStringAttribute(segWall, "Z_Differenz", zDifferenzStr);
                    }
                    if (zFensterAslStr != null) {
                        CityGmlUtils.addStringAttribute(segWall, "Z_Fenster_ASL", zFensterAslStr);
                    }
                }

                // DachTyp/DachName nur am obersten Geschoss
                if (isTopSegment && dachTyp != null) {
                    CityGmlUtils.addStringAttribute(segWall, "DachTyp_LOD3", dachTyp);
                }
                if (isTopSegment && dachName != null) {
                    CityGmlUtils.addStringAttribute(segWall, "DachName_LOD3", dachName);
                }

                toAdd.add(new AbstractSpaceBoundaryProperty(segWall));
                segmentsCreated++;
            }
        }

        target.getBoundaries().removeAll(toRemove);
        target.getBoundaries().addAll(toAdd);

        // --- Original-GroundSurface markieren (fuer spaetere Verwendung) ---
        // Nur GroundSurfaces markieren, die NICHT vom BasementGenerator erzeugt wurden
        // (BA-Bodenplatten haben STRUKTUR="Bodenplatte")
        for (var boundary : target.getBoundaries()) {
            if (boundary.getObject() instanceof org.citygml4j.core.model.construction.GroundSurface gs) {
                String struktur = CityGmlUtils.getStringAttribute(gs, "STRUKTUR");
                if (!"Bodenplatte".equals(struktur)) {
                    CityGmlUtils.addStringAttribute(gs, "Original_GroundSurface", "preserved");
                }
            }
        }

        // --- Floor/Ceiling pro Geschoss erzeugen (XLink-Ansatz) ---
        // Prinzip: "Geometry once, semantics twice"
        // Das Ceiling-Polygon eines Geschosses wird inline mit gml:id definiert.
        // Der Floor des naechsten Geschosses referenziert es per xlink:href.

        // Mischdach-Boden-Artefakt: Wenn die globale rawMinRoofZ (= flache Dachflaeche) nur
        // knapp ueber dem EG-Fussboden liegt (< 2m), handelt es sich um eine Artefaktflaeche
        // (z.B. falsch klassifizierte Grenzflaeche im Keller/EG-Bereich). In diesem Fall soll
        // die Slab-Begrenzung durch slopedRawMinZ (Min-Z geneigter Flaechen) bestimmt werden.
        // Liegt die flache Dachflaeche hoeher (>= 2m ueber EG), ist sie eine echte gemeinsame
        // Dachflaeche mit einem Nachbar-Bauteil → Slab-Begrenzung bleibt erhalten (keine Protrusion).
        if (slopedRawMinRoofZ < Double.MAX_VALUE / 2
                && slopedRawMinRoofZ > rawMinRoofZ + CUT_TOLERANCE
                && rawMinRoofZ < egFloorZ + 2.0) {
            log.debug("  Mischdach-Artefakt {}: rawMinRoofZ={} nahe EG={}, verwende slopedRawMinZ={}",
                    targetId, CityGmlUtils.formatNum(rawMinRoofZ),
                    CityGmlUtils.formatNum(egFloorZ), CityGmlUtils.formatNum(slopedRawMinRoofZ));
            rawMinRoofZ = slopedRawMinRoofZ;
        }
        // Slab-Traufe = rawMinRoofZ (globale Min-Z, ggf. korrigiert um Boden-Artefakte).
        // Artefakt-Dachflaechen (Grenzflaechen zwischen BuildingParts) bei intermediarer Hoehe
        // begrenzen die Slab-Erzeugung weiterhin, um Protrusion ueber Nachbar-Parts zu vermeiden.
        double slabsTraufeZ = rawMinRoofZ;

        if (slabsTraufeZ <= egFloorZ + CUT_TOLERANCE) {
            // Terrain-Niveau-Artefakt: keine Decken/Boeden erzeugen
            log.debug("Part/Building {} rawMinRoofZ={} <= egFloorZ={}, ueberspringe Decken/Boeden",
                    targetId, CityGmlUtils.formatNum(slabsTraufeZ), CityGmlUtils.formatNum(egFloorZ));
            stats.storeysCreated += storeys.size();
            stats.wallsCut += wallsCut;
            stats.wallSegmentsCreated += segmentsCreated;
            return;
        }

        // Slab-Geschosse: begrenzt durch slabsTraufeZ.
        // Normalfall (rawMinRoofZ ≈ traufeZ): slabStoreys == storeys, identisches Verhalten.
        // Artefakt-Fall (rawMinRoofZ < traufeZ): weniger Slab-Geschosse → keine Protrusion.
        final boolean slabsAreLimited = (slabsTraufeZ < traufeZ - CUT_TOLERANCE);
        final List<StoreyInfo> slabStoreys;
        if (slabsAreLimited) {
            boolean isFlachdachSlabs = (firstZ - slabsTraufeZ) < FLAT_ROOF_TOLERANCE;
            slabStoreys = calculateStoreys(egFloorZ, gfHeight, ufHeight, slabsTraufeZ, sst, isFlachdachSlabs);
            log.debug("  Slab-Begrenzung {}: slabsTraufeZ={} < traufeZ={}, {} statt {} Slab-Geschosse",
                    targetId, CityGmlUtils.formatNum(slabsTraufeZ), CityGmlUtils.formatNum(traufeZ),
                    slabStoreys.size(), storeys.size());
        } else {
            slabStoreys = storeys;
        }
        if (slabStoreys.isEmpty()) {
            stats.storeysCreated += storeys.size();
            stats.wallsCut += wallsCut;
            stats.wallSegmentsCreated += segmentsCreated;
            return;
        }

        List<Polygon> groundPolygons = CityGmlUtils.collectGroundPolygons(target);
        int floorsAdded = 0;
        int ceilingsAdded = 0;

        // --- BA-Ceiling-IDs finden (vom BasementGenerator erzeugt in Schritt 2) ---
        // Wenn ein Keller existiert, hat dessen CeilingSurface ein Polygon mit
        // gml:id="Slab_{targetId}_BA_{polyIdx}". Der GF-Floor referenziert es per XLink.
        Map<Integer, String> baCeilingSlabIds = new HashMap<>();
        int baPolyIdx = 0;
        for (var boundary : target.getBoundaries()) {
            if (boundary.getObject() instanceof CeilingSurface cs) {
                String csGeschoss = CityGmlUtils.getStringAttribute(cs, "Geschoss");
                if ("BA".equals(csGeschoss)) {
                    baPolyIdx++;
                    // Slab-ID nach Konvention: Slab_{targetId}_BA_{nr}
                    String expectedSlabId = "Slab_" + targetId + "_BA_" + baPolyIdx;
                    baCeilingSlabIds.put(baPolyIdx, expectedSlabId);
                }
            }
        }
        boolean hasBasement = !baCeilingSlabIds.isEmpty();

        // Flachdach-Erkennung: bereits oben (vor calculateStoreys) berechnet.
        // isFlachdach steuert auch die CeilingSurface-Erzeugung:
        // Bei Flachdach wird keine CeilingSurface am obersten Geschoss erzeugt,
        // da die RoofSurface bereits die Decke bildet.

        // Mischdach-Erkennung: Gebaeude mit Flachdach UND geneigtem Dach.
        // Flache Dachflaechen (maxZ nahe traufeZ) bilden selbst die Decke.
        // Geneigte Dachflaechen (maxZ deutlich ueber traufeZ) brauchen eine CeilingSurface.
        // Bei Mischdach: CeilingSurface nur unter geneigten Dachflaechen erzeugen
        // (projizierte Dachpolygone statt Grundrisspolygone).
        List<Polygon> roofPolygons = CityGmlUtils.collectRoofPolygons(target);
        List<Polygon> slopedRoofPolygons = new ArrayList<>();
        int flatRoofCount = 0;

        for (Polygon roofPoly : roofPolygons) {
            List<Point3D> pts = CityGmlUtils.toPoints(roofPoly);
            if (pts.isEmpty()) continue;
            double maxZ = pts.stream().mapToDouble(p -> p.z).max().orElse(0);
            if ((maxZ - traufeZ) < 0.30) {
                flatRoofCount++;
            } else {
                slopedRoofPolygons.add(roofPoly);
            }
        }

        boolean isMixedRoof = flatRoofCount > 0 && !slopedRoofPolygons.isEmpty();
        if (isMixedRoof) {
            log.debug("  Mischdach erkannt fuer {}: {} flache + {} geneigte Dachflaechen",
                    targetId, flatRoofCount, slopedRoofPolygons.size());
        }

        // Per-Polygon Hoehengrenze: verhindert Protrusion von Slabs bei Gebaeuden mit
        // Abschnitten unterschiedlicher Wandhoehe (z.B. Fluegel kuerzer als Hauptbau).
        // Berechnet aus den nach dem Wand-Schnitt verfuegbaren Wandsegmenten:
        // Pro Grundriss-Polygon werden alle Wandsegmente gesucht, deren Basis-XY-Kante
        // mit einer Kante des Polygons uebereinstimmt; das Maximum ihrer MaxZ-Werte
        // begrenzt die Slab-Erzeugung fuer dieses Polygon.
        List<WallSurface> cutWalls = CityGmlUtils.collectWallSurfaces(target);
        List<Double> polyTopZList = new ArrayList<>();
        for (Polygon gp : groundPolygons) {
            List<Point3D> gpts = CityGmlUtils.toPoints(gp);
            if (gpts.size() >= 3) {
                polyTopZList.add(computePolygonTopZ(gpts, cutWalls, slabsTraufeZ));
            }
        }
        // Gestoppte Polygone: einmal gestoppt (floorZ >= polyTopZ) bleibt gestoppt
        Set<Integer> stoppedPolygons = new HashSet<>();

        // Map: polyIdx → Slab-gml:id des vorherigen Ceiling (fuer XLink im naechsten Floor)
        // Initialisierung: BA-Ceiling-IDs falls Keller vorhanden
        Map<Integer, String> previousCeilingSlabIds = new HashMap<>(baCeilingSlabIds);

        for (StoreyInfo storey : slabStoreys) {
            // Slab-IDs die in DIESEM Geschoss als Ceiling erzeugt werden
            Map<Integer, String> currentCeilingSlabIds = new HashMap<>();

            int polyIdx = 0;
            for (Polygon groundPoly : groundPolygons) {
                List<Point3D> groundPoints = CityGmlUtils.toPoints(groundPoly);
                if (groundPoints.size() < 3) continue;
                polyIdx++;

                // Per-polygon protrusion check: kein Slab wenn keine Waende fuer dieses
                // Polygon auf diesem Geschoss-Boden mehr vorhanden (Fluegel endet frueher).
                if (stoppedPolygons.contains(polyIdx)) continue;
                if (polyIdx <= polyTopZList.size()) {
                    double polyTopZ = polyTopZList.get(polyIdx - 1);
                    if (storey.floorZ >= polyTopZ - CUT_TOLERANCE) {
                        stoppedPolygons.add(polyIdx);
                        log.debug("  PerPolyTopZ {}: Poly {} (idx={}) floorZ={} >= topZ={} → gestoppt",
                                targetId, storey.geschoss, polyIdx,
                                CityGmlUtils.formatNum(storey.floorZ),
                                CityGmlUtils.formatNum(polyTopZ));
                        continue;
                    }
                }

                double area = CityGmlUtils.calculatePolygonArea2D(groundPoints);

                // --- FloorSurface ---
                FloorSurface floor = new FloorSurface();
                String floorFaceId = targetId + "_" + storey.geschoss + "_Floor_" + polyIdx;
                floor.setId("Face_" + floorFaceId);
                CityGmlUtils.setGmlName(floor, "LOD3_Floor");

                // XLink-Referenz: Wenn ein vorheriges Ceiling-Slab existiert → referenzieren
                String prevSlabId = previousCeilingSlabIds.get(polyIdx);
                if (prevSlabId != null) {
                    // XLink: Referenziert das Ceiling-Polygon des vorherigen Geschosses
                    floor.setLod3MultiSurface(
                            CityGmlUtils.createXLinkMultiSurfaceProperty(prevSlabId));
                } else {
                    // Inline: Kein vorheriges Ceiling → eigene Geometrie
                    List<Point3D> floorPoints = CityGmlUtils.projectToZ(groundPoints, storey.floorZ);
                    Polygon floorPoly = CityGmlUtils.createPolygon(floorPoints);
                    floor.setLod3MultiSurface(
                            CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(floorPoly));
                }

                CityGmlUtils.addHorizontalSurfaceAttributes(floor, floorFaceId,
                        storey.floorZ, hDgm, area, storey.geschoss);
                CityGmlUtils.addStringAttribute(floor, "STRUKTUR", "Geschossboden");

                target.getBoundaries().add(new AbstractSpaceBoundaryProperty(floor));
                floorsAdded++;

                // --- CeilingSurface ---
                // Bei Flachdach: Keine Decke am obersten Geschoss (RoofSurface = Decke).
                // Bei Slab-Begrenzung: ebenfalls keine Decke am obersten Slab-Geschoss,
                // weil slabsTraufeZ = rawMinRoofZ die Z-Hoehe einer echten RoofSurface ist.
                // Diese RoofSurface bildet die Decke — eine zusaetzliche CeilingSurface dort
                // wuerde (a) dieselbe Flaeche doppeln und (b) durch Fenster schneiden,
                // da die Waende an storeys-Grenzen (traufeZ) geschnitten sind, nicht an slabsTraufeZ.
                if (storey.isTopStorey && (isFlachdach || slabsAreLimited)) {
                    continue;
                }

                // Bei Mischdach am obersten Geschoss: Ceiling wird separat
                // aus projizierten geneigten Dachflaechen erzeugt (siehe unten).
                // Bei Slab-Begrenzung (slabsAreLimited): kein Mischdach-Ceiling
                // (bereits oben abgefangen).
                if (storey.isTopStorey && isMixedRoof && !slabsAreLimited) {
                    continue;
                }

                // Bei Schraegdach (Satteldach etc.): Giebel in oberstes Geschoss mergen
                // → keine Decke bei Traufe, es sei denn das Geschoss darunter ist ein
                //   vollstaendiges Stockwerk (Hoehe >= erwartet). Dann bleibt die Decke
                //   und der Giebel bildet ein eigenes Stockwerk.
                if (storey.isTopStorey && !isFlachdach && !isMixedRoof) {
                    double topStoreyHeight = storey.ceilingZ - storey.floorZ;
                    double expectedHeight = storey.geschoss.equals("GF") ? gfHeight : ufHeight;
                    if (topStoreyHeight < expectedHeight - CUT_TOLERANCE) {
                        log.debug("  Giebel-Merge: {} hat {}m < erwartet {}m → keine Decke bei Traufe",
                                storey.geschoss, CityGmlUtils.formatNum(topStoreyHeight),
                                CityGmlUtils.formatNum(expectedHeight));
                        continue;
                    }
                    log.debug("  Giebel-Stockwerk: {} hat {}m >= erwartet {}m → Decke bei Traufe bleibt",
                            storey.geschoss, CityGmlUtils.formatNum(topStoreyHeight),
                            CityGmlUtils.formatNum(expectedHeight));
                }

                // Ceiling-Polygon inline erzeugen mit gml:id fuer XLink-Referenz
                List<Point3D> ceilingPoints = CityGmlUtils.projectToZ(groundPoints, storey.ceilingZ);
                Polygon ceilingPoly = CityGmlUtils.createPolygon(ceilingPoints);

                // gml:id auf dem Polygon setzen (fuer XLink-Referenz vom naechsten Floor)
                String slabGmlId = "Slab_" + targetId + "_" + storey.geschoss + "_" + polyIdx;
                ceilingPoly.setId(slabGmlId);

                CeilingSurface ceiling = new CeilingSurface();
                String ceilingFaceId = targetId + "_" + storey.geschoss + "_Ceiling_" + polyIdx;
                ceiling.setId("Face_" + ceilingFaceId);
                CityGmlUtils.setGmlName(ceiling, "LOD3_Ceiling");
                ceiling.setLod3MultiSurface(
                        CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(ceilingPoly));

                CityGmlUtils.addHorizontalSurfaceAttributes(ceiling, ceilingFaceId,
                        storey.ceilingZ, hDgm, area, storey.geschoss);
                CityGmlUtils.addStringAttribute(ceiling, "STRUKTUR", "Geschossdecke");

                target.getBoundaries().add(new AbstractSpaceBoundaryProperty(ceiling));
                ceilingsAdded++;

                // Slab-ID merken fuer den Floor des naechsten Geschosses
                currentCeilingSlabIds.put(polyIdx, slabGmlId);
            }

            // --- Mischdach: CeilingSurface aus projizierten geneigten Dachflaechen ---
            // Statt das gesamte Grundrisspolygon als Decke zu verwenden, wird jedes
            // geneigte Dachpolygon auf Z=ceilingZ projiziert. So entsteht die Decke
            // nur unter dem geneigten Dachteil, nicht unter dem Flachdach.
            // Mischdach-Ceilings verwenden KEINE XLink-Referenz (andere Geometrie).
            if (storey.isTopStorey && isMixedRoof && !slabsAreLimited) {
                int roofIdx = 0;
                for (Polygon slopedPoly : slopedRoofPolygons) {
                    roofIdx++;
                    List<Point3D> roofPts = CityGmlUtils.toPoints(slopedPoly);
                    if (roofPts.size() < 3) continue;

                    List<Point3D> ceilingPoints = CityGmlUtils.projectToZ(roofPts, storey.ceilingZ);
                    double roofArea = CityGmlUtils.calculatePolygonArea2D(ceilingPoints);

                    Polygon ceilingPoly = CityGmlUtils.createPolygon(ceilingPoints);

                    CeilingSurface ceiling = new CeilingSurface();
                    String ceilingFaceId = targetId + "_" + storey.geschoss + "_Ceiling_R" + roofIdx;
                    ceiling.setId("Face_" + ceilingFaceId);
                    CityGmlUtils.setGmlName(ceiling, "LOD3_Ceiling");
                    ceiling.setLod3MultiSurface(
                            CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(ceilingPoly));

                    CityGmlUtils.addHorizontalSurfaceAttributes(ceiling, ceilingFaceId,
                            storey.ceilingZ, hDgm, roofArea, storey.geschoss);
                    CityGmlUtils.addStringAttribute(ceiling, "STRUKTUR", "Geschossdecke");

                    target.getBoundaries().add(new AbstractSpaceBoundaryProperty(ceiling));
                    ceilingsAdded++;
                }
            }

            // Ceiling-Slab-IDs dieses Geschosses werden zum "previous" fuer das naechste
            previousCeilingSlabIds = currentCeilingSlabIds;
        }

        // --- Metadaten aktualisieren ---
        CityGmlUtils.setStringAttribute(target, "storeysGenerated",
                String.valueOf(storeys.size()));

        // storeysAboveGround aktualisieren (dynamisch berechnet)
        target.setStoreysAboveGround(storeys.size());

        stats.storeysCreated += storeys.size();
        stats.wallsCut += wallsCut;
        stats.wallSegmentsCreated += segmentsCreated;
        stats.floorsCreated += floorsAdded;
        stats.ceilingsCreated += ceilingsAdded;

        log.debug("  => sst={}: {} Geschosse, {} Wand-Segmente, {} Boeden, {} Decken",
                sst, storeys.size(), segmentsCreated, floorsAdded, ceilingsAdded);
    }

    // ==================== Geschossberechnung ====================

    /**
     * Berechnet die Geschossgrenzen dynamisch von GF aufwaerts.
     *
     * Regeln:
     * - GF beginnt bei egFloorZ (= H_DGM + heightGr)
     * - GF-Hoehe = GF.height + GF.CeHe
     * - UF-Hoehe = UF.height + UF.CeHe
     * - Anzahl UFs wird dynamisch berechnet (so viele wie in die Gebaeudehoehe passen)
     * - storeysAboveGround aus CityGML wird NICHT verwendet
     * - Letztes Geschoss endet an der Traufe (Fitzelchen-Loesung!)
     * - Wenn ein berechnetes Ceiling die Traufe erreicht: vorzeitig abbrechen
     * - Fitzelchen (&lt; 1.20m Resthoehe): wird ins vorherige Geschoss gemerged
     * - AUSNAHME Flachdach: Wenn durch Fitzelchen-Merge das Geschoss &gt; 4.0m wuerde,
     *   wird das Fitzelchen als eigenes kurzes Geschoss beibehalten
     *
     * @param egFloorZ    Unterkante GF (= H_DGM + heightGr)
     * @param gfHeight    GF-Geschosshoehe (GF.height + GF.CeHe)
     * @param ufHeight    UF-Geschosshoehe (UF.height + UF.CeHe), kann 0 sein
     * @param traufeZ     Traufe (Z_MIN RoofSurface)
     * @param sst         Modul-ID (fuer Logging)
     * @param isFlachdach true wenn Flachdach (First ≈ Traufe) – beeinflusst Fitzelchen-Merge
     */
    private List<StoreyInfo> calculateStoreys(double egFloorZ, double gfHeight,
            double ufHeight, double traufeZ, String sst, boolean isFlachdach) {

        List<StoreyInfo> storeys = new ArrayList<>();
        traufeZ = CityGmlUtils.roundZ(traufeZ);

        // --- GF ---
        double gfCeilingZ = CityGmlUtils.roundZ(egFloorZ + gfHeight);

        // Sicherheitscheck: GF-Ceiling darf nicht ueber Traufe liegen
        if (gfCeilingZ >= traufeZ - CUT_TOLERANCE) {
            log.debug("GF-Ceiling ({}) >= Traufe ({}) fuer sst={}, nur GF erzeugt",
                    CityGmlUtils.formatNum(gfCeilingZ), CityGmlUtils.formatNum(traufeZ), sst);
            storeys.add(new StoreyInfo("GF", egFloorZ, traufeZ, true));
            return storeys;
        }

        // Kein UF moeglich (ufHeight fehlt oder 0)?
        if (ufHeight <= 0) {
            storeys.add(new StoreyInfo("GF", egFloorZ, traufeZ, true));
            return storeys;
        }

        storeys.add(new StoreyInfo("GF", egFloorZ, gfCeilingZ, false));

        // --- Upper Floors (dynamisch berechnet) ---
        double currentFloorZ = gfCeilingZ;
        int ufNum = 0;

        while (true) {
            double remainingHeight = traufeZ - currentFloorZ;

            // Fitzelchen-Pruefung: Resthoehe zu gering fuer ein neues Geschoss?
            if (remainingHeight < MIN_STOREY_HEIGHT) {
                if (!storeys.isEmpty()) {
                    StoreyInfo prev = storeys.get(storeys.size() - 1);
                    double mergedHeight = traufeZ - prev.floorZ;

                    if (isFlachdach && mergedHeight > MAX_STOREY_HEIGHT_FLACHDACH) {
                        // Flachdach-Sonderregel: Nicht mergen wenn Ergebnis > 4m,
                        // stattdessen kurzes Fitzelchen-Geschoss beibehalten
                        ufNum++;
                        String geschoss = "UF_" + ufNum;
                        storeys.add(new StoreyInfo(geschoss, currentFloorZ, traufeZ, true));
                        log.debug("Flachdach: Fitzelchen-Geschoss {} mit {}m erzeugt (Merge haette {}m > {}m ergeben)",
                                geschoss, CityGmlUtils.formatNum(remainingHeight),
                                CityGmlUtils.formatNum(mergedHeight),
                                CityGmlUtils.formatNum(MAX_STOREY_HEIGHT_FLACHDACH));
                    } else {
                        // Standard: vorheriges Geschoss bis Traufe erweitern
                        storeys.set(storeys.size() - 1,
                                new StoreyInfo(prev.geschoss, prev.floorZ, traufeZ, true));
                        log.debug("UF uebersprungen: Resthoehe {}m < {}m, {} bis Traufe erweitert",
                                CityGmlUtils.formatNum(remainingHeight),
                                CityGmlUtils.formatNum(MIN_STOREY_HEIGHT), prev.geschoss);
                    }
                }
                break;
            }

            ufNum++;
            String geschoss = "UF_" + ufNum;
            double ceilingZ = CityGmlUtils.roundZ(currentFloorZ + ufHeight);

            // Ceiling erreicht oder uebersteigt Traufe → letztes UF
            if (ceilingZ >= traufeZ - CUT_TOLERANCE) {
                storeys.add(new StoreyInfo(geschoss, currentFloorZ, traufeZ, true));
                break;
            }

            // Wuerde nach diesem UF ein Fitzelchen entstehen?
            double remainingAfter = traufeZ - ceilingZ;
            if (remainingAfter < MIN_STOREY_HEIGHT) {
                double extendedHeight = traufeZ - currentFloorZ;
                if (isFlachdach && extendedHeight > MAX_STOREY_HEIGHT_FLACHDACH) {
                    // Flachdach: Normal anlegen, naechste Iteration erzeugt Fitzelchen
                    storeys.add(new StoreyInfo(geschoss, currentFloorZ, ceilingZ, false));
                    currentFloorZ = ceilingZ;
                } else {
                    // Dieses UF bis Traufe erweitern
                    storeys.add(new StoreyInfo(geschoss, currentFloorZ, traufeZ, true));
                    break;
                }
            } else {
                // Normales UF
                storeys.add(new StoreyInfo(geschoss, currentFloorZ, ceilingZ, false));
                currentFloorZ = ceilingZ;
            }
        }

        // Warnung bei ungewoehnlich hohem letztem Geschoss
        if (storeys.size() > 1 && ufHeight > 0) {
            StoreyInfo last = storeys.get(storeys.size() - 1);
            double lastHeight = last.ceilingZ - last.floorZ;
            if (lastHeight > 1.5 * ufHeight && !last.geschoss.equals("GF")) {
                log.debug("Letztes Geschoss {} = {}m (erwartet ~{}m) fuer sst={} (Traufe-Auffuellung)",
                        last.geschoss, CityGmlUtils.formatNum(lastHeight),
                        CityGmlUtils.formatNum(ufHeight), sst);
            }
        }

        return storeys;
    }

    // ==================== Wand-Schnitt ====================

    /**
     * Schneidet ein Wand-Polygon an mehreren Z-Hoehen.
     * Verwendet iteratives Schneiden: Unterer Teil wird als Segment gesichert,
     * oberer Teil wird weitergeschnitten.
     *
     * Funktioniert fuer beliebige Polygon-Formen dank Sutherland-Hodgman-Algorithmus
     * in CityGmlUtils.cutWallPolygonAtZ (Dreiecke, Rechtecke, Fuenfecke, Sechsecke, etc.)
     *
     * @param wallPoly   Das zu schneidende Polygon (3+ Eckpunkte)
     * @param cutZValues Sortierte Z-Werte fuer die Schnitte (aufsteigend)
     * @return Liste von Segment-Polygonen von unten nach oben, oder null bei Fehler
     */
    private List<Polygon> cutWallAtMultipleZ(Polygon wallPoly, List<Double> cutZValues) {
        if (cutZValues.isEmpty()) return null;

        List<Polygon> segments = new ArrayList<>();
        Polygon remaining = wallPoly;

        for (double cutZ : cutZValues) {
            Polygon[] result = CityGmlUtils.cutWallPolygonAtZ(remaining, cutZ, CUT_TOLERANCE);
            if (result == null) {
                // Schnitt fehlgeschlagen → Rest als letztes Segment
                break;
            }
            segments.add(result[0]); // unterer Teil
            remaining = result[1];   // oberer Teil weiterschneiden
        }

        segments.add(remaining); // oberstes Segment (Rest)
        return segments;
    }

    /**
     * Findet das Geschoss fuer eine gegebene Z-Hoehe.
     * Sucht erst exakte Zuordnung, dann naechstliegendes Geschoss als Fallback.
     */
    private StoreyInfo findStoreyForZ(List<StoreyInfo> storeys, double z) {
        // Exakte Zuordnung: Z liegt innerhalb der Geschossgrenzen
        for (StoreyInfo s : storeys) {
            if (z >= s.floorZ - CUT_TOLERANCE && z <= s.ceilingZ + CUT_TOLERANCE) {
                return s;
            }
        }

        // Fallback: naechstliegendes Geschoss (nach Mitte)
        return storeys.stream()
                .min(java.util.Comparator.comparingDouble(
                        s -> Math.abs(z - (s.floorZ + s.ceilingZ) / 2.0)))
                .orElse(null);
    }

    /**
     * Fuegt Geschoss-Attribute zu einer bestehenden Wand hinzu
     * (fuer Waende die nicht geschnitten werden koennen, z.B. nicht-rechteckig).
     */
    private void assignGeschossToExistingWall(WallSurface wall, StoreyInfo storey) {
        CityGmlUtils.addStringAttribute(wall, "Geschoss", storey.geschoss);
    }

    // ==================== Innere Klassen ====================

    /** Beschreibt ein Geschoss mit seinen Z-Grenzen. */
    private record StoreyInfo(
            String geschoss,    // Tag: EG, 1.OG, 2.OG, ...
            double floorZ,      // Unterkante (absolut, m ue. NHN)
            double ceilingZ,    // Oberkante (absolut)
            boolean isTopStorey // true = oberstes Geschoss (reicht bis Traufe)
    ) {}

    public static class GenerationStats {
        public int buildingsProcessed = 0;
        public int storeysCreated = 0;
        public int wallsCut = 0;
        public int wallSegmentsCreated = 0;
        public int floorsCreated = 0;
        public int ceilingsCreated = 0;
    }

    // ==================== Per-Polygon Hoehenberechnung ====================

    /**
     * Berechnet die maximale Wandhoehe fuer ein Grundriss-Polygon.
     * <p>
     * Sucht in der Liste der geschnittenen Wandsegmente alle Segmente, deren
     * Basis-XY-Kante mit einer Kante des Grundriss-Polygons uebereinstimmt
     * (innerhalb {@link #XY_EDGE_TOLERANCE}). Gibt das Maximum der wallMaxZ-Werte
     * dieser Wandsegmente zurueck.
     * <p>
     * Zweck: Verhindert Protrusion von Slab-Flaechen ueber die Wandhoehe hinaus
     * bei Gebaeuden mit Abschnitten unterschiedlicher Hoehe (z.B. Nebenfluegelkuerzer).
     *
     * @param groundPts Punkte des Grundriss-Polygons (inkl. optionalem Schlusspunkt)
     * @param walls     Liste der geschnittenen WallSurfaces (nach dem Wand-Schnitt-Loop)
     * @param defaultZ  Fallback-Wert wenn keine passende Wand gefunden wird
     * @return maximale Z-Hoehe der zugehoerigen Waende, oder defaultZ
     */
    private double computePolygonTopZ(List<Point3D> groundPts,
                                      List<WallSurface> walls,
                                      double defaultZ) {
        if (groundPts.size() < 3) return defaultZ;

        // Grundriss-Kanten aufbauen (Schlusspunkt ggf. entfernen)
        List<Point3D> edgePts = new ArrayList<>(groundPts);
        Point3D gFirst = edgePts.get(0);
        Point3D gLast  = edgePts.get(edgePts.size() - 1);
        if (Math.hypot(gFirst.x - gLast.x, gFirst.y - gLast.y) < 0.01) {
            edgePts.remove(edgePts.size() - 1);
        }
        int n = edgePts.size();

        double maxZ    = Double.NEGATIVE_INFINITY;
        boolean anyMatch = false;

        for (WallSurface wall : walls) {
            Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
            if (wallPoly == null) continue;
            List<Point3D> wallPts = CityGmlUtils.toPoints(wallPoly);
            if (wallPts.size() < 4) continue;

            double wallMinZ = Double.MAX_VALUE;
            double wallMaxZ = -Double.MAX_VALUE;
            for (Point3D p : wallPts) {
                if (p.z < wallMinZ) wallMinZ = p.z;
                if (p.z > wallMaxZ) wallMaxZ = p.z;
            }

            // Basis-Punkte des Wandsegments (bei wallMinZ, Toleranz 0.30m)
            List<Point3D> basePts = new ArrayList<>();
            for (Point3D p : wallPts) {
                if (Math.abs(p.z - wallMinZ) < 0.30) basePts.add(p);
            }
            if (basePts.size() < 2) continue;

            // Pruefen ob eine Basis-Kante der Wand mit einer Grundriss-Kante uebereinstimmt (XY)
            boolean matched = false;
            outer:
            for (int j = 0; j < basePts.size() && !matched; j++) {
                for (int k = j + 1; k < basePts.size() && !matched; k++) {
                    Point3D wa = basePts.get(j);
                    Point3D wb = basePts.get(k);
                    for (int i = 0; i < n && !matched; i++) {
                        Point3D ga = edgePts.get(i);
                        Point3D gb = edgePts.get((i + 1) % n);
                        double d1 = Math.hypot(wa.x - ga.x, wa.y - ga.y);
                        double d2 = Math.hypot(wb.x - gb.x, wb.y - gb.y);
                        double d3 = Math.hypot(wa.x - gb.x, wa.y - gb.y);
                        double d4 = Math.hypot(wb.x - ga.x, wb.y - ga.y);
                        if ((d1 < XY_EDGE_TOLERANCE && d2 < XY_EDGE_TOLERANCE)
                                || (d3 < XY_EDGE_TOLERANCE && d4 < XY_EDGE_TOLERANCE)) {
                            matched = true;
                        }
                    }
                }
            }
            if (matched) {
                anyMatch = true;
                if (wallMaxZ > maxZ) maxZ = wallMaxZ;
            }
        }

        if (anyMatch) {
            log.trace("  computePolygonTopZ: {} Punkte → topZ={} (defaultZ={})",
                    groundPts.size(), CityGmlUtils.formatNum(maxZ), CityGmlUtils.formatNum(defaultZ));
        }
        return anyMatch ? maxZ : defaultZ;
    }
}
