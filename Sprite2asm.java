import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Convert all kinds of graphic data to asm

public class Sprite2asm {

    // text output: ACME style uses !byte, KickAssembler expects .byte, DreamAss uses .db
    private static final String PREFIX = "!byte ";

    // color specifiers
    private static final Pattern BGPATTERN = Pattern.compile("-bg([0-9a-fA-F])"); // -bgX bg (forces hires)
    private static final Pattern FGPATTERN = Pattern.compile("-fg([0-9a-fA-F])"); // -fgX fg (forces hires)
    private static final Pattern CMPATTERN = Pattern.compile("-cm([0-9a-fA-F])"); // -cmX enable colormap with default color
    private static final Pattern MCPATTERN = Pattern.compile("-mc([0-9a-fA-F])([0-9a-fA-F])"); // -mcXX mc1 mc2
    // charset specifiers
    private static final Pattern CHPATTERN = Pattern.compile("-ch([0-9a-fA-F][0-9a-fA-F])"); // -chXX create charset
    // other specifiers
    private static final Pattern SYPATTERN = Pattern.compile("-sy([0-9a-fA-F][0-9a-fA-F])"); // -syXX starting sprite y-offset

    public static void main(String[] args) throws Exception {
        StringBuilder arguments = new StringBuilder();
        Sprite2asm instance = new Sprite2asm();
        for (String arg : args) {
            if (arg.startsWith("-")) {
                arguments.append(arg);
            } else {
                instance.processFile(arg, arguments.toString());
                arguments.setLength(0); // reset
            }
        }
    }

    private Raster pixels;
    private int width;
    private int height;
    private int width8;
    private int height8;

    private int pixelWidth = 1;  // defaults to hires (1=hires, 2=mc)
    private int fgIndex = -1;    // disabled, takes prio over bgIndex
    private int bgIndex = 0;     // black, but actually defaults to transparent index
    private int mc1Index = 1;    // white
    private int mc2Index = 2;    // red
    private int defaultIndex = -1;// >= 0 enables charmap in charset mode
    private int uniqueIndex = 0; // unique color detected by colorIndexToBits()
    private int chOffset = -1;   // >= 0 enables charset mode, but default is sprites
    private int syOffset = 0;    // sprite y-offset

    /** remaps retrieved pixel color to bit pattern; also sets uniqueIndex repeatedly to detected color */
    // pixelWidth 1: fgIndex set: fgIndex is '1', any other color is '0' (background)
    //                 otherwise: bgIndex is '0', any other color is '1' (char/sprite color)
    // pixelWidth 2: bgIndex is '00'(0), mc1Index is '01'(1), mc2Index is '11'(3), any other is '10'(2) (sprite color)
    //               bgIndex is '00'(0), mc1Index is '01'(1), mc2Index is '10'(2), any other is '11'(3) (char color)
    private int colorIndexToBits(int colorIndex, int myPixelWidth) {
        int b;
        if (myPixelWidth == 1) {
            // hires
            if (fgIndex >= 0) {
                b = (colorIndex == fgIndex) ? 1 : 0;
            } else {
                b = (colorIndex == bgIndex) ? 0 : 1;
            }
            if (b > 0) uniqueIndex = colorIndex;
        } else {
            if (chOffset >= 0) {
                // mc char
                if (colorIndex == bgIndex) b = 0;
                else if (colorIndex == mc1Index) b = 1;
                else if (colorIndex == mc2Index) b = 2;
                else { b = 3; uniqueIndex = colorIndex; }
            } else {
                // mc sprite
                if (colorIndex == bgIndex) b = 0;
                else if (colorIndex == mc1Index) b = 1;
                else if (colorIndex == mc2Index) b = 3;
                else { b = 2; uniqueIndex = colorIndex; }
            }
        }
        return b;
    }

    private void extractObject(int xoff, int yoff, int w, int h, byte[] buf, int myPixelWidth) {
        int bufoffset = 0;
        int bitcount = 0;
        byte b = 0;
        for (int y = yoff; y < h + yoff; y++) {
            for (int x = xoff; x < w + xoff; x += myPixelWidth) {
                int colorIndex = pixels.getSample(x, y, 0);
                b <<= myPixelWidth;
                b |= colorIndexToBits(colorIndex, myPixelWidth);
                bitcount += myPixelWidth;
                if (bitcount == 8) {
                    buf[bufoffset++] = b;
                    bitcount = 0;
                    b = 0;
                }
            }
        }
    }

