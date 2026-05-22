package de.mpsc.lod2tolod3.util;

import org.citygml4j.core.model.CityGMLVersion;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.CeilingSurface;
import org.citygml4j.core.model.construction.FloorSurface;
import org.citygml4j.core.model.construction.GroundSurface;
import org.citygml4j.core.model.construction.RoofSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.core.AbstractCityObject;
import org.citygml4j.core.model.core.AbstractFeature;
import org.citygml4j.core.model.core.AbstractGenericAttributeProperty;
import org.citygml4j.core.model.core.AbstractSpaceBoundaryProperty;
import org.citygml4j.core.model.core.AbstractThematicSurface;
import org.citygml4j.core.model.generics.StringAttribute;
import org.citygml4j.xml.CityGMLContext;
import org.citygml4j.xml.reader.CityGMLReader;
import org.citygml4j.xml.reader.ChunkOptions;
import org.citygml4j.xml.writer.CityGMLChunkWriter;
import org.citygml4j.xml.writer.CityGMLOutputFactory;
import org.xmlobjects.gml.model.basictypes.Code;
import org.xmlobjects.gml.model.geometry.DirectPositionList;
import org.xmlobjects.gml.model.geometry.aggregates.MultiCurve;
import org.xmlobjects.gml.model.geometry.aggregates.MultiCurveProperty;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurface;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.AbstractRingProperty;
import org.xmlobjects.gml.model.geometry.primitives.CurveProperty;
import org.xmlobjects.gml.model.geometry.primitives.LinearRing;
import org.xmlobjects.gml.model.geometry.primitives.LineString;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;
import org.xmlobjects.gml.model.geometry.primitives.SolidProperty;
import org.xmlobjects.gml.model.geometry.primitives.SurfaceProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Gemeinsame Hilfsfunktionen für CityGML-Verarbeitung.
 */
public final class CityGmlUtils {

    /** Epsilon fuer Punkt-Gleichheit (XYZ-Vergleich). */
    private static final double POINT_EQUALITY_EPS = 1e-6;

    private CityGmlUtils() {
        // Utility-Klasse
    }

