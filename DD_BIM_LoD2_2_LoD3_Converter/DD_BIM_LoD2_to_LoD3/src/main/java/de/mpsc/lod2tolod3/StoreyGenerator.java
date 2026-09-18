package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.model.WindowPreference;
import de.mpsc.lod2tolod3.util.BuildingQueryUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.GeometryUtils;
import de.mpsc.lod2tolod3.util.Point3D;
import de.mpsc.lod2tolod3.util.SlabClippingUtils;
import de.mpsc.lod2tolod3.util.SlabClippingUtils.SlabPiece;
import de.mpsc.lod2tolod3.util.SolidShellUtils;
import de.mpsc.lod2tolod3.util.WallCuttingUtils;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.CeilingSurface;
import org.citygml4j.core.model.construction.FloorSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.core.AbstractSpaceBoundaryProperty;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.*;

/**
 * Schritt 3: Geschoss-Unterteilung. Teilt Waende in geschossweise Segmente (GF, UF_1, UF_2, ...)
 * und erzeugt Floor-/CeilingSurface pro Geschoss (siehe Doku.md, Abschnitt "Schritt 3").
 */
public class StoreyGenerator extends AbstractGenerator<StoreyGenerator.GenerationStats> {

    /** Mindesthoehe fuer Schnitt-Ergebnisse (5cm) – verhindert degenerierte Geometrien. */
    private static final double CUT_TOLERANCE = 0.05;

    /** Mindesthoehe fuer Wand-Segmente nach Schnitt (50cm), verhindert sichtbare Duennstreifen. */
    private static final double MIN_WALL_SEGMENT_HEIGHT = 0.50;

    /** Toleranz fuer Flachdach-Erkennung (30cm) — wenn First-Traufe < Wert → Flachdach. */
    private static final double FLAT_ROOF_TOLERANCE = 0.30;

    /** Mindest-Geschosshoehe (1.2m) – verhindert unrealistisch kurze Geschosse (Fitzelchen). */
    private static final double MIN_STOREY_HEIGHT = 1.20;

    /** Max. Geschosshoehe bei Flachdach-Fitzelchen-Merge (4.0m), sonst eigenes kurzes Geschoss. */
    private static final double MAX_STOREY_HEIGHT_FLACHDACH = 4.0;

    /** Geschoss oberhalb der Traufe: eine Wand muss mindestens diesen Anteil einer Geschosshoehe
     *  ueber die Traufe ragen (siehe Doku.md "Geschosse oberhalb der Traufe"). */
    private static final double UPPER_STOREY_MIN_RISE_FACTOR = 0.75;
    /** Waagerechte Wand-Oberkante (kein Giebel): die Punkte auf Hoehe zMax liegen mindestens so weit auseinander. */
    private static final double UPPER_STOREY_MIN_TOP_WIDTH = 1.0;
    /** Fitzelchen-Gate fuer den Bereich des neuen Geschosses: zusammenhaengende Flaeche und Mindestbreite. */
    private static final double UPPER_STOREY_MIN_AREA = 8.0;
    private static final double UPPER_STOREY_MIN_WIDTH = 2.0;
    /** Kein neu aufgeteiltes Geschoss oberhalb der Traufe niedriger als das. */
    private static final double UPPER_STOREY_MIN_HEIGHT = 2.0;

