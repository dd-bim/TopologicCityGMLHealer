package de.mpsc.lod2tolod3.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.plugins.tiff.TIFFTag;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

/**
 * Liest ein digitales Gelaendemodell (DGM) aus einer GeoTIFF-Datei (.tif / .tiff).
 *
 * <p>Verwendet Java 9+ eingebauten TIFF-Reader (javax.imageio.plugins.tiff),
 * keine externen Dependencies.
 *
 * <p>Unterstuetzt:
 * <ul>
 *   <li>Single-Band GeoTIFF (Float32, Int16, Int32, etc.)</li>
 *   <li>Georeferenzierung via ModelTiepointTag (33922) + ModelPixelScaleTag (33550)</li>
 *   <li>NODATA via GDAL_NODATA-Tag (42113) oder Standard -9999</li>
 *   <li>LZW, Deflate, PackBits Kompression</li>
 * </ul>
 *
 * <p>Typische Verwendung:
 * <pre>
 * GeoTiffReader dgm = GeoTiffReader.load(Path.of("dgm1.tif"));
 * double z = dgm.getHeight(416290.5, 5657417.3);
 * </pre>
 */
public class GeoTiffReader implements DgmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeoTiffReader.class);

    /** GeoTIFF tag: ModelPixelScaleTag (ScaleX, ScaleY, ScaleZ). */
    private static final int TAG_MODEL_PIXEL_SCALE = 33550;

    /** GeoTIFF tag: ModelTiepointTag (I, J, K, X, Y, Z). */
    private static final int TAG_MODEL_TIEPOINT = 33922;

    /** GeoTIFF tag: ModelTransformationTag (4x4 affine matrix, alternative). */
    private static final int TAG_MODEL_TRANSFORMATION = 34264;

    /** GDAL extension tag: NODATA value as string. */
    private static final int TAG_GDAL_NODATA = 42113;

    private final int width;
    private final int height;
    private final double xOrigin;    // X der linken oberen Ecke (Pixel-Mitte oder -Ecke)
    private final double yOrigin;    // Y der linken oberen Ecke
    private final double pixelSizeX; // positiv, Pixelbreite in Metern
    private final double pixelSizeY; // positiv, Pixelhoehe in Metern (Y laeuft abwaerts)
    private final float nodata;
    private final float[][] data;    // [row][col], row 0 = noerdlichste Zeile

    private GeoTiffReader(int width, int height, double xOrigin, double yOrigin,
                          double pixelSizeX, double pixelSizeY, float nodata, float[][] data) {
        this.width = width;
        this.height = height;
        this.xOrigin = xOrigin;
        this.yOrigin = yOrigin;
        this.pixelSizeX = pixelSizeX;
        this.pixelSizeY = pixelSizeY;
        this.nodata = nodata;
        this.data = data;
    }

    // ==================== Laden ====================

    /**
     * Laedt ein DGM aus einer GeoTIFF-Datei.
     *
     * @param path Pfad zur .tif / .tiff Datei
     * @return GeoTiffReader-Instanz
     * @throws IOException bei Lesefehlern oder ungueltigem Format
     */
    public static GeoTiffReader load(Path path) throws IOException {
        log.info("Lade GeoTIFF-DGM: {}", path);
        try (ImageInputStream iis = ImageIO.createImageInputStream(Files.newInputStream(path))) {
            return loadFromStream(iis, path.getFileName().toString());
        }
    }

    /**
     * Laedt ein DGM aus einem InputStream (z.B. aus einer ZIP-Datei).
     *
     * <p>Der InputStream wird komplett in den Speicher gelesen, da der
     * TIFF-Reader Random-Access benoetigt.
     *
     * @param is InputStream mit TIFF-Daten
     * @param name Dateiname (fuer Logging)
     * @return GeoTiffReader-Instanz
     * @throws IOException bei Lesefehlern
     */
    public static GeoTiffReader load(InputStream is, String name) throws IOException {
        log.info("Lade GeoTIFF-DGM aus Stream: {}", name);
        byte[] bytes = is.readAllBytes();
        try (ImageInputStream iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            return loadFromStream(iis, name);
        }
    }

    private static GeoTiffReader loadFromStream(ImageInputStream iis, String name) throws IOException {
        long start = System.currentTimeMillis();

        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("tiff");
        if (!readers.hasNext()) {
            throw new IOException("Kein TIFF-ImageReader verfuegbar. Java 9+ erforderlich.");
        }

        ImageReader reader = readers.next();
        try {
            reader.setInput(iis, true, false);

            // --- Raster lesen ---
            RenderedImage image = reader.readAsRenderedImage(0, null);
            int w = image.getWidth();
            int h = image.getHeight();
            Raster raster = image.getData();

            float[][] data = new float[h][w];
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    data[row][col] = raster.getSampleFloat(col + raster.getMinX(),
                            row + raster.getMinY(), 0);
                }
            }

            // --- GeoTIFF-Tags lesen ---
            IIOMetadata metadata = reader.getImageMetadata(0);
            TIFFDirectory dir = TIFFDirectory.createFromMetadata(metadata);

            double pixelSizeX, pixelSizeY;
            double xOrigin, yOrigin;

            // Variante 1: ModelPixelScaleTag + ModelTiepointTag (Standard)
            TIFFField scaleField = dir.getTIFFField(TAG_MODEL_PIXEL_SCALE);
            TIFFField tiepointField = dir.getTIFFField(TAG_MODEL_TIEPOINT);

            if (scaleField != null && tiepointField != null) {
                double[] scale = getDoubleArray(scaleField);
                double[] tiepoint = getDoubleArray(tiepointField);

                if (scale.length < 2 || tiepoint.length < 6) {
                    throw new IOException("GeoTIFF: Unvollstaendige Scale/Tiepoint-Tags in " + name);
                }

                pixelSizeX = scale[0];
                pixelSizeY = scale[1];

                // Tiepoint: (I, J, K) -> (X, Y, Z)
                // Pixel (I,J) entspricht Koordinate (X,Y)
                double tpI = tiepoint[0];
                double tpJ = tiepoint[1];
                double tpX = tiepoint[3];
                double tpY = tiepoint[4];

                // Umrechnen auf Pixel (0,0) = linke obere Ecke
                xOrigin = tpX - tpI * pixelSizeX;
                yOrigin = tpY + tpJ * pixelSizeY;

            } else {
                // Variante 2: ModelTransformationTag (4x4 Matrix)
                TIFFField transformField = dir.getTIFFField(TAG_MODEL_TRANSFORMATION);
                if (transformField != null) {
                    double[] matrix = getDoubleArray(transformField);
                    if (matrix.length < 16) {
                        throw new IOException("GeoTIFF: Unvollstaendige TransformationMatrix in " + name);
                    }
                    // Affine: X = matrix[3] + col*matrix[0] + row*matrix[1]
                    //         Y = matrix[7] + col*matrix[4] + row*matrix[5]
                    pixelSizeX = Math.abs(matrix[0]);
                    pixelSizeY = Math.abs(matrix[5]);
                    xOrigin = matrix[3];
                    yOrigin = matrix[7];
                } else {
                    throw new IOException("GeoTIFF: Keine Georeferenzierung gefunden in " + name
                            + " (weder ModelPixelScale+Tiepoint noch ModelTransformation)");
                }
            }

            // --- NODATA ---
            float nodata = -9999f;
            TIFFField nodataField = dir.getTIFFField(TAG_GDAL_NODATA);
            if (nodataField != null) {
                try {
                    String nodataStr = nodataField.getAsString(0).trim();
                    nodata = Float.parseFloat(nodataStr);
                } catch (Exception e) {
                    log.warn("GeoTIFF: GDAL_NODATA-Tag nicht parsbar in {}, verwende -9999", name);
                }
            }

            long ms = System.currentTimeMillis() - start;
            double xMax = xOrigin + w * pixelSizeX;
            double yMin = yOrigin - h * pixelSizeY;
            log.info("GeoTIFF geladen: {}x{}, pixelSize={}, Bereich [{}, {}] - [{}, {}] in {} ms",
                    w, h,
                    String.format(Locale.US, "%.2f", pixelSizeX),
                    String.format(Locale.US, "%.1f", xOrigin),
                    String.format(Locale.US, "%.1f", yMin),
                    String.format(Locale.US, "%.1f", xMax),
                    String.format(Locale.US, "%.1f", yOrigin),
                    ms);

            return new GeoTiffReader(w, h, xOrigin, yOrigin, pixelSizeX, pixelSizeY, nodata, data);

        } finally {
            reader.dispose();
        }
    }

    // ==================== Abfrage ====================

    /**
     * Liefert die bilinear interpolierte Gelaendehoehe fuer eine (x, y)-Koordinate.
     *
     * @param x X-Koordinate (Rechtswert, z.B. UTM-Easting)
     * @param y Y-Koordinate (Hochwert, z.B. UTM-Northing)
     * @return interpolierte Gelaendhoehe oder NaN
     */
    @Override
    public double getHeight(double x, double y) {
        // Floating-Point Rasterposition (Pixel-Mitte)
        double col = (x - xOrigin) / pixelSizeX - 0.5;
        double row = (yOrigin - y) / pixelSizeY - 0.5;

        int c0 = (int) Math.floor(col);
        int r0 = (int) Math.floor(row);

        // Randpruefung: Nearest-Neighbor am Rand
        if (c0 < 0 || c0 >= width - 1 || r0 < 0 || r0 >= height - 1) {
            int cn = Math.max(0, Math.min(width - 1, (int) Math.round(col)));
            int rn = Math.max(0, Math.min(height - 1, (int) Math.round(row)));
            float v = data[rn][cn];
            return isNodata(v) ? Double.NaN : v;
        }

        // 4 Nachbarzellen
        float z00 = data[r0][c0];
        float z10 = data[r0][c0 + 1];
        float z01 = data[r0 + 1][c0];
        float z11 = data[r0 + 1][c0 + 1];

        if (isNodata(z00) || isNodata(z10) || isNodata(z01) || isNodata(z11)) {
            return Double.NaN;
        }

        double fx = col - c0;
        double fy = row - r0;
        return (1 - fx) * (1 - fy) * z00
                + fx * (1 - fy) * z10
                + (1 - fx) * fy * z01
                + fx * fy * z11;
    }

    /**
     * Prueft, ob eine Koordinate innerhalb des GeoTIFF-Bereichs liegt.
     */
    @Override
    public boolean contains(double x, double y) {
        double xMax = xOrigin + width * pixelSizeX;
        double yMin = yOrigin - height * pixelSizeY;
        return x >= xOrigin && x <= xMax && y >= yMin && y <= yOrigin;
    }

    @Override
    public String describe() {
        double xMax = xOrigin + width * pixelSizeX;
        double yMin = yOrigin - height * pixelSizeY;
        return String.format("TIFF %dx%d pixel=%.2f [%.0f,%.0f]-[%.0f,%.0f]",
                width, height, pixelSizeX, xOrigin, yMin, xMax, yOrigin);
    }

    // ==================== Getter ====================

    public int getWidth() { return width; }
    public int getImageHeight() { return height; }
    public double getPixelSizeX() { return pixelSizeX; }
    public double getPixelSizeY() { return pixelSizeY; }
    public double getXOrigin() { return xOrigin; }
    public double getYOrigin() { return yOrigin; }
    public double getXMax() { return xOrigin + width * pixelSizeX; }
    public double getYMin() { return yOrigin - height * pixelSizeY; }

    // ==================== Intern ====================

    private boolean isNodata(float value) {
        return Float.isNaN(value) || Math.abs(value - nodata) < 0.01f;
    }

    /**
     * Liest Double-Werte aus einem TIFFField, unabhaengig vom Datentyp
     * (TIFF_DOUBLE, TIFF_FLOAT, TIFF_RATIONAL, etc.).
     */
    private static double[] getDoubleArray(TIFFField field) {
        int count = field.getCount();
        double[] result = new double[count];

        int type = field.getType();
        for (int i = 0; i < count; i++) {
            switch (type) {
                case TIFFTag.TIFF_DOUBLE:
                    result[i] = field.getAsDouble(i);
                    break;
                case TIFFTag.TIFF_FLOAT:
                    result[i] = field.getAsFloat(i);
                    break;
                case TIFFTag.TIFF_RATIONAL:
                case TIFFTag.TIFF_SRATIONAL:
                    long[] rational = field.getAsRational(i);
                    result[i] = (double) rational[0] / rational[1];
                    break;
                default:
                    result[i] = field.getAsDouble(i);
                    break;
            }
        }
        return result;
    }
}
