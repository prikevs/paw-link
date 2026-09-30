# Animation asset provenance

The seven PNG files in `assets/` are existing PawLink project animation outputs generated using OpenAI ImageGen from the project owner's cat references and animation prompts. This import includes the final runtime resources only. Original reference photographs, people, local paths, draft images, and preview pages are not imported.

| File | Use |
| --- | --- |
| `eat-sheet-v1.png` | Feeding, six frames |
| `jump-sheet-v1.png` | Jumping, six frames |
| `groom-sheet-v1.png` | Grooming, six frames |
| `wash-large-v5.png` | Face washing, eight frames |
| `roll-photo-v3.png` | Rolling, eight source frames with a repeated playback order |
| `walk-sheet-v1.png` | Walking, eight frames |
| `sleep-v1.png` | Sleeping, one source image with procedural breathing |

The bytes are copied without image changes; `assets/manifest.json` records their SHA-256 values. These generated sprites depict the project cat; they are not the original reference photos.

The repository's Apache-2.0 license covers original software and documentation. No separate license for these PNG resources is specified by this import; do not assume that the software license grants rights to the images or to the excluded reference photographs.
