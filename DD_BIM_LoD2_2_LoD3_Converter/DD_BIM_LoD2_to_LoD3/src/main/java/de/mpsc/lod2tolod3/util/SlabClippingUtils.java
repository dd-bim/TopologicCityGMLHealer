package de.mpsc.lod2tolod3.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.geometry.primitives.AbstractRingProperty;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * Zuschnitt von Geschossflaechen (Boeden/Decken) auf Hoehe z bei Anbauten, mittels JTS.
 */
public final class SlabClippingUtils {

    private static final Logger log = LoggerFactory.getLogger(SlabClippingUtils.class);

    private SlabClippingUtils() {
        // Utility-Klasse
    }

    /** Ein Teilstueck einer Geschossflaeche bei fester Hoehe: offener Aussenring + (selten)
     * offene Innenringe. Mehrere Teilstuecke entstehen, wenn ein niedriger Anbau in der Mitte
     * das Gebaeude in getrennte Fluegel teilt. */
    public record SlabPiece(List<Point3D> exterior, List<List<Point3D>> interiors) {}

    /** Kleinere Zuschnitt-Ergebnisse sind Artefakte (Rundungsschlieren), keine echten Raeume. */
    private static final double MIN_SLAB_AREA = 0.50;

    /** Grundriss- und Dachpolygone stammen aus unabhaengig digitalisierten LoD2-Flaechen und
     * treffen sich an gemeinsamen Gebaeudeecken oft nicht exakt (Abweichungen im cm-Bereich). Ohne
     * Gegenmassnahme erzeugt JTS' difference() dort einen entarteten "Spike" statt eines sauberen
     * gemeinsamen Eckpunkts. Fix: `excluded` wird um diese Toleranz aufgeblaht (`buffer`) — eine
     * rein lokale, monotone Vergroesserung, die (anders als GeometrySnapper) nirgendwo im
     * Grundriss Punkte verschiebt und daher keine schwebenden Slabs erzeugen kann, siehe Doku.md.
     * Bewusst mit JOIN_BEVEL statt dem JTS-Standard JOIN_ROUND: ein
     * Rundungs-Join naehert jede Ecke durch mehrere kurze Kreisbogen-Segmente an (Default 8 pro
     * Viertelkreis) — bei 2cm Radius liegen deren Punkte nur Millimeter auseinander und wurden bei
     * einem realen Gebaeude als eigenstaendiger neuer Fehler erkannt (GE_R_CONSECUTIVE_POINTS_SAME,
     * siehe Doku.md). Bevel schneidet die Ecke stattdessen mit maximal einem zusaetzlichen,
     * ausreichend weit entfernten Punkt gerade ab — kein Spike-Risiko wie bei einem Mitre-Join. */
    private static final double SLAB_EXCLUSION_GROW_TOL = 0.02;

    private static final org.locationtech.jts.operation.buffer.BufferParameters
            SLAB_EXCLUSION_GROW_PARAMS = new org.locationtech.jts.operation.buffer.BufferParameters();
    static {
        SLAB_EXCLUSION_GROW_PARAMS.setJoinStyle(
                org.locationtech.jts.operation.buffer.BufferParameters.JOIN_BEVEL);
    }

    /**
     * Grundpolygon auf Hoehe z, abzueglich aller Dachanteile die auf/unter z liegen — verhindert
     * schwebende Slabs ueber Anbauten, die sich das Grundpolygon mit einem hoeheren Hauptbau
     * teilen (kein eigenes BuildingPart), siehe Doku.md. Ergebnispunkte liegen bereits auf z.
     * Leere Liste = an dieser Hoehe traegt nichts (mehr).
     */
    public static List<SlabPiece> clipSlabAtZ(List<Point3D> groundPts, List<Polygon> roofPolygons,
            double z, double tolerance) {
        List<Point3D> ground = GeometryUtils.removeClosingPoint(groundPts);
        if (ground.size() < 3) return List.of();

        org.locationtech.jts.geom.Geometry excluded = roofAreaBelowZ(roofPolygons, z, tolerance);
        if (excluded == null) {
            return List.of(new SlabPiece(GeometryUtils.projectToZ(ground, z), List.of()));
        }

        org.locationtech.jts.geom.Polygon footprint = toJts(ground, List.of());
        if (footprint == null || !excluded.intersects(footprint)) {
            return List.of(new SlabPiece(GeometryUtils.projectToZ(ground, z), List.of()));
        }

        try {
            org.locationtech.jts.geom.Geometry excludedGrown =
                    org.locationtech.jts.operation.buffer.BufferOp.bufferOp(
                            excluded, SLAB_EXCLUSION_GROW_TOL, SLAB_EXCLUSION_GROW_PARAMS);
            return toSlabPieces(footprint.difference(excludedGrown), z);
        } catch (RuntimeException e) {
            log.warn("  clipSlabAtZ: JTS-Differenz fehlgeschlagen bei z={} ({}), Flaeche unveraendert",
                    GeometryUtils.formatNum(z), e.toString());
            return List.of(new SlabPiece(GeometryUtils.projectToZ(ground, z), List.of()));
        }
    }

