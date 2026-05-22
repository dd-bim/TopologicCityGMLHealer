package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.util.DgmLoader;
import de.mpsc.lod2tolod3.util.DgmProvider;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.CityGMLVersion;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.core.AbstractFeature;
import org.citygml4j.xml.CityGMLContext;
import org.citygml4j.xml.reader.CityGMLInputFactory;
import org.citygml4j.xml.reader.CityGMLReader;
import org.citygml4j.xml.reader.ChunkOptions;
import org.citygml4j.xml.writer.CityGMLChunkWriter;
import org.citygml4j.xml.writer.CityGMLOutputFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.feature.BoundingShape;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;

/**
 * Haupt-Pipeline: Führt alle Schritte der LoD2→LoD3-Konvertierung aus.
 * 
 * Single-Pass-Architektur: Die Eingabedatei wird einmal gelesen, alle
 * Verarbeitungsschritte werden pro Gebaeude im Speicher ausgefuehrt,
 * und das Ergebnis wird einmal geschrieben. Keine Zwischendateien.
 * 
 * Schritte:
 *   1. LoD2 → LoD3 Geometrie-Konvertierung
 *   2. Keller hinzufügen (basierend auf Baukörpermodulen)
 *   3. Geschosse unterteilen (BuildingStorey)
 *   4. Türen hinzufügen (DoorGenerator)
 *   5. Fenster hinzufügen (WindowGenerator)
 *   6. (TODO) Balkone hinzufügen
 * 
 * Usage:
 *   java -jar lod2-zu-lod3.jar <input.gml> [jsonDir] [outputDir] [dgmPath]
 *   
 * Oder einzelne Schritte:
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.Lod2ToLod3Promoter <input.gml> [output.gml]
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.BasementGenerator <input.gml> <jsonDir> [output.gml]
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.StoreyGenerator <input.gml> <jsonDir> [output.gml]
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.DoorGenerator <input.gml> <jsonDir> [output.gml]
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.WindowGenerator <input.gml> <jsonDir> [output.gml]
 */
public class Lod2ToLod3Pipeline {
    private static final Logger log = LoggerFactory.getLogger(Lod2ToLod3Pipeline.class);

    private static final String DEFAULT_INPUT = "sqltest/LoD2_33_416_5656_2_SN_BuildingPreferences.gml";
    private static final String DEFAULT_JSON = "sqltest/Baukörpermodule_json";
    private static final String DEFAULT_OUTPUT = "sqltest/output";

