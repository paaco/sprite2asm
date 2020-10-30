# Sprite2asm

Sprite2asm converts PNG file assets, specifically sprite sheets and character screens to C64 assembly code.

### USAGE

`java Sprite2asm [option] [file.png] ..`

The default is to convert hires sprites with background color set to the transparent color of the asset.

### OPTIONS

The following options can be given on the command line or put in the file name (where `X` and `Y` are hex digits):

 * `-bgX`:
 selects hires conversion with color `X` mapped to bit pattern `0` (background).

 * `-fgX`:
 selects hires conversion with color `X` mapped to bit pattern `1` (foreground).
 This takes priority over `-bg`.

 * `-mcXY`:
 selects multi color conversion with `X` mapped to bit pattern `01` (MC1) and `Y` mapped to bit pattern `11` for sprites or `10` for charsets (MC2).
 The background color is mapped to bit pattern `00`. Other colors map to the remaining bit pattern (`10` for sprites, `11` for charsets).

 * `-syXX`:
 y-offset applied when converting sprites (defaults to 0).

 * `-chXX`:
 convert to charset and charmap instead of sprites (bytes start at `XX`, usually `00`).

Options on the command line take priority over those in the file name.

Example: `java Sprite2asm -mcXY file1-ch07.png file2.png` will interpret asset `file1-ch07.png` as multi color and convert it to charset and charmap, where bytes in the charmap start at `7`. Asset `file2.png` will be converted to multi color sprites, as sprites are the default.

### BIT FORMATS

Multi color bits are interpreted differently for sprites, characters and bitmaps:

         mc bits  00        01         10         11
    sprites       BG:$D021  MC1:$D025  $D027+     MC2:$D026
    chars         BG:$D021  MC1:$D022  MC2:$D023  $D800+
    bitmap        BG:$D021  SCR:HN     SCR:LN     $D800+

      hires bits  0         1
    sprites       BG:$D021  $D027+
    chars         BG:$D021  $D800+
    bitmap        SCR:LN    SCR:HN

Sprites will always appear on top of bit patterns `0`, `00` or `01` of the underlying characters or bitmap. However, sprites with 'lower' priority - their bit set to `1` in `$D01B` - will appear behind bit patterns `1`, `10` or `11`.

Thus sprites are always displayed on top of MC1, but can be behind MC2.

### TODO
 * specify -dh for double height sprites (height 42) what pixel to take? upper or lower?
 * specify -dw for double width sprites (width 48) what pixel to take? left or right?