    /** Vereinigte 2D-Flaeche aller Dachanteile auf/unter z (jeweils per {@link WallCuttingUtils#splitWallByZ}
     * aus dem, ggf. geneigten, Dachpolygon herausgeschnitten). Null = kein Dach reicht so tief. */
    private static org.locationtech.jts.geom.Geometry roofAreaBelowZ(
            List<Polygon> roofPolygons, double z, double tolerance) {
        org.locationtech.jts.geom.Geometry union = null;
        for (Polygon roof : roofPolygons) {
            List<Point3D> pts = GeometryUtils.removeClosingPoint(GeometryUtils.toPoints(roof));
            if (pts.size() < 3) continue;
            double minZ = Double.MAX_VALUE;
            for (Point3D p : pts) minZ = Math.min(minZ, p.z);
            if (minZ > z + tolerance) continue; // Fast-Path: Dach liegt komplett oberhalb z

            for (List<Point3D> piece : WallCuttingUtils.splitWallByZ(pts, z, tolerance).lower()) {
                org.locationtech.jts.geom.Polygon jtsPoly = toJts(piece, List.of());
                if (jtsPoly == null) continue;
                if (union == null) {
                    union = jtsPoly;
                    continue;
                }
                try {
                    union = union.union(jtsPoly);
                } catch (RuntimeException e) {
                    // "non-noded intersection" bei fast deckungsgleichen Sub-mm-Segmenten (haeufig bei
                    // Schnitthoehen oberhalb der Traufe): robuste Overlay-Variante mit Snapping.
                    try {
                        union = org.locationtech.jts.operation.overlayng.OverlayNGRobust.overlay(
                                polygonal(union), polygonal(jtsPoly),
                                org.locationtech.jts.operation.overlayng.OverlayNG.UNION);
                    } catch (RuntimeException e2) {
                        // Letzter Ausweg: Dachstueck auslassen statt Gebaeude/Kachel abstuerzen zu lassen
                        // (Slab bleibt an dieser Stelle ggf. zu gross).
                        log.warn("  roofAreaBelowZ: JTS-Union fehlgeschlagen bei z={} ({}), "
                                        + "Dachstueck uebersprungen (Ausschlussflaeche bleibt unveraendert)",
                                GeometryUtils.formatNum(z), e2.toString());
                    }
                }
            }
        }
        return union;
    }

    /** Nur die gueltigen Flaechenanteile — die robuste Overlay-Variante akzeptiert weder gemischte
     * (Flaeche + Linienreste entarteter Dachstuecke) noch ungueltige Eingaben. */
    private static org.locationtech.jts.geom.Geometry polygonal(org.locationtech.jts.geom.Geometry g) {
        org.locationtech.jts.geom.Geometry valid =
                g.isValid() ? g : org.locationtech.jts.geom.util.GeometryFixer.fix(g);
        org.locationtech.jts.geom.Geometry polys = valid.getFactory().buildGeometry(
                org.locationtech.jts.geom.util.PolygonExtracter.getPolygons(valid));
        return polys.isValid() ? polys : org.locationtech.jts.operation.overlayng.OverlayNGRobust.union(polys);
    }

    /** Baut ein JTS-Polygon (2D, Z wird verworfen) aus offenen Punktlisten; null bei Entartung. */
    private static org.locationtech.jts.geom.Polygon toJts(List<Point3D> exterior,
            List<List<Point3D>> holes) {
        org.locationtech.jts.geom.GeometryFactory gf = new org.locationtech.jts.geom.GeometryFactory();
        org.locationtech.jts.geom.LinearRing shell = toJtsRing(gf, exterior);
        if (shell == null) return null;
        org.locationtech.jts.geom.LinearRing[] jtsHoles = new org.locationtech.jts.geom.LinearRing[holes.size()];
        for (int i = 0; i < holes.size(); i++) {
            org.locationtech.jts.geom.LinearRing h = toJtsRing(gf, holes.get(i));
            if (h == null) return null;
            jtsHoles[i] = h;
        }
        return gf.createPolygon(shell, jtsHoles);
    }

