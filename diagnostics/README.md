# Diagnostic clips

The `sdr_resolution` pattern in widely supported formats, for players that cannot open the lossless 10-bit
pattern files (for example the TV's own player): 4K, 23.976 fps, 5 s, BT.709 limited range.

- `resolution_h264.mp4`: H.264 High, 8-bit 4:2:0, QP 1
- `resolution_hevc8.mp4`: HEVC Main, 8-bit 4:2:0, QP 2

Both were decoded and checked: the 1 px checkerboard still alternates between black and white on every pixel.
Made with `patterns/sdr.py` (`resolution()`), not by CI.