    public static void main(String[] args) {
        StoreyGenerator gen = new StoreyGenerator();
        try {
            gen.runCli(args);
        } catch (Exception e) {
            gen.log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    @Override protected String outputSuffix() { return "_storeys"; }
    @Override protected String displayName()  { return "Geschoss-Generator"; }
    @Override protected GenerationStats newStats() { return new GenerationStats(); }

    @Override
    protected void logResult(GenerationStats stats) {
        log.info("Geschosse erstellt: {}", stats.storeysCreated);
        log.info("Wandsegmente erstellt: {}", stats.wallSegmentsCreated);
        log.info("Waende geschnitten: {}", stats.wallsCut);
        log.info("Boeden erstellt: {}", stats.floorsCreated);
        log.info("Decken erstellt: {}", stats.ceilingsCreated);
    }

    // ==================== Gebaeude-Verarbeitung ====================

    /** Verarbeitet ein Gebaeude: delegiert an processAbstractBuilding fuer Building und BuildingParts. */
    @Override
    protected void processBuilding(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {

        BuildingParams bp = resolveParams(building, paramLoader).orElse(null);
        if (bp == null) return;

        Double hDgm = CityGmlUtils.parseDoubleAttribute(building, "H_DGM");
        if (hDgm == null) return;

        // Eckpunkt-Hoehen aller Gebaeudeteile VOR dem Schneiden (Abstand neuer Grenzen, teiluebergreifend).
        List<AbstractBuilding> targets = BuildingQueryUtils.getBuildingTargets(building);
        List<Double> buildingVertexZ = new ArrayList<>();
        for (var target : targets) {
            for (Polygon roof : BuildingQueryUtils.collectRoofPolygons(target)) {
                for (Point3D p : GeometryUtils.toPoints(roof)) buildingVertexZ.add(p.z);
            }
            for (var boundary : target.getBoundaries()) {
                if (!(boundary.getObject() instanceof WallSurface w)) continue;
                Polygon wp = BuildingQueryUtils.getWallPolygon(w);
                if (wp != null) for (Point3D p : GeometryUtils.toPoints(wp)) buildingVertexZ.add(p.z);
            }
        }

        // Solid-Shell immer neu aufbauen, auch bei vorzeitigem Abbruch.
        for (var target : targets) {
            processAbstractBuilding(target, bp.sst(), hDgm, bp.params(), buildingVertexZ, stats);
            SolidShellUtils.rebuildSolidShell(target);
        }
    }

    /** Berechnet Geschossgrenzen fuer ein AbstractBuilding und schneidet dessen Waende entsprechend. */
    private void processAbstractBuilding(AbstractBuilding target, String sst, double hDgm,
            ModuleParameters params, List<Double> buildingVertexZ, GenerationStats stats) {

        double[] roofZRange = BuildingQueryUtils.getRoofZRange(target);
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

        // egFloorZ = Oberkante Sockel/Fundament; nur Keller-Gebaeude schneiden Waende hier.
        double egFloorZ = GeometryUtils.roundZ(hDgm + heightGr);

        if (gfHeight <= 0) {
            log.warn("GF.height fehlt/ungueltig fuer sst={}, ueberspringe Geschossteilung", sst);
            return;
        }

        if (traufeZ <= egFloorZ + CUT_TOLERANCE) {
            log.warn("Traufe ({}) <= EG-Floor ({}) fuer sst={}, ueberspringe",
                    GeometryUtils.formatNum(traufeZ), GeometryUtils.formatNum(egFloorZ), sst);
            return;
        }

        // --- Flachdach-Erkennung (vor Geschossberechnung fuer Fitzelchen-Logik) ---
        boolean isFlachdach = (firstZ - traufeZ) < FLAT_ROOF_TOLERANCE;

        // --- Geschossgrenzen dynamisch berechnen ---
        List<StoreyInfo> storeys = new ArrayList<>(calculateStoreys(
                egFloorZ, gfHeight, ufHeight, traufeZ, sst, isFlachdach));
        if (storeys.isEmpty()) return;

        // --- Geschosse oberhalb der Traufe (Fluegel/Turm ohne eigenen BuildingPart) ---
        // Slab-Begrenzung vorab mit derselben Regel wie unten beim Decken-Aufbau bestimmen.
        double slabLimitZ = (slopedRawMinRoofZ < Double.MAX_VALUE / 2
                && slopedRawMinRoofZ > rawMinRoofZ + CUT_TOLERANCE) ? slopedRawMinRoofZ : rawMinRoofZ;
        boolean slabsLimitedEarly = slabLimitZ < traufeZ - CUT_TOLERANCE
                || slabLimitZ <= egFloorZ + CUT_TOLERANCE;
        Set<Double> newCutZ = new HashSet<>();
        final boolean upperExtended = !isFlachdach
                && appendUpperStoreys(target, storeys, traufeZ, gfHeight, ufHeight, slabsLimitedEarly,
                        buildingVertexZ, newCutZ, stats);

        String targetId = target.getId() != null ? target.getId() : "unknown";

        log.debug("Verarbeite sst={} (gml:id={}): {} Geschosse, EG-Floor={}, Traufe={}",
                sst, targetId, storeys.size(),
                GeometryUtils.formatNum(egFloorZ), GeometryUtils.formatNum(traufeZ));

        // --- Waende schneiden: egFloorZ (nur Keller) + Geschoss-Ceilings ---
        List<Double> cutZValues = new ArrayList<>();
        if (params.hasBasement()) {
            cutZValues.add(egFloorZ);
        }
        for (int i = 0; i < storeys.size() - 1; i++) {
            cutZValues.add(storeys.get(i).ceilingZ);
        }

        // Geschosse, die unterhalb der Traufe beginnen: Zuordnung fuer Waende ohne Schnitt oberhalb der Traufe
        List<StoreyInfo> lowerStoreys = storeys;
        if (upperExtended) {
            lowerStoreys = storeys.stream().filter(s -> s.floorZ < traufeZ - CUT_TOLERANCE).toList();
            if (lowerStoreys.isEmpty()) lowerStoreys = storeys;
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

            Polygon wallPoly = BuildingQueryUtils.getWallPolygon(wall);
            if (wallPoly == null) continue;

            List<Point3D> wallPoints = GeometryUtils.toPoints(wallPoly);
            if (wallPoints.size() < 3) continue;

            double[] zRange = GeometryUtils.getZRange(wallPoints);
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

            // Neue Grenzen der Geschoss-Erweiterung nur dort schneiden, wo die Schnittlinie die Kontur an
            // senkrechten Kanten trifft und darueber kein Duennstreifen entsteht — ein Schnitt durch eine
            // schraege Kante (Giebel, Wand auf der Dachschraege) setzt einen T-Naht-Punkt in die angrenzende
            // Dachflaeche, einer knapp darunter erzeugt einen Kamm aus Streifen und Zacken (siehe Doku.md).
            boolean upperCutSkipped = false;
            if (upperExtended) {
                List<Point3D> contour = GeometryUtils.removeClosingPoint(wallPoints);
                upperCutSkipped = applicableCuts.removeIf(
                        cz -> newCutZ.contains(cz) && slopedEdgeNear(contour, cz, MIN_WALL_SEGMENT_HEIGHT));
            }
            List<StoreyInfo> tagStoreys = upperCutSkipped ? lowerStoreys : storeys;

            // Duennstreifen-Vermeidung: letzten Schnitt entfernen wenn er ein zu duennes Segment erzeugt.
            if (!applicableCuts.isEmpty()) {
                double lastCut = applicableCuts.get(applicableCuts.size() - 1);
                if (wallMaxZ - lastCut < MIN_WALL_SEGMENT_HEIGHT
                        && Math.abs(lastCut - egFloorZ) > 0.001) {
                    applicableCuts.remove(applicableCuts.size() - 1);
                }
            }

            // Traufen-Schnitt nur bei Flachdach (Schraegdach-Giebelwaende bleiben unangetastet).
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
                // Unbeschnittene Wand haengt unter egFloorZ → hart kappen (siehe trimWallBelowEgFloor).
                if (params.hasBasement() && wallMinZ < egFloorZ - CUT_TOLERANCE) {
                    wallPoly = trimWallBelowEgFloor(wallPoly, egFloorZ, wall.getId());
                    wall.setLod3MultiSurface(CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(wallPoly));
                    wallPoints = GeometryUtils.toPoints(wallPoly);
                    zRange = GeometryUtils.getZRange(wallPoints);
                    wallMinZ = zRange[0];
                    wallMaxZ = zRange[1];
                }
                StoreyInfo storey = findStoreyForZ(tagStoreys, (wallMinZ + wallMaxZ) / 2.0);
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
            boolean isAboveNeighbor = WindowPreference.parse(windowPref) == WindowPreference.ABOVE_NEIGHBOR;
            // ABOVE_NEIGHBOR: absoluten Z_Fenster_ASL = Z_MIN_ASL + Z_Fenster vorberechnen
            String zDifferenzStr = CityGmlUtils.getStringAttribute(wall, "Z_Differenz");
            String zFensterAslStr = null;
            if (isAboveNeighbor) {
                String zFensterStr = CityGmlUtils.getStringAttribute(wall, "Z_Fenster");
                String origZMinAsl = CityGmlUtils.getStringAttribute(wall, "Z_MIN_ASL");
                if (zFensterStr != null && origZMinAsl != null) {
                    try {
                        double zFensterAsl = Double.parseDouble(origZMinAsl)
                                           + Double.parseDouble(zFensterStr);
                        zFensterAslStr = GeometryUtils.formatNum(zFensterAsl);
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (innenwand == null) innenwand = "0";

            // Bandweises Schneiden mit JTS (robust auch bei Anbau-Kerben, siehe Doku.md)
            List<Polygon> segments = WallCuttingUtils.cutWallAtMultipleZJTS(
                    GeometryUtils.toPoints(wallPoly), applicableCuts);
            if (segments == null || segments.isEmpty()) {
                // Schnitt fehlgeschlagen → nur Tag setzen, aber wie oben auf egFloorZ trimmen.
                if (params.hasBasement() && wallMinZ < egFloorZ - CUT_TOLERANCE) {
                    wallPoly = trimWallBelowEgFloor(wallPoly, egFloorZ, wall.getId());
                    wall.setLod3MultiSurface(CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(wallPoly));
                    double[] trimmedZRange = GeometryUtils.getZRange(GeometryUtils.toPoints(wallPoly));
                    wallMinZ = trimmedZRange[0];
                    wallMaxZ = trimmedZRange[1];
                }
                StoreyInfo storey = findStoreyForZ(tagStoreys, (wallMinZ + wallMaxZ) / 2.0);
                if (storey != null) {
                    assignGeschossToExistingWall(wall, storey);
                    segmentsCreated++;
                }
                continue;
            }

            // Original-Wand zum Entfernen markieren
            toRemove.add(boundary);
            wallsCut++;

            // Segmente unterhalb egFloorZ bzw. oberhalb traufeZ verwerfen. Das Kellerband wird
            // ueber die OBERKANTE geprueft (nicht den Mittelpunkt): ein Segment, dessen Oberkante
            // egFloorZ nicht um mehr als CUT_TOLERANCE ueberragt, liegt komplett im Kellerbereich —
            // exakt dieselbe Toleranz wie cutWallPolygonAtZ's eigener "zCut nahe maxZ"-Schutz
            // (siehe trimWallBelowEgFloor), so dass ein hier nicht verworfenes Segment garantiert
            // trimmbar bleibt (kein Randfall, bei dem beide Schwellen knapp aneinander vorbeilaufen).
            double keptMaxTop = Double.NEGATIVE_INFINITY;
            for (Polygon seg : segments) {
                double[] z = GeometryUtils.getZRange(GeometryUtils.toPoints(seg));
                double midZ = (z[0] + z[1]) / 2.0;
                if (hasEgFloorCut && z[1] <= egFloorZ + CUT_TOLERANCE) continue;
                if (hasTraufeCut && midZ > traufeZ + FLAT_ROOF_TOLERANCE) continue;
                if (z[1] > keptMaxTop) keptMaxTop = z[1];
            }

            // Fuer jedes Segment: neues WallSurface mit Geschoss-Attributen
            for (int i = 0; i < segments.size(); i++) {
                // Segment entduplizieren (1mm) → gegen CONSECUTIVE_POINTS_SAME (kein kollineares
                // Mergen, sonst brechen geteilte Kanten auf). Polygon neu aufbauen.
                List<Point3D> segPoints =
                        GeometryUtils.dedupConsecutive(GeometryUtils.toPoints(segments.get(i)), GeometryUtils.POINT_MERGE_TOL);
                if (segPoints.size() < 3) continue; // nach Dedup degeneriert
                double[] segZ = GeometryUtils.getZRange(segPoints);
                double segMidZ = (segZ[0] + segZ[1]) / 2.0;

                // Rand-Segmente Z-basiert verwerfen (Oberkante statt Mittelpunkt, siehe oben).
                // Traufe-Schwelle bewusst FLAT_ROOF_TOLERANCE (nicht CUT_TOLERANCE): dieselbe
                // Toleranz, mit der ein Dach ueberhaupt erst als "flach" (isFlachdach) klassifiziert
                // wird, muss auch hier gelten — sonst wird ein einzelnes lokales First-/Grat-Detail
                // (z.B. 19cm ueber traufeZ, von der Flachdach-Klassifikation noch als "flach"
                // durchgehen gelassen) an GENAU DER Wand verworfen, deren zugehoerige Dachflaechen
                // dieses Detail (unveraendert) weiterhin erwarten — GE_S_NOT_CLOSED. Siehe Doku.md.
                if (hasEgFloorCut && segZ[1] <= egFloorZ + CUT_TOLERANCE) continue;
                if (hasTraufeCut && segMidZ > traufeZ + FLAT_ROOF_TOLERANCE) continue;

                // Auch ein "erfolgreich" geschnittenes Segment kann unter egFloorZ haengen bleiben
                // (uebersprungener Einzelschnitt in cutWallSinglePieceGuarded) — notfalls nachtrimmen.
                if (params.hasBasement() && segZ[0] < egFloorZ - CUT_TOLERANCE) {
                    Polygon trimmed = trimWallBelowEgFloor(
                            GeometryUtils.createPolygon(segPoints), egFloorZ, originalWallId);
                    segPoints = GeometryUtils.dedupConsecutive(
                            GeometryUtils.toPoints(trimmed), GeometryUtils.POINT_MERGE_TOL);
                    if (segPoints.size() < 3) continue;
                    segZ = GeometryUtils.getZRange(segPoints);
                    segMidZ = (segZ[0] + segZ[1]) / 2.0;
                }

                Polygon segPoly = GeometryUtils.createPolygon(segPoints);

                StoreyInfo storey = findStoreyForZ(tagStoreys, segMidZ);
                if (storey == null) storey = tagStoreys.get(tagStoreys.size() - 1);

                boolean isTopSegment = (segZ[1] >= keptMaxTop - CUT_TOLERANCE);

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
                GeometryUtils.addWallAttributes(segWall, segPoints,
                        segFaceId, hDgm, storey.geschoss, null, struktur,
                        originalWallId);

                // Geschossdecke-Z schreiben (fuer WindowGenerator: Obergrenze fuer Fensterplatzierung)
                CityGmlUtils.addStringAttribute(segWall, "GeschossDeckeZ",
                        GeometryUtils.formatNum(storey.ceilingZ));

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
                // ABOVE_NEIGHBOR: Z_Differenz und absoluten Z_Fenster_ASL fuer den WindowGenerator mitgeben
                if (isAboveNeighbor) {
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

        // --- Floor/Ceiling pro Geschoss erzeugen (Ceiling inline, Floor des naechsten Geschosses per XLink) ---

        // Mischdach-Boden-Artefakt: ein niedrigeres Anbau-Flachdach darf nicht die
        // Slab-Begrenzung des GESAMTEN Gebaeudes auf seine eigene (niedrigere) Hoehe ziehen —
        // sonst werden obere Geschosse des hoeheren Hauptteils komplett uebersprungen (Boden
        // UND Decke), nicht nur deren Decke wie bei der regulaeren Mischdach-Erkennung. Bewusst
        // unabhaengig vom Abstand zum Erdgeschoss (vorher nur "nahe EG" < 2m, siehe Doku.md).
        if (slopedRawMinRoofZ < Double.MAX_VALUE / 2
                && slopedRawMinRoofZ > rawMinRoofZ + CUT_TOLERANCE) {
            log.debug("  Mischdach-Artefakt {}: rawMinRoofZ={} < slopedRawMinZ={}, verwende slopedRawMinZ",
                    targetId, GeometryUtils.formatNum(rawMinRoofZ), GeometryUtils.formatNum(slopedRawMinRoofZ));
            rawMinRoofZ = slopedRawMinRoofZ;
        }
        double slabsTraufeZ = rawMinRoofZ;

        if (slabsTraufeZ <= egFloorZ + CUT_TOLERANCE) {
            // Terrain-Niveau-Artefakt: keine Decken/Boeden erzeugen
            log.debug("Part/Building {} rawMinRoofZ={} <= egFloorZ={}, ueberspringe Decken/Boeden",
                    targetId, GeometryUtils.formatNum(slabsTraufeZ), GeometryUtils.formatNum(egFloorZ));
            stats.storeysCreated += storeys.size();
            stats.wallsCut += wallsCut;
            stats.wallSegmentsCreated += segmentsCreated;
            return;
        }

        // Slab-Geschosse, begrenzt durch slabsTraufeZ (Normalfall: identisch zu storeys).
        final boolean slabsAreLimited = (slabsTraufeZ < traufeZ - CUT_TOLERANCE);
        final List<StoreyInfo> slabStoreys;
        if (slabsAreLimited) {
            boolean isFlachdachSlabs = (firstZ - slabsTraufeZ) < FLAT_ROOF_TOLERANCE;
            slabStoreys = calculateStoreys(egFloorZ, gfHeight, ufHeight, slabsTraufeZ, sst, isFlachdachSlabs);
            log.debug("  Slab-Begrenzung {}: slabsTraufeZ={} < traufeZ={}, {} statt {} Slab-Geschosse",
                    targetId, GeometryUtils.formatNum(slabsTraufeZ), GeometryUtils.formatNum(traufeZ),
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

        List<Polygon> groundPolygons = BuildingQueryUtils.collectGroundPolygons(target);
        int floorsAdded = 0;
        int ceilingsAdded = 0;

        // BA-Ceiling-IDs vom BasementGenerator (der GF-Floor referenziert sie per XLink).
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
        // Mischdach-Erkennung: bei Flachdach+Schraegdach-Mix braucht nur die geneigte Flaeche eine CeilingSurface.
        List<Polygon> roofPolygons = BuildingQueryUtils.collectRoofPolygons(target);
        List<Polygon> slopedRoofPolygons = new ArrayList<>();
        int flatRoofCount = 0;

        for (Polygon roofPoly : roofPolygons) {
            List<Point3D> pts = GeometryUtils.toPoints(roofPoly);
            if (pts.isEmpty()) continue;
            double maxZ = pts.stream().mapToDouble(p -> p.z).max().orElse(0);
            if ((maxZ - traufeZ) < 0.30) {
                flatRoofCount++;
            } else {
                slopedRoofPolygons.add(roofPoly);
            }
        }

        // Nicht fuer ueber die Traufe erweiterte Gebaeude: das oberste Geschoss endet dann oberhalb der
        // Traufe, projizierte Schraegflaechen laegen dort teils ausserhalb des Volumens — die Decke
        // entsteht stattdessen regulaer per Zuschnitt (Giebel-Zweig unten).
        boolean isMixedRoof = !upperExtended && flatRoofCount > 0 && !slopedRoofPolygons.isEmpty();
        if (isMixedRoof) {
            log.debug("  Mischdach erkannt fuer {}: {} flache + {} geneigte Dachflaechen",
                    targetId, flatRoofCount, slopedRoofPolygons.size());
        }

        // Anbau-Zuschnitt: Slab endet dort, wo eine niedrigere Dachflaeche schon auf/unter dieser
        // Hoehe liegt (Anbauten ohne eigenes BuildingPart, die sich das Grundpolygon mit einem
        // hoeheren Hauptbau teilen) — siehe Doku.md, Abschnitt "Anbau-Zuschnitt". Boden und Decke
        // EINES Geschosses liegen auf unterschiedlicher Z-Hoehe und werden daher separat
        // zugeschnitten; ein Zuschnitt kann das Grundpolygon in mehrere Teilstuecke trennen
        // (z.B. ein niedriger Mitteltrakt zwischen zwei Fluegeln).
        Map<Integer, List<String>> previousCeilingSlabIds = new HashMap<>();
        for (var e : baCeilingSlabIds.entrySet()) {
            previousCeilingSlabIds.put(e.getKey(), List.of(e.getValue()));
        }

        for (StoreyInfo storey : slabStoreys) {
            // Slab-IDs die in DIESEM Geschoss als Ceiling erzeugt werden
            Map<Integer, List<String>> currentCeilingSlabIds = new HashMap<>();

            int polyIdx = 0;
            for (Polygon groundPoly : groundPolygons) {
                List<Point3D> groundPoints = GeometryUtils.toPoints(groundPoly);
                if (groundPoints.size() < 3) continue;
                polyIdx++;

                List<SlabPiece> floorPieces = SlabClippingUtils.clipSlabAtZ(
                        groundPoints, roofPolygons, storey.floorZ, CUT_TOLERANCE);
                if (floorPieces.isEmpty()) continue; // an dieser Hoehe traegt hier nichts (mehr)

                List<String> prevSlabIds = previousCeilingSlabIds.get(polyIdx);
                List<String> currentSlabIds = new ArrayList<>();

                for (int pieceIdx = 0; pieceIdx < floorPieces.size(); pieceIdx++) {
                    SlabPiece floorPiece = floorPieces.get(pieceIdx);
                    String pieceSuffix = floorPieces.size() > 1 ? "_" + (pieceIdx + 1) : "";
                    double areaFloor = SlabClippingUtils.calculateNetArea2D(floorPiece);

                    // --- FloorSurface ---
                    FloorSurface floor = new FloorSurface();
                    String floorFaceId = targetId + "_" + storey.geschoss + "_Floor_" + polyIdx + pieceSuffix;
                    floor.setId("Face_" + floorFaceId);
                    CityGmlUtils.setGmlName(floor, "LOD3_Floor");

                    // XLink-Referenz: passendes vorheriges Ceiling-Teilstueck (gleiche Teilstueck-
                    // Zahl vorausgesetzt) → referenzieren, sonst eigene Geometrie inline.
                    String prevSlabId = (prevSlabIds != null && prevSlabIds.size() == floorPieces.size())
                            ? prevSlabIds.get(pieceIdx) : null;
                    if (prevSlabId != null) {
                        // XLink mit umgekehrter Normale (Decke ist fest auf unten erzwungen, Boden
                        // braucht oben — siehe Doku.md, Abschnitt Boden/Decke-Normalen).
                        floor.setLod3MultiSurface(
                                CityGmlUtils.createReversedXLinkMultiSurfaceProperty(prevSlabId));
                    } else {
                        Polygon floorPoly = buildSlabPolygon(floorPiece, true);
                        floor.setLod3MultiSurface(
                                CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(floorPoly));
                    }

                    GeometryUtils.addHorizontalSurfaceAttributes(floor, floorFaceId,
                            storey.floorZ, hDgm, areaFloor, storey.geschoss);
                    CityGmlUtils.addStringAttribute(floor, "STRUKTUR", "Geschossboden");

                    target.getBoundaries().add(new AbstractSpaceBoundaryProperty(floor));
                    floorsAdded++;
                }

                // --- CeilingSurface --- (Flachdach/Slab-Begrenzung: RoofSurface bildet bereits die Decke)
                if (storey.isTopStorey && (isFlachdach || slabsAreLimited)) {
                    continue;
                }

                // Mischdach: Ceiling wird separat aus geneigten Dachflaechen erzeugt (siehe unten).
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
                                storey.geschoss, GeometryUtils.formatNum(topStoreyHeight),
                                GeometryUtils.formatNum(expectedHeight));
                        continue;
                    }
                    log.debug("  Giebel-Stockwerk: {} hat {}m >= erwartet {}m → Decke bei Traufe bleibt",
                            storey.geschoss, GeometryUtils.formatNum(topStoreyHeight),
                            GeometryUtils.formatNum(expectedHeight));
                }

                // Decken-Kontur separat bei storey.ceilingZ zuschneiden (kann staerker beschnitten
                // sein als der Boden desselben Geschosses, siehe oben).
                List<SlabPiece> ceilingPieces = SlabClippingUtils.clipSlabAtZ(
                        groundPoints, roofPolygons, storey.ceilingZ, CUT_TOLERANCE);
                // Oberstes Geschoss oberhalb der Traufe: das Dach schliesst es ohnehin, nur ausreichend grosse
                // Deckenstuecke unter hoeheren Dachteilen anlegen (kein Folge-Boden verweist darauf).
                if (upperExtended && storey.isTopStorey) {
                    ceilingPieces = ceilingPieces.stream()
                            .filter(p -> SlabClippingUtils.hasSubstantialRegion(
                                    List.of(p), UPPER_STOREY_MIN_AREA, UPPER_STOREY_MIN_WIDTH / 2))
                            .toList();
                }

                for (int pieceIdx = 0; pieceIdx < ceilingPieces.size(); pieceIdx++) {
                    SlabPiece ceilingPiece = ceilingPieces.get(pieceIdx);
                    String pieceSuffix = ceilingPieces.size() > 1 ? "_" + (pieceIdx + 1) : "";
                    double areaCeiling = SlabClippingUtils.calculateNetArea2D(ceilingPiece);

                    // Normale nach unten erzwungen (CityDoctor IsCeilingCheck, siehe Doku.md).
                    Polygon ceilingPoly = buildSlabPolygon(ceilingPiece, false);

                    // gml:id auf dem Polygon setzen (fuer XLink-Referenz vom naechsten Floor)
                    String slabGmlId = "Slab_" + targetId + "_" + storey.geschoss + "_" + polyIdx + pieceSuffix;
                    ceilingPoly.setId(slabGmlId);

                    CeilingSurface ceiling = new CeilingSurface();
                    String ceilingFaceId = targetId + "_" + storey.geschoss + "_Ceiling_" + polyIdx + pieceSuffix;
                    ceiling.setId("Face_" + ceilingFaceId);
                    CityGmlUtils.setGmlName(ceiling, "LOD3_Ceiling");
                    ceiling.setLod3MultiSurface(
                            CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(ceilingPoly));

                    GeometryUtils.addHorizontalSurfaceAttributes(ceiling, ceilingFaceId,
                            storey.ceilingZ, hDgm, areaCeiling, storey.geschoss);
                    CityGmlUtils.addStringAttribute(ceiling, "STRUKTUR", "Geschossdecke");

                    target.getBoundaries().add(new AbstractSpaceBoundaryProperty(ceiling));
                    ceilingsAdded++;

                    // Slab-IDs merken fuer den Floor des naechsten Geschosses
                    currentSlabIds.add(slabGmlId);
                }
                if (!currentSlabIds.isEmpty()) {
                    currentCeilingSlabIds.put(polyIdx, currentSlabIds);
                }
            }

            // --- Mischdach: CeilingSurface aus den auf ceilingZ projizierten geneigten Dachflaechen ---
            if (storey.isTopStorey && isMixedRoof && !slabsAreLimited) {
                int roofIdx = 0;
                for (Polygon slopedPoly : slopedRoofPolygons) {
                    roofIdx++;
                    List<Point3D> roofPts = GeometryUtils.toPoints(slopedPoly);
                    if (roofPts.size() < 3) continue;

                    List<Point3D> ceilingPoints = GeometryUtils.orientForNormalZ(
                            GeometryUtils.projectToZ(roofPts, storey.ceilingZ), false);
                    double roofArea = GeometryUtils.calculatePolygonArea2D(ceilingPoints);

                    Polygon ceilingPoly = GeometryUtils.createPolygon(ceilingPoints);

                    CeilingSurface ceiling = new CeilingSurface();
                    String ceilingFaceId = targetId + "_" + storey.geschoss + "_Ceiling_R" + roofIdx;
                    ceiling.setId("Face_" + ceilingFaceId);
                    CityGmlUtils.setGmlName(ceiling, "LOD3_Ceiling");
                    ceiling.setLod3MultiSurface(
                            CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(ceilingPoly));

                    GeometryUtils.addHorizontalSurfaceAttributes(ceiling, ceilingFaceId,
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

    /** Berechnet die Geschossgrenzen dynamisch von GF aufwaerts bis zur Traufe (siehe Doku.md Schritt 3). */
    private List<StoreyInfo> calculateStoreys(double egFloorZ, double gfHeight,
            double ufHeight, double traufeZ, String sst, boolean isFlachdach) {

        List<StoreyInfo> storeys = new ArrayList<>();
        traufeZ = GeometryUtils.roundZ(traufeZ);

        // --- GF ---
        double gfCeilingZ = GeometryUtils.roundZ(egFloorZ + gfHeight);

        // Sicherheitscheck: GF-Ceiling darf nicht ueber Traufe liegen
        if (gfCeilingZ >= traufeZ - CUT_TOLERANCE) {
            log.debug("GF-Ceiling ({}) >= Traufe ({}) fuer sst={}, nur GF erzeugt",
                    GeometryUtils.formatNum(gfCeilingZ), GeometryUtils.formatNum(traufeZ), sst);
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
                        // Flachdach-Sonderregel: nicht mergen, Fitzelchen als eigenes Geschoss.
                        ufNum++;
                        String geschoss = "UF_" + ufNum;
                        storeys.add(new StoreyInfo(geschoss, currentFloorZ, traufeZ, true));
                        log.debug("Flachdach: Fitzelchen-Geschoss {} mit {}m erzeugt (Merge haette {}m > {}m ergeben)",
                                geschoss, GeometryUtils.formatNum(remainingHeight),
                                GeometryUtils.formatNum(mergedHeight),
                                GeometryUtils.formatNum(MAX_STOREY_HEIGHT_FLACHDACH));
                    } else {
                        // Standard: vorheriges Geschoss bis Traufe erweitern
                        storeys.set(storeys.size() - 1,
                                new StoreyInfo(prev.geschoss, prev.floorZ, traufeZ, true));
                        log.debug("UF uebersprungen: Resthoehe {}m < {}m, {} bis Traufe erweitert",
                                GeometryUtils.formatNum(remainingHeight),
                                GeometryUtils.formatNum(MIN_STOREY_HEIGHT), prev.geschoss);
                    }
                }
                break;
            }

            ufNum++;
            String geschoss = "UF_" + ufNum;
            double ceilingZ = GeometryUtils.roundZ(currentFloorZ + ufHeight);

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
                        last.geschoss, GeometryUtils.formatNum(lastHeight),
                        GeometryUtils.formatNum(ufHeight), sst);
            }
        }

        return storeys;
    }

    // ==================== Wand-Schnitt ====================

    /** Trimmt eine Wand, die unter egFloorZ haengt, auf einen Einzelschnitt (siehe Doku.md Schritt 3). */
    private Polygon trimWallBelowEgFloor(Polygon wallPoly, double egFloorZ, String wallId) {
        Polygon[] cut = WallCuttingUtils.cutWallPolygonAtZ(wallPoly, egFloorZ, CUT_TOLERANCE);
        if (cut == null || cut[1] == null) {
            log.warn("Wand {} reicht unter egFloorZ ({}), Einzelschnitt dort aber nicht moeglich "
                    + "— Kontur bleibt unveraendert (moegliche Ueberlappung mit Kellerwand).",
                    wallId, GeometryUtils.formatNum(egFloorZ));
            return wallPoly;
        }
        List<Point3D> upperPts = GeometryUtils.toPoints(cut[1]);
        if (GeometryUtils.ringSelfIntersects(upperPts)) {
            log.warn("Wand {} reicht unter egFloorZ ({}), Einzelschnitt dort erzeugt aber ein "
                    + "selbstschneidendes Stueck — Kontur bleibt unveraendert.",
                    wallId, GeometryUtils.formatNum(egFloorZ));
            return wallPoly;
        }
        return cut[1];
    }

    /** Schneidet ein Wand-Polygon an mehreren Z-Hoehen in Geschoss-Segmente (siehe Doku.md Schritt 3). */
    /** Findet das Geschoss fuer eine gegebene Z-Hoehe (exakt, sonst naechstliegend). */
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

    /** Fuegt Geschoss-Attribute zu einer bestehenden, nicht geschnittenen Wand hinzu. */
    private void assignGeschossToExistingWall(WallSurface wall, StoreyInfo storey) {
        CityGmlUtils.addStringAttribute(wall, "Geschoss", storey.geschoss);
    }

    // ==================== Geschosse oberhalb der Traufe ====================

    /**
     * Haengt Geschosse oberhalb der Traufe an, wenn eine Wand mit waagerechter Oberkante mindestens
     * {@link #UPPER_STOREY_MIN_RISE_FACTOR} x Geschosshoehe darueber hinausragt (Fluegel, Turm,
     * Treppenhauskopf ohne eigenen BuildingPart) und dort ein nicht fitzeliger Bereich unter dem
     * Dach liegt. Das bisherige oberste Geschoss endet dann an der Traufe. true = erweitert
     * (siehe Doku.md "Geschosse oberhalb der Traufe").
     */
    private boolean appendUpperStoreys(AbstractBuilding target, List<StoreyInfo> storeys, double traufeZ,
            double gfHeight, double ufHeight, boolean slabsLimited, List<Double> buildingVertexZ,
            Set<Double> newCutZ, GenerationStats stats) {
        if (ufHeight <= 0 || storeys.isEmpty()) return false;
        double minRise = UPPER_STOREY_MIN_RISE_FACTOR * ufHeight;

        double topZ = Double.NEGATIVE_INFINITY;
        for (var boundary : target.getBoundaries()) {
            if (!(boundary.getObject() instanceof WallSurface wall)) continue;
            if (CityGmlUtils.getStringAttribute(wall, "Geschoss") != null) continue; // Kellerwand
            Polygon poly = BuildingQueryUtils.getWallPolygon(wall);
            if (poly == null) continue;
            List<Point3D> pts = GeometryUtils.removeClosingPoint(GeometryUtils.toPoints(poly));
            if (pts.size() < 3) continue;
            double zMax = GeometryUtils.getZRange(pts)[1];
            if (zMax - traufeZ < minRise || zMax <= topZ) continue;
            if (topEdgeWidth(pts, zMax) >= UPPER_STOREY_MIN_TOP_WIDTH) topZ = zMax;
        }
        if (topZ == Double.NEGATIVE_INFINITY) return false;
        stats.upperStoreyCandidates++;

        // Slab-Begrenzung (niedrigeres Anbaudach): Decken reichen dort nicht bis zur Traufe —
        // neue Waende ohne passende Boeden waeren inkonsistent, daher auslassen.
        if (slabsLimited) {
            stats.upperSkippedSlabLimit++;
            return false;
        }

        // Fitzelchen-Gate: in Mindesthoehe ueber der Traufe muss unter dem Dach ein zusammen-
        // haengender, ausreichend grosser und breiter Bereich liegen.
        List<Polygon> roofs = BuildingQueryUtils.collectRoofPolygons(target);
        List<SlabPiece> region = new ArrayList<>();
        for (Polygon ground : BuildingQueryUtils.collectGroundPolygons(target)) {
            region.addAll(SlabClippingUtils.clipSlabAtZ(
                    GeometryUtils.toPoints(ground), roofs, traufeZ + minRise, CUT_TOLERANCE));
        }
        if (!SlabClippingUtils.hasSubstantialRegion(region, UPPER_STOREY_MIN_AREA, UPPER_STOREY_MIN_WIDTH / 2)) {
            stats.upperSkippedRegion++;
            return false;
        }

        // Ist das bisherige oberste Geschoss vollstaendig, endet es CUT_TOLERANCE ueber der Traufe: die
        // Traufpunkte der Waende und Dachkanten streuen mm-genau, ein Schnitt bzw. Deckenzuschnitt exakt
        // auf Traufhoehe erzeugte dort Splitter, offene Naehte und selbstberuehrende Deckenringe. Ist es
        // nur ein kurzes Restgeschoss oder bliebe darueber weniger als UPPER_STOREY_MIN_HEIGHT, wird der
        // Stapel ab dessen Boden neu aufgebaut — sonst entstuende ein Fitzelchen-Geschoss.
        int storeyCountBefore = storeys.size();
        StoreyInfo prev = storeys.remove(storeys.size() - 1);
        boolean prevIsGf = "GF".equals(prev.geschoss);
        int ufNum = prevIsGf ? 0 : Integer.parseInt(prev.geschoss.substring(3));
        double top = GeometryUtils.roundZ(topZ);
        // Neue Grenzhoehen auf einem 1-cm-Raster und mindestens 1 cm neben allen Wand-/Dach-Eckpunkten des
        // ganzen Gebaeudes: sonst entstehen an T-Naehten (auch zwischen Gebaeudeteilen mit mm-verschiedener
        // Traufe) mm-Stummelkanten, die die Einfuege-Toleranz nicht mehr erfasst (offene Naehte).
        final double clearance = 0.01;
        java.util.function.DoubleUnaryOperator clearOfVertices = z0 -> {
            double z = Math.ceil(z0 * 100 - 1e-6) / 100;
            for (int step = 0; step < 10; step++) {
                final double cz = z;
                if (buildingVertexZ.stream().noneMatch(v -> Math.abs(v - cz) < clearance - 1e-6)) break;
                z = Math.round((z + clearance) * 100) / 100.0;
            }
            return z;
        };
        double floorZ;
        boolean gfPending = false;
        double eaveBoundaryZ = clearOfVertices.applyAsDouble(GeometryUtils.roundZ(traufeZ + CUT_TOLERANCE));
        if (prev.ceilingZ - prev.floorZ >= (prevIsGf ? gfHeight : ufHeight) - CUT_TOLERANCE
                && top - eaveBoundaryZ >= UPPER_STOREY_MIN_HEIGHT) {
            floorZ = eaveBoundaryZ;
            storeys.add(new StoreyInfo(prev.geschoss, prev.floorZ, floorZ, false));
            newCutZ.add(floorZ);
        } else {
            floorZ = prev.floorZ;
            if (prevIsGf) gfPending = true; else ufNum--;
        }
        boolean done = false;
        if (gfPending) {
            double gfCeilingZ = clearOfVertices.applyAsDouble(GeometryUtils.roundZ(floorZ + gfHeight));
            if (top - gfCeilingZ < Math.max(minRise, UPPER_STOREY_MIN_HEIGHT)) {
                storeys.add(new StoreyInfo("GF", floorZ, top, true));
                done = true;
            } else {
                storeys.add(new StoreyInfo("GF", floorZ, gfCeilingZ, false));
                newCutZ.add(gfCeilingZ);
                floorZ = gfCeilingZ;
            }
        }
        if (!done) {
            // Hoehe bis zur Wand-Oberkante gleichmaessig auf ganze Geschosse verteilen: kein Fitzelchen-Rest
            // und kein ueberhohes Geschoss, je Geschoss aber mindestens UPPER_STOREY_MIN_HEIGHT.
            double height = top - floorZ;
            int count = Math.max(1, (int) Math.min(Math.round(height / ufHeight),
                    Math.floor(height / UPPER_STOREY_MIN_HEIGHT)));
            double step = height / count;
            for (int i = 1; i <= count; i++) {
                double ceilingZ = i == count ? top : clearOfVertices.applyAsDouble(GeometryUtils.roundZ(floorZ + step));
                storeys.add(new StoreyInfo("UF_" + (++ufNum), floorZ, ceilingZ, i == count));
                if (i < count) newCutZ.add(ceilingZ);
                floorZ = ceilingZ;
            }
        }
        // Kein zusaetzliches Geschoss (nur das Restgeschoss bis zur Oberkante verlaengert): bisheriger Stand.
        if (storeys.size() <= storeyCountBefore) {
            while (storeys.size() > storeyCountBefore - 1) storeys.remove(storeys.size() - 1);
            storeys.add(prev);
            newCutZ.clear();
            stats.upperSkippedHeight++;
            return false;
        }
        stats.upperStoreysAdded++;
        log.debug("  Geschoss oberhalb der Traufe {}: Traufe={}, Oberkante={}, jetzt {} Geschosse",
                target.getId(), GeometryUtils.formatNum(traufeZ), GeometryUtils.formatNum(top), storeys.size());
        return true;
    }

    /** true, wenn eine nicht senkrechte Konturkante (Horizontalversatz > 1 cm) die Hoehe z kreuzt oder
     *  hoechstens {@code band} darueber beginnt. */
    static boolean slopedEdgeNear(List<Point3D> contour, double z, double band) {
        int n = contour.size();
        for (int i = 0; i < n; i++) {
            Point3D a = contour.get(i), b = contour.get((i + 1) % n);
            if (Math.hypot(b.x - a.x, b.y - a.y) <= 0.01) continue;
            double lo = Math.min(a.z, b.z), hi = Math.max(a.z, b.z);
            if (hi <= z + 0.001) continue;
            if (lo <= z + band) return true;
        }
        return false;
    }

    /** Laengste zusammenhaengende waagerechte Oberkante (XY-Laenge) auf Hoehe zMax. */
    static double topEdgeWidth(List<Point3D> pts, double zMax) {
        // Nur zusammenhaengende Kanten zaehlen: einzelne Spitzen (Giebel, Saegezahn-Oberkante) liefern 0.
        int n = pts.size();
        double best = 0, run = 0, perimeter = 0;
        for (int k = 0; k < 2 * n; k++) {
            Point3D a = pts.get(k % n), b = pts.get((k + 1) % n);
            double len = Math.hypot(b.x - a.x, b.y - a.y);
            if (k < n) perimeter += len;
            if (a.z >= zMax - CUT_TOLERANCE && b.z >= zMax - CUT_TOLERANCE) {
                run += len;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
        }
        return Math.min(best, perimeter);
    }

    // ==================== Innere Klassen ====================

    /** Beschreibt ein Geschoss mit seinen Z-Grenzen. */
    private record StoreyInfo(
            String geschoss,    // Tag: GF, UF_1, UF_2, ... (BA wird vom BasementGenerator erzeugt)
            double floorZ,      // Unterkante (absolut, m ue. NHN)
            double ceilingZ,    // Oberkante (absolut)
            boolean isTopStorey // true = oberstes Geschoss (reicht bis Traufe bzw. Wand-Oberkante darueber)
    ) {}

    public static class GenerationStats extends AbstractGenerator.BaseStats {
        public int storeysCreated = 0;
        public int wallsCut = 0;
        public int wallSegmentsCreated = 0;
        public int floorsCreated = 0;
        public int ceilingsCreated = 0;
        /** Gebaeude(teile) mit zusaetzlichen Geschossen oberhalb der Traufe. */
        public int upperStoreysAdded = 0;
        /** Gebaeude(teile) mit einer Wand, die das Mindestmass ueber die Traufe ragt. */
        public int upperStoreyCandidates = 0;
        public int upperSkippedSlabLimit = 0;
        public int upperSkippedRegion = 0;
        public int upperSkippedHeight = 0;
    }

    // ==================== Slab-Geometrie ====================

    /** Baut aus einem {@link SlabPiece} (bereits auf Ziel-Z) ein Polygon mit erzwungener
     * Normalenrichtung; Innenringe (seltene Anbau-Loecher) bekommen die entgegengesetzte
     * Windung, wie bei Fenster-/Tueroeffnungen ueblich. */
    private Polygon buildSlabPolygon(SlabPiece piece, boolean wantUpward) {
        List<Point3D> exterior = GeometryUtils.orientForNormalZ(piece.exterior(), wantUpward);
        List<List<Point3D>> interiors = new ArrayList<>();
        for (List<Point3D> hole : piece.interiors()) {
            interiors.add(GeometryUtils.orientForNormalZ(hole, !wantUpward));
        }
        return SlabClippingUtils.createPolygonWithHoles(exterior, interiors);
    }
}
