package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.util.BuildingQueryUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.GeometryUtils;
import de.mpsc.lod2tolod3.util.OpeningUtils;
import de.mpsc.lod2tolod3.util.Point3D;
import de.mpsc.lod2tolod3.util.SolidShellUtils;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.RoofSurface;
import org.citygml4j.core.model.construction.WindowSurface;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * Schritt 5c: Dachfenster-Generator. Platziert Dachflaechenfenster (flach in der Dachschraege
 * liegend, keine Gauben) auf geneigten RoofSurface-Flaechen, analog zur Wandfenster-Logik in
 * {@link WindowGenerator}, aber mit einer schraegen statt senkrechten lokalen Ebene (siehe
 * Doku.md, Abschnitt "Schritt 5c: Dachfenster").
 *
 * Usage:
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.RoofWindowGenerator input.gml jsonDir [output.gml]
 */
public class RoofWindowGenerator extends AbstractGenerator<RoofWindowGenerator.GenerationStats> {

    /** Maximaler Window-to-Wall Ratio je Dachflaeche (60 %, wie bei Waenden — keine
     * dachspezifische Vorgabe vorhanden). */
    private static final double MAX_WWR = 0.60;

    /** Toleranz fuer Flachdach-Erkennung, identisch zu {@code BuildingQueryUtils.getRoofZRange}. */
    private static final double FLAT_ROOF_TOLERANCE = 0.05;

    /** Z-Toleranz, innerhalb derer zwei Dachpunkte als gemeinsame Traufkante gelten. Die 1 cm der
     * Wand-Unterkanten sind fuer LoD2-Dachtraufen zu eng: auf der Testkachel sind ~800 von 12.400
     * geneigten Dachflaechen um 1–5 cm aus der Waage (Digitalisierung), ohne Traufkante gibt es
     * dort kein Dachfenster (z.B. imX: 2 cm). Seit die u-Achse den echten Z-Anteil der Traufe
     * traegt (siehe Doku.md "Dachfenster exakt in der Dachebene"), ist eine leicht geneigte
     * Traufe geometrisch unkritisch. Bewusst nur 2 cm: 5 cm brachte tile-weit keine weitere
     * Verbesserung ohne Nebenwirkung (mehr Fenster auf gekerbten Flaechen -> CityDoctor2-
     * Trianguations-Fehlalarme, siehe Doku.md). */
    private static final double ROOF_EAVE_Z_TOL = 0.02;

    /** Maximale Verdrehung (Abstand zur Ausgleichsebene) einer Dachflaeche, in die noch
     * Fensterloecher geschnitten werden. Darueber ist die Flaeche schon in den Quelldaten
     * kaputt (CityDoctor2 flaggt ab 1,4 mm, val3dity ab 10 mm) — Loecher machen es nur
     * schlimmer. 5 mm trifft auf der Testkachel 4 Flaechen / 4 Fenster. */
    private static final double ROOF_MAX_PLANE_DEVIATION = 0.005;

