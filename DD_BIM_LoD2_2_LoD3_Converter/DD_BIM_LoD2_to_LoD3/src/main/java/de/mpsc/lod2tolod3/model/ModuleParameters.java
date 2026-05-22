package de.mpsc.lod2tolod3.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Strukturierte Klasse für alle Baukörpermodul-Parameter aus den JSON-Dateien.
 * 
 * Hauptkategorien:
 * - BA: Basement (Keller)
 * - GF: Ground Floor (Erdgeschoss)
 * - UF: Upper Floor (Obergeschoss)
 * - RO: Roof (Dach)
 * - UT: Utilities (Versorgungsschächte)
 * - GA: Gallery (Balkon/Galerie)
 * - IN: Interior (Innenraum-Aufteilung)
 * - FL: Stairwell/Floor (Treppenhaus)
 * - BU: Building (Allgemeine Gebäudedaten)
 * - FD: Facade Details (Materialien/Vulnerabilität)
 */
public class ModuleParameters {
    
    private static final Logger log = LoggerFactory.getLogger(ModuleParameters.class);
    
    // Hauptkategorien
    private final Basement basement;
    private final GroundFloor groundFloor;
    private final UpperFloor upperFloor;
    private final Roof roof;
    private final Utilities utilities;
    private final Gallery gallery;
    private final Interior interior;
    private final Stairwell stairwell;
    private final Building building;
    private final FacadeDetails facadeDetails;
    
    private final String moduleId;
    
    // ==================== Konstruktor ====================
    
    private ModuleParameters(JsonObject json, String moduleId) {
        this.moduleId = moduleId;
        this.basement = parseBasement(json.getAsJsonObject("BA"));
        this.groundFloor = parseGroundFloor(json.getAsJsonObject("GF"));
        this.upperFloor = parseUpperFloor(json.getAsJsonObject("UF"));
        this.roof = parseRoof(json.getAsJsonObject("RO"));
        this.utilities = parseUtilities(json.getAsJsonObject("UT"));
        this.gallery = parseGallery(json.getAsJsonObject("GA"));
        this.interior = parseInterior(json.getAsJsonObject("IN"));
        this.stairwell = parseStairwell(json.getAsJsonObject("FL"));
        this.building = parseBuilding(json.getAsJsonObject("BU"));
        this.facadeDetails = parseFacadeDetails(json.getAsJsonObject("FD"));
    }
    
    // ==================== Factory-Methode ====================
    
    /**
     * Lädt ModuleParameters aus einer JSON-Datei.
     */
    public static Optional<ModuleParameters> fromFile(Path jsonFile) {
        try {
            String content = Files.readString(jsonFile);
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            String moduleId = jsonFile.getFileName().toString().replace(".json", "");
            return Optional.of(new ModuleParameters(json, moduleId));
        } catch (IOException e) {
            log.error("Fehler beim Laden von {}: {}", jsonFile, e.getMessage());
            return Optional.empty();
        }
    }
    
    // ==================== Getter ====================
    
    public String getModuleId() { return moduleId; }
    public Basement getBasement() { return basement; }
    public GroundFloor getGroundFloor() { return groundFloor; }
    public UpperFloor getUpperFloor() { return upperFloor; }
    public Roof getRoof() { return roof; }
    public Utilities getUtilities() { return utilities; }
    public Gallery getGallery() { return gallery; }
    public Interior getInterior() { return interior; }
    public Stairwell getStairwell() { return stairwell; }
    public Building getBuilding() { return building; }
    public FacadeDetails getFacadeDetails() { return facadeDetails; }
    
    // ==================== Convenience-Methoden ====================
    
    /**
     * Prüft ob das Gebäude einen Keller hat.
     */
    public boolean hasBasement() {
        return building != null && building.hasBasement && 
               basement != null && basement.height != null && basement.height > 0;
    }

    /**
     * Liest den oberirdischen Anteil des Kellers (GF.heightAboveGround).
     * Gibt 0 zurueck wenn nicht vorhanden oder ungueltig.
     */
    public double getHeightGr() {
        if (groundFloor == null) return 0;
        Double hg = groundFloor.heightAboveGround;
        if (hg == null || Double.isNaN(hg) || hg <= 0) return 0;
        return hg;
    }

