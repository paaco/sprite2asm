import com.cedarsoftware.util.io.JsonObject;
import com.cedarsoftware.util.io.JsonReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// compile with: javac -cp ".;json-io-4.12.0.jar" Ldtk2asm.java

public class Ldtk2asm {

    private static final Pattern CHPATTERN = Pattern.compile("-ch([0-9a-fA-F][0-9a-fA-F])([0-9a-fA-F][0-9a-fA-F])?"); // -chXX[YY] offset charset and put #0 at YY

    private int chOffset = 0;  // offset to start char indexing in tiles (default 0)
    private int chEmpty = -1;  // index to put char #0 (default -1 put at chOffset)

    public static void main(String[] args) throws Exception {
        StringBuilder arguments = new StringBuilder();
        Ldtk2asm instance = new Ldtk2asm();
        for (String arg : args) {
            if (arg.startsWith("-")) {
                arguments.append(arg);
            } else {
                instance.run(arg, arguments.toString());
            }
        }
    }

    // extract formatting instructions from string
    private void updateSettings(String str) {
        Matcher ch = CHPATTERN.matcher(str);
        if (ch.find()) { // -chXX[YY] offset charset and put #0 at YY
            chOffset = Integer.parseInt(ch.group(1),16);
            if (ch.group(2) != null) {
                chEmpty =  Integer.parseInt(ch.group(2),16);
                chOffset--; // char #0 is placed somewhere else
            }
        }
    }

