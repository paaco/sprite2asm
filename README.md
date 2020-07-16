# sprite2asm

### Assets

Assets are drawn in Aseprite and saved as .png file. Custom converter Sprite2asm converts the png file based on instructions in the filename:

    -bgX specifies background color (defaults to transparent color index) and forces hires
    -mcXY specifies multi color 1 (X) and 2 (Y) and forces multi color
    -chXX specifies to create charset and charmap (bytes start at XX) instead of sprites

Note that multicolor bits are interpreted differently for sprites and characters:

        bits    00        01         10         11
    sprites     BG:$D021  MC1:$D025  $D027+     MC2:$D026
    chars       BG:$D021  MC1:$D022  MC2:$D023  $D800+

Sprites will always cover character/bitmap bits 00 and 01 (bit 0 for hires). However, sprites with 'lower' priority (their bit is set to 1 in $D01B) will be covered by bits 10 and 11 (bit 1 for hires).

### TODO
 * specify -dh for double height sprites (height 42) what pixel to take? upper or lower?
 * specify -dw for double width sprites (width 48) what pixel to take? left or right?
