package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils.Point3D;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.AbstractFillingSurface;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.DoorSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.construction.WindowSurface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.geometry.primitives.AbstractRingProperty;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Schritt 5: Fenster-Generator.
 *
 * Erzeugt Fenster (WindowSurface) an Aussenwand-Segmenten aller Geschosse (GF, UF, BA).
 * Im Gegensatz zum DoorGenerator, der den aeusseren Polygon-Ring modifiziert
 * (Tuer-Ausschnitte), werden Fenster als innere Polygon-Ringe (Loecher) in das
 * Wand-Polygon eingefuegt. Der aeussere Ring bleibt unveraendert.
 *
 * Fenster-Parameter aus JSON (pro Geschoss: GF.window, UF.window, BA.window):
 * - WiLen:          Fensterbreite
 * - WiHe:           Fensterhoehe
 * - HDistWaWi:      Horizontaler Abstand Wandecke → erstes Fenster
 * - HDistWiWi:      Lichter Abstand (edge-to-edge) zwischen Fenstern
 * - HDistMinWaWi:   Mindestabstand Wandecke → Fensterkante
 * - VDistFlWi:      Bruestungshoehe (Boden → Fensterunterkante)
 * - HDistDoWi:      Abstand Tuer → naechstes Fenster (nur GF)
 *
 * Positionierung:
 * - Fensteranzahl wird berechnet (nicht aus Attribut gelesen wie bei Tueren)
 * - Mehrere Fensterreihen moeglich wenn Wandhoehe ausreicht
 * - GF-Waende: Links/Rechts-Aufspaltung um vorhandene Tueren
 * - BA-Waende: Nur oberirdischer Anteil (Z_Max > 0)
 * - Giebelwaende: Point-in-Polygon-Check fuer alle 4 Fenster-Ecken
 *
 * Geometrie:
 * - Fenster werden als innere Polygon-Ringe (CW) im Wand-Polygon erzeugt
 * - Separate WindowSurface mit Fenster-Rechteck als lod3MultiSurface
 * - WindowSurface wird als FillingSurface an der WallSurface verankert
 *
 * Erwartet als Input den Output des DoorGenerators (Schritt 4).
 *
 * Usage:
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.WindowGenerator input.gml jsonDir [output.gml]
 */
public class WindowGenerator {

    private static final Logger log = LoggerFactory.getLogger(WindowGenerator.class);

