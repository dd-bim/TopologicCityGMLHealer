package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils.Point3D;
import de.mpsc.lod2tolod3.util.DgmLoader;
import de.mpsc.lod2tolod3.util.DgmProvider;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.CeilingSurface;
import org.citygml4j.core.model.construction.GroundSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.core.AbstractSpaceBoundaryProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.geometry.aggregates.MultiCurveProperty;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Schritt 2: Keller-Generator.
 * 
 * Erzeugt fuer Gebaeude mit Keller (BA=true, BA.height > 0):
 * - Kellerwaende (Footprint-Extrusion von H_DGM + heightGr nach unten)
 *   Oberkante = H_DGM + heightGr (oberirdischer Anteil des Kellers)
 *   Unterkante = H_DGM + heightGr - (BA.height + BA.CeHe)
 * - Kellerboden als GroundSurface (bei Unterkante = physische Bodenplatte)
 * - Kellerdecke (CeilingSurface bei H_DGM + heightGr = Unterkante GF)
 * - Original-GroundSurface (H_DGM-Ebene) wird entfernt
 * - lod3TerrainIntersectionCurve (TIC) wird erzeugt
 * 
 * GroundSurface-Behandlung:
 *   Die urspruengliche GroundSurface (bei H_DGM, aus LoD2) wird entfernt.
 *   Der Kellerboden wird als neue GroundSurface erzeugt — das ist die physische
 *   Bodenplatte des Gebaeudes. Die Gelaendeschnittlinie (TIC) dokumentiert,
 *   wo die Gebaeudehuelle das Gelaende (DGM) schneidet.
 *
 * TerrainIntersectionCurve (TIC):
 *   - Ohne DGM: Flacher Ring bei Z = H_DGM (Fallback)
 *   - Mit DGM:  Bilinear interpolierte Hoehen pro Footprint-Vertex aus dem DGM
 *   Die TIC wird auf dem Building/BuildingPart verankert (lod3TerrainIntersectionCurve).
 * 
 * heightGr = oberirdischer Anteil des Kellers (GF.heightAboveGround).
 * Der Keller reicht also von heightGr ueber Gelaende bis
 * (BA.height + BA.CeHe - heightGr) unter Gelaende.
 * 
 * Alle neuen Flaechen erhalten:
 * - Geschoss-Tag (BA)
 * - Lage-Tag (belowGround)
 * - Geometrische Attribute (Z_MAX_ASL, Z_MIN_ASL, FACEAREA, NORMAL_AZI, etc.)
 * - WindowPreference (uebernommen von zugehoerigen Original-Waenden)
 * 
 * Benennung: Face_{targetId}_BA_{Typ}_{LaufendeNummer}
 * Typ: Ground, Wall, Ceiling
 * 
 * Erwartet als Input eine LoD3-hochgestufte CityGML-Datei (von Lod2ToLod3Promoter).
 * 
 * Usage:
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.BasementGenerator input.gml jsonDir [output.gml] [dgm.asc]
 */
public class BasementGenerator {
    private static final Logger log = LoggerFactory.getLogger(BasementGenerator.class);
    private static final String BASEMENT_MARKER = "LoD3_Basement";

    /** Toleranz fuer WindowPreference-Zuordnung Kellerwand ↔ Original-Wand (50cm). */
    private static final double WINDOW_PREFERENCE_TOLERANCE = 0.5;