    private void run(String filename, String arguments) throws IOException {
        updateSettings(arguments);
        //noinspection IOStreamConstructor
        Object json = JsonReader.jsonToJava(new FileInputStream(filename), null);
        JsonObject<String,Object> head = map(json, "__header__");
        String fileType = string(head, "fileType");
        if (!fileType.equals("LDtk Project JSON")) {
            throw new IOException("Not a LDtk Project JSON");
        }

        Sprite2asm graphics = new Sprite2asm();
        graphics.setHeader("Ldtk2asm", "", filename);

        Object[] levels = array(json, "levels");
        for (Object level : levels) {
            String levelIdentifier = string(level, "identifier");
            Object[] layerInstances = array(level, "layerInstances");
            for (Object layerInstance : layerInstances) {
                String type = string(layerInstance, "__type");
                String layerIdentifier = string(layerInstance, "__identifier");

                if (type.equals("Tiles")) {
                    int width = integer(layerInstance, "__cWid");
                    int height = integer(layerInstance, "__cHei");
                    Object[] gridTiles = array(layerInstance, "gridTiles");
                    String tilesetPath = string(layerInstance, "__tilesetRelPath");
                    // `tilesetPath` can be relative to `filename`
                    if (!tilesetPath.contains(":") && !tilesetPath.startsWith("/")) {
                        String folder = new File(filename).getParent();
                        tilesetPath = new File(folder, tilesetPath).getCanonicalPath();
                    }
                    // set -ch00 to force building charmap from 0
                    graphics.load(tilesetPath, "-ch00");
                    graphics.buildCharmap();
                    int tileWidth = integer(layerInstance, "__gridSize") / 8; // tile size in #pixels (square)
                    int tileSize = tileWidth * tileWidth * 2; // first the chars, followed by a color byte per character
                    byte[] tileMap = new byte[width*height];
                    int[] tileSet = new int[tileSize * 256];
                    int tileSetCount = 0;
                    int[] tile = new int[tileSize];
                    if (gridTiles.length < tileMap.length) {
                        // gridTiles can be a "sparse" array if not all tiles have been set so gridTiles.length can be < tileMap.length!
                        throw new IOException("Some cells on the level are still unset (-), unsupported!");
                    }
                    // extract the graphics of the used tiles from the gridTiles map
                    for (int i = 0; i < tileMap.length; i++) {
                        // int tilenr = integer(gridTiles[i], "t"); // uncompressed tile#
                        Object[] src = array(gridTiles[i], "src"); // top-left coordinate
                        int ctx = ((Long)src[0]).intValue() / 8;
                        int cty = ((Long)src[1]).intValue() / 8;
                        graphics.extractTile(ctx, cty, tileWidth, tileWidth, tile);
                        int tilenr = findTile(tile, tileSet, tileSetCount);
                        if (tilenr == tileSetCount) {
                            System.arraycopy(tile, 0, tileSet, tileSetCount * tile.length, tile.length);
                            tilenr = tileSetCount++;
                        }
                        tileMap[i] = (byte)tilenr;
                    }
                    // create optimized charset with only the chars used by the tiles in the map
                    Map<Integer, Byte> usedChars = new HashMap<>();
                    byte[] optimizedCharset = new byte[graphics.charset.length];
                    int optimizedCharsetCount = 0;
                    // always copy character 0
                    usedChars.put(0, (byte) 0);
                    System.arraycopy(graphics.charset, 0, optimizedCharset, 0, 8);
                    optimizedCharsetCount++;
                    for (int charnr : tileSet) {
                        if (usedChars.containsKey(charnr)) {
                            continue;
                        }
                        usedChars.put(charnr, (byte)optimizedCharsetCount);
                        System.arraycopy(graphics.charset, charnr * 8, optimizedCharset, optimizedCharsetCount * 8, 8);
                        optimizedCharsetCount++;
                    }
                    StringBuilder sb = graphics.getOutputSB();
                    sb.append(String.format("; level: '%s', layer '%s', tileset '%s'%n", levelIdentifier, layerIdentifier, new File(tilesetPath).getName()));
                    sb.append(String.format("; tilemap %d bytes (%d x %d)%n", width * height, width, height));
                    Sprite2asm.appendByteRows(sb, tileMap, width * height, width);
                    graphics.flushOutputSB(sb, "tilemap");
                    sb.append(String.format("; tiles %d bytes %dx%d SoA %d x %d (%d uniques)%n",
                            tileSetCount * tileSize/2, tileWidth, tileWidth, tileSetCount, tileSize/2, tileSetCount));
                    byte[] tileRow = new byte[tileSetCount];
                    for (int c = 0; c < tileSize/2; c++) {
                        for (int i = 0; i < tileSetCount; i++) {
                            byte cindex = usedChars.get(tileSet[i * tileSize + c]);
                            // now shift offset or move char#0 if required
                            tileRow[i] = (chEmpty == -1 || cindex != 0) ? (byte)(cindex + chOffset) : (byte)chEmpty;
                        }
                        Sprite2asm.appendByteRows(sb, tileRow, tileSetCount, tileSetCount);
                    }
                    graphics.flushOutputSB(sb, "tiles");
                    sb.append(String.format("; colortiles %d bytes %dx%d SoA %d x %d (%d uniques)%n",
                            tileSetCount * tileSize/2, tileWidth, tileWidth, tileSetCount, tileSize/2, tileSetCount));
                    for (int c = tileSize/2; c < tileSize; c++) {
                        for (int i = 0; i < tileSetCount; i++) {
                            tileRow[i] = (byte)tileSet[i * tileSize + c];
                        }
                        Sprite2asm.appendByteRows(sb, tileRow, tileSetCount, tileSetCount);
                    }
                    graphics.flushOutputSB(sb, "colortiles");
                    sb.append(String.format("; charset %d bytes (%d uniques)%n", optimizedCharsetCount * 8, optimizedCharsetCount));
                    if (chEmpty != -1) {
                        sb.append(String.format("; NOTE chars start at index %d (byteoffset %d)%n", (chOffset+1), (chOffset+1) * 8));
                        sb.append(String.format("; NOTE char#0 needs to be put at place %d (offset %d)!%n", chEmpty, chEmpty * 8));
                    } else if (chOffset != 0) {
                        sb.append(String.format("; NOTE chars start at index %d (byteoffset %d)%n", chOffset, chOffset * 8));
                    }
                    Sprite2asm.appendByteRows(sb, optimizedCharset, optimizedCharsetCount * 8, 8);
                    graphics.flushOutputSB(sb, "charset");

                } else if (type.equals("Entities")) {
                    int gridSize = integer(layerInstance, "__gridSize"); // grid size in #pixels (square)
                    Object[] entityInstances = array(layerInstance, "entityInstances");
                    // "column based" entities
                    Map<Integer, String> entities = new HashMap<>();
                    for (Object entityInstance : entityInstances) {
                        Object[] px = array(entityInstance, "px"); // top-left coordinate
                        int pxx = ((Long)px[0]).intValue() / gridSize;
                        int width = integer(entityInstance, "width") / gridSize;
                        String identifier = string(entityInstance, "__identifier");
                        Object[] fieldInstances = array(entityInstance, "fieldInstances"); // list of entity properties
                        @SuppressWarnings("unchecked")
                        String value = fieldInstances.length > 0 ? ((JsonObject<String, Object>)fieldInstances[0]).get("__value").toString() : "0";
                        // TODO data as (byte) list so that it can be SoA instead
                        String data = String.format("!byte %d,%d,%s,%s%n", pxx, width, identifier, value);
                        entities.put(pxx, entities.getOrDefault(pxx,"") + data); // append
                    }
                    StringBuilder sb = graphics.getOutputSB();
                    sb.append(String.format("; level: '%s', layer '%s'%n; xtile,width,entity,value%n", levelIdentifier, layerIdentifier));
                    entities.keySet().stream().sorted().forEach(key -> sb.append(entities.get(key)));
                    graphics.flushOutputSB(sb, "entities");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String string(Object obj, String fieldname) {
        return ((JsonObject<String,String>) obj).get(fieldname);
    }

    @SuppressWarnings({"SameParameterValue","unchecked"})
    private JsonObject<String,Object> map(Object obj, String fieldname) {
        return ((JsonObject<String,JsonObject<String,Object>>) obj).get(fieldname);
    }

    @SuppressWarnings("unchecked")
    private Object[] array(Object obj, String fieldname) {
        return ((JsonObject<String,Object[]>) obj).get(fieldname);
    }

    @SuppressWarnings("unchecked")
    private int integer(Object obj, String fieldname) {
        return ((JsonObject<String,Long>) obj).get(fieldname).intValue();
    }

    /** returns index of 'tile' in the first 'tileCount' tiles in 'tileset' or 'tileCount' if not found */
    static int findTile(int[] tile, int[] tileset, int tileCount) {
        int i = 0;
        int size = tile.length;
        while (i < tileCount) {
            int j = 0;
            while (j < size && tile[j] == tileset[i * size + j])
                j++;
            if (j == size)
                return i;
            i++;
        }
        return i;
    }

}