    /**
     * Gibt die Fenster-Parameter fuer ein Geschoss zurueck.
     *
     * @param geschoss Geschoss-Tag (GF, UF_1, UF_2, ..., BA)
     * @return WindowParams oder null wenn nicht vorhanden
     */
    public WindowParams getWindowParamsForGeschoss(String geschoss) {
        return switch (geschoss) {
            case null  -> null;
            case "GF"  -> groundFloor != null ? groundFloor.window : null;
            case "BA"  -> basement    != null ? basement.window    : null;
            case String s when s.startsWith("UF_") -> upperFloor != null ? upperFloor.window : null;
            default    -> null;
        };
    }
    
    // ==================== Innere Klassen ====================
    
    /**
     * BA - Basement (Kellergeschoss)
     */
    public static class Basement {
        public final Double height;          // Kellerhöhe
        public final Double ceilingHeight;   // Deckendicke
        public final WindowParams window;    // Kellerfenster
        public final DoorParams door;        // Kellertür
        
        Basement(Double height, Double ceilingHeight, WindowParams window, DoorParams door) {
            this.height = height;
            this.ceilingHeight = ceilingHeight;
            this.window = window;
            this.door = door;
        }
    }
    
    /**
     * GF - Ground Floor (Erdgeschoss)
     */
    public static class GroundFloor {
        public final Double height;          // Geschosshöhe
        public final Double heightAboveGround; // Höhe über Gelände (oberirdischer Anteil Keller)
        public final Double ceilingHeight;   // Deckendicke
        public final WindowParams window;    // Fenster
        public final DoorParams door;        // Eingangstür
        
        GroundFloor(Double height, Double heightAboveGround, Double ceilingHeight, 
                    WindowParams window, DoorParams door) {
            this.height = height;
            this.heightAboveGround = heightAboveGround;
            this.ceilingHeight = ceilingHeight;
            this.window = window;
            this.door = door;
        }

        /**
         * Gesamte Geschosshoehe (height + ceilingHeight).
         * Gibt 0 zurueck wenn height fehlt.
         */
        public double getTotalHeight() {
            if (height == null || Double.isNaN(height)) return 0;
            double ceHe = (ceilingHeight != null && !Double.isNaN(ceilingHeight)) ? ceilingHeight : 0;
            return height + ceHe;
        }
    }
    
    /**
     * UF - Upper Floor (Obergeschoss)
     */
    public static class UpperFloor {
        public final Boolean extendPolygonHeight; // Polygon-Höhe erweitern?
        public final Double height;               // Geschosshöhe
        public final Double ceilingHeight;        // Deckendicke
        public final WindowParams window;         // Fenster
        
        UpperFloor(Boolean extendPolygonHeight, Double height, Double ceilingHeight, WindowParams window) {
            this.extendPolygonHeight = extendPolygonHeight;
            this.height = height;
            this.ceilingHeight = ceilingHeight;
            this.window = window;
        }

        /**
         * Gesamte Geschosshoehe (height + ceilingHeight).
         * Gibt 0 zurueck wenn height fehlt.
         */
        public double getTotalHeight() {
            if (height == null || Double.isNaN(height)) return 0;
            double ceHe = (ceilingHeight != null && !Double.isNaN(ceilingHeight)) ? ceilingHeight : 0;
            return height + ceHe;
        }
    }
    
    /**
     * RO - Roof (Dach)
     */
    public static class Roof {
        public final WindowParams window;    // Dachfenster
        public final RoofShape shape;        // Dachform
        
        Roof(WindowParams window, RoofShape shape) {
            this.window = window;
            this.shape = shape;
        }
    }
    
    /**
     * Dachform-Parameter
     */
    public static class RoofShape {
        public final Integer type;           // Dachtyp (1=Satteldach, 2=Flachdach, etc.)
        public final Double ridgeHeight;     // Firsthöhe
        public final Double hSideTile;       // Horizontal Side Tile
        public final Double vSideTile;       // Vertical Side Tile
        public final Double hFrontTile;      // Horizontal Front Tile
        public final Double vFrontTile;      // Vertical Front Tile
        
        RoofShape(Integer type, Double ridgeHeight, Double hSideTile, Double vSideTile,
                  Double hFrontTile, Double vFrontTile) {
            this.type = type;
            this.ridgeHeight = ridgeHeight;
            this.hSideTile = hSideTile;
            this.vSideTile = vSideTile;
            this.hFrontTile = hFrontTile;
            this.vFrontTile = vFrontTile;
        }
    }
    
