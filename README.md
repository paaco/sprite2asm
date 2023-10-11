# Sprite2asm

Sprite2asm converts 16 color indexed PNG file assets, specifically sprite sheets, but also (tiled) character screens to C64 assembly code.

### USAGE

`java Sprite2asm [options] file.png [[options] file2.png] ..`

The default is to convert hires sprites with background color set to the transparent color of the asset.

The following options can be put in the file name or given on the command line (with `X` and `Y` being hex digits):

### INPUT OPTIONS

 * `-bgX`:
 selects hires conversion with color `X` mapped to bit pattern `0` (background).

 * `-fgX`:
 selects hires conversion with color `X` mapped to bit pattern `1` (foreground).
 This takes priority over `-bg`.

 * `-mcXY`:
 selects multicolor conversion with color `X` mapped to bit pattern `01` (MC1) and color `Y` mapped to bit pattern `11` for sprites or `10` for charsets (MC2).
 The background color (set via `-bg`) is mapped to bit pattern `00`. Other colors map to the remaining bit pattern `10` for sprites or `11` for charsets.

 * `-syXX`:
 y-offset applied when converting sprites (defaults to 0). Useful when extracting sprite overlays.

### OUTPUT OPTIONS

 * `-chXX`:
 converts to charset and charmap instead of sprites (bytes start at `XX`, usually `00`).
 With multicolor conversion, characters consisting of 2 colors (where one is background) that contain single pixels are still converted as hires.

   * `-cmX`:
   together with `-ch`, also generate colormap with `X` as color when a character is empty or only contains multi colors. 

Options on the command line take priority over those in the file name.

Example: `java Sprite2asm -mcXY file1-ch07.png file2.png` will interpret asset `file1-ch07.png` as multicolor and convert it to charset and charmap, where bytes in the charmap start at `7`.
Asset `file2.png` will be converted to multicolor sprites, as sprites are the default.

### BIT FORMATS

Multicolor bits are interpreted differently for sprites, characters and bitmaps:

         mc bits  00        01         10         11
    sprites       BG:$D021  MC1:$D025  $D027+     MC2:$D026
    chars         BG:$D021  MC1:$D022  MC2:$D023  $D800:LN
    bitmap        BG:$D021  SCR:HN     SCR:LN     $D800:LN

      hires bits  0         1
    sprites       BG:$D021  $D027+
    chars         BG:$D021  $D800:LN
    bitmap        SCR:LN    SCR:HN

Sprites will always appear on top of bit patterns `0`, `00` or `01` of the underlying characters or bitmap.
However, sprites with 'lower' priority - their bit set to `1` in `$D01B` - will appear behind bit patterns `1`, `10` or `11`.
Thus, sprites are always displayed on top of BG and MC1, but can be behind MC2 and $D800+.

### TODO
 * specify -dh for double height sprites (height 42) y-step 2 instead of 1
 * specify -dw for double width sprites (width 48) x-step 2 instead of 1 (MC sample at x+2 instead x+1)
 * specify -hw for half width multicolor sprites (width 12)

# Ldtk2asm

Ldtk2asm converts [Ldtk](https://ldtk.io/) map files using 16 color indexed PNG tile sheets to charset, tiles and tilemap(s) in C64 assembly code.

### USAGE

`java Ldtk2asm [options] file.ldtk [[options] file2.ldtk] ..`

The following options can be given on the command line (with X and Y being hex digits):

### OPTIONS

* `-chXX[YY]`:
  start char indexes in tiles at `XX` (default 0), putting character #0 at index `YY` (default `XX`).

### TODO
 * combine tiles of multiple levels that use the same tileset
 * handle Entities