    public static void main(String[] args) {
        RoofWindowGenerator gen = new RoofWindowGenerator();
        try {
            gen.runCli(args);
        } catch (Exception e) {
            gen.log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    @Override protected String outputSuffix() { return "_roofwindows"; }
    @Override protected String displayName()  { return "Dachfenster-Generator"; }
    @Override protected GenerationStats newStats() { return new GenerationStats(); }

    @Override
    protected void logResult(GenerationStats stats) {
        log.info("Dachfenster erzeugt: {}", stats.roofWindowsCreated);
        log.info("Dachflaechen mit Fenstern: {}", stats.roofsWithWindows);
        log.info("Dachflaechen uebersprungen: {}", stats.roofsSkipped);
        log.info("WWR-Warnungen: {}", stats.wwrWarnings);
        log.info(stats.toSummary());
    }

    // ==================== Gebaeude-Verarbeitung ====================

    @Override
    protected void processBuilding(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {

        BuildingParams bp = resolveParams(building, paramLoader).orElse(null);
        if (bp == null) return;
        ModuleParameters.Roof roof = bp.params().getRoof();
        ModuleParameters.WindowParams wp = roof != null ? roof.window : null;
        if (wp == null || !wp.isValid()) {
            return; // Modul ohne Dachfenster-Parameter — kein Sondercode, still ueberspringen
        }

        for (var target : BuildingQueryUtils.getBuildingTargets(building)) {
            processAbstractBuilding(target, wp, stats);
            SolidShellUtils.rebuildSolidShell(target);
        }
    }

    /** Prueft alle RoofSurfaces eines AbstractBuilding und platziert Dachfenster auf geneigten. */
    private void processAbstractBuilding(AbstractBuilding target,
            ModuleParameters.WindowParams wp, GenerationStats stats) {

        List<RoofSurface> roofs = BuildingQueryUtils.collectBoundariesByType(target, RoofSurface.class);
        for (RoofSurface roof : roofs) {
            processRoof(roof, wp, stats);
        }
    }

    // ==================== Dachflaechen-Verarbeitung ====================

    private void processRoof(RoofSurface roof, ModuleParameters.WindowParams wp,
            GenerationStats stats) {

        // 1. Polygon lesen
        Polygon roofPoly = BuildingQueryUtils.getRoofPolygon(roof);
        if (roofPoly == null) { stats.skip(SkipReason.NO_POLY); return; }
        List<Point3D> allPoints = GeometryUtils.toPoints(roofPoly);
        List<Point3D> open = GeometryUtils.removeClosingPoint(allPoints);
        if (open.size() < 3) { stats.skip(SkipReason.NO_POLY); return; }

        // 2. Flachdach-Erkennung: keine Dachfenster auf flachen Teilflaechen
        double[] zRange = GeometryUtils.getZRange(open);
        if (zRange[1] - zRange[0] < FLAT_ROOF_TOLERANCE) {
            stats.skip(SkipReason.FLAT_ROOF); return;
        }

        // 2b. Verdrehte Traegerflaeche: Dachflaechen, die selbst deutlich aus der Ebene sind
        // (Quelldaten-Verdrehung, z.B. 8 mm bei gY3), bekommen keine Fensterloecher — dort
        // koennen die Loch-Ecken nicht "in der Ebene" liegen, jede Platzierung erzeugt oder
        // verstaerkt Planaritaets-/Validitaetsfehler (val3dity 203, siehe Doku.md).
        if (GeometryUtils.maxPlaneDeviation(open) > ROOF_MAX_PLANE_DEVIATION) {
            stats.skip(SkipReason.NON_PLANAR); return;
        }

        // 3. Traufkante (unterste Kante, wie bei Waenden) — komplexe/unregelmaessige
        // Verschneidungsflaechen ohne 2 Punkte auf zMin (z.B. Kehlflaechen) werden hier
        // automatisch uebersprungen, kein Sondercode noetig.
        GeometryUtils.BottomEdge edge = GeometryUtils.findBottomEdge(open, ROOF_EAVE_Z_TOL);
        if (edge == null) { stats.skip(SkipReason.NO_BOTTOM_EDGE); return; }
        // Traufrichtung als echter 3D-Vektor: findBottomEdge laesst bis 1 cm Hoehenunterschied
        // zwischen den Traufpunkten zu. Eine rein horizontale u-Achse laege dann NICHT in der
        // Dachebene, und die Fensterecken wanderten proportional zu u aus der Ebene heraus
        // (mehrere mm -> GE_P_NON_PLANAR, siehe Doku.md "Dachfenster exakt in der Dachebene").
        double dx = edge.end().x - edge.start().x;
        double dy = edge.end().y - edge.start().y;
        double dz = edge.end().z - edge.start().z;
        double len3 = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double dirX = dx / len3;
        double dirY = dy / len3;
        double dirZ = dz / len3;

        // 4. Aufwaerts-Vektor (Traufe -> First) entlang der Dachschraege
        double[] up = GeometryUtils.computeUpSlopeVector(open, dirX, dirY, dirZ);
        if (up == null) { stats.skip(SkipReason.NO_BOTTOM_EDGE); return; }
        double upX = up[0], upY = up[1], upZ = up[2];

        // 5. Verfuegbare Schraeglaenge (Traufe -> First) dieser Flaeche
        double maxV = 0;
        for (Point3D p : open) {
            double dpx = p.x - edge.start().x, dpy = p.y - edge.start().y, dpz = p.z - edge.start().z;
            double v = dpx * upX + dpy * upY + dpz * upZ;
            if (v > maxV) maxV = v;
        }

        // 6. Groessen-Checks (analog WindowGenerator)
        double vDist = ModuleParameters.WindowParams.safeValue(wp.vDistFloorWindow);
        double vBottom = vDist;
        double vTop = vDist + wp.windowHeight;
        if (vTop > maxV + 0.001) { stats.skip(SkipReason.TOO_LOW); return; }

        double hDistMin = ModuleParameters.WindowParams.safeValue(wp.hDistMinWallWindow);
        if (edge.wallLength() < 2 * hDistMin + wp.windowWidth) {
            stats.skip(SkipReason.TOO_SHORT); return;
        }

        // 7. Horizontale Platzierung entlang der Traufe (identische Arithmetik wie bei Waenden)
        int count = WindowGenerator.calculateWindowCount(edge.wallLength(), wp);
        if (count < 1) { stats.skip(SkipReason.NO_FIT); return; }
        double[] offsets = WindowGenerator.calculateWindowOffsets(count, edge.wallLength(), wp);
        if (offsets.length < 1) { stats.skip(SkipReason.NO_FIT); return; }

        // 8. Validierung: Fenster muss vollstaendig in der (ggf. zum First hin schmaler
        // werdenden) Dachflaeche liegen
        double[][] roofPoly2D = GeometryUtils.projectPlaneTo2D(open, edge.start(), dirX, dirY, dirZ, upX, upY, upZ);
        // Vorhandene Loecher der Dachflaeche (z.B. Gauben-/Schornsteinausschnitte aus den Quelldaten)
        List<double[][]> holes2D = OpeningUtils.projectInteriorRings2D(
                roofPoly, edge.start(), dirX, dirY, dirZ, upX, upY, upZ);
        List<double[]> validWindows = new ArrayList<>();
        for (double hOffset : offsets) {
            double uLeft = hOffset, uRight = hOffset + wp.windowWidth;
            // Kontur-Check mit Seiten-/Oberkanten-Sicherheitsabstand (wie bei Wandfenstern/-tueren,
            // siehe Doku.md "Fenster-Seitenkante-auf-Anbau-Kerbe-Fix") PLUS Durchquerungs-Check: bei
            // einer Dachflaeche, die per Kerbe um eine Gaube herumfuehrt (nicht-konvexe Kontur, "M"-
            // Form analog zu einer Wand unter einem Satteldach mit Gaube), reicht der reine 4-Eck-
            // punkt-Containment-Test nicht — ein Fenster kann komplett innerhalb der Bounding-Box
            // liegen und trotzdem die Kerbe ueberspannen, was die Dachflaeche mit der Gaubenwand/
            // -dach selbst ueberschneidet (GE_S_SELF_INTERSECTION, siehe Doku.md).
            if (OpeningUtils.openingInsideWallSideTopClearance2D(uLeft, uRight, vBottom, vTop, roofPoly2D)
                    && !OpeningUtils.wallContourEntersOpening(uLeft, uRight, vBottom, vTop, roofPoly2D)
                    && !OpeningUtils.openingTouchesHoles2D(uLeft, uRight, vBottom, vTop, holes2D)) {
                validWindows.add(new double[]{uLeft, uRight});
            } else {
                stats.gableRoofWindowsDropped++;
            }
        }
        if (validWindows.isEmpty()) { stats.skip(SkipReason.PIP_FAIL); return; }

        // 9. Erzeugen
        placeRoofWindowSurfaces(roof, roofPoly, open, edge.start(), dirX, dirY, dirZ, upX, upY, upZ,
                validWindows, vBottom, vTop, wp, stats);
    }

    /** Erzeugt innere Polygon-Ringe und WindowSurface-Objekte fuer alle validen Dachfenster. */
    private void placeRoofWindowSurfaces(RoofSurface roof, Polygon roofPoly, List<Point3D> open,
            Point3D origin, double dirX, double dirY, double dirZ, double upX, double upY, double upZ,
            List<double[]> validWindows, double vBottom, double vTop,
            ModuleParameters.WindowParams wp, GenerationStats stats) {

        String roofFaceId = CityGmlUtils.getStringAttribute(roof, "BldgFaceID");
        if (roofFaceId == null) {
            roofFaceId = roof.getId() != null ? roof.getId() : "unknown";
        }

        boolean extCCW = SolidShellUtils.isRingCCWOnPlane(open, origin, dirX, dirY, dirZ, upX, upY, upZ);
        // Ecken zusaetzlich auf die Ausgleichsebene der (ggf. leicht verzogenen) Dachflaeche legen: die
        // Traufebene weicht dort um Bruchteile eines mm ab, Loecher wuerden die Planaritaet sonst verschlechtern.
        double[] roofPlane = GeometryUtils.newellPlane(open);

        int windowIdx = 0;
        for (double[] win : validWindows) {
            double uLeft = win[0], uRight = win[1];
            windowIdx++;

            Point3D bl = onRoofPlane(planePoint(origin, dirX, dirY, dirZ, upX, upY, upZ, uLeft, vBottom), roofPlane);
            Point3D br = onRoofPlane(planePoint(origin, dirX, dirY, dirZ, upX, upY, upZ, uRight, vBottom), roofPlane);
            Point3D tr = onRoofPlane(planePoint(origin, dirX, dirY, dirZ, upX, upY, upZ, uRight, vTop), roofPlane);
            Point3D tl = onRoofPlane(planePoint(origin, dirX, dirY, dirZ, upX, upY, upZ, uLeft, vTop), roofPlane);

            Polygon winPoly = OpeningUtils.addOpeningToWall(roofPoly, bl, br, tr, tl, extCCW);

            String windowId = roofFaceId + "_RoofWin_" + windowIdx;
            WindowSurface windowSurface = new WindowSurface();
            windowSurface.setId("Face_" + windowId);
            CityGmlUtils.setGmlName(windowSurface, "LOD3_RoofWindow");
            windowSurface.setLod3MultiSurface(
                    CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(winPoly));
            CityGmlUtils.addStringAttribute(windowSurface, "BldgFaceID", windowId);
            CityGmlUtils.addStringAttribute(windowSurface, "FACEAREA",
                    GeometryUtils.formatNum(wp.windowWidth * wp.windowHeight));
            CityGmlUtils.addStringAttribute(windowSurface, "Geschoss", "RO");
            roof.getFillingSurfaces().add(new AbstractFillingSurfaceProperty(windowSurface));
            stats.roofWindowsCreated++;
        }

        // FACEAREA der Dachflaeche aktualisieren + WWR-Warnung
        double roofArea = GeometryUtils.calculateWallArea(open);
        String faceAreaStr = CityGmlUtils.getStringAttribute(roof, "FACEAREA");
        if (faceAreaStr != null) {
            try { roofArea = Double.parseDouble(faceAreaStr); } catch (NumberFormatException ignored) {}
        }
        double totalWindowArea = validWindows.size() * wp.windowWidth * wp.windowHeight;
        if (roofArea > 0) {
            CityGmlUtils.setStringAttribute(roof, "FACEAREA",
                    GeometryUtils.formatNum(Math.max(0, roofArea - totalWindowArea)));
            double wwr = totalWindowArea / roofArea;
            if (wwr > MAX_WWR) {
                log.warn("Dachflaeche {}: WWR {} ueberschreitet {} ({} Fenster)",
                        roof.getId(), GeometryUtils.formatNum(wwr), MAX_WWR, validWindows.size());
                stats.wwrWarnings++;
            }
        }

        stats.roofsWithWindows++;
        log.debug("Dachflaeche {}: Fenster={} (dropped={})",
                roof.getId(), validWindows.size(), stats.gableRoofWindowsDropped);
    }

    private static Point3D onRoofPlane(Point3D p, double[] plane) {
        return plane == null ? p : GeometryUtils.projectOntoPlane(p, plane);
    }

    private static Point3D planePoint(Point3D origin, double dirX, double dirY, double dirZ,
            double upX, double upY, double upZ, double u, double v) {
        return new Point3D(
                origin.x + u * dirX + v * upX,
                origin.y + u * dirY + v * upY,
                origin.z + u * dirZ + v * upZ);
    }

    // ==================== Statistiken ====================

    /** Skip-Gruende fuer uebersprungene Dachflaechen (Label = Kuerzel in der Log-Zusammenfassung). */
    public enum SkipReason {
        NO_POLY("noPoly"),               // Kein Polygon oder < 3 Punkte
        FLAT_ROOF("flatRoof"),           // Flachdach-Teilflaeche, keine Dachfenster
        NO_BOTTOM_EDGE("noBottom"),      // Keine Traufkante oder keine gueltige Normale
        TOO_LOW("tooLow"),               // Flaeche zu kurz entlang der Schraege (Traufe->First)
        TOO_SHORT("tooShort"),           // Traufkante zu kurz fuer ein Fenster
        NO_FIT("noFit"),                 // Kein Fenster passt entlang der Traufe
        NON_PLANAR("warped"),            // Traegerflaeche selbst zu stark verdreht (Quelldaten)
        PIP_FAIL("pipFail");             // Alle Kandidaten liegen ausserhalb der Dachkontur

        final String label;
        SkipReason(String label) { this.label = label; }
    }

    public static class GenerationStats extends AbstractGenerator.BaseStats {
        public int roofWindowsCreated = 0;
        public int roofsWithWindows = 0;
        public int roofsSkipped = 0;
        public int gableRoofWindowsDropped = 0;
        public int wwrWarnings = 0;

        public final java.util.EnumMap<SkipReason, Integer> skips =
                new java.util.EnumMap<>(SkipReason.class);

        public void skip(SkipReason reason) {
            roofsSkipped++;
            skips.merge(reason, 1, Integer::sum);
        }

        public String toSummary() {
            StringBuilder sb = new StringBuilder("Skip-Gruende: ");
            boolean first = true;
            for (SkipReason r : SkipReason.values()) {
                if (!first) sb.append(", ");
                sb.append(r.label).append('=').append(skips.getOrDefault(r, 0));
                first = false;
            }
            return sb.toString();
        }
    }
}