    /**
     * Fenster-Parameter (für alle Geschosse)
     */
    public static class WindowParams {
        public final Double hDistWallWindow;     // Horizontaler Abstand Wand-Fenster
        public final Double vDistFloorWindow;    // Vertikaler Abstand Boden-Fenster (Brüstungshöhe)
        public final Double hDistWindowWindow;   // Horizontaler Abstand Fenster-Fenster
        public final Double hDistDoorWindow;     // Horizontaler Abstand Tür-Fenster
        public final Double windowWidth;         // Fensterbreite
        public final Double windowHeight;        // Fensterhöhe
        public final Double hDistMinWallWindow;  // Minimaler Abstand Wand-Fenster
        public final Integer maxWindowsPerRow;   // Max. Fenster pro Reihe (optional, null=kein Limit)
        
        WindowParams(Double hDistWallWindow, Double vDistFloorWindow, Double hDistWindowWindow,
                     Double hDistDoorWindow, Double windowWidth, Double windowHeight, 
                     Double hDistMinWallWindow, Integer maxWindowsPerRow) {
            this.hDistWallWindow = hDistWallWindow;
            this.vDistFloorWindow = vDistFloorWindow;
            this.hDistWindowWindow = hDistWindowWindow;
            this.hDistDoorWindow = hDistDoorWindow;
            this.windowWidth = windowWidth;
            this.windowHeight = windowHeight;
            this.hDistMinWallWindow = hDistMinWallWindow;
            this.maxWindowsPerRow = maxWindowsPerRow;
        }
        
        /**
         * Prüft ob gültige Fensterparameter vorhanden sind.
         * NaN-Werte gelten als ungültig.
         */
        public boolean isValid() {
            return windowWidth != null && !Double.isNaN(windowWidth) && windowWidth > 0 &&
                   windowHeight != null && !Double.isNaN(windowHeight) && windowHeight > 0;
        }

        /**
         * Gibt den Wert zurück oder 0 wenn null/NaN.
         */
        public static double safeValue(Double val) {
            return (val != null && !Double.isNaN(val)) ? val : 0;
        }
    }
    
    /**
     * Tür-Parameter
     */
    public static class DoorParams {
        public final Double doorHeight;      // Türhöhe
        public final Double doorWidth;       // Türbreite
        public final Double hDistDoorWall;   // Horizontaler Abstand Tür-Wand
        
        DoorParams(Double doorHeight, Double doorWidth, Double hDistDoorWall) {
            this.doorHeight = doorHeight;
            this.doorWidth = doorWidth;
            this.hDistDoorWall = hDistDoorWall;
        }
        
        /**
         * Prüft ob gültige Türparameter vorhanden sind.
         */
        public boolean isValid() {
            return doorWidth != null && doorWidth > 0 &&
                   doorHeight != null && doorHeight > 0;
        }
    }
    
    /**
     * UT - Utilities (Versorgungsschächte)
     */
    public static class Utilities {
        public final Double hDistWallPipe;       // Horizontaler Abstand Wand-Schacht
        public final Double hDistWallPipe2;      // Zweiter Abstand
        public final Double vDistFloorPipe;      // Vertikaler Abstand Boden-Schacht
        public final Double sizeX;               // Schachtgröße X
        public final Double sizeY;               // Schachtgröße Y
        public final Double sizeZ;               // Schachtgröße Z
        public final Boolean inUpperFloor;       // Schacht in OG?
        public final Boolean inGroundFloor;      // Schacht in EG?
        public final Boolean inBasement;         // Schacht in Keller?
        public final Integer count;              // Anzahl Schächte
        
        Utilities(Double hDistWallPipe, Double hDistWallPipe2, Double vDistFloorPipe,
                  Double sizeX, Double sizeY, Double sizeZ,
                  Boolean inUpperFloor, Boolean inGroundFloor, Boolean inBasement, Integer count) {
            this.hDistWallPipe = hDistWallPipe;
            this.hDistWallPipe2 = hDistWallPipe2;
            this.vDistFloorPipe = vDistFloorPipe;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.inUpperFloor = inUpperFloor;
            this.inGroundFloor = inGroundFloor;
            this.inBasement = inBasement;
            this.count = count;
        }
    }
    
