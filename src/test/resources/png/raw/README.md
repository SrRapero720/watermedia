# PNG reference provenance

`broken.pam` and `static.pam` are the first image decoded from `../broken.png` and `../static.png` by the JDK's `javax.imageio.ImageIO` PNG reader, stored as 8-bit RGBA PAM. `broken.png` decodes successfully despite its filename. WaterMedia classes were not used to produce the references.

`broken.pam` was generated on September 7, 2026 with Microsoft OpenJDK 25.0.4.1+1-LTS (1439 × 1440); `static.pam` on October 19, 2026 with Microsoft OpenJDK 21.0.10+7-LTS (64 × 40). From the project root, reproduce either with:

```powershell
java src/test/tools/GeneratePngReference.java src/test/resources/png/broken.png src/test/resources/png/raw/broken.pam
java src/test/tools/GeneratePngReference.java src/test/resources/png/static.png src/test/resources/png/raw/static.pam
```

| File | SHA-256 |
| --- | --- |
| `broken.png` | `1c10305592e15d1df2061f1bd6d86fd9359ef30fc49964cd852ef4850a45344c` |
| `broken.pam` | `1a99940314a519e3a6412555bf711ff8f208a5e46429328e9c8f6b2abd652852` |
| `static.png` | `cff595e316d13e8b93813aabcc12f8bd9bcbdb031885c191a17265311d233954` |
| `static.pam` | `401fbf5eb65cd0e53a19f02f8c3d68cf5bb5422c98998896f05556e00bc74ee9` |

The other PAM files predate this work; their original generator and version were not recorded.