    /** Optionaler DGM-Provider fuer detaillierte TIC-Hoehen. */
    private DgmProvider dgm;

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                System.err.println("Usage: BasementGenerator <input.gml> <jsonDir> [output.gml] [dgm.asc]");
                System.exit(1);
            }

            Path inputPath = Paths.get(args[0]);
            Path jsonDir = Paths.get(args[1]);
            Path outputPath = CityGmlUtils.resolveOutputPath(inputPath, "_basement",
                    args.length >= 3 ? Paths.get(args[2]) : null);

            // Optionales DGM laden (Datei, ZIP oder Verzeichnis)
            DgmProvider dgm = null;
            if (args.length >= 4) {
                Path dgmPath = Paths.get(args[3]);
                if (Files.exists(dgmPath)) {
                    dgm = DgmLoader.load(dgmPath);
                } else {
                    log.warn("DGM-Pfad nicht gefunden: {} — verwende Flat-TIC", dgmPath);
                }
            }

            Files.createDirectories(outputPath.getParent());

            log.info("=== Keller-Generator ===");
            log.info("Input:  {}", inputPath);
            log.info("JSON:   {}", jsonDir);
            log.info("Output: {}", outputPath);
            log.info("DGM:    {}", dgm != null ? dgm.describe() : "nicht vorhanden (Flat-TIC)");

            BasementGenerator generator = new BasementGenerator();
            generator.setDgm(dgm);
            ModuleParametersLoader paramLoader = new ModuleParametersLoader(jsonDir);
            GenerationStats stats = generator.addBasements(inputPath, outputPath, paramLoader);

            log.info("=== Fertig ===");
            log.info("Gebaeude verarbeitet: {}", stats.buildingsProcessed);
            log.info("Keller hinzugefuegt: {}", stats.basementsAdded);
            log.info("GroundSurfaces ersetzt: {}", stats.groundSurfacesReplaced);
            log.info("TICs erzeugt: {}", stats.ticsCreated);

        } catch (Exception e) {
            log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Setzt den optionalen DGM-Provider fuer detaillierte TIC-Hoehen.
     * Wenn null, wird ein Flat-TIC bei H_DGM erzeugt.
     */
    public void setDgm(DgmProvider dgm) {
        this.dgm = dgm;
    }

    // ==================== Hauptverarbeitung ====================

    /**
     * Fuegt Keller zu allen passenden Gebaeuden hinzu.
     */
    public GenerationStats addBasements(Path inputFile, Path outputFile,
            ModuleParametersLoader paramLoader) throws Exception {
        GenerationStats stats = new GenerationStats();

        CityGmlUtils.processGmlFile(inputFile, outputFile, building -> {
            stats.buildingsProcessed++;
            processBuilding(building, paramLoader, stats);
        });

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

        for (var target : CityGmlUtils.getBuildingTargets(building)) {
            processAbstractBuilding(target, sst, hDgm, params, stats);
        }
    }

    /**
     * Verarbeitet ein AbstractBuilding (Building oder BuildingPart).
     * 
     * Parameter sst und hDgm werden vom Parent-Building geerbt,
     * da diese Attribute nur auf Building-Ebene vorhanden sind.
     * 
     * Ablauf:
     * 1. Keller-Parameter aus ModuleParameters ableiten (BA.height + BA.CeHe)
     * 2. Original-GroundSurface sammeln (Footprint), dann entfernen
     * 3. Kellerwaende aus Footprint extrudieren (Oberkante = H_DGM + heightGr)
     * 4. Kellerboden als GroundSurface (statt FloorSurface) erzeugen
     * 5. Kellerdecke (CeilingSurface) erzeugen
     * 6. TerrainIntersectionCurve (TIC) aus Footprint + DGM/H_DGM erzeugen
     */
    private void processAbstractBuilding(AbstractBuilding target, String sst, double hDgm,
            ModuleParameters params, GenerationStats stats) {

        // Keller-Parameter
        if (!params.hasBasement() || params.getBasement() == null) return;
        var basement = params.getBasement();
        if (basement.height == null || Double.isNaN(basement.height) || basement.height <= 0) return;
        double basementHeight = basement.height;
        double basementCeHe = (basement.ceilingHeight != null && !Double.isNaN(basement.ceilingHeight))
                ? basement.ceilingHeight : 0;

        // Gesamthoehe Keller = BA.height + BA.CeHe
        double basementTotalHeight = basementHeight + basementCeHe;

        // heightGr = oberirdischer Anteil des Kellers (GF.heightAboveGround)
        double heightGr = params.getHeightGr();

        // GroundSurface-Polygone nach Terrain-Niveau filtern:
        // Polygone knapp ueber H_DGM (innerhalb 0.5m) gehoeren zum Kellerfussabdruck.
        // Erhöhte Polygone (> H_DGM + 0.5m) sind erhoehte Sockelflächen oder Verbindungsabschnitte
        // ohne eigenen Keller — sie bleiben unveraendert erhalten und werden NICHT entfernt.
        final double ELEVATED_THRESHOLD = 0.50;
        List<Polygon> groundPolygons = new ArrayList<>();
        List<AbstractSpaceBoundaryProperty> gsToRemove = new ArrayList<>();
        for (var boundary : target.getBoundaries()) {
            if (!(boundary.getObject() instanceof GroundSurface gs)) continue;
            String struktur = CityGmlUtils.getStringAttribute(gs, "STRUKTUR");
            if ("Bodenplatte".equals(struktur)) continue; // bereits vom BasementGenerator erzeugt
            MultiSurfaceProperty msp = gs.getLod3MultiSurface();
            if (msp == null || msp.getObject() == null) continue;
            for (var member : msp.getObject().getSurfaceMember()) {
                if (!(member.getObject() instanceof Polygon poly)) continue;
                List<CityGmlUtils.Point3D> pts = CityGmlUtils.toPoints(poly);
                if (pts.isEmpty()) continue;
                double polyMinZ = pts.stream().mapToDouble(p -> p.z).min().orElse(hDgm);
                if (polyMinZ <= hDgm + ELEVATED_THRESHOLD) {
                    groundPolygons.add(poly);
                    if (!gsToRemove.contains(boundary)) gsToRemove.add(boundary);
                } else {
                    log.debug("  Erhöhte GroundSurface {} (minZ={}) bleibt erhalten (> H_DGM+{}m)",
                            gs.getId(), CityGmlUtils.formatNum(polyMinZ), ELEVATED_THRESHOLD);
                }
            }
        }
        if (groundPolygons.isEmpty()) {
            log.warn("Keine terrain-nahen GroundSurface-Polygone fuer sst={} (gml:id={})", sst, target.getId());
            return;
        }

        String targetId = target.getId() != null ? target.getId() : "unknown";

        // === Nur terrain-nahe Original-GroundSurfaces entfernen ===
        // Erhoehte Polygone (> H_DGM + 0.5m) bleiben als GroundSurface erhalten.
        target.getBoundaries().removeAll(gsToRemove);
        stats.groundSurfacesReplaced += gsToRemove.size();

        // === Hoehen berechnen (gerundet auf mm-Genauigkeit) ===
        // Keller-Oberkante = H_DGM + heightGr (oberirdischer Anteil)
        double basementTopZ = CityGmlUtils.roundZ(hDgm + heightGr);
        // Kellerboden = Oberkante - Gesamthoehe
        double basementFloorZ = CityGmlUtils.roundZ(basementTopZ - basementTotalHeight);

        log.info("Verarbeite sst={} (gml:id={}): BA.height={}, BA.CeHe={}, heightGr={}, H_DGM={}, Top={}, Floor={}",
                sst, targetId, basementHeight, basementCeHe, heightGr, hDgm,
                CityGmlUtils.formatNum(basementTopZ), CityGmlUtils.formatNum(basementFloorZ));

        // === WindowPreference-Zuordnung vorbereiten ===
        // Bestehende Waende sammeln, um WindowPreference auf Kellerwaende zu uebertragen
        List<WallSurface> existingWalls = CityGmlUtils.collectWallSurfaces(target);

        int floorCount = 0;
        int wallCount = 0;

        for (Polygon groundPoly : groundPolygons) {
            List<Point3D> groundPoints = CityGmlUtils.toPoints(groundPoly);
            if (groundPoints.size() < 4) continue;

            floorCount++;

            // ── Kellerboden als GroundSurface (physische Bodenplatte) ──
            List<Point3D> floorPoints = CityGmlUtils.projectToZ(groundPoints, basementFloorZ);
            Polygon floorPoly = CityGmlUtils.createPolygon(floorPoints);

            GroundSurface ground = new GroundSurface();
            String groundFaceId = targetId + "_BA_Ground_" + floorCount;
            ground.setId("Face_" + groundFaceId);
            CityGmlUtils.setGmlName(ground, "LOD3_Ground");
            ground.setLod3MultiSurface(
                    CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(floorPoly));

            double floorArea = CityGmlUtils.calculatePolygonArea2D(floorPoints);
            CityGmlUtils.addHorizontalSurfaceAttributes(ground, groundFaceId,
                    basementFloorZ, hDgm, floorArea, "BA");
            CityGmlUtils.addStringAttribute(ground, "STRUKTUR", "Bodenplatte");
            CityGmlUtils.addStringAttribute(ground, "Lage", "belowGround");

            target.getBoundaries().add(new AbstractSpaceBoundaryProperty(ground));

            // ── Kellerwaende (WallSurface pro Kante) ──
            // Oberkante bei basementTopZ (= H_DGM + heightGr)
            // Unterkante bei basementFloorZ (= basementTopZ - basementTotalHeight)
            List<Point3D> topProjected = CityGmlUtils.projectToZ(groundPoints, basementTopZ);
            List<Point3D> topNoClose = CityGmlUtils.removeClosingPoint(topProjected);
            for (int i = 0; i < topNoClose.size(); i++) {
                wallCount++;
                Point3D a = topNoClose.get(i);
                Point3D b = topNoClose.get((i + 1) % topNoClose.size());
                Point3D aDown = new Point3D(a.x, a.y, basementFloorZ);
                Point3D bDown = new Point3D(b.x, b.y, basementFloorZ);

                // Polygon: A → B → B' → A' (CCW von aussen)
                List<Point3D> wallPoints = List.of(a, b, bDown, aDown);
                Polygon wallPoly = CityGmlUtils.createPolygon(new ArrayList<>(wallPoints));

                WallSurface wall = new WallSurface();
                String wallFaceId = targetId + "_BA_Wall_" + wallCount;
                wall.setId("Face_" + wallFaceId);
                CityGmlUtils.setGmlName(wall, "LOD3_BasementWall");
                wall.setLod3MultiSurface(
                        CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(wallPoly));

                CityGmlUtils.addWallAttributes(wall, new ArrayList<>(wallPoints),
                        wallFaceId, hDgm, "BA", "belowGround", "Kellerwand", null);

                // WindowPreference vom zugehoerigen Original-Wall uebertragen
                String windowPref = findWindowPreferenceForEdge(existingWalls, a, b);
                if (windowPref != null) {
                    CityGmlUtils.addStringAttribute(wall, "WindowPreference", windowPref);
                }

                target.getBoundaries().add(new AbstractSpaceBoundaryProperty(wall));
            }
        }

        // ── Kellerdecke (CeilingSurface) ──
        // Z = basementTopZ (= H_DGM + heightGr = Unterkante EG)
        // Das Polygon bekommt eine gml:id (Slab_...), damit der StoreyGenerator
        // den GF-Floor per XLink referenzieren kann ("Geometry once, semantics twice").
        int ceilingCount = 0;
        for (Polygon groundPoly : groundPolygons) {
            List<Point3D> top = CityGmlUtils.toPoints(groundPoly);
            if (top.size() < 4) continue;
            ceilingCount++;

            List<Point3D> ceilingPoints = CityGmlUtils.projectToZ(top, basementTopZ);
            Polygon ceilingPoly = CityGmlUtils.createPolygon(ceilingPoints);

            // gml:id auf dem Polygon setzen (fuer XLink-Referenz vom GF-Floor)
            String slabGmlId = "Slab_" + targetId + "_BA_" + ceilingCount;
            ceilingPoly.setId(slabGmlId);

            CeilingSurface ceiling = new CeilingSurface();
            String ceilingFaceId = targetId + "_BA_Ceiling_" + ceilingCount;
            ceiling.setId("Face_" + ceilingFaceId);
            CityGmlUtils.setGmlName(ceiling, "LOD3_Ceiling");
            ceiling.setLod3MultiSurface(
                    CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(ceilingPoly));

            double ceilingArea = CityGmlUtils.calculatePolygonArea2D(ceilingPoints);
            CityGmlUtils.addHorizontalSurfaceAttributes(ceiling, ceilingFaceId,
                    basementTopZ, hDgm, ceilingArea, "BA");

            target.getBoundaries().add(new AbstractSpaceBoundaryProperty(ceiling));
        }

        // storeysBelowGround als natives CityGML-Element
        target.setStoreysBelowGround(1);

        // === TerrainIntersectionCurve (TIC) ===
        // Dokumentiert wo das Gebaeude das Gelaende schneidet.
        // Ohne DGM: flacher Ring bei H_DGM. Mit DGM: bilinear interpolierte Hoehen.
        MultiCurveProperty tic = CityGmlUtils.createTerrainIntersectionCurve(
                groundPolygons, hDgm, dgm);
        if (tic != null) {
            target.setLod3TerrainIntersectionCurve(tic);
            stats.ticsCreated++;
        }

        // === Ergebnis ===
        CityGmlUtils.addStringAttribute(target, BASEMENT_MARKER, "generated");
        stats.basementsAdded++;

        log.info("  => {} Kellerwaende, {} GroundSurfaces, {} Decken, {} alte GS entfernt, TIC={}",
                wallCount, floorCount, ceilingCount, gsToRemove.size(), tic != null);
    }

    // ==================== Hilfsmethoden ====================

    /**
     * Sucht die WindowPreference des Original-Walls, dessen Grundkante der
     * gegebenen Kante (a→b) in XY entspricht.
     * Vergleicht den XY-Mittelpunkt der Kante mit den bestehenden Waenden.
     *
     * @param walls Bestehende WallSurfaces des Gebaeudes
     * @param a Startpunkt der Kellerwand-Kante
     * @param b Endpunkt der Kellerwand-Kante
     * @return WindowPreference-String oder null
     */
    private String findWindowPreferenceForEdge(List<WallSurface> walls, Point3D a, Point3D b) {
        double edgeMidX = (a.x + b.x) / 2.0;
        double edgeMidY = (a.y + b.y) / 2.0;

        String bestPref = null;
        double bestDist = Double.MAX_VALUE;

        for (WallSurface wall : walls) {
            String pref = CityGmlUtils.getStringAttribute(wall, "WindowPreference");
            if (pref == null) continue;

            Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
            if (wallPoly == null) continue;

            List<Point3D> pts = CityGmlUtils.toPoints(wallPoly);
            if (pts.size() < 3) continue;

            // Wandmittelpunkt in XY berechnen
            double wallMidX = pts.stream().mapToDouble(p -> p.x).average().orElse(0);
            double wallMidY = pts.stream().mapToDouble(p -> p.y).average().orElse(0);

            double dist = Math.sqrt(Math.pow(edgeMidX - wallMidX, 2)
                    + Math.pow(edgeMidY - wallMidY, 2));
            if (dist < bestDist && dist < WINDOW_PREFERENCE_TOLERANCE) {
                bestDist = dist;
                bestPref = pref;
            }
        }

        return bestPref;
    }

    // ==================== Statistiken ====================

    /**
     * Statistiken der Generierung.
     */
    public static class GenerationStats {
        public int buildingsProcessed = 0;
        public int basementsAdded = 0;
        public int groundSurfacesReplaced = 0;
        public int ticsCreated = 0;
    }
}
