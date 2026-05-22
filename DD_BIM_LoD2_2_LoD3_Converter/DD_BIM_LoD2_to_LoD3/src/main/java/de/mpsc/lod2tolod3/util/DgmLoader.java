package de.mpsc.lod2tolod3.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Factory zum Laden von DGM-Daten aus verschiedenen Quellen und Formaten.
 *
 * <p>Erkennt automatisch:
 * <ul>
 *   <li>Einzelne Dateien: .asc (ESRI ASCII Grid), .tif/.tiff (GeoTIFF)</li>
 *   <li>ZIP-Dateien: Enthaelt .asc oder .tif/.tiff → wird on-the-fly gelesen</li>
 *   <li>Verzeichnisse: Alle .asc, .tif, .tiff und .zip Dateien werden als
 *       Mosaik geladen (rekursiv)</li>
 * </ul>
 *
 * <p>Typische Verwendung:
 * <pre>
 * // Einzelne Datei
 * DgmProvider dgm = DgmLoader.load(Path.of("dgm.asc"));
 *
 * // Einzelnes ZIP
 * DgmProvider dgm = DgmLoader.load(Path.of("dgm1_33416_5656.zip"));
 *
 * // Verzeichnis mit vielen ZIPs/TIFFs
 * DgmProvider dgm = DgmLoader.load(Path.of("DGM/Dresden"));
 * </pre>
 */
public class DgmLoader {

    private static final Logger log = LoggerFactory.getLogger(DgmLoader.class);

    /** Erkannte Kachel-Formate (direkt ladbar, auch innerhalb von ZIPs). */
    private static final Set<String> TILE_EXTENSIONS = Set.of(".asc", ".tif", ".tiff");

    private DgmLoader() {} // Utility-Klasse

    /** Gibt die Dateiendung inkl. Punkt zurueck (z.B. ".tif"), oder "" wenn keine vorhanden. */
    private static String fileExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    /**
     * Laedt ein DGM aus einer Datei, einem ZIP oder einem Verzeichnis.
     *
     * <p>Erkennt Format automatisch:
     * <ul>
     *   <li>Datei .asc → {@link DgmReader}</li>
     *   <li>Datei .tif/.tiff → {@link GeoTiffReader}</li>
     *   <li>Datei .zip → entpackt und liest das erste .asc/.tif darin</li>
     *   <li>Verzeichnis → scannt rekursiv, laedt alles in ein {@link DgmMosaic}</li>
     * </ul>
     *
     * @param path Pfad zu Datei oder Verzeichnis
     * @return DgmProvider (Einzeltile oder Mosaik)
     * @throws IOException bei Lesefehlern
     * @throws IllegalArgumentException bei unbekanntem Format
     */
    public static DgmProvider load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("DGM-Pfad existiert nicht: " + path);
        }

        if (Files.isDirectory(path)) {
            return loadDirectory(path);
        }

        String ext = fileExtension(path.getFileName().toString().toLowerCase(Locale.ROOT));
        return switch (ext) {
            case ".asc"          -> DgmReader.load(path);
            case ".tif", ".tiff" -> GeoTiffReader.load(path);
            case ".zip"          -> loadZip(path);
            default              -> throw new IllegalArgumentException(
                    "Unbekanntes DGM-Format: " + path + " (erwartet: .asc, .tif, .tiff, .zip oder Verzeichnis)");
        };
    }

    /**
     * Laedt alle DGM-Dateien aus einem Verzeichnis als Mosaik.
     * Scannt rekursiv nach .asc, .tif, .tiff und .zip Dateien.
     */
    private static DgmProvider loadDirectory(Path dir) throws IOException {
        log.info("Scanne DGM-Verzeichnis: {}", dir);
        long start = System.currentTimeMillis();

        List<Path> files = new ArrayList<>();
        collectDgmFiles(dir, files);

        if (files.isEmpty()) {
            throw new IOException("Keine DGM-Dateien (.asc, .tif, .tiff, .zip) gefunden in: " + dir);
        }

        log.info("  {} DGM-Dateien gefunden", files.size());

        if (files.size() == 1) {
            // Nur eine Datei → direkt laden, kein Mosaik noetig
            return load(files.get(0));
        }

        DgmMosaic mosaic = new DgmMosaic();
        int loaded = 0;
        int failed = 0;

        for (Path file : files) {
            try {
                DgmProvider tile = load(file);
                mosaic.addTile(tile);
                loaded++;
            } catch (Exception e) {
                log.warn("  Fehler beim Laden von {}: {}", file.getFileName(), e.getMessage());
                failed++;
            }
        }

        long ms = System.currentTimeMillis() - start;
        log.info("DGM-Mosaik geladen: {} Tiles in {} ms{}",
                loaded, ms,
                failed > 0 ? " (" + failed + " fehlgeschlagen)" : "");

        if (loaded == 0) {
            throw new IOException("Keine DGM-Tiles erfolgreich geladen aus: " + dir);
        }

        return mosaic;
    }

    /**
     * Sammelt rekursiv alle DGM-Dateien (.asc, .tif, .tiff, .zip).
     */
    private static void collectDgmFiles(Path dir, List<Path> result) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    // Rekursiv in Unterverzeichnisse
                    collectDgmFiles(entry, result);
                } else {
                    String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                    String ext = fileExtension(name);
                    if (TILE_EXTENSIONS.contains(ext) || ".zip".equals(ext)) {
                        result.add(entry);
                    }
                }
            }
        }
    }

    /**
     * Laedt ein DGM aus einer ZIP-Datei.
     * Sucht nach der ersten .asc oder .tif/.tiff Datei im ZIP.
     */
    private static DgmProvider loadZip(Path zipPath) throws IOException {
        log.debug("Lese DGM aus ZIP: {}", zipPath.getFileName());

        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            // Passenden Eintrag suchen
            ZipEntry dgmEntry = null;
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String entryName = entry.getName().toLowerCase(Locale.ROOT);
                if (TILE_EXTENSIONS.contains(fileExtension(entryName))) {
                    dgmEntry = entry;
                    break;
                }
            }

            if (dgmEntry == null) {
                throw new IOException("Kein DGM (.asc/.tif/.tiff) in ZIP gefunden: " + zipPath);
            }

            String entryName = dgmEntry.getName().toLowerCase(Locale.ROOT);
            try (InputStream is = zipFile.getInputStream(dgmEntry)) {
                if (".asc".equals(fileExtension(entryName))) {
                    // ASCII Grid aus ZIP: in temp-Buffer lesen und parsen
                    return loadAscFromStream(is, dgmEntry.getName());
                } else {
                    // GeoTIFF aus ZIP
                    return GeoTiffReader.load(is, dgmEntry.getName());
                }
            }
        }
    }

    /**
     * Laedt ein ESRI ASCII Grid aus einem InputStream.
     * Schreibt den Stream in eine temporaere Datei, da DgmReader.load(Path) erwartet.
     */
    private static DgmReader loadAscFromStream(InputStream is, String name) throws IOException {
        Path tempFile = Files.createTempFile("dgm_", ".asc");
        try {
            Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return DgmReader.load(tempFile);
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
        }
    }
}
