package de.mpsc.lod2tolod3.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests fuer {@link SlabClippingUtils#unionFootprints}, siehe Doku "Keller bei mehreren GroundSurfaces". */
class CityGmlUtilsFootprintUnionTest {

    private static List<Point3D> rect(double x0, double y0, double x1, double y1) {
        return List.of(new Point3D(x0, y0, 5), new Point3D(x1, y0, 5),
                new Point3D(x1, y1, 5), new Point3D(x0, y1, 5));
    }

    private static double signedArea(List<Point3D> ring) {
        double s = 0;
        for (int i = 0; i < ring.size(); i++) {
            Point3D a = ring.get(i), b = ring.get((i + 1) % ring.size());
            s += a.x * b.y - b.x * a.y;
        }
        return s / 2;
    }

    /** Nachbau gJv: zwei Grundrisse ueberlappen sich entlang ihrer gemeinsamen Kante um 9,6 mm. */
    @Test
    void mergesAdjacentFootprintsWithSliverIntoOneRing() {
        List<List<Point3D>> merged = SlabClippingUtils.unionFootprints(
                List.of(rect(0, 0, 4, 4), rect(3.9904, 0, 8, 4)));

        assertNotNull(merged);
        assertEquals(1, merged.size());
        assertEquals(32.0, signedArea(merged.get(0)), 1e-6, "Flaeche und Umlaufsinn (CCW) wie Eingabe");
        assertTrue(merged.get(0).stream().allMatch(p -> p.z == 5), "Z bleibt erhalten");
    }

    /** Getrennte Grundrisse bleiben getrennt — der Aufrufer erkennt das an der unveraenderten Anzahl. */
    @Test
    void keepsDisjointFootprintsSeparate() {
        List<List<Point3D>> merged = SlabClippingUtils.unionFootprints(
                List.of(rect(0, 0, 2, 2), rect(5, 5, 7, 7)));

        assertNotNull(merged);
        assertEquals(2, merged.size());
    }

    /** Innenhof aus vier Randpolygonen: Vereinigung haette ein Loch — bewusst nicht unterstuetzt. */
    @Test
    void rejectsUnionWithCourtyard() {
        List<List<Point3D>> merged = SlabClippingUtils.unionFootprints(List.of(
                rect(0, 0, 10, 2), rect(0, 8, 10, 10), rect(0, 2, 2, 8), rect(8, 2, 10, 8)));

        assertNull(merged);
    }
}
