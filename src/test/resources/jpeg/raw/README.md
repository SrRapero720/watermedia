# JPEG reference provenance

`7.jpg` is a synthetic 127 × 95 picture (gradients, a soft-edged disc, fine stripes and a dark diagonal line) written as a baseline (SOF0) 4:2:0 JPEG at quality 0.9 by the JDK's `javax.imageio.ImageIO` JPEG writer. `7.pam` is what the JDK's JPEG reader decodes from those bytes, stored as 8-bit RGB PAM. Neither side is a multiple of the 16 × 16 MCU, so the last MCU row and column are partial. WaterMedia classes were not used to produce either file.

Both were generated on November 18, 2026 with Microsoft OpenJDK 21.0.10+7-LTS. From the project root, reproduce them with:

```powershell
java src/test/tools/GenerateJpegReference.java src/test/resources/jpeg/7.jpg src/test/resources/jpeg/raw/7.pam
```

| File | SHA-256 |
| --- | --- |
| `7.jpg` | `c208d38fd565fac35f75684616ea90b8eacff7f5d6771ac4ad13cf1d03a36623` |
| `7.pam` | `7f20cfef17914646a9fa44d94c1bb32a4a8a8d65a601b58e4d4752558155ef96` |

`1.jpg` to `6.jpg` and their references predate this work; their original generator and version were not recorded. All six are progressive (SOF2), and `5.jpg` and `5.pam` are byte copies of `4.jpg` and `4.pam`.