    /**
     * Liest ein StringAttribute aus einem AbstractCityObject
     * (Building, WallSurface, FloorSurface, etc.).
     */
    public static String getStringAttribute(AbstractCityObject cityObject, String name) {
        if (cityObject.getGenericAttributes() == null) {
            return null;
        }
        return cityObject.getGenericAttributes().stream()
                .map(AbstractGenericAttributeProperty::getObject)
                .filter(Objects::nonNull)
                .filter(attr -> attr instanceof StringAttribute)
                .map(attr -> (StringAttribute) attr)
                .filter(attr -> name.equals(attr.getName()))
                .map(StringAttribute::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * Fuegt ein StringAttribute zu einem AbstractCityObject hinzu
     * (Building, WallSurface, FloorSurface, etc.).
     */
    public static void addStringAttribute(AbstractCityObject cityObject, String name, String value) {
        StringAttribute attr = new StringAttribute(name, value);
        AbstractGenericAttributeProperty prop = new AbstractGenericAttributeProperty(attr);
        cityObject.getGenericAttributes().add(prop);
    }

    /**
     * Setzt ein StringAttribute auf einem AbstractCityObject.
     * Aktualisiert den Wert wenn das Attribut bereits existiert,
     * fuegt es sonst neu hinzu.
     * Verwenden fuer Updates bestehender Attribute (z.B. nach Geometrie-Aenderung).
     */
    public static void setStringAttribute(AbstractCityObject cityObject, String name, String value) {
        if (cityObject.getGenericAttributes() != null) {
            for (var prop : cityObject.getGenericAttributes()) {
                if (prop.getObject() instanceof StringAttribute sa && name.equals(sa.getName())) {
                    sa.setValue(value);
                    return;
                }
            }
        }
        addStringAttribute(cityObject, name, value);
    }

    /**
     * Liest ein StringAttribute als Double.
     * Gibt null zurueck bei fehlendem Attribut oder Parse-Fehler.
     */
    public static Double parseDoubleAttribute(AbstractCityObject cityObject, String name) {
        String str = getStringAttribute(cityObject, name);
        if (str == null) return null;
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Setzt den gml:name eines CityGML-Objekts.
     * Wird als <gml:name>...</gml:name> serialisiert.
     */
    public static void setGmlName(AbstractCityObject cityObject, String name) {
        cityObject.getNames().add(new Code(name));
    }

    // ==================== Geometrie-Berechnungen ====================

    /**
     * Berechnet die 2D-Flaeche eines Polygons (Gauss'sche Trapezformel / Shoelace).
     * Ignoriert die Z-Koordinate, berechnet die projizierte Flaeche in der XY-Ebene.
     *
     * Formel: A = 0.5 * |sum(x_i * y_{i+1} - x_{i+1} * y_i)|
     */
    public static double calculatePolygonArea2D(List<Point3D> points) {
        List<Point3D> pts = removeClosingPoint(points);
        double area = 0.0;
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            Point3D current = pts.get(i);
            Point3D next = pts.get((i + 1) % n);
            area += current.x * next.y - next.x * current.y;
        }
        return Math.abs(area) / 2.0;
    }

    /**
     * Berechnet den Azimut der Wandnormalen fuer eine vertikale Wand
     * definiert durch die Kante A -> B.
     *
     * Die Wandnormale steht senkrecht auf der Kante in der Horizontalebene.
     * Fuer ein Polygon mit CW-Wicklung (CityGML GroundSurface) zeigt die
     * Normale nach aussen.
     *
     * Berechnung:
     *   Kantenrichtung: (dx, dy) = (B.x - A.x, B.y - A.y)
     *   Wandnormale:    (-dy, dx)  (Kreuzprodukt Kante x Vertikale)
     *   Azimut:         atan2(normal_east, normal_north) = atan2(-dy, dx)
     *
     * Ergebnis: Kompassrichtung in Grad (0=Nord, 90=Ost, 180=Sued, 270=West).
     */
    private static double calculateWallNormalAzimuth(Point3D a, Point3D b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double azimuth = Math.toDegrees(Math.atan2(-dy, dx));
        if (azimuth < 0) azimuth += 360.0;
        return azimuth;
    }

    /**
     * Berechnet die 2D-Kantenlaenge zwischen zwei Punkten (ignoriert Z).
     */
    public static double calculateEdgeLength2D(Point3D a, Point3D b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Formatiert einen double-Wert als String, analog zu den bestehenden
     * Attribut-Werten in der GML-Datei (max. 5 Nachkommastellen, ohne
     * abschliessende Nullen).
     */
    public static String formatNum(double value) {
        String s = String.format(java.util.Locale.US, "%.5f", value);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            s = s.replaceAll("\\.$", "");
        }
        return s;
    }

    /**
     * Rundet einen Z-Wert auf 3 Nachkommastellen (mm-Genauigkeit).
     * Verhindert Floating-Point-Artefakte bei Additions-Operationen
     * wie z.B. 159.57 + 1.26 = 160.82999999999998 → 160.83.
     */
    public static double roundZ(double z) {
        return Math.round(z * 1000.0) / 1000.0;
    }

    /**
     * Extrahiert 3D-Punkte aus einem Polygon.
     */
    public static List<Point3D> toPoints(Polygon polygon) {
        if (polygon.getExterior() == null || polygon.getExterior().getObject() == null) {
            return Collections.emptyList();
        }
        LinearRing ring = (LinearRing) polygon.getExterior().getObject();
        if (ring.getControlPoints() == null || ring.getControlPoints().getPosList() == null) {
            return Collections.emptyList();
        }
        DirectPositionList posList = ring.getControlPoints().getPosList();
        List<Double> coords = posList.getValue();
        if (coords == null || coords.isEmpty()) {
            return Collections.emptyList();
        }

        List<Point3D> points = new ArrayList<>();
        for (int i = 0; i + 2 < coords.size(); i += 3) {
            points.add(new Point3D(coords.get(i), coords.get(i + 1), coords.get(i + 2)));
        }
        return points;
    }

    /**
     * Erstellt ein Polygon aus einer Liste von 3D-Punkten.
     */
    public static Polygon createPolygon(List<Point3D> pts) {
        List<Point3D> closed = new ArrayList<>(pts);
        if (!pts.isEmpty()) {
            Point3D first = pts.get(0);
            Point3D last = pts.get(pts.size() - 1);
            if (!first.nearlyEquals(last)) {
                closed.add(first);
            }
        }

        List<Double> coords = new ArrayList<>(closed.size() * 3);
        for (Point3D p : closed) {
            coords.add(p.x);
            coords.add(p.y);
            coords.add(p.z);
        }

        DirectPositionList posList = new DirectPositionList(coords);
        posList.setSrsDimension(3);

        LinearRing ring = new LinearRing();
        ring.getControlPoints().setPosList(posList);

        Polygon poly = new Polygon();
        poly.setExterior(new AbstractRingProperty(ring));
        return poly;
    }

    /**
     * Entfernt den schließenden Punkt eines Polygons (wenn erster == letzter).
     */
    public static List<Point3D> removeClosingPoint(List<Point3D> pts) {
        if (pts.size() < 2) {
            return pts;
        }
        Point3D first = pts.get(0);
        Point3D last = pts.get(pts.size() - 1);
        if (first.nearlyEquals(last)) {
            return pts.subList(0, pts.size() - 1);
        }
        return pts;
    }

    // ==================== Gebäude-Abfragen ====================

    /**
     * Sammelt alle Boundary-Objekte eines bestimmten Typs.
     * Beispiel: collectBoundariesByType(building, WallSurface.class)
     */
    public static <T> List<T> collectBoundariesByType(AbstractBuilding building, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (var boundary : building.getBoundaries()) {
            if (type.isInstance(boundary.getObject())) {
                result.add(type.cast(boundary.getObject()));
            }
        }
        return result;
    }

    /**
     * Sammelt alle WallSurface-Objekte eines Gebaeudes oder BuildingParts.
     */
    public static List<WallSurface> collectWallSurfaces(AbstractBuilding building) {
        return collectBoundariesByType(building, WallSurface.class);
    }

    /**
     * Gibt alle Verarbeitungsziele eines Buildings zurueck (BuildingParts + Building selbst).
     * Parts kommen zuerst, dann das Building (falls es eigene Boundaries hat).
     */
    public static List<AbstractBuilding> getBuildingTargets(Building building) {
        List<AbstractBuilding> targets = new ArrayList<>();
        if (building.getBuildingParts() != null) {
            for (var pp : building.getBuildingParts()) {
                if (pp.getObject() != null) targets.add(pp.getObject());
            }
        }
        if (!building.getBoundaries().isEmpty()) {
            targets.add(building);
        }
        return targets;
    }

    /**
     * Sammelt alle GroundSurface-Polygone eines Gebäudes oder BuildingParts.
     * Sucht zuerst in lod3MultiSurface, dann lod2MultiSurface.
     */
    public static List<Polygon> collectGroundPolygons(AbstractBuilding building) {
        List<Polygon> polygons = new ArrayList<>();
        for (var boundary : building.getBoundaries()) {
            if (boundary.getObject() instanceof GroundSurface gs) {
                MultiSurfaceProperty msp = gs.getLod3MultiSurface();
                if (msp == null || msp.getObject() == null) continue;
                for (var member : msp.getObject().getSurfaceMember()) {
                    if (member.getObject() instanceof Polygon poly) {
                        polygons.add(poly);
                    }
                }
            }
        }
        return polygons;
    }

    /**
     * Sammelt alle RoofSurface-Polygone eines Gebaeuedes oder BuildingParts.
     */
    public static List<Polygon> collectRoofPolygons(AbstractBuilding building) {
        List<Polygon> polygons = new ArrayList<>();
        for (var boundary : building.getBoundaries()) {
            if (boundary.getObject() instanceof RoofSurface rs) {
                MultiSurfaceProperty msp = rs.getLod3MultiSurface();
                if (msp == null || msp.getObject() == null) continue;
                for (var member : msp.getObject().getSurfaceMember()) {
                    if (member.getObject() instanceof Polygon poly) {
                        polygons.add(poly);
                    }
                }
            }
        }
        return polygons;
    }

    /**
     * Ermittelt Traufe (Z_MIN) und First (Z_MAX) der RoofSurface-Polygone.
     *
     * Als traufeZ wird das Z_MIN der flaechengroessten RoofSurface verwendet,
     * da kleine Nebenflaechen (z.B. Attika-Absaetze) nicht die Geschosshoehe
     * bestimmen sollen. Falls kein FACEAREA-Attribut vorhanden ist, wird das
     * globale Z_MIN verwendet (Fallback).
     *
     * Mischdach-Sonderfall: Falls das Gebaeude sowohl flache (DachTyp_LOD2=1000)
     * als auch geneigte Dachflaechen hat, wird die Traufe aus den geneigten
     * Flaechen bestimmt. Flache Teilflaechen (z.B. Innenhof, Anbau) koennen
     * eine groessere Flaeche haben als einzelne geneigte Dachflaechen und
     * wuerden sonst faelschlicherweise die Traufe nach unten ziehen.
     *
     * @return double[]{traufeZ, firstZ, rawMinZ} oder null wenn keine RoofSurface vorhanden
     */
    public static double[] getRoofZRange(AbstractBuilding building) {
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        double dominantMinZ = Double.MAX_VALUE;
        double dominantArea = -1;
        // Separate Verfolgung geneigter Dachflaechen fuer Mischdach-Erkennung
        double slopedDominantMinZ = Double.MAX_VALUE;
        double slopedDominantArea = -1;
        double slopedRawMinZ = Double.MAX_VALUE; // Fallback: globales MinZ aller geneigten Flaechen
        boolean hasSlopedRoof = false;
        boolean hasFlatRoof = false;
        for (var boundary : building.getBoundaries()) {
            if (boundary.getObject() instanceof RoofSurface rs) {
                // FACEAREA-Attribut lesen (gesetzt von Schritt 1 / Promoter)
                double area = -1;
                String faceAreaStr = getStringAttribute(rs, "FACEAREA");
                if (faceAreaStr != null) {
                    try { area = Double.parseDouble(faceAreaStr); } catch (NumberFormatException ignored) {}
                }
                MultiSurfaceProperty msp = rs.getLod3MultiSurface();
                if (msp == null || msp.getObject() == null) continue;
                for (var member : msp.getObject().getSurfaceMember()) {
                    if (member.getObject() instanceof Polygon poly) {
                        double localMinZ = Double.MAX_VALUE;
                        double localMaxZ = -Double.MAX_VALUE;
                        for (Point3D p : toPoints(poly)) {
                            minZ = Math.min(minZ, p.z);
                            maxZ = Math.max(maxZ, p.z);
                            localMinZ = Math.min(localMinZ, p.z);
                            localMaxZ = Math.max(localMaxZ, p.z);
                        }
                        if (area > dominantArea) {
                            dominantArea = area;
                            dominantMinZ = localMinZ;
                        }
                        // Geometrische Flacherkennung: alle Z-Werte nahezu gleich → Flachdach-Polygon
                        boolean polyIsFlat = (localMaxZ - localMinZ) < 0.05;
                        if (polyIsFlat) {
                            hasFlatRoof = true;
                        } else {
                            hasSlopedRoof = true;
                            slopedRawMinZ = Math.min(slopedRawMinZ, localMinZ);
                            if (area > slopedDominantArea) {
                                slopedDominantArea = area;
                                slopedDominantMinZ = localMinZ;
                            }
                        }
                    }
                }
            }
        }
        if (minZ == Double.MAX_VALUE) return null;
        // Mischdach: geneigte Flaeche bestimmt die Traufe (flache Teilflaechen ignorieren)
        double traufeZ;
        if (hasFlatRoof && hasSlopedRoof) {
            // Bevorzuge groesste geneigte Flaeche (FACEAREA), sonst globales MinZ der geneigten
            traufeZ = (slopedDominantArea > 0) ? slopedDominantMinZ : slopedRawMinZ;
        } else {
            traufeZ = (dominantArea > 0) ? dominantMinZ : minZ;
        }
        // Index 0: traufeZ (dominante Dachflaeche), Index 1: maxZ,
        // Index 2: globalMinZ (Min-Z aller RoofSurface-Polygone, fuer Slab-Begrenzung),
        // Index 3: slopedRawMinZ (Min-Z nur geneigter Flaechen; MAX_VALUE = kein Mischdach)
        return new double[]{traufeZ, maxZ, minZ, slopedRawMinZ};
    }

    /**
     * Liest das erste Polygon aus einer WallSurface (LoD3).
     */
    public static Polygon getWallPolygon(WallSurface wall) {
        MultiSurfaceProperty msp = wall.getLod3MultiSurface();
        if (msp == null || msp.getObject() == null) return null;
        var members = msp.getObject().getSurfaceMember();
        if (members == null || members.isEmpty()) return null;
        return members.get(0).getObject() instanceof Polygon poly ? poly : null;
    }

    // ==================== Wand-Schnitt (Sutherland-Hodgman) ====================

    /**
     * Schneidet ein Wand-Polygon horizontal bei einer gegebenen Z-Hoehe
     * mittels Sutherland-Hodgman-Algorithmus.
     *
     * Funktioniert fuer beliebige Polygon-Formen:
     * - Dreiecke (3 Punkte) - Giebelspitzen
     * - Rechtecke (4 Punkte) - Standard-Waende
     * - Fuenfecke (5 Punkte) - Giebelwaende
     * - Sechsecke (6 Punkte) - Walmdach-Waende
     * - Beliebig komplexe Formen (7+ Punkte) - Gauben, Erker, L-Form, etc.
     *
     * Algorithmus:
     * Jeder Polygon-Eckpunkt wird als "unterhalb", "auf" oder "oberhalb" der
     * Schnittebene z=zCut klassifiziert. Fuer jede Kante, die die Schnittebene
     * kreuzt, wird ein Schnittpunkt per linearer Interpolation berechnet.
     * Die Punkte werden den beiden Ergebnis-Polygonen (unten/oben) zugeordnet.
     * Punkte exakt auf der Schnittebene gehören zu BEIDEN Haelften.
     *
     * Ergebnis: [untererTeil, obererTeil] oder null wenn Schnitt nicht moeglich.
     *
     * Geometrie-Beispiele:
     *
     *   Rechteck (4P):        Fuenfeck/Giebel (5P):     Sechseck/Walm (6P):
     *   D ─── C                    C                     D ─── C
     *   │     │    →           D ╱   ╲ B    →           ╱       ╲
     *   │     │              E ╱       ╲ A            E           B
     *   A ─── B                                       F ───────── A
     *
     * @param wallPoly Das zu schneidende Wand-Polygon (3+ Eckpunkte)
     * @param zCut Die Z-Hoehe an der geschnitten wird
     * @param tolerance Mindestabstand von zCut zu den Polygon-Kanten (verhindert Fitzelchen)
     * @return [lowerPoly, upperPoly] oder null
     */
    public static Polygon[] cutWallPolygonAtZ(Polygon wallPoly, double zCut, double tolerance) {
        List<Point3D> pts = toPoints(wallPoly);
        List<Point3D> open = removeClosingPoint(pts);

        if (open.size() < 3) {
            return null; // Degeneriertes Polygon
        }

        // Z-Grenzen des Polygons ermitteln
        double minZ = open.stream().mapToDouble(p -> p.z).min().orElse(0);
        double maxZ = open.stream().mapToDouble(p -> p.z).max().orElse(0);

        // Schnitt nur sinnvoll wenn zCut innerhalb des Z-Bereichs liegt (mit Toleranz)
        if (zCut <= minZ + tolerance || zCut >= maxZ - tolerance) {
            return null;
        }

        // Klassifikations-Schwelle (1mm) - feiner als die Fitzelchen-Toleranz
        double eps = 0.001;
        double zRounded = roundZ(zCut);

        // Sutherland-Hodgman: Polygon an der horizontalen Ebene z=zCut teilen
        List<Point3D> lowerPoly = new ArrayList<>();
        List<Point3D> upperPoly = new ArrayList<>();

        int n = open.size();
        for (int i = 0; i < n; i++) {
            Point3D p = open.get(i);
            Point3D q = open.get((i + 1) % n);

            boolean pBelow = p.z < zRounded - eps;
            boolean pAbove = p.z > zRounded + eps;

            boolean qBelow = q.z < zRounded - eps;
            boolean qAbove = q.z > zRounded + eps;

            // --- Aktuellen Punkt zuordnen ---
            if (pBelow) {
                lowerPoly.add(p);
            } else if (pAbove) {
                upperPoly.add(p);
            } else {
                // Punkt liegt auf der Schnittebene → gehoert zu beiden Haelften
                Point3D onPlane = new Point3D(p.x, p.y, zRounded);
                lowerPoly.add(onPlane);
                upperPoly.add(onPlane);
            }

            // --- Schnittpunkt berechnen wenn Kante die Ebene kreuzt ---
            if ((pBelow && qAbove) || (pAbove && qBelow)) {
                double t = (zRounded - p.z) / (q.z - p.z);
                Point3D intersection = new Point3D(
                        p.x + t * (q.x - p.x),
                        p.y + t * (q.y - p.y),
                        zRounded
                );
                lowerPoly.add(intersection);
                upperPoly.add(intersection);
            }
        }

        // Validierung: Beide Haelften muessen mindestens 3 Punkte haben
        if (lowerPoly.size() < 3 || upperPoly.size() < 3) {
            return null;
        }

        // Post-Processing: Zwischenpunkte der Unterkante in upperPoly bewahren.
        // Bei Waenden mit mehreren kollinearen Punkten auf Gelaendeniveau (z ≈ minZ)
        // verwirft der Standard-S-H die Zwischenpunkte und erzeugt eine gerade
        // Unterkante in upperPoly. Das zerstoert die topologische Ausrichtung mit
        // den BA-Wand-Oberkanten aus dem BasementGenerator.
        // Nur anwenden wenn der Schnitt dicht ueber dem Wandfuss liegt (≤ 2.0 m),
        // d.h. beim Schnitt auf Hoehe egFloorZ (= H_DGM + heightGr ≈ 0.3..1.0 m).
        if (zRounded - minZ <= 2.0) {
            final double floorTol = 0.05; // 5 cm: Punkte innerhalb von minZ gelten als "Sohlpunkte"

            int entryEdgeIdx = -1; // Kante oben→Sohle
            int exitEdgeIdx  = -1; // Kante Sohle→oben
            for (int i = 0; i < n; i++) {
                Point3D p = open.get(i);
                Point3D q = open.get((i + 1) % n);
                boolean pFloor = p.z < zRounded - eps && Math.abs(p.z - minZ) < floorTol;
                boolean qFloor = q.z < zRounded - eps && Math.abs(q.z - minZ) < floorTol;
                boolean pAbv   = p.z > zRounded + eps;
                boolean qAbv   = q.z > zRounded + eps;
                if (pAbv && qFloor && entryEdgeIdx < 0) {
                    entryEdgeIdx = i;
                }
                if (pFloor && qAbv) {
                    exitEdgeIdx = i;
                }
            }

            if (entryEdgeIdx >= 0 && exitEdgeIdx >= 0 && entryEdgeIdx != exitEdgeIdx) {
                // Zwischenpunkte sammeln: vom zweiten Sohlpunkt bis zum vorletzten
                // (erster und letzter Sohlpunkt decken sich XY-nah mit den
                // Schnittpunkten der entry-/exit-Kanten und werden nicht nochmal eingefuegt)
                List<Point3D> intermediates = new ArrayList<>();
                int cur = (entryEdgeIdx + 2) % n; // zweiter Sohlpunkt
                while (cur != exitEdgeIdx) {
                    intermediates.add(open.get(cur));
                    cur = (cur + 1) % n;
                }

                if (!intermediates.isEmpty()) {
                    // Schnittpunkte der entry- und exit-Kante berechnen
                    Point3D pE = open.get(entryEdgeIdx);
                    Point3D qE = open.get((entryEdgeIdx + 1) % n);
                    double tE = (zRounded - pE.z) / (qE.z - pE.z);
                    double entryX = pE.x + tE * (qE.x - pE.x);
                    double entryY = pE.y + tE * (qE.y - pE.y);

                    Point3D pX = open.get(exitEdgeIdx);
                    Point3D qX = open.get((exitEdgeIdx + 1) % n);
                    double tX = (zRounded - pX.z) / (qX.z - pX.z);
                    double exitX = pX.x + tX * (qX.x - pX.x);
                    double exitY = pX.y + tX * (qX.y - pX.y);

                    // Aufeinanderfolgendes Paar (entryIntersect, exitIntersect) in upperPoly suchen
                    final double posTol = 0.02; // 2 cm Lagetoleranz
                    int sz = upperPoly.size();
                    for (int i = 0; i < sz; i++) {
                        int j = (i + 1) % sz;
                        Point3D pi = upperPoly.get(i);
                        Point3D pj = upperPoly.get(j);
                        if (Math.abs(pi.z - zRounded) < eps
                                && Math.abs(pj.z - zRounded) < eps
                                && Math.hypot(pi.x - entryX, pi.y - entryY) < posTol
                                && Math.hypot(pj.x - exitX, pj.y - exitY) < posTol) {
                            // Zwischenpunkte auf Schnitthoehe in upperPoly einfuegen
                            int insertIdx = i + 1;
                            for (int k = 0; k < intermediates.size(); k++) {
                                Point3D fv = intermediates.get(k);
                                upperPoly.add(insertIdx + k, new Point3D(fv.x, fv.y, zRounded));
                            }
                            break;
                        }
                    }
                }
            }
        }

        return new Polygon[]{createPolygon(lowerPoly), createPolygon(upperPoly)};
    }

    /**
     * Projiziert einen Grundriss (Liste von 3D-Punkten) auf eine neue Z-Höhe.
     * Behält X und Y bei, setzt Z auf den neuen Wert.
     */
    public static List<Point3D> projectToZ(List<Point3D> points, double newZ) {
        List<Point3D> projected = new ArrayList<>(points.size());
        for (Point3D p : points) {
            projected.add(new Point3D(p.x, p.y, newZ));
        }
        return projected;
    }

    /**
     * Erstellt eine MultiSurfaceProperty mit SRS-Information.
     * Setzt srsName und srsDimension auf dem MultiSurface-Element.
     */
    private static MultiSurfaceProperty createMultiSurfacePropertyWithSrs(Polygon polygon,
            String srsName, int srsDimension) {
        MultiSurface ms = new MultiSurface();
        ms.setSrsName(srsName);
        ms.setSrsDimension(srsDimension);
        ms.getSurfaceMember().add(new SurfaceProperty(polygon));
        return new MultiSurfaceProperty(ms);
    }

    /**
     * Standard-SRS für das Projekt.
     */
    public static final String SRS_NAME = "urn:adv:crs:ETRS89_UTM33*DE_DHHN2016_NH";
    public static final int SRS_DIMENSION = 3;

    /**
     * Erstellt eine MultiSurfaceProperty mit Standard-SRS (ETRS89_UTM33*DE_DHHN2016_NH, 3D).
     */
    public static MultiSurfaceProperty createMultiSurfacePropertyWithDefaultSrs(Polygon polygon) {
        return createMultiSurfacePropertyWithSrs(polygon, SRS_NAME, SRS_DIMENSION);
    }

    /**
     * Erstellt eine MultiSurfaceProperty mit XLink-Referenz auf ein bestehendes Polygon.
     * Das referenzierte Polygon muss eine gml:id haben und im selben Dokument existieren.
     *
     * Erzeugt: {@code <gml:surfaceMember xlink:href="#polygonId"/>}
     *
     * @param polygonGmlId Die gml:id des referenzierten Polygons (ohne '#'-Praefix)
     * @return MultiSurfaceProperty mit XLink-Referenz
     */
    public static MultiSurfaceProperty createXLinkMultiSurfaceProperty(String polygonGmlId) {
        MultiSurface ms = new MultiSurface();
        ms.setSrsName(SRS_NAME);
        ms.setSrsDimension(SRS_DIMENSION);
        ms.getSurfaceMember().add(new SurfaceProperty("#" + polygonGmlId));
        return new MultiSurfaceProperty(ms);
    }

    // ==================== Wand-Attribute berechnen ====================

    /**
     * Berechnet Z_MIN und Z_MAX aus einer Liste von Wandpunkten.
     * @return double[] { minZ, maxZ }
     */
    public static double[] getZRange(List<Point3D> points) {
        double minZ = points.stream().mapToDouble(p -> p.z).min().orElse(0);
        double maxZ = points.stream().mapToDouble(p -> p.z).max().orElse(0);
        return new double[] { minZ, maxZ };
    }

    /**
     * Berechnet die Flaeche eines beliebigen 3D-Polygons (Newell's Method).
     *
     * Funktioniert fuer alle planaren Polygon-Formen (Dreiecke, Rechtecke,
     * Fuenfecke, Sechsecke, etc.) im dreidimensionalen Raum.
     *
     * Algorithmus (Newell's Method):
     *   Flaechennormale N = Summe(P_i x P_{i+1})  (Kreuzprodukt-Summe)
     *   Flaeche A = 0.5 * |N|
     *
     * Detailliert:
     *   N.x = Summe( (y_i - y_{i+1}) * (z_i + z_{i+1}) )
     *   N.y = Summe( (z_i - z_{i+1}) * (x_i + x_{i+1}) )
     *   N.z = Summe( (x_i - x_{i+1}) * (y_i + y_{i+1}) )
     *   A = 0.5 * sqrt(N.x^2 + N.y^2 + N.z^2)
     *
     * @param wallPoints Punkte des Polygons (offen oder geschlossen)
     * @return Flaeche in m^2
     */
    public static double calculateWallArea(List<Point3D> wallPoints) {
        List<Point3D> open = removeClosingPoint(wallPoints);
        if (open.size() < 3) return 0;

        // Newell's Method: Flaechennormale durch Kreuzprodukt-Summe
        double nx = 0, ny = 0, nz = 0;
        int n = open.size();
        for (int i = 0; i < n; i++) {
            Point3D curr = open.get(i);
            Point3D next = open.get((i + 1) % n);
            nx += (curr.y - next.y) * (curr.z + next.z);
            ny += (curr.z - next.z) * (curr.x + next.x);
            nz += (curr.x - next.x) * (curr.y + next.y);
        }

        // Flaeche = halbe Laenge des Normalenvektors
        return 0.5 * Math.sqrt(nx * nx + ny * ny + nz * nz);
    }

    /**
     * Berechnet den Azimut der Wandnormalen aus einem beliebigen Wand-Polygon.
     *
     * Strategie:
     * 1. Suche zwei aufeinanderfolgende Punkte am unteren Rand (minZ) des Polygons.
     *    Dies funktioniert fuer alle gaengigen Wandformen (Rechtecke, Giebel, Walm, etc.)
     * 2. Fallback: Verwende die laengste horizontale Kante.
     * 3. Letzter Fallback: Verwende die erste Kante des Polygons.
     *
     * @param wallPoints Punkte des Wand-Polygons (3+ Punkte)
     * @return Azimut in Grad (0=Nord, 90=Ost, 180=Sued, 270=West)
     */
    private static double calculateWallNormalAzimuthFromPolygon(List<Point3D> wallPoints) {
        List<Point3D> open = removeClosingPoint(wallPoints);
        if (open.size() < 3) return 0;
        
        // Finde die untere Kante (zwei aufeinanderfolgende Punkte bei minZ)
        double minZ = open.stream().mapToDouble(p -> p.z).min().orElse(0);
        double tolerance = 0.01;
        
        // Suche zwei aufeinanderfolgende Punkte bei minZ
        for (int i = 0; i < open.size(); i++) {
            int next = (i + 1) % open.size();
            if (Math.abs(open.get(i).z - minZ) < tolerance && 
                Math.abs(open.get(next).z - minZ) < tolerance) {
                return calculateWallNormalAzimuth(open.get(i), open.get(next));
            }
        }

        // Fallback: Laengste horizontale Kante verwenden
        double bestLen = 0;
        int bestIdx = -1;
        for (int i = 0; i < open.size(); i++) {
            int next = (i + 1) % open.size();
            double dz = Math.abs(open.get(i).z - open.get(next).z);
            if (dz < tolerance) {
                double len = calculateEdgeLength2D(open.get(i), open.get(next));
                if (len > bestLen) {
                    bestLen = len;
                    bestIdx = i;
                }
            }
        }
        if (bestIdx >= 0) {
            return calculateWallNormalAzimuth(open.get(bestIdx),
                    open.get((bestIdx + 1) % open.size()));
        }

        // Letzter Fallback: Erste Kante (selten, z.B. rein schiefe Waende)
        return calculateWallNormalAzimuth(open.get(0), open.get(1));
    }

    /**
     * Fügt alle Standard-Wand-Attribute zu einer WallSurface hinzu.
     *
     * @param wall Die WallSurface
     * @param wallPoints Punkte des Wand-Polygons
     * @param faceId BldgFaceID
     * @param hDgm Geländehöhe (für relative Z-Werte), kann null sein
     * @param geschoss Geschoss-Tag (KG, EG, 1.OG, ...)
     * @param lage Lage-Tag (belowGround, unterirdisch, oder null)
     * @param struktur Struktur-Beschreibung
     * @param ursprungspolygonId gml:id des Original-Polygons vor Schnitt (oder null)
     */
    public static void addWallAttributes(WallSurface wall, List<Point3D> wallPoints,
            String faceId, Double hDgm, String geschoss, String lage, String struktur,
            String ursprungspolygonId) {
        
        double[] zRange = getZRange(wallPoints);
        double wallMinZ = zRange[0];
        double wallMaxZ = zRange[1];
        double area = calculateWallArea(wallPoints);
        double azimuth = calculateWallNormalAzimuthFromPolygon(wallPoints);
        
        addStringAttribute(wall, "BldgFaceID", faceId);
        addStringAttribute(wall, "Z_MAX_ASL", formatNum(wallMaxZ));
        addStringAttribute(wall, "Z_MIN_ASL", formatNum(wallMinZ));
        if (hDgm != null) {
            addStringAttribute(wall, "Z_Max", formatNum(wallMaxZ - hDgm));
            addStringAttribute(wall, "Z_Min", formatNum(wallMinZ - hDgm));
        }
        addStringAttribute(wall, "FACEAREA", formatNum(area));
        addStringAttribute(wall, "NORMAL_AZI", formatNum(azimuth));
        addStringAttribute(wall, "NORMAL_H", "0");
        addStringAttribute(wall, "STRUKTUR", struktur);
        addStringAttribute(wall, "Innenwand", "0");
        addStringAttribute(wall, "Geschoss", geschoss);
        if (lage != null) {
            addStringAttribute(wall, "Lage", lage);
        }
        if (ursprungspolygonId != null) {
            addStringAttribute(wall, "UrsprungspolygonID", ursprungspolygonId);
        }
    }

    /**
     * Fügt Standard-Attribute zu einer FloorSurface oder CeilingSurface hinzu.
     *
     * @param surface Die Surface (Floor oder Ceiling)
     * @param faceId BldgFaceID
     * @param z Z-Koordinate der Fläche
     * @param hDgm Geländehöhe (für relativen Z-Wert), kann null sein
     * @param area Fläche in m²
     * @param geschoss Geschoss-Tag (KG, EG, 1.OG, ...)
     */
    public static void addHorizontalSurfaceAttributes(AbstractCityObject surface,
            String faceId, double z, Double hDgm, double area, String geschoss) {
        addStringAttribute(surface, "BldgFaceID", faceId);
        addStringAttribute(surface, "Z_MIN_ASL", formatNum(z));
        if (hDgm != null) {
            addStringAttribute(surface, "Z_Min", formatNum(z - hDgm));
        }
        addStringAttribute(surface, "FACEAREA", formatNum(area));
        addStringAttribute(surface, "Geschoss", geschoss);
    }

    /**
     * Einfache 3D-Punkt-Klasse.
     */
    public static class Point3D {
        public final double x;
        public final double y;
        public final double z;

        public Point3D(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public boolean nearlyEquals(Point3D other) {
            if (other == null) return false;
            return Math.abs(x - other.x) < POINT_EQUALITY_EPS
                    && Math.abs(y - other.y) < POINT_EQUALITY_EPS
                    && Math.abs(z - other.z) < POINT_EQUALITY_EPS;
        }

        @Override
        public String toString() {
            return String.format("(%.3f, %.3f, %.3f)", x, y, z);
        }
    }

    // ==================== TerrainIntersectionCurve ====================

    /**
     * Erzeugt eine lod3TerrainIntersectionCurve (TIC) als MultiCurveProperty.
     *
     * <p>Einfacher Modus (ohne DGM): Alle Punkte werden auf eine konstante
     * Gelaendehoehe (hDgm) projiziert. Das ergibt einen flachen Ring bei Z = hDgm.
     *
     * @param groundPolygons GroundSurface-Polygone (Footprint-Polygone)
     * @param hDgm konstante Gelaendehoehe
     * @return MultiCurveProperty mit geschlossenen 3D-Ringen
     */
    public static MultiCurveProperty createTerrainIntersectionCurve(
            List<Polygon> groundPolygons, double hDgm) {
        return createTerrainIntersectionCurve(groundPolygons, hDgm, null);
    }

    /**
     * Erzeugt eine lod3TerrainIntersectionCurve (TIC) als MultiCurveProperty.
     *
     * <p>Wenn ein DGM vorhanden ist, wird die Gelaendehoehe pro Vertex per
     * bilinearer Interpolation aus dem DGM abgefragt. Falls das DGM fuer einen
     * Vertex kein Ergebnis liefert (ausserhalb oder NODATA), wird hDgm als
     * Fallback verwendet.
     *
     * <p>Jedes GroundSurface-Polygon erzeugt einen geschlossenen Ring (LineString)
     * in der MultiCurve. Bei Gebaeuden mit Innenhoefen (mehrere GroundSurface-
     * Polygone) enthaelt die MultiCurve entsprechend mehrere Ringe.
     *
     * @param groundPolygons GroundSurface-Polygone (Footprint-Polygone)
     * @param hDgm Fallback-Gelaendehoehe (konstant, wenn kein DGM)
     * @param dgm optionaler DGM-Provider fuer detaillierte Gelaendehoehen (kann null sein)
     * @return MultiCurveProperty mit geschlossenen 3D-Ringen, oder null wenn keine Polygone
     */
    public static MultiCurveProperty createTerrainIntersectionCurve(
            List<Polygon> groundPolygons, double hDgm, DgmProvider dgm) {

        if (groundPolygons == null || groundPolygons.isEmpty()) {
            return null;
        }

        MultiCurve multiCurve = new MultiCurve();

        for (Polygon groundPoly : groundPolygons) {
            List<Point3D> points = toPoints(groundPoly);
            if (points.size() < 4) continue;  // mind. 3 Punkte + Schlusspunkt

            // Z-Werte per DGM oder konstantem hDgm setzen
            List<Double> coords = new ArrayList<>(points.size() * 3);
            for (Point3D p : points) {
                double z;
                if (dgm != null && dgm.contains(p.x, p.y)) {
                    double dgmZ = dgm.getHeight(p.x, p.y);
                    z = Double.isNaN(dgmZ) ? hDgm : roundZ(dgmZ);
                } else {
                    z = roundZ(hDgm);
                }
                coords.add(p.x);
                coords.add(p.y);
                coords.add(z);
            }

            // Ring schliessen (sicherheitshalber)
            if (coords.size() >= 6) {
                double x0 = coords.get(0), y0 = coords.get(1), z0 = coords.get(2);
                int last = coords.size() - 3;
                double xn = coords.get(last), yn = coords.get(last + 1), zn = coords.get(last + 2);
                double dist = Math.sqrt(Math.pow(x0 - xn, 2) + Math.pow(y0 - yn, 2));
                if (dist > 1e-6) {
                    coords.add(x0);
                    coords.add(y0);
                    coords.add(z0);
                }
            }

            DirectPositionList posList = new DirectPositionList(coords);
            posList.setSrsDimension(3);

            LineString lineString = new LineString(posList);
            lineString.setSrsName(SRS_NAME);
            lineString.setSrsDimension(SRS_DIMENSION);

            multiCurve.getCurveMember().add(new CurveProperty(lineString));
        }

        if (multiCurve.getCurveMember().isEmpty()) {
            return null;
        }

        return new MultiCurveProperty(multiCurve);
    }

    // ==================== Solid-Shell-Rebuild ====================

    /**
     * Bestimmt ob der Aussenring eines Wand-Polygons im lokalen 2D-Koordinatensystem
     * (Horizontalachse = Wandunterkante, Vertikalachse = Z) gegen den Uhrzeigersinn (CCW) laeuft.
     *
     * Sachsen LoD2-Waende: CW (Normale zeigt nach innen).
     * Generierte Kellerwaende (BA): CCW (Normale zeigt nach aussen, Standard GML).
     *
     * @param open       Offener Ring (ohne Schliessungspunkt) der Wand
     * @param edgeStart  Startpunkt der Unterkante (= Ursprung des lokalen KS)
     * @param dirX       X-Komponente des Einheitsvektors der Unterkante
     * @param dirY       Y-Komponente des Einheitsvektors der Unterkante
     */
    public static boolean isExteriorRingCCW(List<Point3D> open, Point3D edgeStart,
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
     * Baut die lod3Solid-Shell eines Gebaeudes/BuildingParts komplett neu auf.
     *
     * Sammelt alle Polygon-gml:ids aus allen BoundarySurfaces (nur inline-Polygone,
     * keine XLink-Referenzen) und ersetzt die surfaceMembers der Shell mit neuen
     * xlink:href-Eintraegen.
     *
     * Weist Polygonen ohne gml:id automatisch eine "Poly_"-basierte ID zu,
     * abgeleitet aus der gml:id der uebergeordneten BoundarySurface.
     *
     * Hintergrund: Die Pipeline (Promoter → BasementGenerator → StoreyGenerator)
     * kopiert den lod2Solid als lod3Solid, aber die xlink:href-Referenzen in der
     * Shell werden nicht aktualisiert, wenn Wandflaechen geschnitten und neue
     * Flaechen (Keller, Geschosse, Boeden, Decken) hinzugefuegt werden.
     * Dadurch entstehen dangling references auf nicht mehr existierende Polygone,
     * und neue Polygone fehlen im Solid.
     *
     * @param target Das AbstractBuilding (Building oder BuildingPart)
     * @return Anzahl der Polygon-Referenzen in der neuen Shell (0 wenn kein Solid)
     */
    public static int rebuildSolidShell(AbstractBuilding target) {
        SolidProperty solidProp = target.getSolid(3);
        if (solidProp == null || solidProp.getObject() == null) return 0;

        if (!(solidProp.getObject() instanceof
                org.xmlobjects.gml.model.geometry.primitives.Solid solid)) return 0;
        if (solid.getExterior() == null || solid.getExterior().getObject() == null) return 0;

        var shell = solid.getExterior().getObject();

        // Alle inline-Polygon gml:ids aus BoundarySurfaces sammeln
        List<String> polygonIds = new ArrayList<>();
        int autoIdCounter = 0;

        for (var boundary : target.getBoundaries()) {
            var surface = boundary.getObject();
            if (!(surface instanceof AbstractThematicSurface ats)) continue;

            // FloorSurface und CeilingSurface sind innere Geschoss-Trennflaechen
            // und gehoeren NICHT zum aeusseren Solid-Hull:
            // Ihre Kanten an Geschossgrenzen wuerden von 3 Faces geteilt
            // (unteres Wandsegment + oberes Wandsegment + Floor/Ceiling)
            // → GE_S_NON_MANIFOLD_EDGE. Sie bleiben als semantische BoundarySurfaces
            // erhalten, werden aber nicht in die Solid-Shell aufgenommen.
            if (surface instanceof FloorSurface || surface instanceof CeilingSurface) continue;

            MultiSurfaceProperty msp = ats.getMultiSurface(3);
            if (msp == null || msp.getObject() == null) continue;

            for (SurfaceProperty member : msp.getObject().getSurfaceMember()) {
                // Nur inline-Polygone zaehlen (keine XLink-Referenzen)
                // XLink-Floors referenzieren ein Ceiling-Polygon, das bereits
                // ueber die CeilingSurface gezaehlt wird.
                if (member.getObject() instanceof Polygon poly) {
                    // Falls kein gml:id vorhanden → automatisch zuweisen
                    if (poly.getId() == null || poly.getId().isBlank()) {
                        String surfaceId = ats.getId();
                        String baseId;
                        if (surfaceId != null && surfaceId.startsWith("Face_")) {
                            baseId = "Poly_" + surfaceId.substring(5);
                        } else if (surfaceId != null) {
                            baseId = "Poly_" + surfaceId;
                        } else {
                            baseId = "Poly_auto_" + (++autoIdCounter);
                        }
                        poly.setId(baseId);
                    }
                    polygonIds.add(poly.getId());
                }
            }

            // FillingSurfaces (WindowSurface, DoorSurface) in die Shell aufnehmen:
            // Die Wand hat Innenringe (Loecher) fuer Fenster/Tueren. Ohne das
            // Fenster-/Tuerpolygon im Solid sind die Lochkanten nur in 1 Face
            // → GE_S_NOT_CLOSED. Die FillingSurface schliesst das Loch.
            if (surface instanceof WallSurface wall) {
                for (AbstractFillingSurfaceProperty fillProp : wall.getFillingSurfaces()) {
                    var fill = fillProp.getObject();
                    if (fill == null) continue;
                    MultiSurfaceProperty fmsp = fill.getMultiSurface(3);
                    if (fmsp == null || fmsp.getObject() == null) continue;
                    for (SurfaceProperty fmember : fmsp.getObject().getSurfaceMember()) {
                        if (fmember.getObject() instanceof Polygon fpoly) {
                            if (fpoly.getId() == null || fpoly.getId().isBlank()) {
                                // Eindeutige ID aus der FillingSurface-ID ableiten.
                                // fill.getId() ist z.B. "Face_XYZ_Win_1" oder "Face_XYZ_Door_1"
                                // → daraus wird "Poly_XYZ_Win_1" bzw. "Poly_XYZ_Door_1".
                                // So sind die IDs global eindeutig (kein autoIdCounter-Reset-Problem).
                                String fillId = fill.getId();
                                if (fillId != null && !fillId.isBlank()) {
                                    String base = fillId.startsWith("Face_")
                                            ? fillId.substring(5) : fillId;
                                    fpoly.setId("Poly_" + base);
                                } else {
                                    // Fallback: Wall-ID + autoIdCounter (selten)
                                    fpoly.setId("Poly_Fill_" + ats.getId()
                                            + "_" + (++autoIdCounter));
                                }
                            }
                            polygonIds.add(fpoly.getId());
                        }
                    }
                }
            }
        }

        // Shell-surfaceMembers komplett ersetzen
        shell.getSurfaceMembers().clear();
        for (String polyId : polygonIds) {
            shell.getSurfaceMembers().add(new SurfaceProperty("#" + polyId));
        }

        return polygonIds.size();
    }

    // ==================== GML-Datei-Verarbeitung ====================

    /**
     * Verarbeitet eine CityGML-Datei: Liest alle Features, wendet einen
     * Building-Processor an und schreibt das Ergebnis.
     * Uebernimmt das BoundedBy-Envelope aus dem Header.
     *
     * @param input     Eingabe-GML-Datei
     * @param output    Ausgabe-GML-Datei
     * @param processor Funktion die auf jedes Building angewendet wird
     */
    public static void processGmlFile(Path input, Path output,
            Consumer<Building> processor) throws Exception {

        CityGMLContext context = CityGMLContext.newInstance();
        var in = context.createCityGMLInputFactory()
                .withChunking(ChunkOptions.defaults());
        CityGMLOutputFactory out = context.createCityGMLOutputFactory(CityGMLVersion.v1_0);

        org.xmlobjects.gml.model.feature.BoundingShape originalBoundedBy = null;
        try (CityGMLReader headerReader = context.createCityGMLInputFactory()
                .createCityGMLReader(input.toFile())) {
            if (headerReader.hasNext()) {
                var firstFeature = headerReader.next();
                if (firstFeature instanceof org.citygml4j.core.model.core.CityModel cm) {
                    originalBoundedBy = cm.getBoundedBy();
                }
            }
        }

        try (CityGMLReader reader = in.createCityGMLReader(input.toFile());
             CityGMLChunkWriter writer = out.createCityGMLChunkWriter(output,
                     StandardCharsets.UTF_8.name())) {

            writer.withIndent("\t").withDefaultPrefixes();
            if (originalBoundedBy != null) {
                writer.getCityModelInfo().setBoundedBy(originalBoundedBy);
            }

            while (reader.hasNext()) {
                AbstractFeature feature = reader.next();
                if (feature instanceof Building building) {
                    processor.accept(building);
                }
                writer.writeMember(feature);
            }
        }
    }

    /**
     * Erstellt den Ausgabe-Pfad basierend auf dem Eingabe-Pfad und einem Suffix.
     * Wenn explicitOutput nicht null ist, wird dieser verwendet.
     *
     * @param inputPath      Eingabe-Datei
     * @param suffix         Suffix fuer den Dateinamen (z.B. "_storeys")
     * @param explicitOutput Expliziter Ausgabe-Pfad (optional, kann null sein)
     * @return Ausgabe-Pfad
     */
    public static Path resolveOutputPath(Path inputPath, String suffix, Path explicitOutput) {
        if (explicitOutput != null) return explicitOutput;
        String baseName = inputPath.getFileName().toString();
        if (baseName.toLowerCase().endsWith(".gml")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        String outputName = baseName + suffix + ".gml";
        return inputPath.getParent() != null
                ? inputPath.getParent().resolve(outputName)
                : Paths.get(outputName);
    }

    // ==================== Weitere Geometrie-Hilfsmethoden ====================

    /**
     * Erzeugt einen geschlossenen LinearRing aus einer Punktliste.
     * Die Punktliste wird automatisch geschlossen (letzter Punkt = erster Punkt).
     * Verwendet fuer innere Ringe (z.B. Fenster-/Tuerausschnitte).
     */
    public static LinearRing createLinearRing(List<Point3D> pts) {
        List<Double> coords = new ArrayList<>(pts.size() * 3 + 3);
        for (Point3D p : pts) {
            coords.add(p.x);
            coords.add(p.y);
            coords.add(p.z);
        }
        coords.add(pts.get(0).x);
        coords.add(pts.get(0).y);
        coords.add(pts.get(0).z);

        DirectPositionList posList = new DirectPositionList(coords);
        posList.setSrsDimension(3);

        LinearRing ring = new LinearRing();
        ring.getControlPoints().setPosList(posList);
        return ring;
    }

    /**
     * Ray-Casting-Algorithmus: prueft ob ein Punkt (px, py) innerhalb eines
     * 2D-Polygons liegt. Verwendet horizontalen Strahl nach rechts.
     *
     * @param px   X-Koordinate des Testpunktes
     * @param py   Y-Koordinate des Testpunktes
     * @param poly Polygon als Array von [x, y]-Paaren (nicht geschlossen)
     * @return true wenn der Punkt innerhalb liegt
     */
    public static boolean pointInPolygon2D(double px, double py, double[][] poly) {
        int n = poly.length;
        boolean inside = false;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = poly[i][1], yj = poly[j][1];
            double xi = poly[i][0], xj = poly[j][0];

            if ((yi > py) != (yj > py)) {
                double xIntersect = xi + (py - yi) / (yj - yi) * (xj - xi);
                if (px < xIntersect) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }
}
