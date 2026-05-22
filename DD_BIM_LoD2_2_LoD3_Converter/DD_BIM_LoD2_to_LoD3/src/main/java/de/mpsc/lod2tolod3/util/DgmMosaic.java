package de.mpsc.lod2tolod3.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Kombiniert mehrere DGM-Tiles zu einem virtuellen Gesamtmodell (Mosaik).
 *
 * <p>Verwaltet eine Sammlung von {@link DgmProvider}-Tiles und leitet
 * {@link #getHeight(double, double)} an das passende Tile weiter.
 *
 * <p>Tiles koennen beliebig gemischt sein (ASCII Grid + GeoTIFF).
 * Die Suche nach dem passenden Tile erfolgt linear — bei typischen
 * Tile-Anzahlen (< 200) ist das performant genug.
 *
 * <p>Typische Verwendung (via {@link DgmLoader}):
 * <pre>
 * DgmProvider dgm = DgmLoader.load(Path.of("DGM/Dresden"));
 * // Mosaic aus 116 Tiles, Abfrage transparent
 * double z = dgm.getHeight(416290.5, 5657417.3);
 * </pre>
 */
public class DgmMosaic implements DgmProvider {

    private static final Logger log = LoggerFactory.getLogger(DgmMosaic.class);

    private final List<DgmProvider> tiles = new ArrayList<>();

    /**
     * Fuegt ein Tile zum Mosaik hinzu.
     */
    public void addTile(DgmProvider tile) {
        tiles.add(tile);
    }

    /**
     * Anzahl der geladenen Tiles.
     */
    public int getTileCount() {
        return tiles.size();
    }

    /**
     * Liefert die bilinear interpolierte Gelaendehoehe.
     * Sucht das passende Tile per {@link DgmProvider#contains(double, double)}.
     *
     * @param x X-Koordinate (Rechtswert)
     * @param y Y-Koordinate (Hochwert)
     * @return interpolierte Hoehe oder {@code Double.NaN} wenn kein Tile zustaendig
     */
    @Override
    public double getHeight(double x, double y) {
        for (DgmProvider tile : tiles) {
            if (tile.contains(x, y)) {
                double h = tile.getHeight(x, y);
                if (!Double.isNaN(h)) {
                    return h;
                }
            }
        }
        return Double.NaN;
    }

    /**
     * Prueft, ob irgendein Tile die Koordinate abdeckt.
     */
    @Override
    public boolean contains(double x, double y) {
        for (DgmProvider tile : tiles) {
            if (tile.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String describe() {
        return String.format("Mosaic mit %d Tiles", tiles.size());
    }
}
