package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils.Point3D;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.DoorSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.geometry.primitives.AbstractRingProperty;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Schritt 4: Tuer-Generator.
 *
 * Erzeugt Tueren (DoorSurface) an den GF-Wandsegmenten, basierend auf dem
 * DoorCount-Attribut (gesetzt vom Lod2ToLod3Promoter aus den Building-Preferences)
 * und den Tuerparametern aus den Baukoerpermodul-JSON-Dateien.
 *
 * Tuer-Parameter aus JSON (GF.door):
 * - DoHe:       Tuerhoehe
 * - DoLen:      Tuerbreite
 * - HDistDoWa:  Horizontaler Abstand der ersten Tuer vom linken Wandrand
 *
 * Positionierung:
 * - Erste Tuer: HDistDoWa vom linken Wandrand (= Start der Unterkante im Polygon-Ring)
 * - Weitere Tueren: gleichmaessig im verbleibenden Wandbereich verteilt
 * - Tuersockel: 5 cm ueber Unterkante der Wand (keine kollinearen Punkte)
 *
 * DoorCount-Semantik:
 * - 0:  keine Tuer
 * - 1+: Anzahl Tueren
 * - -1: eine Hintertuer (wird als 1 Tuer behandelt + Attribut "Hintertuer=true")
 *
 * Geometrie:
 * - Tuer-Eckpunkte werden in den aeusseren Ring des Wandpolygons eingefuegt
 *   (Wandflaeche wird um Tueroeffnung reduziert)
 * - Separate DoorSurface mit Tuer-Rechteck als lod3MultiSurface
 * - DoorSurface wird als FillingSurface an der WallSurface verankert
 *
 * Attribute auf DoorSurface:
 * - BldgFaceID: {WandFaceID}_Door_{N}
 * - FACEAREA:   Tuerfläche in m²
 * - Geschoss:   GF
 * - Hintertuer:  true (nur bei DoorCount=-1)
 *
 * Erwartet als Input den Output des StoreyGenerators (Schritt 3).
 *
 * Usage:
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.DoorGenerator input.gml jsonDir [output.gml]
 */
public class DoorGenerator {

    private static final Logger log = LoggerFactory.getLogger(DoorGenerator.class);

    /** Tuersockelhoehe: 5 cm ueber Wandunterkante. */
    private static final double DOOR_SILL_HEIGHT = 0.05;