    /** Maximaler Window-to-Wall Ratio (60 %). */
    private static final double MAX_WWR = 0.60;

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                System.err.println("Usage: WindowGenerator <input.gml> <jsonDir> [output.gml]");
                System.exit(1);
            }

            Path inputPath = Paths.get(args[0]);
            Path jsonDir = Paths.get(args[1]);
            Path outputPath = CityGmlUtils.resolveOutputPath(inputPath, "_windows",
                    args.length >= 3 ? Paths.get(args[2]) : null);

            Files.createDirectories(outputPath.getParent());

            log.info("=== Fenster-Generator ===");
            log.info("Input:  {}", inputPath);
            log.info("JSON:   {}", jsonDir);
            log.info("Output: {}", outputPath);

            WindowGenerator generator = new WindowGenerator();
            ModuleParametersLoader paramLoader = new ModuleParametersLoader(jsonDir);
            GenerationStats stats = generator.addWindows(inputPath, outputPath, paramLoader);

            log.info("=== Fertig ===");
            log.info("Gebaeude verarbeitet: {}", stats.buildingsProcessed);
            log.info("Fenster erzeugt: {}", stats.windowsCreated);
            log.info("Waende mit Fenstern: {}", stats.wallsWithWindows);
            log.info("Waende uebersprungen: {}", stats.wallsSkipped);
            log.info("Giebel-Fenster verworfen: {}", stats.gableWindowsDropped);
            log.info("WWR-Warnungen: {}", stats.wwrWarnings);
            log.info(stats.toSummary());

        } catch (Exception e) {
            log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    // ==================== Standalone-Verarbeitung ====================

    /**
     * Fuegt Fenster zu allen passenden Waenden hinzu (Standalone-Modus).
     */
    public GenerationStats addWindows(Path inputFile, Path outputFile,
            ModuleParametersLoader paramLoader) throws Exception {
        GenerationStats stats = new GenerationStats();

        CityGmlUtils.processGmlFile(inputFile, outputFile, building -> {
            stats.buildingsProcessed++;
            processBuilding(building, paramLoader, stats);
        });

        return stats;
    }

    // ==================== Pipeline-Integration ====================

    /**
     * Verarbeitet ein einzelnes Gebaeude (Pipeline- und Standalone-Modus).
     * Liest Building-level Attribute (sst) und delegiert die Verarbeitung
     * an processAbstractBuilding() fuer das Building selbst und/oder seine BuildingParts.
     */
    void processBuilding(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {

        String sst = CityGmlUtils.getStringAttribute(building, "sst");
        if (sst == null || sst.isBlank()) return;

        Optional<ModuleParameters> paramsOpt = paramLoader.getParameters(sst);
        if (paramsOpt.isEmpty()) return;
        ModuleParameters params = paramsOpt.get();

        // processedWallMids: kumulatives Set aller bereits verarbeiteten Wand-Mittelpunkte.
        // Wenn zwei BuildingParts eine gemeinsame Wand teilen (identische Geometrie),
        // bekommt der erste Part die Fenster; der zweite Part ueberspringt diese Wand
        // (Mittelpunkt bereits im Set).
        Set<String> processedWallMids = new HashSet<>();

        for (var target : CityGmlUtils.getBuildingTargets(building)) {
            processAbstractBuilding(target, params, stats, processedWallMids);
            CityGmlUtils.rebuildSolidShell(target);
        }
    }

    /**
     * Berechnet einen String-Key fuer den 2D-Mittelpunkt der untersten Wandkante.
     * Aufloesung 0.1m (ausreichend, da deckungsgleiche Waende exakt gleiche Koordinaten haben).
     * Gibt null zurueck wenn das Polygon nicht ausgewertet werden kann.
     */
    private static String wallBottomMidKey(WallSurface wall) {
        Polygon poly = CityGmlUtils.getWallPolygon(wall);
        if (poly == null) return null;
        List<Point3D> pts = CityGmlUtils.toPoints(poly);
        List<Point3D> open = CityGmlUtils.removeClosingPoint(pts);
        if (open.size() < 2) return null;
        double[] zRange = CityGmlUtils.getZRange(open);
        double zBotMin = zRange[0];
        double zTol = 0.01;
        double sumX = 0, sumY = 0;
        int cnt = 0;
        for (Point3D p : open) {
            if (Math.abs(p.z - zBotMin) < zTol) { sumX += p.x; sumY += p.y; cnt++; }
        }
        if (cnt < 2) return null;
        long gx = Math.round((sumX / cnt) * 10);
        long gy = Math.round((sumY / cnt) * 10);
        long gz = Math.round(zBotMin * 10);
        return gx + "," + gy + "," + gz;
    }

    // ==================== Gate-Check Chain ====================

    /**
     * Einzelner Gate-Check: prueft eine Wand und zaehlt bei Misserfolg in stats.
     * Gibt true zurueck wenn die Wand den Check besteht (verarbeitung fortsetzen),
     * false wenn sie uebersprungen werden soll.
     */
    @FunctionalInterface
    private interface WallCheck {
        boolean test(WallSurface wall, String geschoss, GenerationStats stats);
    }

    /**
     * Erstellt die Kette von Gate-Checks fuer processAbstractBuilding.
     * Reihenfolge ist relevant: guenstige Checks (billig, oft feuern) zuerst.
     */
    private List<WallCheck> buildGateChecks(Set<String> processedWallMids, ModuleParameters params) {
        List<WallCheck> checks = new ArrayList<>();

        // [1] Eligibles Geschoss (GF, UF_*, BA)
        checks.add((wall, geschoss, stats) -> {
            if (!isWindowEligibleGeschoss(geschoss)) return false;
            stats.addSkipForGeschoss(geschoss); // wird bei Erfolg korrigiert
            return true;
        });

        // [2] Doppelte Wand zwischen BuildingParts
        checks.add((wall, geschoss, stats) -> {
            String midKey = wallBottomMidKey(wall);
            if (midKey != null && !processedWallMids.add(midKey)) {
                stats.wallsSkipped++;
                stats.skipCoveredByPart++;
                return false;
            }
            return true;
        });

        // [3] WindowPreference muss gesetzt und != "0" sein;
        //     bei "2" muss Z_Differenz > 0 (Wand ueberragt die Nachbarwand)
        checks.add((wall, geschoss, stats) -> {
            String windowPref = CityGmlUtils.getStringAttribute(wall, "WindowPreference");
            if (windowPref == null || "0".equals(windowPref)) {
                stats.wallsSkipped++;
                stats.skipNoWindowPref++;
                return false;
            }
            if (!"2".equals(windowPref)) return true;
            String zDiffStr = CityGmlUtils.getStringAttribute(wall, "Z_Differenz");
            if (zDiffStr == null) {
                stats.wallsSkipped++; stats.skipNoWindowPref++; return false;
            }
            try {
                if (Double.parseDouble(zDiffStr) <= 0) {
                    stats.wallsSkipped++; stats.skipNoWindowPref++; return false;
                }
            } catch (NumberFormatException e) {
                stats.wallsSkipped++; stats.skipNoWindowPref++; return false;
            }
            return true;
        });

        // [4] BA: Z_Max muss existieren und > 0 sein
        checks.add((wall, geschoss, stats) -> {
            if (!"BA".equals(geschoss)) return true;
            String zMaxStr = CityGmlUtils.getStringAttribute(wall, "Z_Max");
            if (zMaxStr == null) { stats.wallsSkipped++; stats.skipBaNoZMax++; return false; }
            try {
                double zMax = Double.parseDouble(zMaxStr);
                if (zMax <= 0) { stats.wallsSkipped++; stats.skipBaNoZMax++; return false; }
            } catch (NumberFormatException e) {
                stats.wallsSkipped++;
                stats.skipBaNoZMax++;
                return false;
            }
            return true;
        });

        return checks;
    }

    /**
     * Verarbeitet ein AbstractBuilding (Building oder BuildingPart).
     * Sucht alle WallSurfaces und fuegt Fenster ein wo die Vorbedingungen erfuellt sind.
     *
     * @param processedWallMids kumulatives Set aller bisher verarbeiteten Wand-Mittelpunkte;
     *                          bereits enthaltene Waende werden uebersprungen (Duplikat zwischen
     *                          zwei BuildingParts), neue Mittelpunkte werden hinzugefuegt.
     */
    private void processAbstractBuilding(AbstractBuilding target,
            ModuleParameters params, GenerationStats stats,
            Set<String> processedWallMids) {

        List<WallSurface> walls = CityGmlUtils.collectWallSurfaces(target);
        List<WallCheck> gateChecks = buildGateChecks(processedWallMids, params);

        for (WallSurface wall : walls) {
            String geschoss = CityGmlUtils.getStringAttribute(wall, "Geschoss");

            // Gate-Check-Chain: jeder Check muss true liefern
            boolean passed = true;
            for (WallCheck check : gateChecks) {
                if (!check.test(wall, geschoss, stats)) { passed = false; break; }
            }
            if (!passed) continue;

            // Fenster-Parameter fuer dieses Geschoss holen
            ModuleParameters.WindowParams wp = params.getWindowParamsForGeschoss(geschoss);
            if (wp == null || !wp.isValid()) {
                stats.wallsSkipped++;
                stats.skipNoParams++;
                continue;
            }

            processWall(wall, geschoss, wp, stats);
        }
    }

    // ==================== Storey Strategy ====================

    /**
     * Kapselt die geschoss-spezifische Geometrie-Logik fuer die Fensterplatzierung.
     * Jedes Geschoss (GF/UF vs. BA) hat unterschiedliche Regeln fuer
     * - Nutzbare Hoehe (usableHeight)
     * - Gelaendeniveau (terrainZ, nur BA)
     * - Wandabschnitte (sections: GF teilt um Tueren, UF/BA nimmt ganze Wand)
     */
    private interface StoreyWindowStrategy {
        /**
         * Berechnet die nutzbare Wandhoehe (Obergrenze fuer Fenster-Reihen).
         * BA: wallHeight + 0.10 cm Toleranz; GF/UF: min(wallHeight, GeschossDeckeZ - zMin).
         */
        double usableHeight(WallSurface wall, double wallHeight, double zMin);

        /**
         * Gibt die Gelaendehoehe (TIC) zurueck, unterhalb derer BA-Reihen uebersprungen werden.
         * BA: zMaxAsl - zMaxRel; GF/UF: NaN (kein Filter).
         */
        double terrainZ(WallSurface wall);

        /**
         * Bestimmt die horizontal verfuegbaren Wandabschnitte.
         * GF: freie Abschnitte um Tueren herum; UF/BA: gesamte Wand.
         */
        List<double[]> sections(WallSurface wall, Point3D edgeStart,
                double dirX, double dirY, double wallLength,
                ModuleParameters.WindowParams wp);
    }

    /** Strategie fuer GF- und UF-Geschosse. */
    private final StoreyWindowStrategy strategyGfUf = new StoreyWindowStrategy() {
        @Override
        public double usableHeight(WallSurface wall, double wallHeight, double zMin) {
            String geschossDeckeStr = CityGmlUtils.getStringAttribute(wall, "GeschossDeckeZ");
            if (geschossDeckeStr != null) {
                double geschossDeckeZ = Double.parseDouble(geschossDeckeStr);
                return Math.min(wallHeight, geschossDeckeZ - zMin);
            }
            return wallHeight;
        }

        @Override
        public double terrainZ(WallSurface wall) {
            return Double.NaN; // kein Gelaende-Filter fuer GF/UF
        }

        @Override
        public List<double[]> sections(WallSurface wall, Point3D edgeStart,
                double dirX, double dirY, double wallLength,
                ModuleParameters.WindowParams wp) {
            return extractFreeSections(wall, edgeStart, dirX, dirY, wallLength, wp);
        }
    };

    /** Strategie fuer BA-Geschoss (Keller, teilweise oberirdisch). */
    private static final StoreyWindowStrategy strategyBa = new StoreyWindowStrategy() {
        @Override
        public double usableHeight(WallSurface wall, double wallHeight, double zMin) {
            return wallHeight + 0.10; // +10 cm Toleranz fuer JSON-Rundungsfehler
        }

        @Override
        public double terrainZ(WallSurface wall) {
            String zMaxRelStr = CityGmlUtils.getStringAttribute(wall, "Z_Max");
            String zMaxAslStr = CityGmlUtils.getStringAttribute(wall, "Z_MAX_ASL");
            double zMaxRel = Double.parseDouble(zMaxRelStr);
            double zMaxAsl = Double.parseDouble(zMaxAslStr);
            return zMaxAsl - zMaxRel; // TIC-Niveau
        }

        @Override
        public List<double[]> sections(WallSurface wall, Point3D edgeStart,
                double dirX, double dirY, double wallLength,
                ModuleParameters.WindowParams wp) {
            return List.of(new double[]{0, wallLength}); // gesamte Wand
        }
    };

    /** Waehlt die passende Strategie fuer ein Geschoss. */
    private StoreyWindowStrategy strategyFor(String geschoss) {
        return "BA".equals(geschoss) ? strategyBa : strategyGfUf;
    }

    // ==================== WallContext ====================

    /**
     * Buendelt alle geometrischen Kontextdaten einer Wand fuer die Fensterplatzierung.
     * Wird von processWall() zusammengestellt und an Unter-Methoden weitergegeben,
     * um lange Parameterlisten zu vermeiden.
     */
    private record WallContext(
            WallSurface wall,
            String geschoss,
            ModuleParameters.WindowParams wp,
            List<Point3D> open,     // offener Polygon (kein schliessender Punkt)
            Point3D edgeStart,      // linker Startpunkt der Unterkante
            double dirX,            // normierter Richtungsvektor X der Unterkante
            double dirY,            // normierter Richtungsvektor Y der Unterkante
            double wallLength,      // Laenge der Unterkante [m]
            double zMin,            // unterster Z-Wert des Polygons
            double floorZ,          // absolute Z-Koordinate des Fussbodens (= zMin)
            double usableHeight,    // nutzbare Wandhoehe fuer Fenster [m]
            double terrainZ,        // Gelaendehoehe TIC; NaN fuer GF/UF
            List<double[]> sections // verfuegbare Wandabschnitte [sectionStart, sectionEnd]
    ) {}

    // ==================== Wand-Verarbeitung (Intermediate Records) ====================

    /** Ergebnis der Unterkanten-Ermittlung. */
    private record BottomEdge(
            Point3D start,     // linker Startpunkt der Unterkante
            Point3D end,       // rechter Endpunkt der Unterkante
            double wallLength, // 2D-Laenge [m]
            double zMin,       // unterster Z-Wert des Polygons
            double zMax        // hoechster Z-Wert des Polygons
    ) {}

    /** Horizontale Fenster-Offsets fuer alle Wandabschnitte einer Reihe. */
    private record HorizResult(
            List<double[]> sectionOffsets, // absolute Offsets von edgeStart pro Abschnitt
            int windowsPerRow              // Summe aller Fenster pro Reihe
    ) {}

    // ==================== Wand-Verarbeitung ====================

    /**
     * Orchestrator: analysiert eine Wand und platziert Fenster.
     * Delegiert alle Teilschritte an spezialisierte Hilfsmethoden.
     */
    private void processWall(WallSurface wall, String geschoss,
            ModuleParameters.WindowParams wp, GenerationStats stats) {

        // 1. Polygon lesen
        Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
        if (wallPoly == null) { stats.wallsSkipped++; stats.skipNoPoly++; return; }
        List<Point3D> allPoints = CityGmlUtils.toPoints(wallPoly);
        List<Point3D> open = CityGmlUtils.removeClosingPoint(allPoints);
        if (open.size() < 3) { stats.wallsSkipped++; stats.skipNoPoly++; return; }

        // 2. Unterkante ermitteln
        BottomEdge edge = resolveBottomEdge(wall, open, stats);
        if (edge == null) return;
        double dx = edge.end().x - edge.start().x;
        double dy = edge.end().y - edge.start().y;
        double dirX = dx / edge.wallLength();
        double dirY = dy / edge.wallLength();

        // 3. Geschoss-Strategie + BA-Vorpruefung
        StoreyWindowStrategy strategy = strategyFor(geschoss);
        if ("BA".equals(geschoss)) {
            String zMaxAslStr = CityGmlUtils.getStringAttribute(wall, "Z_MAX_ASL");
            if (zMaxAslStr == null) { stats.wallsSkipped++; stats.skipBaNoZMaxAsl++; return; }
        }
        double usableHeight = strategy.usableHeight(wall, edge.zMax() - edge.zMin(), edge.zMin());
        double terrainZ = strategy.terrainZ(wall);

        // WP=2: Fenster nur oberhalb Z_Fenster_ASL (Hoehe der Nachbarwand).
        // effectiveFloorZ ist der unterste Z-Wert ab dem Fenster platziert werden duerfen.
        double effectiveFloorZ = edge.zMin();
        String windowPref = CityGmlUtils.getStringAttribute(wall, "WindowPreference");
        if ("2".equals(windowPref)) {
            String zFensterAslStr = CityGmlUtils.getStringAttribute(wall, "Z_Fenster_ASL");
            if (zFensterAslStr != null) {
                try {
                    double zFensterAsl = Double.parseDouble(zFensterAslStr);
                    if (zFensterAsl >= edge.zMax()) {
                        // Segment komplett durch Nachbarwand verdeckt
                        stats.wallsSkipped++; stats.skipNoWindowPref++; return;
                    }
                    if (zFensterAsl > edge.zMin()) {
                        usableHeight -= (zFensterAsl - edge.zMin());
                        effectiveFloorZ = zFensterAsl;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 4. Groessen-Checks
        double vDist = ModuleParameters.WindowParams.safeValue(wp.vDistFloorWindow);
        if (usableHeight < vDist + wp.windowHeight) {
            stats.wallsSkipped++; stats.skipTooLow++; return;
        }
        double hDistMin = ModuleParameters.WindowParams.safeValue(wp.hDistMinWallWindow);
        if (edge.wallLength() < 2 * hDistMin + wp.windowWidth) {
            stats.wallsSkipped++; stats.skipTooShort++; return;
        }

        // 5. WallContext zusammenstellen
        List<double[]> sections = strategy.sections(wall, edge.start(), dirX, dirY, edge.wallLength(), wp);
        WallContext ctx = new WallContext(wall, geschoss, wp, open,
                edge.start(), dirX, dirY, edge.wallLength(),
                edge.zMin(), effectiveFloorZ, usableHeight, terrainZ, sections);

        // 6. Horizontale Offsets
        HorizResult horiz = computeHorizontalOffsets(ctx, stats);
        if (horiz == null) return;

        // 7. Wandflaeche (fuer WWR-Check und FACEAREA-Korrektur)
        double wallArea = resolveWallArea(ctx);

        // 8. Fensterreihen (mit WWR-Trim)
        List<double[]> rows = computeRowPositions(ctx, horiz, wallArea, stats);
        if (rows.isEmpty()) return;

        // 9. PiP-Check: Fenster muessen vollstaendig im Wandpolygon liegen
        List<double[]> validWindows = collectValidWindows(ctx, rows, horiz, stats);
        if (validWindows.isEmpty()) { stats.wallsSkipped++; stats.skipPipFail++; return; }

        // 10. Innere Ringe + WindowSurface erzeugen
        placeWindowSurfaces(ctx, wallPoly, validWindows, wallArea, rows.size(), stats);
    }

    // ==================== Wand-Verarbeitung Hilfsmethoden ====================

    /**
     * Ermittelt Start- und Endpunkt der Unterkante (laengste Kante bei Z_min).
     * Gibt null zurueck wenn weniger als 2 Punkte auf der Unterkante liegen.
     */
    private static BottomEdge resolveBottomEdge(WallSurface wall, List<Point3D> open,
            GenerationStats stats) {
        double[] zRange = CityGmlUtils.getZRange(open);
        double zMin = zRange[0];
        double zMax = zRange[1];
        double zTol = 0.01;

        List<Integer> bottomIndices = new ArrayList<>();
        for (int i = 0; i < open.size(); i++) {
            if (Math.abs(open.get(i).z - zMin) < zTol) bottomIndices.add(i);
        }

        if (bottomIndices.size() < 2) {
            log.warn("Weniger als 2 Punkte an Unterkante fuer Wand {}", wall.getId());
            stats.wallsSkipped++;
            stats.skipNoBottomEdge++;
            return null;
        }

        // Paar mit maximalem 2D-Abstand waehlen
        int startIdx = bottomIndices.get(0);
        int endIdx = bottomIndices.get(1);
        double maxDist2D = 0;
        for (int i = 0; i < bottomIndices.size(); i++) {
            for (int j = i + 1; j < bottomIndices.size(); j++) {
                double d = CityGmlUtils.calculateEdgeLength2D(
                        open.get(bottomIndices.get(i)), open.get(bottomIndices.get(j)));
                if (d > maxDist2D) {
                    maxDist2D = d;
                    startIdx = bottomIndices.get(i);
                    endIdx = bottomIndices.get(j);
                }
            }
        }
        return new BottomEdge(open.get(startIdx), open.get(endIdx), maxDist2D, zMin, zMax);
    }

    /**
     * Berechnet horizontale Fenster-Offsets fuer alle Wandabschnitte.
     * Gibt null zurueck wenn kein einziges Fenster in eine Reihe passt.
     */
    private static HorizResult computeHorizontalOffsets(WallContext ctx, GenerationStats stats) {
        List<double[]> sectionOffsets = new ArrayList<>();
        int windowsPerRow = 0;

        for (double[] sec : ctx.sections()) {
            double secStart = sec[0];
            double secLength = sec[1] - sec[0];
            int count = calculateWindowCount(secLength, ctx.wp());
            if (count < 1) continue;

            double[] relOffsets = calculateWindowOffsets(count, secLength, ctx.wp());
            double[] absOffsets = new double[relOffsets.length];
            for (int i = 0; i < relOffsets.length; i++) absOffsets[i] = secStart + relOffsets[i];
            sectionOffsets.add(absOffsets);
            windowsPerRow += relOffsets.length;
        }

        if (windowsPerRow < 1) {
            stats.wallsSkipped++;
            stats.skipNoFit++;
            return null;
        }
        return new HorizResult(sectionOffsets, windowsPerRow);
    }

    /**
     * Liest die Wandflaeche aus FACEAREA oder berechnet sie als Fallback aus dem Polygon.
     */
    private static double resolveWallArea(WallContext ctx) {
        String faceAreaStr = CityGmlUtils.getStringAttribute(ctx.wall(), "FACEAREA");
        if (faceAreaStr != null) {
            try { return Double.parseDouble(faceAreaStr); } catch (NumberFormatException ignored) {}
        }
        return CityGmlUtils.calculateWallArea(ctx.open());
    }

    /**
     * Berechnet die Z-Positionen aller Fensterreihen und trimmt sie auf den WWR-Grenzwert.
     * Gibt eine leere Liste zurueck wenn keine Reihe den Terrain-Filter besteht (BA)
     * oder keine vertikal passt.
     */
    private List<double[]> computeRowPositions(WallContext ctx, HorizResult horiz,
            double wallArea, GenerationStats stats) {
        double vDist = ModuleParameters.WindowParams.safeValue(ctx.wp().vDistFloorWindow);
        double wallTopZ = ctx.floorZ() + ctx.usableHeight();

        List<double[]> rows = new ArrayList<>();
        // BA: Fensterreihen ab Gelaendeoberflaehe (TIC) + Bruestungshoehe starten.
        // Verhindert (a) Fenster unterhalb der Oberflaeche und
        // (b) unnoetige Iteration durch den unterirdischen Wandbereich.
        // GF/UF: kein terrainZ (NaN) → Start wie bisher ab Fussboden.
        double rowStartZ = !Double.isNaN(ctx.terrainZ()) ? ctx.terrainZ() : ctx.floorZ();
        double rowBottomZ = CityGmlUtils.roundZ(rowStartZ + vDist);
        double rowTopZ = CityGmlUtils.roundZ(rowBottomZ + ctx.wp().windowHeight);

        while (rowTopZ <= wallTopZ + 0.001) {
            rows.add(new double[]{rowBottomZ, rowTopZ});
            rowBottomZ = CityGmlUtils.roundZ(rowTopZ + vDist);
            rowTopZ = CityGmlUtils.roundZ(rowBottomZ + ctx.wp().windowHeight);
        }

        if (rows.isEmpty()) {
            stats.wallsSkipped++;
            stats.skipTooLow++;
            return rows;
        }

        // WWR-Check: ueberzaehlige Reihen von oben entfernen
        if (wallArea > 0) {
            int numRows = rows.size();
            double winW = ctx.wp().windowWidth;
            double winH = ctx.wp().windowHeight;
            double windowArea = numRows * horiz.windowsPerRow() * winW * winH;
            double wwr = windowArea / wallArea;

            while (wwr > MAX_WWR && numRows > 1) {
                numRows--;
                wwr = numRows * horiz.windowsPerRow() * winW * winH / wallArea;
                stats.wwrWarnings++;
            }

            if (wwr > MAX_WWR) {
                log.warn("WWR={} > {} an Wand {} (Geschoss={}, {} Fenster, Wandflaeche={})",
                        CityGmlUtils.formatNum(wwr), CityGmlUtils.formatNum(MAX_WWR),
                        ctx.wall().getId(), ctx.geschoss(),
                        numRows * horiz.windowsPerRow(), CityGmlUtils.formatNum(wallArea));
                stats.wwrWarnings++;
            }
            rows = rows.subList(0, numRows);
        }
        return rows;
    }

    /**
     * Fuehrt den Point-in-Polygon-Check fuer alle Fenster-Kandidaten durch.
     * Fenster, deren mindestens eine Ecke ausserhalb liegt (Giebelwaende),
     * werden verworfen und in stats.gableWindowsDropped gezaehlt.
     */
    private static List<double[]> collectValidWindows(WallContext ctx,
            List<double[]> rowZPositions, HorizResult horiz, GenerationStats stats) {
        // 2D-Projektion der Wandpunkte: u entlang Unterkante, v = z - zMin
        double[][] wallPoly2D = new double[ctx.open().size()][2];
        for (int i = 0; i < ctx.open().size(); i++) {
            Point3D p = ctx.open().get(i);
            wallPoly2D[i][0] = (p.x - ctx.edgeStart().x) * ctx.dirX()
                             + (p.y - ctx.edgeStart().y) * ctx.dirY();
            wallPoly2D[i][1] = p.z - ctx.zMin();
        }

        List<double[]> validWindows = new ArrayList<>();
        for (double[] rowZ : rowZPositions) {
            double wBottomZ = rowZ[0];
            double wTopZ    = rowZ[1];
            double vBottom  = wBottomZ - ctx.zMin();
            double vTop     = wTopZ    - ctx.zMin();

            for (double[] offsets : horiz.sectionOffsets()) {
                for (double hOffset : offsets) {
                    double uLeft  = hOffset;
                    double uRight = hOffset + ctx.wp().windowWidth;
                    if (CityGmlUtils.pointInPolygon2D(uLeft,  vBottom, wallPoly2D)
                     && CityGmlUtils.pointInPolygon2D(uRight, vBottom, wallPoly2D)
                     && CityGmlUtils.pointInPolygon2D(uRight, vTop,    wallPoly2D)
                     && CityGmlUtils.pointInPolygon2D(uLeft,  vTop,    wallPoly2D)) {
                        validWindows.add(new double[]{hOffset, wBottomZ, wTopZ});
                    } else {
                        stats.gableWindowsDropped++;
                    }
                }
            }
        }
        return validWindows;
    }

    /**
     * Prueft ob der Aussen-Ring eines Wandpolygons CCW orientiert ist.
     * Projiziert die Punkte auf die 2D-Wandflaeche
     * (u = horizontal entlang Unterkante, v = Z) und berechnet das Vorzeichen
     * der Flaeche via Shoelace-Formel. Positives Vorzeichen = CCW.
     *
     * Saechsische LoD2-Daten (GF/UF-Waende): CW (Normale zeigt nach innen).
     * Generierte Kellerwaende (BA): CCW (Normale zeigt nach aussen, Standard GML).
     */
    private static boolean isExteriorRingCCW(List<Point3D> open, Point3D edgeStart,
            double dirX, double dirY) {
        double area2 = 0;
        int n = open.size();
        for (int i = 0; i < n; i++) {
            Point3D a = open.get(i);
            Point3D b = open.get((i + 1) % n);
            double ua = (a.x - edgeStart.x) * dirX + (a.y - edgeStart.y) * dirY;
            double ub = (b.x - edgeStart.x) * dirX + (b.y - edgeStart.y) * dirY;
            area2 += (ua * b.z - ub * a.z);
        }
        return area2 > 0;
    }

    /**
     * Erzeugt innere Polygon-Ringe und WindowSurface-Objekte fuer alle validen Fenster.
     * Der innere Ring wird entgegengesetzt zum Aussen-Ring orientiert (GML-Pflicht).
     * Aktualisiert die FACEAREA der Wand (Wandflaeche minus Fensterflaechen).
     */
    private static void placeWindowSurfaces(WallContext ctx, Polygon wallPoly,
            List<double[]> validWindows, double wallArea, int numRows, GenerationStats stats) {
        String wallFaceId = CityGmlUtils.getStringAttribute(ctx.wall(), "BldgFaceID");
        if (wallFaceId == null) {
            wallFaceId = ctx.wall().getId() != null ? ctx.wall().getId() : "unknown";
        }

        // Orientierung des Aussen-Rings einmalig bestimmen; Innen-Ring muss entgegengesetzt sein.
        // Saechsische LoD2-Daten (GF/UF): CW-Aussen-Ring → Innen-Ring CCW (BL->BR->TR->TL)
        // Generierte Kellerwaende  (BA):   CCW-Aussen-Ring → Innen-Ring CW  (BL->TL->TR->BR)
        boolean extCCW = isExteriorRingCCW(ctx.open(), ctx.edgeStart(), ctx.dirX(), ctx.dirY());

        int windowIdx = 0;
        for (double[] win : validWindows) {
            double hOffset  = win[0];
            double wBottomZ = win[1];
            double wTopZ    = win[2];
            windowIdx++;

            Point3D bl = new Point3D(ctx.edgeStart().x + hOffset                        * ctx.dirX(),
                                     ctx.edgeStart().y + hOffset                        * ctx.dirY(), wBottomZ);
            Point3D br = new Point3D(ctx.edgeStart().x + (hOffset + ctx.wp().windowWidth) * ctx.dirX(),
                                     ctx.edgeStart().y + (hOffset + ctx.wp().windowWidth) * ctx.dirY(), wBottomZ);
            Point3D tr = new Point3D(ctx.edgeStart().x + (hOffset + ctx.wp().windowWidth) * ctx.dirX(),
                                     ctx.edgeStart().y + (hOffset + ctx.wp().windowWidth) * ctx.dirY(), wTopZ);
            Point3D tl = new Point3D(ctx.edgeStart().x + hOffset                        * ctx.dirX(),
                                     ctx.edgeStart().y + hOffset                        * ctx.dirY(), wTopZ);

            // Innerer Ring: entgegengesetzt zum Aussen-Ring orientieren (GML-Pflicht)
            List<Point3D> innerRing = extCCW
                    ? List.of(bl, tl, tr, br)   // exterior CCW → interior CW  (BL->TL->TR->BR)
                    : List.of(bl, br, tr, tl);  // exterior CW  → interior CCW (BL->BR->TR->TL)
            wallPoly.getInterior().add(new AbstractRingProperty(
                    CityGmlUtils.createLinearRing(innerRing)));

            // WindowSurface: Orientierung GLEICH wie Aussenring (= ENTGEGEN dem Innenring)
            // extCCW → WindowSurface CCW (BL->BR->TR->TL)
            // extCW  → WindowSurface CW  (BL->TL->TR->BR)
            // Nur so werden die Kanten des Innenrings im Solid genau einmal in
            // entgegengesetzter Richtung vom Fenster-Polygon abgedeckt → manifold.
            List<Point3D> winSurfacePoints = extCCW
                    ? List.of(bl, br, tr, tl)   // CCW (Standard)
                    : List.of(bl, tl, tr, br);  // CW  (Sachsen LoD2)
            String windowId = wallFaceId + "_Win_" + windowIdx;
            WindowSurface windowSurface = new WindowSurface();
            windowSurface.setId("Face_" + windowId);
            CityGmlUtils.setGmlName(windowSurface, "LOD3_Window");
            windowSurface.setLod3MultiSurface(CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(
                    CityGmlUtils.createPolygon(winSurfacePoints)));
            CityGmlUtils.addStringAttribute(windowSurface, "BldgFaceID", windowId);
            CityGmlUtils.addStringAttribute(windowSurface, "FACEAREA",
                    CityGmlUtils.formatNum(ctx.wp().windowWidth * ctx.wp().windowHeight));
            CityGmlUtils.addStringAttribute(windowSurface, "Geschoss", ctx.geschoss());
            ctx.wall().getFillingSurfaces().add(new AbstractFillingSurfaceProperty(windowSurface));
            stats.windowsCreated++;
        }

        // FACEAREA der Wand aktualisieren
        double totalWindowArea = validWindows.size() * ctx.wp().windowWidth * ctx.wp().windowHeight;
        if (wallArea > 0) {
            CityGmlUtils.setStringAttribute(ctx.wall(), "FACEAREA",
                    CityGmlUtils.formatNum(Math.max(0, wallArea - totalWindowArea)));
        }

        stats.wallsWithWindows++;
        stats.addWindowsForGeschoss(ctx.geschoss(), validWindows.size());
        log.debug("Wand {}: Geschoss={}, L={}, H_nutzbar={}, Abschnitte={}, Reihen={}, Fenster={} (dropped={})",
                ctx.wall().getId(), ctx.geschoss(),
                CityGmlUtils.formatNum(ctx.wallLength()),
                CityGmlUtils.formatNum(ctx.usableHeight()),
                ctx.sections().size(), numRows, validWindows.size(), stats.gableWindowsDropped);
    }

    // ==================== GF Tuer-Aufspaltung ====================

    /**
     * Extrahiert freie Wandabschnitte auf einer GF-Wand unter Beruecksichtigung
     * vorhandener Tueren (DoorSurface als FillingSurface).
     *
     * Fuer jede Tuer wird deren horizontale Ausdehnung (Projektion auf die Unterkante)
     * ermittelt und ein Pufferbereich (HDistDoWi) links und rechts freigehalten.
     * Die verbleibenden Abschnitte stehen fuer Fenster zur Verfuegung.
     *
     * @return Liste von [sectionStart, sectionEnd] relativ zum edgeStart
     */
    private List<double[]> extractFreeSections(WallSurface wall, Point3D edgeStart,
            double dirX, double dirY, double wallLength,
            ModuleParameters.WindowParams wp) {

        double hDistDoWi = ModuleParameters.WindowParams.safeValue(wp.hDistDoorWindow);

        // --- Tuer-Positionen sammeln ---
        List<double[]> doorRanges = new ArrayList<>(); // [leftOffset, rightOffset]

        for (AbstractFillingSurfaceProperty fsp : wall.getFillingSurfaces()) {
            AbstractFillingSurface fs = fsp.getObject();
            if (!(fs instanceof DoorSurface)) continue;

            // Tuer-Polygon auslesen
            Polygon doorPoly = null;
            if (fs.getLod3MultiSurface() != null
                    && fs.getLod3MultiSurface().getObject() != null) {
                var members = fs.getLod3MultiSurface().getObject()
                        .getSurfaceMember();
                if (!members.isEmpty()
                        && members.get(0).getObject() instanceof Polygon p) {
                    doorPoly = p;
                }
            }
            if (doorPoly == null) continue;

            List<Point3D> doorPts = CityGmlUtils.toPoints(doorPoly);
            List<Point3D> doorOpen = CityGmlUtils.removeClosingPoint(doorPts);

            // Horizontale Projektion aller Tuer-Punkte auf die Wandrichtung
            double minProj = Double.MAX_VALUE;
            double maxProj = -Double.MAX_VALUE;
            for (Point3D p : doorOpen) {
                double proj = (p.x - edgeStart.x) * dirX + (p.y - edgeStart.y) * dirY;
                minProj = Math.min(minProj, proj);
                maxProj = Math.max(maxProj, proj);
            }

            doorRanges.add(new double[]{minProj, maxProj});
        }

        // Keine Tueren → gesamte Wand als ein Abschnitt
        if (doorRanges.isEmpty()) {
            return List.of(new double[]{0, wallLength});
        }

        // Tuer-Ranges nach Position sortieren
        doorRanges.sort((a, b) -> Double.compare(a[0], b[0]));

        // --- Freie Abschnitte berechnen ---
        List<double[]> sections = new ArrayList<>();
        double cursor = 0;

        for (double[] door : doorRanges) {
            double doorLeft = door[0];
            double doorRight = door[1];

            // Abschnitt links der Tuer (mit Puffer)
            double secEnd = doorLeft - hDistDoWi;
            if (secEnd > cursor + 0.01) {
                sections.add(new double[]{cursor, secEnd});
            }

            // Cursor hinter Tuer + Puffer
            cursor = doorRight + hDistDoWi;
        }

        // Abschnitt rechts der letzten Tuer
        if (cursor < wallLength - 0.01) {
            sections.add(new double[]{cursor, wallLength});
        }

        return sections;
    }

    // ==================== Fenster-Berechnung ====================

    /**
     * Berechnet die maximale Fensteranzahl fuer einen Wandabschnitt.
     *
     * Formel: n_max = floor((length - 2 * HDistMinWaWi + HDistWiWi) / (WiLen + HDistWiWi))
     * Optional begrenzt durch MaxWiPerRow.
     */
    static int calculateWindowCount(double availableLength, ModuleParameters.WindowParams wp) {
        double wiLen = wp.windowWidth;
        double hDistMin = ModuleParameters.WindowParams.safeValue(wp.hDistMinWallWindow);
        double hDistWiWi = ModuleParameters.WindowParams.safeValue(wp.hDistWindowWindow);

        double numerator = availableLength - 2 * hDistMin + hDistWiWi;
        double denominator = wiLen + hDistWiWi;

        if (denominator <= 0) return 0;

        int n = (int) Math.floor(numerator / denominator);
        if (n < 1) return 0;

        // MaxWiPerRow begrenzen (falls gesetzt und > 0)
        if (wp.maxWindowsPerRow != null && wp.maxWindowsPerRow > 0) {
            n = Math.min(n, wp.maxWindowsPerRow);
        }

        return n;
    }

    /**
     * Berechnet die horizontalen Offsets (Abstand Wandanfang → linke Fensterkante)
     * fuer n Fenster auf einem Wandabschnitt.
     *
     * Verwendung: HDistWaWi als Startabstand, HDistWiWi zwischen Fenstern.
     * Pruefung: letzte Fensterkante + HDistMinWaWi <= Abschnittlaenge.
     */
    static double[] calculateWindowOffsets(int count, double sectionLength,
            ModuleParameters.WindowParams wp) {
        double wiLen = wp.windowWidth;
        double hDistWaWi = ModuleParameters.WindowParams.safeValue(wp.hDistWallWindow);
        double hDistWiWi = ModuleParameters.WindowParams.safeValue(wp.hDistWindowWindow);
        double hDistMin = ModuleParameters.WindowParams.safeValue(wp.hDistMinWallWindow);

        // --- Primaer: Platzierung ab hDistWaWi ---
        double[] offsets = new double[count];
        for (int k = 0; k < count; k++) {
            offsets[k] = hDistWaWi + k * (wiLen + hDistWiWi);
        }

        // Pruefung: passt das letzte Fenster + Mindestabstand?
        int fittingCount = count;
        while (fittingCount > 0) {
            double lastRight = offsets[fittingCount - 1] + wiLen;
            if (lastRight + hDistMin <= sectionLength + 0.001) break;
            fittingCount--;
        }

        if (fittingCount == count) {
            return offsets; // Alle Fenster passen bei bevorzugtem Offset
        }

        // --- Fallback: Fenster zentriert platzieren ---
        // Wenn hDistWaWi-Platzierung nicht passt, Fensterblock zentrieren
        // (Randabstand wird dann symmetrisch >= hDistMin)
        for (int n = count; n >= 1; n--) {
            double totalWidth = n * wiLen + Math.max(0, n - 1) * hDistWiWi;
            double startOffset = (sectionLength - totalWidth) / 2.0;
            if (startOffset >= hDistMin - 0.001) {
                double[] centered = new double[n];
                for (int k = 0; k < n; k++) {
                    centered[k] = startOffset + k * (wiLen + hDistWiWi);
                }
                return centered;
            }
        }

        // Nichts passt (sollte nach calculateWindowCount nicht vorkommen)
        return new double[0];
    }

    // ==================== Geschoss-/Parameter-Logik ====================

    /**
     * Prueft ob ein Geschoss fuer Fenster in Frage kommt.
     * Ausgeschlossen: 1000 (Dach), 2000 (Boden), null/leer.
     */
    private static boolean isWindowEligibleGeschoss(String geschoss) {
        if (geschoss == null || geschoss.isBlank()) return false;
        if ("1000".equals(geschoss)) return false;
        if ("2000".equals(geschoss)) return false;
        return "GF".equals(geschoss)
                || geschoss.startsWith("UF_")
                || "BA".equals(geschoss);
    }

    // ==================== Statistiken ====================

    public static class GenerationStats {
        public int buildingsProcessed = 0;
        public int windowsCreated = 0;
        public int wallsWithWindows = 0;
        public int wallsSkipped = 0;
        public int gableWindowsDropped = 0;
        public int wwrWarnings = 0;

        // Detaillierte Skip-Gruende
        public int skipNoWindowPref = 0;   // WindowPreference null oder "0"
        public int skipCoveredByPart = 0;  // Hauptgebaeude-Wand geometrisch vom BuildingPart ueberdeckt
        public int skipBaNoZMax = 0;       // BA ohne Z_Max oder Z_Max <= 0
        public int skipNoParams = 0;       // Keine oder ungueltige WindowParams
        public int skipNoPoly = 0;         // Kein Polygon oder < 3 Punkte
        public int skipNoBottomEdge = 0;   // Keine Unterkante gefunden
        public int skipTooShort = 0;       // Wand zu kurz fuer Fenster
        public int skipTooLow = 0;         // Wand zu niedrig fuer Fenster
        public int skipNoFit = 0;          // Kein Fenster passt (incl. Section-Fit)
        public int skipBaNoZMaxAsl = 0;    // BA ohne Z_MAX_ASL
        public int skipPipFail = 0;        // Alle Fenster PiP-Fail (Giebel)

        // Per-Geschoss Fenster-Zaehler
        public final java.util.Map<String, int[]> perGeschoss = new java.util.TreeMap<>();

        /** Zaehlt Fenster fuer ein Geschoss. Dekrementiert den Skip-Zaehler. */
        public void addWindowsForGeschoss(String geschoss, int count) {
            int[] c = perGeschoss.computeIfAbsent(geschoss, k -> new int[]{0, 0});
            c[0] += count;
            c[1]--; // war vorab als Skip gezaehlt
        }

        /** Zaehlt eine uebersprungene Wand fuer ein Geschoss. */
        public void addSkipForGeschoss(String geschoss) {
            perGeschoss.computeIfAbsent(geschoss, k -> new int[]{0, 0})[1]++;
        }

        public String toSummary() {
            return String.format(
                "Skip-Gruende: WP0/null=%d, coveredByPart=%d, BA_noZMax=%d, noParams=%d, " +
                "noPoly=%d, noBottom=%d, tooShort=%d, tooLow=%d, noFit=%d, " +
                "BA_noZMaxAsl=%d, pipFail=%d",
                skipNoWindowPref, skipCoveredByPart, skipBaNoZMax, skipNoParams,
                skipNoPoly, skipNoBottomEdge, skipTooShort, skipTooLow, skipNoFit,
                skipBaNoZMaxAsl, skipPipFail);
        }

        public String toGeschossSummary() {
            StringBuilder sb = new StringBuilder("Per-Geschoss: ");
            for (var entry : perGeschoss.entrySet()) {
                int[] counts = entry.getValue();
                sb.append(entry.getKey())
                  .append("(win=").append(counts[0])
                  .append("/skip=").append(counts[1])
                  .append(") ");
            }
            return sb.toString().trim();
        }
    }
}
