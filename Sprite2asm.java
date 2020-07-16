import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Convert spritesheet to asm

public class Sprite2asm {

    // text output: ACME style uses !byte, KickAssembler expects .byte, DreamAss uses .db
    private static final String PREFIX = "!byte ";

    // color specifiers
    private static final Pattern BGPATTERN = Pattern.compile("-bg([0-9a-fA-F])"); // -bgX bg (forces hires)
    private static final Pattern MCPATTERN = Pattern.compile("-mc([0-9a-fA-F])([0-9a-fA-F])"); // -mcXX mc1 mc2
    // charset specifiers
    private static final Pattern CHPATTERN = Pattern.compile("-ch([0-9a-fA-F][0-9a-fA-F])"); // -chXX create charset

    private Sprite2asm(String fname) {
        srcfilename = fname;
    }

    private final String srcfilename;

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            new Sprite2asm(arg).run();
        }
    }

    private int pixel_width = 1; // default to hires
    private int bgIndex = 0;     // black, but actually defaults to transparent index
    private int mc1Index = 1;    // white
    private int mc2Index = 2;    // red
    private int chOffset = -1;   // >= 0 enables charset mode, but default is sprites

    // pixelWidth 1: bgIndex is '0', any other color is '1' (sprite color)
    // pixelWidth 2: bgIndex is '00'(0), mc1Index is '01'(1), mc2Index is '11'(3), any other is '10'(2) (sprite color)
    private void extractSprite(Raster pixels, int xoff, int yoff, byte[] buf) {
        int bufoffset = 0;
        int bitcount = 0;
        byte b = 0;
        for (int y = 0; y < 21; y++) {
            for (int x = 0; x < 24; x += pixel_width) {
                int pixel = pixels.getSample(x + xoff, y + yoff, 0);
                b <<= pixel_width;

                if (pixel_width == 1) {
                    // hires
                    if (pixel == bgIndex) b |= 0;
                    else b |= 1;
                } else {
                    // mc
                    if (pixel == bgIndex) b |= 0;
                    else if (pixel == mc1Index) b |= 1;
                    else if (pixel == mc2Index) b |= 3;
                    else b |= 2;
                }

                // flush
                bitcount += pixel_width;
                if (bitcount == 8) {
                    buf[bufoffset++] = b;
                    bitcount = 0;
                    b = 0;
                }
            }
        }
    }

    // pixelWidth 1: bgIndex is '0', any other color is '1' (hires color)
    // pixelWidth 2: bgIndex is '00'(0), mc1Index is '01'(1), mc2Index is '11'(3), any other is '10'(2) (sprite color)
    private void extractChar(Raster pixels, int xoff, int yoff, byte[] buf) {
        int bufoffset = 0;
        int bitcount = 0;
        byte b = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x += pixel_width) {
                int pixel = pixels.getSample(x + xoff, y + yoff, 0);
                b <<= pixel_width;

                if (pixel_width == 1) {
                    // hires
                    if (pixel == bgIndex) b |= 0;
                    else b |= 1;
                } else {
                    // mc char
                    if (pixel == bgIndex) b |= 0;
                    else if (pixel == mc1Index) b |= 1;
                    else if (pixel == mc2Index) b |= 2;
                    else b |= 3;
                }

                // flush
                bitcount += pixel_width;
                if (bitcount == 8) {
                    buf[bufoffset++] = b;
                    bitcount = 0;
                    b = 0;
                }
            }
        }
    }

    // extract color format from string
    private void updateColorMapping(String str) {
        Matcher bg = BGPATTERN.matcher(str);
        Matcher mc = MCPATTERN.matcher(str);
        Matcher ch = CHPATTERN.matcher(str);
        if (bg.find()) { // "-bgX" sets bg index and forces hires
            bgIndex = Integer.parseInt(bg.group(1),16);
            pixel_width = 1;
        }
        if (mc.find()) { // -mcXX sets mc1 and mc2 indices and forces mc
            mc1Index = Integer.parseInt(mc.group(1),16);
            mc2Index = Integer.parseInt(mc.group(2),16);
            pixel_width = 2;
        }
        chOffset = -1;
        if (ch.find()) { // -chXX set charmap mode
            chOffset = Integer.parseInt(ch.group(1),16);
        }
    }

    private void run() throws IOException {
        File f = new File(srcfilename);
        BufferedImage image = ImageIO.read(f);
        int height = image.getHeight();
        int width = image.getWidth();
        if (!(image.getColorModel() instanceof IndexColorModel)) {
            System.err.format("ERROR: image should have palette\n");
        } else {
            // pick bg from transparent color index
            bgIndex = ((IndexColorModel)image.getColorModel()).getTransparentPixel();
            updateColorMapping(srcfilename);

            System.out.format("; Sprite2asm %s %s\n", f.getName(), DateFormat.getDateTimeInstance().format(new Date()));
            Raster pixels = image.getData();

            if (chOffset >= 0) {
                // convert characters with charset and charmap
                // TODO: colormap from extractChar?
                byte[] charset = new byte[8 * 256];
                int charsetSize = 0;
                byte[] charmap = new byte[1000]; // max full screen
                int charmapSize = 0;
                byte[] curChar = new byte[8];
                int emptyChar = -1; // not found
                for (int cy = 0; cy + 8 <= height; cy += 8) {
                    for (int cx = 0; cx + 8 <= width; cx += 8) {
                        extractChar(pixels, cx, cy, curChar);
                        int ch = findInCharset(curChar, charset, charsetSize);
                        if (ch == charsetSize) { // not found
                            if (charsetSize * 8 < charset.length) {
                                System.arraycopy(curChar, 0, charset, charsetSize * 8, 8);
                                if (emptyChar < 0 && !containsBits(curChar)) {
                                    emptyChar = charsetSize;
                                }
                                charsetSize++;
                            } else {
                                System.err.format("ERROR: aborting, more than %d uniques is not supported\n",
                                        charset.length / 8);
                                cy = height; // end outer loop
                                break;
                            }
                        }
                        charmap[charmapSize++] = (byte)(ch + chOffset);
                    }
                }
                // if there's an empty character, move that to front
                if (emptyChar > 0) {
                    System.arraycopy(charset, 0, charset, emptyChar * 8, 8);
                    Arrays.fill(charset, 0, 8, (byte) 0);
                    for (int i = 0; i < charmapSize; i++) {
                        if (charmap[i] == chOffset) {
                            charmap[i] = (byte)(emptyChar + chOffset);
                        } else if (charmap[i] == (byte)(emptyChar + chOffset)) {
                            charmap[i] = (byte)chOffset;
                        }
                    }
                }
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("; charset %d uniques\n", charsetSize));
                appendByteRows(sb, charset, charsetSize * 8, 8);
                sb.append(String.format("; charmap %d bytes (%d x %d)\n", charmapSize, width / 8, height / 8));
                appendByteRows(sb, charmap, charmapSize, width / 8);
                System.out.print(sb);
                if (charsetSize + chOffset > 256) {
                    System.err.format("WARNING: charmap overflows with %d characters; use offset -ch%02X instead\n",
                        charsetSize + chOffset - 256, 256 - charsetSize);
                }
            } else {
                // convert sprites
                int nr = 0;
                byte[] sprite = new byte[64];
                for (int sy = 0; sy + 21 <= height; sy += 21) {
                    for (int sx = 0; sx + 24 <= width; sx += 24) {
                        extractSprite(pixels, sx, sy, sprite);
                        if (containsBits(sprite)) {
                            StringBuilder sb = new StringBuilder();
                            sb.append(String.format("; %d (%d,%d)\n", nr, sx, sy));
                            appendByteRows(sb, sprite, 64, 21);
                            System.out.print(sb);
                            nr++;
                        }
                    }
                }
            }
        }
    }

    private boolean containsBits(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return true;
            }
        }
        return false;
    }

    private void appendByteRows(StringBuilder sb, byte[] input, int len, int wrap) {
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

    /** lookup char in charset, returns 'count' if not found */
    private int findInCharset(byte[] curChar, byte[] charset, int count) {
        int i = 0;
        while (i < count) {
            int j = 0;
            while (j < 8 && curChar[j] == charset[i * 8 + j])
                j++;
            if (j == 8)
                return i;
            i++;
        }
        return i;
    }

}