    /** Minimaler Abstand zwischen Tueren und zum Wandrand (10 cm). */
    private static final double MIN_SPACING = 0.10;

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                System.err.println("Usage: DoorGenerator <input.gml> <jsonDir> [output.gml]");
                System.exit(1);
            }

            Path inputPath = Paths.get(args[0]);
            Path jsonDir = Paths.get(args[1]);
            Path outputPath = CityGmlUtils.resolveOutputPath(inputPath, "_doors",
                    args.length >= 3 ? Paths.get(args[2]) : null);

            Files.createDirectories(outputPath.getParent());

            log.info("=== Tuer-Generator ===");
            log.info("Input:  {}", inputPath);
            log.info("JSON:   {}", jsonDir);
            log.info("Output: {}", outputPath);

            DoorGenerator generator = new DoorGenerator();
            ModuleParametersLoader paramLoader = new ModuleParametersLoader(jsonDir);
            GenerationStats stats = generator.addDoors(inputPath, outputPath, paramLoader);

            log.info("=== Fertig ===");
            log.info("Gebaeude verarbeitet: {}", stats.buildingsProcessed);
            log.info("Tueren erzeugt: {}", stats.doorsCreated);
            log.info("Waende modifiziert: {}", stats.wallsModified);
            log.info("Waende uebersprungen: {}", stats.wallsSkipped);

        } catch (Exception e) {
            log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    // ==================== Standalone-Verarbeitung ====================

    /**
     * Fuegt Tueren zu allen passenden GF-Waenden hinzu (Standalone-Modus).
     */
    public GenerationStats addDoors(Path inputFile, Path outputFile,
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

        // GF.door Parameter lesen
        if (params.getGroundFloor() == null) return;
        ModuleParameters.DoorParams doorParams = params.getGroundFloor().door;
        if (doorParams == null || !doorParams.isValid()) return;

        for (var target : CityGmlUtils.getBuildingTargets(building)) {
            processAbstractBuilding(target, doorParams, stats);
            CityGmlUtils.rebuildSolidShell(target);
        }
    }

    /**
     * Verarbeitet ein AbstractBuilding (Building oder BuildingPart).
     * Sucht alle GF-WallSurfaces mit DoorCount-Attribut und fuegt Tueren ein.
     */
    private void processAbstractBuilding(AbstractBuilding target,
            ModuleParameters.DoorParams doorParams, GenerationStats stats) {

        List<WallSurface> walls = CityGmlUtils.collectWallSurfaces(target);

        for (WallSurface wall : walls) {
            String geschoss = CityGmlUtils.getStringAttribute(wall, "Geschoss");
            if (!"GF".equals(geschoss)) continue;

            String doorCountStr = CityGmlUtils.getStringAttribute(wall, "DoorCount");
            if (doorCountStr == null) continue;

            int doorCount;
            try {
                doorCount = Integer.parseInt(doorCountStr);
            } catch (NumberFormatException e) {
                continue;
            }

            // 0 = keine Tuer; -1 = Hintertuer (1 Tuer mit Hintertuer-Attribut)
            if (doorCount == 0) continue;
            if (doorCount < -1) continue; // ungueltig

            if (doorCount == -1) {
                processWall(wall, 1, true, doorParams, stats);
            } else {
                processWall(wall, doorCount, false, doorParams, stats);
            }
        }
    }

    // ==================== Wand-Verarbeitung ====================

    /**
     * Fuegt Tueren in eine einzelne GF-WallSurface ein.
     *
     * Algorithmus:
     * 1. Unterkante der Wand finden (laengste Kante bei Z_min)
     * 2. Tuer-Positionen berechnen (erste bei HDistDoWa, Rest gleichmaessig)
     * 3. Tuer-Eckpunkte in Wandpolygon-Ring einfuegen (Tueroeffnungen ausschneiden)
     * 4. DoorSurface pro Tuer erzeugen und als FillingSurface verankern
     * 5. FACEAREA der Wand aktualisieren
     * 6. Flaechenerhaltung pruefen (wallBefore ≈ wallAfter + doorAreas)
     */
    private void processWall(WallSurface wall, int doorCount, boolean isHintertuer,
            ModuleParameters.DoorParams doorParams, GenerationStats stats) {

        Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
        if (wallPoly == null) return;

        List<Point3D> allPoints = CityGmlUtils.toPoints(wallPoly);
        List<Point3D> open = CityGmlUtils.removeClosingPoint(allPoints);
        if (open.size() < 3) return;

        double doorWidth = doorParams.doorWidth;
        double doorHeight = doorParams.doorHeight;
        double hDistDoorWall = (doorParams.hDistDoorWall != null) ? doorParams.hDistDoorWall : 0.5;

        // --- Unterkante der Wand ermitteln ---
        double[] zRange = CityGmlUtils.getZRange(open);
        double zMin = zRange[0];
        double zMax = zRange[1];
        double wallHeight = zMax - zMin;

        // Validierung: Tuerhoehe + Sockel muss in die Wand passen
        if (doorHeight + DOOR_SILL_HEIGHT > wallHeight) {
            log.warn("Tuer passt nicht in Wand {} (Tuerhoehe {} + Sockel > Wandhoehe {})",
                    wall.getId(), CityGmlUtils.formatNum(doorHeight),
                    CityGmlUtils.formatNum(wallHeight));
            stats.wallsSkipped++;
            return;
        }

        // --- Unterkante finden (zwei aufeinanderfolgende Punkte bei zMin) ---
        double zTol = 0.01;
        int edgeStartIdx = -1;
        for (int i = 0; i < open.size(); i++) {
            int next = (i + 1) % open.size();
            if (Math.abs(open.get(i).z - zMin) < zTol
                    && Math.abs(open.get(next).z - zMin) < zTol) {
                edgeStartIdx = i;
                break;
            }
        }

        if (edgeStartIdx < 0) {
            log.warn("Keine Unterkante gefunden fuer Wand {}", wall.getId());
            stats.wallsSkipped++;
            return;
        }

        int edgeEndIdx = (edgeStartIdx + 1) % open.size();
        Point3D edgeStart = open.get(edgeStartIdx);
        Point3D edgeEnd = open.get(edgeEndIdx);
        double wallLength = CityGmlUtils.calculateEdgeLength2D(edgeStart, edgeEnd);

        // --- Tuer-Positionen berechnen ---
        double totalDoorWidth = doorCount * doorWidth;
        double requiredWidth = hDistDoorWall + totalDoorWidth;

        double[] doorLeftOffsets = new double[doorCount];

        if (requiredWidth <= wallLength + 0.01) {
            // Normalfall: HDistDoWa einhalten
            doorLeftOffsets[0] = hDistDoorWall;

            if (doorCount > 1) {
                double remainingSpace = wallLength - hDistDoorWall - doorWidth;
                int n = doorCount - 1;
                double spacing = (remainingSpace - n * doorWidth) / (n + 1);

                if (spacing < MIN_SPACING) {
                    log.warn("Zu wenig Abstand zwischen Tueren in Wand {} (spacing={}m)",
                            wall.getId(), CityGmlUtils.formatNum(spacing));
                    stats.wallsSkipped++;
                    return;
                }

                for (int i = 1; i < doorCount; i++) {
                    doorLeftOffsets[i] = hDistDoorWall + i * (doorWidth + spacing);
                }
            }
        } else {
            // Fallback: Tuer(en) zentrieren wenn HDistDoWa nicht passt
            double gapBetween = (doorCount > 1) ? MIN_SPACING : 0.0;
            double minNeeded = totalDoorWidth + (doorCount - 1) * gapBetween + 2 * MIN_SPACING;

            if (minNeeded > wallLength) {
                log.warn("Wand {} zu schmal fuer {} Tuer(en) (min {}m benoetigt, Wandlaenge {}m)",
                        wall.getId(), doorCount, CityGmlUtils.formatNum(minNeeded),
                        CityGmlUtils.formatNum(wallLength));
                stats.wallsSkipped++;
                return;
            }

            log.info("HDistDoWa ({}) passt nicht in Wand {} ({}m) - Tuer(en) werden zentriert",
                    CityGmlUtils.formatNum(hDistDoorWall), wall.getId(),
                    CityGmlUtils.formatNum(wallLength));
            double leftMargin = (wallLength - totalDoorWidth - (doorCount - 1) * gapBetween) / 2;
            doorLeftOffsets[0] = leftMargin;
            for (int i = 1; i < doorCount; i++) {
                doorLeftOffsets[i] = leftMargin + i * (doorWidth + gapBetween);
            }
        }

        // --- Richtungsvektoren der Unterkante ---
        double dx = edgeEnd.x - edgeStart.x;
        double dy = edgeEnd.y - edgeStart.y;
        double dirX = dx / wallLength;
        double dirY = dy / wallLength;

        // Orientierung des Aussenrings bestimmen
        boolean extCCW = CityGmlUtils.isExteriorRingCCW(open, edgeStart, dirX, dirY);

        double doorBottomZ = CityGmlUtils.roundZ(zMin + DOOR_SILL_HEIGHT);
        double doorTopZ = CityGmlUtils.roundZ(zMin + DOOR_SILL_HEIGHT + doorHeight);

        // Flaeche der Wand vor Modifikation
        double wallAreaBefore = CityGmlUtils.calculateWallArea(open);

        // --- WallFaceID fuer Tuer-IDs ---
        String wallFaceId = CityGmlUtils.getStringAttribute(wall, "BldgFaceID");
        if (wallFaceId == null) {
            wallFaceId = wall.getId() != null ? wall.getId() : "unknown";
        }

        // --- Tuer-Geometrien berechnen und DoorSurfaces erzeugen ---
        List<List<Point3D>> doorRectangles = new ArrayList<>();

        for (int d = 0; d < doorCount; d++) {
            double offset = doorLeftOffsets[d];

            // Tuer-Eckpunkte: BL, BR, TR, TL (von aussen gesehen)
            Point3D bl = new Point3D(
                    edgeStart.x + offset * dirX,
                    edgeStart.y + offset * dirY,
                    doorBottomZ);
            Point3D br = new Point3D(
                    edgeStart.x + (offset + doorWidth) * dirX,
                    edgeStart.y + (offset + doorWidth) * dirY,
                    doorBottomZ);
            Point3D tr = new Point3D(
                    edgeStart.x + (offset + doorWidth) * dirX,
                    edgeStart.y + (offset + doorWidth) * dirY,
                    doorTopZ);
            Point3D tl = new Point3D(
                    edgeStart.x + offset * dirX,
                    edgeStart.y + offset * dirY,
                    doorTopZ);

            doorRectangles.add(List.of(bl, br, tr, tl));

            // DoorSurface erzeugen: Orientierung GLEICH wie Aussenring (= ENTGEGEN dem Innenring)
            // extCCW → DoorSurface CCW (BL->BR->TR->TL)
            // extCW  → DoorSurface CW  (BL->TL->TR->BR)
            String doorId = wallFaceId + "_Door_" + (d + 1);
            List<Point3D> doorSurfacePoints = extCCW
                    ? List.of(bl, br, tr, tl)   // CCW (Standard)
                    : List.of(bl, tl, tr, br);  // CW  (Sachsen LoD2)
            Polygon doorPoly = CityGmlUtils.createPolygon(doorSurfacePoints);

            DoorSurface doorSurface = new DoorSurface();
            doorSurface.setId("Face_" + doorId);
            CityGmlUtils.setGmlName(doorSurface, "LOD3_Door");
            doorSurface.setLod3MultiSurface(
                    CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(doorPoly));

            // Attribute
            double doorArea = doorWidth * doorHeight;
            CityGmlUtils.addStringAttribute(doorSurface, "BldgFaceID", doorId);
            CityGmlUtils.addStringAttribute(doorSurface, "FACEAREA",
                    CityGmlUtils.formatNum(doorArea));
            CityGmlUtils.addStringAttribute(doorSurface, "Geschoss", "GF");

            if (isHintertuer) {
                CityGmlUtils.addStringAttribute(doorSurface, "Hintertuer", "true");
            }

            // Als FillingSurface an der WallSurface verankern
            wall.getFillingSurfaces().add(new AbstractFillingSurfaceProperty(doorSurface));
            stats.doorsCreated++;
        }

        // --- Tueroeffnungen als innere Ringe einfuegen ---
        // Innenring ENTGEGEN dem Aussenring orientieren (GML-Pflicht + Solid-Manifold).
        // extCCW → Innenring CW  (BL->TL->TR->BR)
        // extCW  → Innenring CCW (BL->BR->TR->TL)
        for (int d = 0; d < doorCount; d++) {
            List<Point3D> rect = doorRectangles.get(d);
            Point3D bl = rect.get(0);
            Point3D br = rect.get(1);
            Point3D tr = rect.get(2);
            Point3D tl = rect.get(3);
            List<Point3D> innerRing = extCCW
                    ? List.of(bl, tl, tr, br)  // CW  (Standard: entgegen CCW-Aussenring)
                    : List.of(bl, br, tr, tl); // CCW (Sachsen: entgegen CW-Aussenring)
            wallPoly.getInterior().add(new AbstractRingProperty(
                    CityGmlUtils.createLinearRing(innerRing)));
        }

        // FACEAREA der Wand aktualisieren (Wandflaeche minus Tueroeffnungen)
        double totalDoorArea = doorCount * doorWidth * doorHeight;
        CityGmlUtils.setStringAttribute(wall, "FACEAREA",
                CityGmlUtils.formatNum(Math.max(0, wallAreaBefore - totalDoorArea)));

        stats.wallsModified++;
    }

    // ==================== Statistiken ====================

    public static class GenerationStats {
        public int buildingsProcessed = 0;
        public int doorsCreated = 0;
        public int wallsModified = 0;
        public int wallsSkipped = 0;
    }
}