    /**
     * GA - Gallery (Balkon/Galerie/Terrasse)
     */
    public static class Gallery {
        public final Double length;              // Balkonlänge
        public final Double width;               // Balkonbreite (Tiefe)
        public final Double height;              // Brüstungshöhe
        public final Double hDistWallGallery;    // Abstand Wand-Balkon
        public final Double hDistGalleryGallery; // Abstand Balkon-Balkon
        public final Double hDistMinWallGallery; // Min. Abstand Wand-Balkon
        public final Double hDistWindowGallery;  // Abstand Fenster-Balkon
        public final Double doorWidth;           // Balkontür-Breite
        public final Double doorHeight;          // Balkontür-Höhe
        public final Double hDistWallDoor;       // Abstand Wand-Balkontür
        public final Double distDoorGallery;     // Abstand Balkontür-Balkon
        public final String pattern;             // Gallery Pattern (z.B. "GaWiWi")
        
        Gallery(Double length, Double width, Double height, Double hDistWallGallery,
                Double hDistGalleryGallery, Double hDistMinWallGallery, Double hDistWindowGallery,
                Double doorWidth, Double doorHeight, Double hDistWallDoor, 
                Double distDoorGallery, String pattern) {
            this.length = length;
            this.width = width;
            this.height = height;
            this.hDistWallGallery = hDistWallGallery;
            this.hDistGalleryGallery = hDistGalleryGallery;
            this.hDistMinWallGallery = hDistMinWallGallery;
            this.hDistWindowGallery = hDistWindowGallery;
            this.doorWidth = doorWidth;
            this.doorHeight = doorHeight;
            this.hDistWallDoor = hDistWallDoor;
            this.distDoorGallery = distDoorGallery;
            this.pattern = pattern;
        }
        
        /**
         * Prüft ob gültige Balkon-Parameter vorhanden sind.
         */
        public boolean isValid() {
            return length != null && length > 0 &&
                   width != null && width > 0;
        }
    }
    
    /**
     * IN - Interior (Innenraum-Aufteilung)
     */
    public static class Interior {
        public final Integer horizontalDivisions; // Horizontale Teilung (Anzahl Räume)
        public final Integer verticalDivisions;   // Vertikale Teilung (Anzahl Räume)
        public final Double minRoomSize;          // Minimale Raumgröße (m²)
        
        Interior(Integer horizontalDivisions, Integer verticalDivisions, Double minRoomSize) {
            this.horizontalDivisions = horizontalDivisions;
            this.verticalDivisions = verticalDivisions;
            this.minRoomSize = minRoomSize;
        }
    }
    
    /**
     * FL - Stairwell (Treppenhaus)
     */
    public static class Stairwell {
        public final Double innerDistStairWall;  // Innerer Abstand Treppe-Wand
        public final Double length;              // Treppenhauslänge
        public final Double width;               // Treppenhausbreite
        
        Stairwell(Double innerDistStairWall, Double length, Double width) {
            this.innerDistStairWall = innerDistStairWall;
            this.length = length;
            this.width = width;
        }
    }
    
    /**
     * BU - Building (Allgemeine Gebäudedaten)
     */
    public static class Building {
        public final boolean hasBasement;        // Hat Keller?
        public final boolean groundFloorToRoof;  // EG bis Dach durchgehend?
        public final Double length;              // Gebäudelänge
        public final Double width;               // Gebäudebreite
        public final Double height;              // Gebäudehöhe
        public final Integer roofShape;          // Dachform (1-4)
        public final boolean hasBasementDoor;    // Hat Kellertür?
        public final List<Integer> entranceDirections; // Eingangsrichtungen
        public final String structureId;         // Struktur-ID (z.B. "ME3")
        
        Building(boolean hasBasement, boolean groundFloorToRoof, Double length, Double width,
                 Double height, Integer roofShape, boolean hasBasementDoor,
                 List<Integer> entranceDirections, String structureId) {
            this.hasBasement = hasBasement;
            this.groundFloorToRoof = groundFloorToRoof;
            this.length = length;
            this.width = width;
            this.height = height;
            this.roofShape = roofShape;
            this.hasBasementDoor = hasBasementDoor;
            this.entranceDirections = entranceDirections;
            this.structureId = structureId;
        }
    }
    
