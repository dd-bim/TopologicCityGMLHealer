package de.mpsc.lod2tolod3.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Liest ein digitales Gelaendemodell (DGM) aus einer ESRI-ASCII-Grid-Datei (.asc).
 *
 * <p>Format (Beispiel):
 * <pre>
 * ncols        1000
 * nrows        1000
 * xllcorner    413000.0
 * yllcorner    5654000.0
 * cellsize     1.0
 * NODATA_value -9999
 * 123.45 123.56 123.67 ...
 * ...
 * </pre>
 *
 * <p>Die Klasse liest das gesamte Raster in den Speicher und bietet
 * bilineare Interpolation der Gelaendehoehe fuer beliebige (x, y)-Koordinaten.
 *
 * <p>Keine externen Dependencies — nur java.nio und java.io.
 *
 * <p>Typische Verwendung:
 * <pre>
 * DgmReader dgm = DgmReader.load(Path.of("dgm1_33_416_5656.asc"));
 * double z = dgm.getHeight(416290.5, 5657417.3);  // bilinear interpoliert
 * </pre>
 */
public class DgmReader implements DgmProvider {

    private static final Logger log = LoggerFactory.getLogger(DgmReader.class);

    private final int ncols;
    private final int nrows;
    private final double xllcorner;
    private final double yllcorner;
    private final double cellsize;
    private final double nodata;
    private final float[][] data;  // [row][col], row 0 = noerdlichste Zeile

    private DgmReader(int ncols, int nrows, double xllcorner, double yllcorner,
                      double cellsize, double nodata, float[][] data) {
        this.ncols = ncols;
        this.nrows = nrows;
        this.xllcorner = xllcorner;
        this.yllcorner = yllcorner;
        this.cellsize = cellsize;
        this.nodata = nodata;
        this.data = data;
    }

    // ==================== Laden ====================

