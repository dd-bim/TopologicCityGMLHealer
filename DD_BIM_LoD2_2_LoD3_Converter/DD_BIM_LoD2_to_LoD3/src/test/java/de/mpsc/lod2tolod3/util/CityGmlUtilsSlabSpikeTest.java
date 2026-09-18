package de.mpsc.lod2tolod3.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests fuer die Bereinigung selbstberuehrender Deckenringe in {@link SlabClippingUtils}. */
class CityGmlUtilsSlabSpikeTest {

    private static Point3D p(double x, double y) {
        return new Point3D(x, y, 7);
    }

    /** Rueckwaerts-Spitze wie bei Zuschnitten knapp ueber der Traufe: Punkt 4 liegt 0,2 mm neben der
     *  Kante 2-3, der Pfad laeuft bis (4,10) und auf derselben Linie zurueck. */
    @Test
    void detectsAndRemovesBacktrackSpike() {
        List<Point3D> ring = List.of(p(0, 0), p(10, 0), p(10, 10), p(4, 10), p(6, 10.0002), p(0, 10));

        assertTrue(SlabClippingUtils.slabRingTouchesItself(ring));
        List<Point3D> cleaned = SlabClippingUtils.removeSlabSpikes(ring);
        assertFalse(SlabClippingUtils.slabRingTouchesItself(cleaned));
        assertEquals(4, cleaned.size());
    }

    /** mm-Zacke wie an Face_DESNALK0ng001DMp_UF_2_Ceiling_1: drei Eckpunkte innerhalb von 6 mm, einer
     *  1,4 mm neben einer nicht angrenzenden Kante — erst mit der 5-mm-Deckentoleranz erkannt. */
    @Test
    void detectsMillimetreZigzagWithSlabTolerance() {
        List<Point3D> ring = List.of(p(0, 0), p(0.004, 0.005), p(-6, 4.7), p(-4, 7.3), p(5.1, 0.3), p(3.1, -2.4), p(0.005, 0.004));

        assertFalse(SlabClippingUtils.slabRingTouchesItself(ring));
        assertTrue(SlabClippingUtils.slabRingTouchesItself(ring, 0.005));
        List<Point3D> cleaned = SlabClippingUtils.removeSlabSpikes(ring, 0.005);
        assertFalse(SlabClippingUtils.slabRingTouchesItself(cleaned, 0.005));
        assertTrue(cleaned.size() >= 4);
    }

    @Test
    void leavesCleanRingUnchanged() {
        List<Point3D> ring = List.of(p(0, 0), p(10, 0), p(10, 10), p(0, 10));

        assertFalse(SlabClippingUtils.slabRingTouchesItself(ring));
        assertEquals(ring, SlabClippingUtils.removeSlabSpikes(ring));
    }
}
