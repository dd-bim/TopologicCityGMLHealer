package de.mpsc.lod2tolod3.util;

/**
 * Gemeinsame Schnittstelle fuer alle DGM-Quellen (Digitale Gelaendemodelle).
 *
 * <p>Implementierungen:
 * <ul>
 *   <li>{@link DgmReader} — ESRI ASCII Grid (.asc)</li>
 *   <li>{@link GeoTiffReader} — GeoTIFF (.tif / .tiff)</li>
 *   <li>{@link DgmMosaic} — Mehrere Tiles (Lazy-Loading, beliebig gemischt)</li>
 * </ul>
 *
 * <p>Verwende {@link DgmLoader#load(java.nio.file.Path)} fuer automatische
 * Format-Erkennung (Datei, Verzeichnis, ZIP).
 */
public interface DgmProvider {

    /**
     * Liefert die bilinear interpolierte Gelaendehoehe fuer eine (x, y)-Koordinate.
     *
     * @param x X-Koordinate (Rechtswert, z.B. UTM-Easting)
     * @param y Y-Koordinate (Hochwert, z.B. UTM-Northing)
     * @return interpolierte Gelaendhoehe oder {@code Double.NaN} wenn ausserhalb/NODATA
     */
    double getHeight(double x, double y);

    /**
     * Prueft, ob eine Koordinate innerhalb des DGM-Bereichs liegt.
     *
     * @param x X-Koordinate
     * @param y Y-Koordinate
     * @return true wenn innerhalb
     */
    boolean contains(double x, double y);

    /**
     * Beschreibung des DGM (fuer Logging).
     */
    default String describe() {
        return getClass().getSimpleName();
    }
}