    /**
     * Laedt ein DGM aus einer ESRI-ASCII-Grid-Datei.
     *
     * @param path Pfad zur .asc-Datei
     * @return DgmReader-Instanz
     * @throws IOException bei Lesefehlern
     * @throws IllegalArgumentException bei ungueltigem Format
     */
    public static DgmReader load(Path path) throws IOException {
        log.info("Lade DGM: {}", path);
        long start = System.currentTimeMillis();

        int ncols = -1, nrows = -1;
        double xllcorner = Double.NaN, yllcorner = Double.NaN;
        double cellsize = Double.NaN;
        double nodata = -9999;

        float[][] data = null;
        int dataRow = 0;

        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Header-Zeilen parsen (case-insensitive)
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("ncols")) {
                    ncols = Integer.parseInt(extractHeaderValue(line));
                } else if (lower.startsWith("nrows")) {
                    nrows = Integer.parseInt(extractHeaderValue(line));
                } else if (lower.startsWith("xllcorner") || lower.startsWith("xllcenter")) {
                    xllcorner = Double.parseDouble(extractHeaderValue(line));
                    // xllcenter → korrigiere auf corner
                    if (lower.startsWith("xllcenter") && !Double.isNaN(cellsize)) {
                        xllcorner -= cellsize / 2.0;
                    }
                } else if (lower.startsWith("yllcorner") || lower.startsWith("yllcenter")) {
                    yllcorner = Double.parseDouble(extractHeaderValue(line));
                    if (lower.startsWith("yllcenter") && !Double.isNaN(cellsize)) {
                        yllcorner -= cellsize / 2.0;
                    }
                } else if (lower.startsWith("cellsize") || lower.startsWith("dx")) {
                    cellsize = Double.parseDouble(extractHeaderValue(line));
                } else if (lower.startsWith("nodata")) {
                    nodata = Double.parseDouble(extractHeaderValue(line));
                } else {
                    // Datenzeile
                    if (data == null) {
                        if (ncols <= 0 || nrows <= 0) {
                            throw new IllegalArgumentException(
                                    "DGM: ncols/nrows fehlt oder ungueltig (ncols=" + ncols + ", nrows=" + nrows + ")");
                        }
                        if (Double.isNaN(xllcorner) || Double.isNaN(yllcorner) || Double.isNaN(cellsize)) {
                            throw new IllegalArgumentException(
                                    "DGM: xllcorner/yllcorner/cellsize fehlt");
                        }
                        data = new float[nrows][ncols];
                    }

                    if (dataRow >= nrows) {
                        break; // Mehr Zeilen als erwartet → ignorieren
                    }

                    String[] tokens = line.split("\\s+");
                    for (int c = 0; c < Math.min(tokens.length, ncols); c++) {
                        data[dataRow][c] = Float.parseFloat(tokens[c]);
                    }
                    dataRow++;
                }
            }
        }

        if (data == null || dataRow < nrows) {
            throw new IllegalArgumentException(
                    "DGM: Unvollstaendige Daten (erwartet " + nrows + " Zeilen, gelesen " + dataRow + ")");
        }

        long ms = System.currentTimeMillis() - start;
        log.info("DGM geladen: {}x{} Zellen, cellsize={}, Bereich [{}, {}] - [{}, {}] in {} ms",
                ncols, nrows, cellsize,
                String.format(Locale.US, "%.1f", xllcorner),
                String.format(Locale.US, "%.1f", yllcorner),
                String.format(Locale.US, "%.1f", xllcorner + ncols * cellsize),
                String.format(Locale.US, "%.1f", yllcorner + nrows * cellsize),
                ms);

        return new DgmReader(ncols, nrows, xllcorner, yllcorner, cellsize, nodata, data);
    }

    // ==================== Abfrage ====================

    /**
     * Liefert die bilinear interpolierte Gelaendehoehe fuer eine (x, y)-Koordinate.
     *
     * <p>Verwendet bilineare Interpolation zwischen den vier umgebenden Rasterzellen.
     * Falls der Punkt ausserhalb des DGM-Bereichs liegt oder auf NODATA-Zellen trifft,
     * wird {@code Double.NaN} zurueckgegeben.
     *
     * @param x X-Koordinate (Rechtswert, z.B. UTM-Easting)
     * @param y Y-Koordinate (Hochwert, z.B. UTM-Northing)
     * @return interpolierte Gelaendhoehe oder NaN
     */
    @Override
    public double getHeight(double x, double y) {
        // Rasterkoordinaten berechnen (floating-point)
        double col = (x - xllcorner) / cellsize - 0.5;
        double row = (yllcorner + nrows * cellsize - y) / cellsize - 0.5;

        // Ganzzahlige Zelle (links-oben des 2x2-Blocks)
        int c0 = (int) Math.floor(col);
        int r0 = (int) Math.floor(row);

        // Randpruefung
        if (c0 < 0 || c0 >= ncols - 1 || r0 < 0 || r0 >= nrows - 1) {
            // Am aeussersten Rand: Nearest-Neighbor statt NaN
            int cn = Math.max(0, Math.min(ncols - 1, (int) Math.round(col)));
            int rn = Math.max(0, Math.min(nrows - 1, (int) Math.round(row)));
            float v = data[rn][cn];
            return isNodata(v) ? Double.NaN : v;
        }

        // 4 Nachbarzellen
        float z00 = data[r0][c0];
        float z10 = data[r0][c0 + 1];
        float z01 = data[r0 + 1][c0];
        float z11 = data[r0 + 1][c0 + 1];

        // NODATA-Pruefung
        if (isNodata(z00) || isNodata(z10) || isNodata(z01) || isNodata(z11)) {
            return Double.NaN;
        }

        // Bilineare Interpolation
        double fx = col - c0;
        double fy = row - r0;
        double z = (1 - fx) * (1 - fy) * z00
                + fx * (1 - fy) * z10
                + (1 - fx) * fy * z01
                + fx * fy * z11;

        return z;
    }

    /**
     * Prueft, ob eine Koordinate innerhalb des DGM-Bereichs liegt.
     *
     * @param x X-Koordinate
     * @param y Y-Koordinate
     * @return true wenn innerhalb
     */
    @Override
    public boolean contains(double x, double y) {
        return x >= xllcorner && x <= xllcorner + ncols * cellsize
                && y >= yllcorner && y <= yllcorner + nrows * cellsize;
    }

    // ==================== Getter ====================

    public int getCols() { return ncols; }
    public int getRows() { return nrows; }
    public double getCellsize() { return cellsize; }
    public double getXllcorner() { return xllcorner; }
    public double getYllcorner() { return yllcorner; }

    /**
     * X-Maximum des Rasterbereichs.
     */
    public double getXMax() { return xllcorner + ncols * cellsize; }

    /**
     * Y-Maximum des Rasterbereichs.
     */
    public double getYMax() { return yllcorner + nrows * cellsize; }

    @Override
    public String describe() {
        return String.format("ASC %dx%d cellsize=%.1f [%.0f,%.0f]-[%.0f,%.0f]",
                ncols, nrows, cellsize, xllcorner, yllcorner, getXMax(), getYMax());
    }

    // ==================== Intern ====================

    private boolean isNodata(float value) {
        return Math.abs(value - nodata) < 0.01f;
    }

    /**
     * Extrahiert den Wert aus einer Header-Zeile (z.B. "ncols     1000" → "1000").
     */
    private static String extractHeaderValue(String headerLine) {
        String[] parts = headerLine.trim().split("\\s+", 2);
        if (parts.length < 2) {
            throw new IllegalArgumentException("DGM: Ungueltiger Header: " + headerLine);
        }
        return parts[1].trim();
    }
}
