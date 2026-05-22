package de.mpsc.lod2tolod3.util;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Lädt und cached ModuleParameters aus JSON-Dateien.
 * 
 * Unterstützt das Mapping von sst-Attributen zu JSON-Dateien:
 * - ME3 → ME3_4.json
 * - EE3 → EE3_4.json
 * - LW2 → LW2.json
 * - etc.
 */
public class ModuleParametersLoader {
    
    private static final Logger log = LoggerFactory.getLogger(ModuleParametersLoader.class);
    
    private final Path jsonDirectory;
    private final Map<String, ModuleParameters> cache = new HashMap<>();
    private final Map<String, String> fileMapping = new HashMap<>();
    
    public ModuleParametersLoader(Path jsonDirectory) {
        this.jsonDirectory = jsonDirectory;
        initializeFileMapping();
    }
    
    /**
     * Initialisiert das Mapping von Modul-IDs zu Dateinamen.
     * Scannt das Verzeichnis nach verfügbaren JSON-Dateien.
     */
    private void initializeFileMapping() {
        try (var files = Files.list(jsonDirectory)) {
            files.filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    String filename = p.getFileName().toString();
                    // Entferne .json und _4 Suffix für das Mapping
                    String baseId = filename.replace(".json", "").replace("_4", "");
                    fileMapping.put(baseId, filename);
                    
                    // Auch die volle ID (mit _4) registrieren
                    String fullId = filename.replace(".json", "");
                    if (!fullId.equals(baseId)) {
                        fileMapping.put(fullId, filename);
                    }
                });
            
            log.info("ModuleParametersLoader initialisiert mit {} Modulen aus {}", 
                    fileMapping.size(), jsonDirectory);
            
        } catch (IOException e) {
            log.error("Fehler beim Scannen des JSON-Verzeichnisses: {}", e.getMessage());
        }
    }
    
    /**
     * Lädt ModuleParameters für eine Modul-ID (z.B. "ME3", "LW2").
     * Verwendet Caching für wiederholte Aufrufe.
     */
    public Optional<ModuleParameters> getParameters(String moduleId) {
        if (moduleId == null || moduleId.isEmpty()) {
            return Optional.empty();
        }
        
        // Aus Cache holen falls vorhanden
        if (cache.containsKey(moduleId)) {
            return Optional.ofNullable(cache.get(moduleId));
        }
        
        // Dateiname ermitteln
        String filename = fileMapping.get(moduleId);
        if (filename == null) {
            // Fallback: direkt als Dateiname versuchen
            filename = moduleId + ".json";
            if (!Files.exists(jsonDirectory.resolve(filename))) {
                filename = moduleId + "_4.json";
            }
        }
        
        Path jsonFile = jsonDirectory.resolve(filename);
        if (!Files.exists(jsonFile)) {
            log.debug("Keine JSON-Datei gefunden für Modul: {}", moduleId);
            cache.put(moduleId, null);
            return Optional.empty();
        }
        
        // JSON laden und parsen
        Optional<ModuleParameters> params = ModuleParameters.fromFile(jsonFile);
        params.ifPresent(p -> {
            cache.put(moduleId, p);
            log.debug("ModuleParameters geladen für {}: {}", moduleId, p);
        });
        
        if (params.isEmpty()) {
            cache.put(moduleId, null);
        }
        
        return params;
    }
    
}