    // Heuristic:
    //  a hires char is detected when:
    //   1) there are exactly 2 colors with one being bgIndex, and
    //   2) there is at least one single width pixel
    // Note that a 2 color character with only double width pixels will map to color bits '11' anyway
    //  if the color is not mc1Index or mc2Index, so its binary content is the same for mc and hires!
    private boolean isHiresChar(int xoff, int yoff) {
        int hiresColor = -1; // starts off unknown
        boolean pixelsDiffer = false; // start off assuming all pixels are double width
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x += 2) {
                int pixel1 = pixels.getSample(x + xoff, y + yoff, 0);
                int pixel2 = pixels.getSample(x + xoff + 1, y + yoff, 0);
                if (hiresColor < 0 && pixel1 != bgIndex) {
                    hiresColor = pixel1;
                }
                if (hiresColor < 0 && pixel2 != bgIndex) {
                    hiresColor = pixel2;
                }
                if ((pixel1 != bgIndex && pixel1 != hiresColor) ||
                    (pixel2 != bgIndex && pixel2 != hiresColor)) {
                    return false; // 3rd color detected
                }
                if (pixel1 != pixel2) {
                    pixelsDiffer = true;
                }
            }
        }
        return pixelsDiffer;
    }

    // extract tile from charmap
    void extractTile(int cx, int cy, int tileW, int tileH, int[] buf) {
        int i = 0;
        for (int y = cy; y < cy + tileH; y++) {
            for (int x = cx; x < cx + tileW; x++) {
                buf[i++] = charmap[y * width8 + x];
            }
        }
    }

    // extract formatting instructions from string
    private void updateSettings(String str) {
        Matcher bg = BGPATTERN.matcher(str);
        if (bg.find()) { // "-bgX" sets bg index and forces hires
            bgIndex = Integer.parseInt(bg.group(1), 16);
            pixelWidth = 1;
        }
        Matcher fg = FGPATTERN.matcher(str);
        fgIndex = -1;
        if (fg.find()) { // "-fgX" sets fg index and forces hires
            fgIndex = Integer.parseInt(fg.group(1), 16);
            pixelWidth = 1;
        }
        Matcher mc = MCPATTERN.matcher(str);
        if (mc.find()) { // -mcXX sets mc1 and mc2 indices and forces mc
            mc1Index = Integer.parseInt(mc.group(1),16);
            mc2Index = Integer.parseInt(mc.group(2),16);
            pixelWidth = 2;
        }
        Matcher ch = CHPATTERN.matcher(str);
        chOffset = -1;
        if (ch.find()) { // -chXX sets charmap mode
            chOffset = Integer.parseInt(ch.group(1),16);
        }
        Matcher sy = SYPATTERN.matcher(str);
        syOffset = 0;
        if (sy.find()) { // -syXX starting sprite y-offset
            syOffset = Integer.parseInt(sy.group(1),16);
        }
        Matcher cm = CMPATTERN.matcher(str);
        defaultIndex = -1;
        if (cm.find()) { // -cmX enable colormap with default color
            defaultIndex = Integer.parseInt(cm.group(1),16);
        }
    }

    void load(String srcfilename, String extraArguments) throws IOException {
        File f = new File(srcfilename);
        BufferedImage image = ImageIO.read(f);
        if (!(image.getColorModel() instanceof IndexColorModel)) {
            throw new IOException("image should have palette");
        }
        // pick bg from transparent color index
        bgIndex = ((IndexColorModel) image.getColorModel()).getTransparentPixel();
        updateSettings(srcfilename + extraArguments);
        pixels = image.getData();
        width = pixels.getWidth();
        height = pixels.getHeight();
        width8 = width/8;
        height8 = height/8;
    }

    private void processFile(String srcfilename, String extraArguments) throws IOException {
        load(srcfilename, extraArguments);
        String header = String.format("; Sprite2asm %s'%s' on %s\n",
                extraArguments.isEmpty() ? "" : extraArguments + " ",
                new File(srcfilename).getName(),
                new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH).format(new Date()));
        if (chOffset >= 0) {
            convertChars(header);
        } else {
            convertSprites(header);
        }
    }

    byte[] charset;
    private int charsetSize;
    private int[] charmap;
    private byte[] colormap;

    void buildCharmap() {
        if (chOffset == -1) chOffset = 0; // force charmap feature (for when you call this externally)
        charset = new byte[width8 * height8 * 8]; // enough to always convert the entire image
        charsetSize = 0;
        charmap = new int[width8 * height8];
        colormap = new byte[width8 * height8];
        int j = 0;
        byte[] curChar = new byte[8];
        int emptyChar = -1; // not found
        for (int cy = 0; cy + 8 <= height; cy += 8) {
            for (int cx = 0; cx + 8 <= width; cx += 8) {
                int detectedPixelWidth = (pixelWidth > 1 && isHiresChar(cx, cy)) ? 1 : pixelWidth;
                uniqueIndex = defaultIndex;
                extractObject(cx, cy, 8, 8, curChar, detectedPixelWidth);
                int ch = findInSet(curChar, charset, charsetSize);
                if (ch == charsetSize) { // not found
                    System.arraycopy(curChar, 0, charset, charsetSize * 8, 8);
                    if (emptyChar < 0 && !containsAnyBits(curChar)) {
                        emptyChar = charsetSize;
                    }
                    charsetSize++;
                }
                // correct character color for mc
                if (pixelWidth > 1) {
                    uniqueIndex = (uniqueIndex & 0x07);
                    if (detectedPixelWidth > 1) {
                        uniqueIndex |= 0x08; // bit 3 of character color determines mc or hires
                    }
                }
                colormap[j] = (byte) uniqueIndex;
                charmap[j++] = ch + chOffset;
            }
        }
        // if there's an empty character, move that to front
        if (emptyChar > 0) {
            System.arraycopy(charset, 0, charset, emptyChar * 8, 8);
            Arrays.fill(charset, 0, 8, (byte) 0);
            for (int i = 0; i < width8 * height8; i++) {
                if (charmap[i] == chOffset) {
                    charmap[i] = emptyChar + chOffset;
                } else if (charmap[i] == emptyChar + chOffset) {
                    charmap[i] = chOffset;
                }
            }
        }
    }

    // convert characters with charset and charmap and tilemap
    private void convertChars(String header) {
        buildCharmap();
        byte[] charmapBytes = new byte[width8 * height8];
        for (int i = 0; i < width8 * height8; i++) {
            charmapBytes[i] = (byte) charmap[i];
        }
        StringBuilder sb = new StringBuilder(header);
        sb.append(String.format("; charset %d bytes (%d uniques)\n", charsetSize * 8, charsetSize));
        appendByteRows(sb, charset, charsetSize * 8, 8);
        sb.append(header);
        sb.append(String.format("; charmap %d bytes (%d x %d)\n", width8 * height8, width8, height8));
        appendByteRows(sb, charmapBytes, width8 * height8, width8);
        if (defaultIndex >= 0) {
            sb.append(header);
            sb.append(String.format("; colormap %d bytes (%d x %d)\n", width8 * height8, width8, height8));
            appendByteRows(sb, colormap, width8 * height8, width8);
        }
        System.out.print(sb);
        if (charsetSize + chOffset > 256) {
            System.err.format("WARNING: charmap overflows with %d characters; use offset -ch%02X instead\n",
                    charsetSize + chOffset - 256, 256 - charsetSize);
        }
    }

    private void convertSprites(String header) {
        int nr = 0;
        byte[] sprite = new byte[64];
        for (int sy = syOffset; sy + 21 <= height; sy += 21) {
            for (int sx = 0; sx + 24 <= width; sx += 24) {
                extractObject(sx, sy, 24, 21, sprite, pixelWidth);
                if (containsAnyBits(sprite)) {
                    StringBuilder sb = new StringBuilder(nr == 0 ? header : "");
                    sb.append(String.format("; %d (%d,%d)\n", nr, sx, sy));
                    appendByteRows(sb, sprite, 64, 24);
                    System.out.print(sb);
                    nr++;
                }
            }
        }
    }

    private boolean containsAnyBits(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return true;
            }
        }
        return false;
    }

    static void appendByteRows(StringBuilder sb, byte[] input, int len, int wrap) {
        int i = 0;
        while (i < len) {
            sb.append(i % wrap == 0 ? PREFIX : ",");
            sb.append(String.format("$%1$02x", input[i++]));
            if (i % wrap == 0) {
                sb.append("\n");
            }
        }
        if (i % wrap != 0) {
            sb.append("\n");
        }
    }

    /** returns index of 'tile' in the first 'tileCount' tiles in 'tileset' or 'tileCount' if not found */
    static int findInSet(byte[] tile, byte[] tileset, int tileCount) {
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