    /**
     * FD - Facade Details (Materialien und Vulnerabilität)
     */
    public static class FacadeDetails {
        public final MaterialInfo wall;
        public final MaterialInfo ceiling;
        public final MaterialInfo window;
        public final MaterialInfo facade;
        public final MaterialInfo groundSurface;
        public final MaterialInfo foundation;
        public final MaterialInfo interiorWall;
        public final MaterialInfo door;
        public final MaterialInfo galleryDoor;
        public final MaterialInfo closureSurface;
        
        FacadeDetails(MaterialInfo wall, MaterialInfo ceiling, MaterialInfo window,
                      MaterialInfo facade, MaterialInfo groundSurface, MaterialInfo foundation,
                      MaterialInfo interiorWall, MaterialInfo door, MaterialInfo galleryDoor,
                      MaterialInfo closureSurface) {
            this.wall = wall;
            this.ceiling = ceiling;
            this.window = window;
            this.facade = facade;
            this.groundSurface = groundSurface;
            this.foundation = foundation;
            this.interiorWall = interiorWall;
            this.door = door;
            this.galleryDoor = galleryDoor;
            this.closureSurface = closureSurface;
        }
    }
    
    /**
     * Material-Information mit Typ, Beschreibung und Vulnerabilität
     */
    public static class MaterialInfo {
        public final String type;
        public final String description;
        public final Double vulnerability;
        
        MaterialInfo(String type, String description, Double vulnerability) {
            this.type = type;
            this.description = description;
            this.vulnerability = vulnerability;
        }
    }
    
    // ==================== Parser-Methoden ====================
    
    private Basement parseBasement(JsonObject ba) {
        if (ba == null) return null;
        return new Basement(
            getDouble(ba, "height"),
            getDouble(ba, "CeHe"),
            parseWindowParams(ba.getAsJsonObject("window")),
            parseDoorParams(ba.getAsJsonObject("door"))
        );
    }
    
    private GroundFloor parseGroundFloor(JsonObject gf) {
        if (gf == null) return null;
        return new GroundFloor(
            getDouble(gf, "height"),
            getDouble(gf, "heightGr"),
            getDouble(gf, "CeHe"),
            parseWindowParams(gf.getAsJsonObject("window")),
            parseDoorParams(gf.getAsJsonObject("door"))
        );
    }
    
    private UpperFloor parseUpperFloor(JsonObject uf) {
        if (uf == null) return null;
        return new UpperFloor(
            getBoolean(uf, "extPolyHeight"),
            getDouble(uf, "height"),
            getDouble(uf, "CeHe"),
            parseWindowParams(uf.getAsJsonObject("window"))
        );
    }
    
    private Roof parseRoof(JsonObject ro) {
        if (ro == null) return null;
        return new Roof(
            parseWindowParams(ro.getAsJsonObject("window")),
            parseRoofShape(ro.getAsJsonObject("shape"))
        );
    }
    
    private RoofShape parseRoofShape(JsonObject shape) {
        if (shape == null) return null;
        return new RoofShape(
            getInt(shape, "Typ"),
            getDouble(shape, "RiHe"),
            getDouble(shape, "HSiTi"),
            getDouble(shape, "VSiTi"),
            getDouble(shape, "HFrTi"),
            getDouble(shape, "VFrTi")
        );
    }
    
    private WindowParams parseWindowParams(JsonObject win) {
        if (win == null) return null;
        return new WindowParams(
            getDouble(win, "HDistWaWi"),
            getDouble(win, "VDistFlWi"),
            getDouble(win, "HDistWiWi"),
            getDouble(win, "HDistDoWi"),
            getDouble(win, "WiLen"),
            getDouble(win, "WiHe"),
            getDouble(win, "HDistMinWaWi"),
            getInt(win, "MaxWiPerRow")
        );
    }
    
    private DoorParams parseDoorParams(JsonObject door) {
        if (door == null) return null;
        return new DoorParams(
            getDouble(door, "DoHe"),
            getDouble(door, "DoLen"),
            getDouble(door, "HDistDoWa")
        );
    }
    
    private Utilities parseUtilities(JsonObject ut) {
        if (ut == null) return null;
        return new Utilities(
            getDouble(ut, "HDistWaPo"),
            getDouble(ut, "HDistWaPo2"),
            getDouble(ut, "VDistFlPo"),
            getDouble(ut, "SiX"),
            getDouble(ut, "SiY"),
            getDouble(ut, "SiZ"),
            getBoolean(ut, "DoUF"),
            getBoolean(ut, "DoGF"),
            getBoolean(ut, "DoBA"),
            getInt(ut, "NrUt")
        );
    }
    
