package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.util.Point3D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests fuer die Wandkriterien der Geschosse oberhalb der Traufe in {@link StoreyGenerator}. */
class StoreyGeneratorUpperStoreyTest {

    private static Point3D p(double x, double z) {
        return new Point3D(x, 0, z);
    }

    // Wand mit waagerechter Oberkante (Fluegel/Turm), Oberkante per T-Naht-Punkt bei x=4 unterteilt.
    private static final List<Point3D> FLAT_TOP = List.of(p(0, 0), p(10, 0), p(10, 8), p(4, 8), p(0, 8));

    // Saegezahn-Oberkante wie an Face_0003PYW_0_17: einzelne Spitzen, dazwischen Taeler.
    private static final List<Point3D> SAWTOOTH = List.of(
            p(0, 0), p(12, 0), p(12, 6), p(10, 8), p(8, 6), p(6, 8), p(4, 6), p(2, 8), p(0, 6));

    // Giebelwand.
    private static final List<Point3D> GABLE = List.of(p(0, 0), p(10, 0), p(10, 6), p(5, 9), p(0, 6));

    @Test
    void flatTopCountsWholeConnectedEdge() {
        assertEquals(10, StoreyGenerator.topEdgeWidth(FLAT_TOP, 8), 1e-9);
    }

    @Test
    void sawtoothAndGableHaveNoFlatTop() {
        assertEquals(0, StoreyGenerator.topEdgeWidth(SAWTOOTH, 8), 1e-9,
                "einzelne Spitzen duerfen nicht als waagerechte Oberkante zaehlen");
        assertEquals(0, StoreyGenerator.topEdgeWidth(GABLE, 9), 1e-9);
    }

    @Test
    void cutThroughOrJustBelowGableIsRejected() {
        assertTrue(StoreyGenerator.slopedEdgeNear(GABLE, 7, 0.5), "Schnitt durch den Giebel");
        assertTrue(StoreyGenerator.slopedEdgeNear(GABLE, 5.8, 0.5), "Schnitt 20 cm unter dem Giebelfuss (Duennstreifen)");
        assertFalse(StoreyGenerator.slopedEdgeNear(GABLE, 5, 0.5), "genug Abstand zum Giebelfuss");
        assertFalse(StoreyGenerator.slopedEdgeNear(FLAT_TOP, 7, 0.5), "Fluegelwand: nur senkrechte Kanten");
        assertTrue(StoreyGenerator.slopedEdgeNear(FLAT_TOP, 7.8, 0.5), "Schnitt knapp unter der Oberkante");
    }
}