    public static void main(String[] args) {
        try {
            Path workspaceRoot = Paths.get("").toAbsolutePath();
            Path inputPath = args.length >= 1 ? Paths.get(args[0]) : workspaceRoot.resolve(DEFAULT_INPUT);
            Path jsonDir = args.length >= 2 ? Paths.get(args[1]) : workspaceRoot.resolve(DEFAULT_JSON);
            Path outputDir = args.length >= 3 ? Paths.get(args[2]) : workspaceRoot.resolve(DEFAULT_OUTPUT);
            Path dgmPath = args.length >= 4 ? Paths.get(args[3]) : null;

            // DGM laden (optional — Datei, ZIP oder Verzeichnis)
            DgmProvider dgm = null;
            if (dgmPath != null && Files.exists(dgmPath)) {
                dgm = DgmLoader.load(dgmPath);
                log.info("DGM geladen: {} — {}", dgmPath, dgm.describe());
            } else if (dgmPath != null) {
                log.warn("DGM-Pfad nicht gefunden: {} — verwende flache TIC bei H_DGM", dgmPath);
            }

            log.info("============================================================");
            log.info("  LoD2 -> LoD3 Konvertierungs-Pipeline (Single-Pass)      ");
            log.info("============================================================");
            log.info("Input:  {}", inputPath);
            log.info("JSON:   {}", jsonDir);
            log.info("Output: {}", outputDir);
            log.info("DGM:    {}", dgm != null ? dgmPath : "(kein DGM — flache TIC)");

            Files.createDirectories(outputDir);

            // Dateinamen vorbereiten
            String baseName = inputPath.getFileName().toString();
            if (baseName.toLowerCase().endsWith(".gml")) {
                baseName = baseName.substring(0, baseName.length() - 4);
            }
            String cleanName = baseName.replace("LoD2", "LoD3")
                    .replaceAll("_BuildingPreferences", "");
            Path outputFile = outputDir.resolve(cleanName + ".gml");

            // Verarbeitungs-Komponenten initialisieren
            Lod2ToLod3Promoter promoter = new Lod2ToLod3Promoter();

            BasementGenerator basementGen = new BasementGenerator();
            if (dgm != null) {
                basementGen.setDgm(dgm);
            }
            ModuleParametersLoader paramLoader = new ModuleParametersLoader(jsonDir);

            StoreyGenerator storeyGen = new StoreyGenerator();

            DoorGenerator doorGen = new DoorGenerator();

            WindowGenerator windowGen = new WindowGenerator();

            // Statistik-Objekte
            Lod2ToLod3Promoter.PromotionStats promStats = new Lod2ToLod3Promoter.PromotionStats();
            BasementGenerator.GenerationStats basementStats = new BasementGenerator.GenerationStats();
            StoreyGenerator.GenerationStats storeyStats = new StoreyGenerator.GenerationStats();
            DoorGenerator.GenerationStats doorStats = new DoorGenerator.GenerationStats();
            WindowGenerator.GenerationStats windowStats = new WindowGenerator.GenerationStats();

            // Generator-Schritte registrieren (Schritte 2–5)
            record PipelineStep(String label, Consumer<Building> action) {}
            List<PipelineStep> buildingSteps = List.of(
                new PipelineStep("Keller",    b -> { basementStats.buildingsProcessed++; basementGen.processBuilding(b, paramLoader, basementStats); }),
                new PipelineStep("Geschosse", b -> { storeyStats.buildingsProcessed++;   storeyGen.processBuilding(b, paramLoader, storeyStats); }),
                new PipelineStep("Tueren",    b -> { doorStats.buildingsProcessed++;     doorGen.processBuilding(b, paramLoader, doorStats); }),
                new PipelineStep("Fenster",   b -> { windowStats.buildingsProcessed++;   windowGen.processBuilding(b, paramLoader, windowStats); })
            );

            // ==================== Single-Pass Verarbeitung ====================
            long startTime = System.currentTimeMillis();

            CityGMLContext context = CityGMLContext.newInstance();
            CityGMLInputFactory in = context.createCityGMLInputFactory()
                    .withChunking(ChunkOptions.defaults());
            CityGMLOutputFactory out = context.createCityGMLOutputFactory(CityGMLVersion.v1_0);

            // Envelope aus Header lesen
            BoundingShape originalBoundedBy = null;
            try (CityGMLReader headerReader = context.createCityGMLInputFactory()
                    .createCityGMLReader(inputPath.toFile())) {
                if (headerReader.hasNext()) {
                    var firstFeature = headerReader.next();
                    if (firstFeature instanceof org.citygml4j.core.model.core.CityModel cm) {
                        originalBoundedBy = cm.getBoundedBy();
                    }
                }
            }

            try (CityGMLReader reader = in.createCityGMLReader(inputPath.toFile());
                 CityGMLChunkWriter writer = out.createCityGMLChunkWriter(outputFile,
                         StandardCharsets.UTF_8.name())) {

                writer.withIndent("\t").withDefaultPrefixes();

                if (originalBoundedBy != null) {
                    writer.getCityModelInfo().setBoundedBy(originalBoundedBy);
                    log.info("BoundedBy-Envelope uebernommen");
                }

                while (reader.hasNext()) {
                    AbstractFeature feature = reader.next();

                    if (feature instanceof Building building) {
                        // Schritt 1: LoD2 -> LoD3 Hochstufung
                        promStats.buildingsProcessed++;
                        var promResult = promoter.promoteBuildingToLod3(building);
                        promStats.geometriesPromoted += promResult.promotedCount;
                        promStats.promotedTypes.addAll(promResult.promotedTypes);
                        promStats.namesRenamed += promoter.renameLod2NamesToLod3(building);
                        promoter.addPromotionMetadata(building, promResult);

                        // Schritte 2–5: registrierte Generator-Schritte
                        for (var step : buildingSteps) {
                            step.action().accept(building);
                        }
                    }

                    writer.writeMember(feature);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;

            // ==================== Zusammenfassung ====================
            log.info("");
            log.info("============================================================");
            log.info("                  Pipeline abgeschlossen                    ");
            log.info("============================================================");
            log.info("Verarbeitungszeit: {}.{} s", elapsed / 1000, String.format("%03d", elapsed % 1000));
            log.info("Ausgabedatei: {}", outputFile);
            log.info("");
            log.info("Schritt 1 — Promotion:  {} Gebaeude, {} Geometrien hochgestuft, {} Namen",
                    promStats.buildingsProcessed, promStats.geometriesPromoted, promStats.namesRenamed);
            log.info("Schritt 2 — Keller:     {} Keller, {} GS ersetzt, {} TICs",
                    basementStats.basementsAdded, basementStats.groundSurfacesReplaced,
                    basementStats.ticsCreated);
            log.info("Schritt 3 — Geschosse:  {} Geschosse, {} Wandsegmente, {} Boeden, {} Decken",
                    storeyStats.storeysCreated, storeyStats.wallSegmentsCreated,
                    storeyStats.floorsCreated, storeyStats.ceilingsCreated);
            log.info("Schritt 4 — Tueren:     {} Tueren, {} Waende modifiziert, {} uebersprungen",
                    doorStats.doorsCreated, doorStats.wallsModified, doorStats.wallsSkipped);
            log.info("Schritt 5 — Fenster:    {} Fenster, {} Waende, {} uebersprungen, {} Giebel-Drops, {} WWR-Warn.",
                    windowStats.windowsCreated, windowStats.wallsWithWindows,
                    windowStats.wallsSkipped, windowStats.gableWindowsDropped,
                    windowStats.wwrWarnings);
            log.info("Schritt 5 — {}", windowStats.toSummary());
            log.info("Schritt 5 — {}", windowStats.toGeschossSummary());

        } catch (Exception e) {
            log.error("Fehler in der Pipeline: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