    private Gallery parseGallery(JsonObject ga) {
        if (ga == null) return null;
        return new Gallery(
            getDouble(ga, "GaLen"),
            getDouble(ga, "GaWid"),
            getDouble(ga, "GaHe"),
            getDouble(ga, "HDistWaGa"),
            getDouble(ga, "HDistGaGa"),
            getDouble(ga, "HDistMinWaGa"),
            getDouble(ga, "HDistWiGa"),
            getDouble(ga, "WiLen"),
            getDouble(ga, "WiHe"),
            getDouble(ga, "HDistWaWi"),
            getDouble(ga, "DistWiGa"),
            getString(ga, "GaPa")
        );
    }
    
    private Interior parseInterior(JsonObject in) {
        if (in == null) return null;
        return new Interior(
            getInt(in, "HIn"),
            getInt(in, "VIn"),
            getDouble(in, "MinInSi")
        );
    }
    
    private Stairwell parseStairwell(JsonObject fl) {
        if (fl == null) return null;
        return new Stairwell(
            getDouble(fl, "IDistStWa"),
            getDouble(fl, "StLen"),
            getDouble(fl, "StWid")
        );
    }
    
    private Building parseBuilding(JsonObject bu) {
        if (bu == null) return null;
        
        List<Integer> entranceDirections = new ArrayList<>();
        if (bu.has("EnDi") && bu.get("EnDi").isJsonArray()) {
            bu.getAsJsonArray("EnDi").forEach(e -> {
                if (!e.isJsonNull()) entranceDirections.add(e.getAsInt());
            });
        }
        
        return new Building(
            Boolean.TRUE.equals(getBoolean(bu, "BA")),
            Boolean.TRUE.equals(getBoolean(bu, "GFRo")),
            getDouble(bu, "BuLen"),
            getDouble(bu, "BuWid"),
            getDouble(bu, "BuHe"),
            getInt(bu, "RoSh"),
            Boolean.TRUE.equals(getBoolean(bu, "BaDo")),
            entranceDirections,
            getString(bu, "SID")
        );
    }
    
    private FacadeDetails parseFacadeDetails(JsonObject fd) {
        if (fd == null) return null;
        return new FacadeDetails(
            parseMaterialInfo(fd.getAsJsonObject("WallAttr")),
            parseMaterialInfo(fd.getAsJsonObject("CeilingAttr")),
            parseMaterialInfo(fd.getAsJsonObject("Window")),
            parseMaterialInfo(fd.getAsJsonObject("FacadeAttr")),
            parseMaterialInfo(fd.getAsJsonObject("Groundsurface")),
            parseMaterialInfo(fd.getAsJsonObject("FoundationAttr")),
            parseMaterialInfo(fd.getAsJsonObject("Interiorwallsurface")),
            parseMaterialInfo(fd.getAsJsonObject("Door")),
            parseMaterialInfo(fd.getAsJsonObject("GalleryDoor")),
            parseMaterialInfo(fd.getAsJsonObject("Closuresurface"))
        );
    }
    
    private MaterialInfo parseMaterialInfo(JsonObject mat) {
        if (mat == null) return null;
        return new MaterialInfo(
            getString(mat, "typ"),
            getString(mat, "description"),
            getDouble(mat, "vulnerability")
        );
    }
    
    // ==================== Hilfs-Methoden ====================
    
    private Double getDouble(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el.isJsonNull()) return null;
        try {
            double val = el.getAsDouble();
            return Double.isNaN(val) ? null : val;
        } catch (Exception e) {
            return null;
        }
    }
    
    private Integer getInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el.isJsonNull()) return null;
        try {
            return el.getAsInt();
        } catch (Exception e) {
            return null;
        }
    }
    
    private Boolean getBoolean(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el.isJsonNull()) return null;
        try {
            return el.getAsBoolean();
        } catch (Exception e) {
            return null;
        }
    }
    
    private String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el.isJsonNull()) return null;
        try {
            return el.getAsString();
        } catch (Exception e) {
            return null;
        }
    }
    
    @Override
    public String toString() {
        return String.format("ModuleParameters[%s: hasBasement=%s, GF.height=%s, UF.height=%s]",
            moduleId,
            hasBasement(),
            groundFloor != null ? groundFloor.height : "null",
            upperFloor != null ? upperFloor.height : "null"
        );
    }
}