    private static org.locationtech.jts.geom.LinearRing toJtsRing(
            org.locationtech.jts.geom.GeometryFactory gf, List<Point3D> pts) {
        List<Point3D> open = GeometryUtils.removeClosingPoint(
                GeometryUtils.dedupConsecutive(pts, GeometryUtils.POINT_MERGE_TOL));
        if (open.size() < 3) return null;
        org.locationtech.jts.geom.Coordinate[] coords = new org.locationtech.jts.geom.Coordinate[open.size() + 1];
        for (int i = 0; i < open.size(); i++) {
            coords[i] = new org.locationtech.jts.geom.Coordinate(open.get(i).x, open.get(i).y);
        }
        coords[open.size()] = coords[0];
        return gf.createLinearRing(coords);
    }

    /** Zerlegt ein JTS-Differenz-Ergebnis (Polygon oder MultiPolygon) in {@link SlabPiece}s auf
     * Hoehe z; zu kleine Teilstuecke/Loecher (Zuschnitt-Artefakte) werden verworfen. */
    private static List<SlabPiece> toSlabPieces(org.locationtech.jts.geom.Geometry result, double z) {
        return toSlabPieces(result, z, true);
    }

    private static List<SlabPiece> toSlabPieces(org.locationtech.jts.geom.Geometry result, double z, boolean sanitize) {
        List<SlabPiece> pieces = new ArrayList<>();
        for (int i = 0; i < result.getNumGeometries(); i++) {
            if (!(result.getGeometryN(i) instanceof org.locationtech.jts.geom.Polygon jtsPoly)) continue;
            List<Point3D> exterior = toOpenRing(jtsPoly.getExteriorRing(), z);
            if (exterior.size() < 3 || GeometryUtils.calculatePolygonArea2D(exterior) < MIN_SLAB_AREA) continue;

            List<List<Point3D>> interiors = new ArrayList<>();
            for (int h = 0; h < jtsPoly.getNumInteriorRing(); h++) {
                List<Point3D> hole = toOpenRing(jtsPoly.getInteriorRingN(h), z);
                if (hole.size() >= 3 && GeometryUtils.calculatePolygonArea2D(hole) >= MIN_SLAB_AREA) interiors.add(hole);
            }
            SlabPiece piece = new SlabPiece(exterior, interiors);
            if (sanitize) pieces.addAll(sanitizeSlabPiece(piece, z, true));
            else pieces.add(piece);
        }
        return pieces;
    }

    /** CityDoctor2 prueft Ringe auf mm gerundet: ein Eckpunkt naeher als diese Toleranz an einer nicht
     *  angrenzenden Kante gilt dort als Selbstberuehrung (GE_R_SELF_INTERSECTION). */
    private static final double SLAB_NEAR_TOUCH_TOL = 0.001;
    /** Deckenstuecke: groessere Toleranz — auch mm-Zacken (Eckpunkt 1–5 mm neben einer Kante) meldet
     *  CityDoctor2 nach dem Runden als Selbstberuehrung; Decken sind nicht Teil der Huelle. */
    private static final double SLAB_SPIKE_TOL = 0.005;

    /** Zuschnitte gegen duenne Dach-Ausschlussstreifen (z.B. knapp ueber einer Traufe) koennen
     *  Rueckwaerts-Spitzen enthalten, deren Eckpunkt im Sub-mm-Bereich an einer anderen Kante liegt —
     *  geometrisch gueltig, fuer CityDoctor2 selbstberuehrend. Nur solche Stuecke werden bereinigt
     *  (Spitzen entfernen, notfalls JTS-GeometryFixer, sonst verwerfen); alle anderen bleiben exakt
     *  unveraendert. Boden und Decke derselben Hoehe durchlaufen denselben Weg (XLink bleibt konsistent). */
    private static List<SlabPiece> sanitizeSlabPiece(SlabPiece piece, double z, boolean allowFixer) {
        List<Point3D> ext = GeometryUtils.dedupConsecutive(piece.exterior(), GeometryUtils.SLAB_RING_DEDUP_TOL);
        if (ext.size() >= 3 && !slabRingTouchesItself(ext, SLAB_SPIKE_TOL)) return List.of(piece);

        List<Point3D> cleaned = removeSlabSpikes(ext, SLAB_SPIKE_TOL);
        if (cleaned.size() >= 3 && !slabRingTouchesItself(cleaned, SLAB_SPIKE_TOL)
                && GeometryUtils.calculatePolygonArea2D(cleaned) >= MIN_SLAB_AREA) {
            log.debug("  Deckenstueck bei z={}: {} Spitzen-/Zwischenpunkte entfernt",
                    GeometryUtils.formatNum(z), ext.size() - cleaned.size());
            return List.of(new SlabPiece(cleaned, piece.interiors()));
        }
        if (allowFixer && cleaned.size() >= 3) {
            try {
                org.locationtech.jts.geom.Polygon jp = toJts(cleaned, piece.interiors());
                if (jp != null) {
                    List<SlabPiece> out = new ArrayList<>();
                    for (SlabPiece p : toSlabPieces(org.locationtech.jts.geom.util.GeometryFixer.fix(jp), z, false)) {
                        out.addAll(sanitizeSlabPiece(p, z, false));
                    }
                    if (!out.isEmpty()) return out;
                }
            } catch (RuntimeException e) {
                log.debug("  Deckenstueck bei z={}: GeometryFixer fehlgeschlagen ({})", GeometryUtils.formatNum(z), e.toString());
            }
        }
        log.warn("  Deckenstueck bei z={} beruehrt sich selbst und ist nicht reparierbar — verworfen", GeometryUtils.formatNum(z));
        return List.of();
    }

    /** true, wenn ein Eckpunkt des offenen, horizontalen Rings naeher als {@link #SLAB_NEAR_TOUCH_TOL}
     *  an einer nicht angrenzenden Kante liegt oder sich Kanten kreuzen. */
    static boolean slabRingTouchesItself(List<Point3D> ring) {
        return slabRingTouchesItself(ring, SLAB_NEAR_TOUCH_TOL);
    }

    static boolean slabRingTouchesItself(List<Point3D> ring, double tol) {
        int n = ring.size();
        if (n < 3) return true;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int j2 = (j + 1) % n;
                if (j == i || j2 == i) continue;
                if (distPointSegment2D(ring.get(i), ring.get(j), ring.get(j2)) < tol) return true;
            }
        }
        org.locationtech.jts.geom.Coordinate[] c = new org.locationtech.jts.geom.Coordinate[n + 1];
        for (int i = 0; i < n; i++) c[i] = new org.locationtech.jts.geom.Coordinate(ring.get(i).x, ring.get(i).y);
        c[n] = c[0];
        return !new org.locationtech.jts.geom.GeometryFactory().createLineString(c).isSimple();
    }

    /** Entfernt Rueckwaerts-Spitzen (Pfad laeuft zu einem Punkt und auf derselben Linie zurueck) sowie
     *  nahezu kollineare Zwischenpunkte aus einem offenen, horizontalen Ring. */
    static List<Point3D> removeSlabSpikes(List<Point3D> ring) {
        return removeSlabSpikes(ring, SLAB_NEAR_TOUCH_TOL);
    }

    static List<Point3D> removeSlabSpikes(List<Point3D> ring, double tol) {
        List<Point3D> r = new ArrayList<>(ring);
        boolean changed = true;
        while (changed && r.size() > 3) {
            changed = false;
            for (int i = 0; i < r.size() && r.size() > 3; i++) {
                int n = r.size();
                Point3D a = r.get((i + n - 1) % n), b = r.get(i), c = r.get((i + 1) % n);
                if (distPointSegment2D(c, a, b) < tol
                        || distPointSegment2D(a, b, c) < tol
                        || distPointSegment2D(b, a, c) < tol) {
                    r.remove(i);
                    i--;
                    changed = true;
                }
            }
        }
        return r;
    }

    private static double distPointSegment2D(Point3D p, Point3D a, Point3D b) {
        double dx = b.x - a.x, dy = b.y - a.y, len2 = dx * dx + dy * dy;
        double t = len2 < 1e-18 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2));
        double ex = p.x - (a.x + t * dx), ey = p.y - (a.y + t * dy);
        return Math.sqrt(ex * ex + ey * ey);
    }

    /** JTS-Ring (geschlossen) als offene Punktliste auf Hoehe z. */
    private static List<Point3D> toOpenRing(org.locationtech.jts.geom.LineString jtsRing, double z) {
        org.locationtech.jts.geom.Coordinate[] coords = jtsRing.getCoordinates();
        List<Point3D> pts = new ArrayList<>(Math.max(0, coords.length - 1));
        for (int i = 0; i < coords.length - 1; i++) { // letzter == erster (geschlossen): weglassen
            pts.add(new Point3D(coords[i].x, coords[i].y, z));
        }
        return pts;
    }

    /** Vereinigt mehrere Grundriss-Ringe (XY) zu ihren zusammenhaengenden Aussenkonturen —
     *  innenliegende Trennkanten UND mm-Sliver zwischen angrenzenden Polygonen verschwinden
     *  (Keller bei mehreren GroundSurfaces, siehe Doku.md). Ergebnis: offene Ringe, Umlaufsinn
     *  wie der erste Eingabering, Z vom ersten Eingabepunkt. Null = Vereinigung nicht moeglich
     *  (JTS-Fehler, Loecher im Ergebnis, entartete Eingabe) — Aufrufer bleibt bei den
     *  Einzelpolygonen. */
    public static List<List<Point3D>> unionFootprints(List<List<Point3D>> rings) {
        if (rings == null || rings.size() < 2) return null;
        try {
            List<org.locationtech.jts.geom.Geometry> polys = new ArrayList<>();
            for (List<Point3D> r : rings) {
                org.locationtech.jts.geom.Polygon p = toJts(r, List.of());
                if (p == null) return null;
                polys.add(p);
            }
            org.locationtech.jts.geom.Geometry union =
                    org.locationtech.jts.operation.union.UnaryUnionOp.union(polys);
            if (union == null || union.isEmpty()) return null;
            boolean ccw = org.locationtech.jts.algorithm.Orientation.isCCW(
                    ((org.locationtech.jts.geom.Polygon) polys.get(0)).getExteriorRing().getCoordinates());
            double z = rings.get(0).get(0).z;
            List<List<Point3D>> out = new ArrayList<>();
            for (int i = 0; i < union.getNumGeometries(); i++) {
                if (!(union.getGeometryN(i) instanceof org.locationtech.jts.geom.Polygon jp)) return null;
                if (jp.getNumInteriorRing() > 0) return null;
                List<Point3D> ring = toOpenRing(jp.getExteriorRing(), z);
                // JTS-gueltig, aber im mm-Raster (CityDoctor2) selbstberuehrend: Einzelpolygone behalten.
                if (ring.size() < 3 || slabRingTouchesItself(ring)) return null;
                if (org.locationtech.jts.algorithm.Orientation.isCCW(jp.getExteriorRing().getCoordinates()) != ccw) {
                    java.util.Collections.reverse(ring);
                }
                out.add(ring);
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("  unionFootprints: JTS-Vereinigung fehlgeschlagen ({}), Einzelpolygone bleiben", e.toString());
            return null;
        }
    }

    /** Fitzelchen-Gate: true, wenn die vereinigten Teilstuecke mindestens einen zusammenhaengenden
     *  Bereich mit Flaeche >= minArea bilden, der irgendwo mindestens 2 x halfWidth breit ist. */
    public static boolean hasSubstantialRegion(List<SlabPiece> pieces, double minArea, double halfWidth) {
        try {
            List<org.locationtech.jts.geom.Geometry> polys = new ArrayList<>();
            for (SlabPiece p : pieces) {
                org.locationtech.jts.geom.Polygon jp = toJts(p.exterior(), p.interiors());
                if (jp != null) polys.add(jp);
            }
            if (polys.isEmpty()) return false;
            org.locationtech.jts.geom.Geometry union =
                    org.locationtech.jts.operation.union.UnaryUnionOp.union(polys);
            for (int i = 0; i < union.getNumGeometries(); i++) {
                org.locationtech.jts.geom.Geometry g = union.getGeometryN(i);
                if (g.getArea() >= minArea && !g.buffer(-halfWidth).isEmpty()) return true;
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("  hasSubstantialRegion: JTS-Fehler ({}), Bereich gilt als zu klein", e.toString());
            return false;
        }
    }

    /** 2D-Nettoflaeche eines Teilstuecks (Aussenring minus Loecher). */
    public static double calculateNetArea2D(SlabPiece piece) {
        double area = GeometryUtils.calculatePolygonArea2D(piece.exterior());
        for (List<Point3D> hole : piece.interiors()) {
            area -= GeometryUtils.calculatePolygonArea2D(hole);
        }
        return area;
    }

    /** Wie {@link GeometryUtils#createPolygon(List)}, zusaetzlich mit Innenringen (gml:interior).
     * Ausschliesslich fuer clipSlabAtZ-Geschossdecken/-boeden verwendet, daher
     * {@link GeometryUtils#SLAB_RING_DEDUP_TOL} statt der allgemeinen {@link GeometryUtils#RING_DEDUP_TOL}
     * fuer den Aussenring. */
    public static Polygon createPolygonWithHoles(List<Point3D> exterior, List<List<Point3D>> interiors) {
        Polygon poly = GeometryUtils.createPolygon(exterior, GeometryUtils.SLAB_RING_DEDUP_TOL);
        for (List<Point3D> hole : interiors) {
            poly.getInterior().add(new AbstractRingProperty(GeometryUtils.createLinearRing(hole)));
        }
        return poly;
    }
}
