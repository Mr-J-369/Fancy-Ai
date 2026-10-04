# Aura HD upscaler — model conversion & host-and-fill checklist

The "Enhance to HD" feature ships **no model in the APK**. On first use of a style it
downloads a Real-ESRGAN `.mnn` and caches it under `filesDir/upscalers/`. This is the
one-time offline prep to produce, host, and wire those two models.

> **Already done (this session):** both `.mnn` files are converted + verified, and their
> **sha256 + sizes are already filled into `UpscalerModels.kt`**. To ship you only need to
> **host the two files** (step 5) and paste their **URLs** (step 7). Steps 1–4 are just the
> reproducible recipe if you ever want to regenerate them. The converted files are in this
> dir as `realesrgan_x4plus.fp16.mnn` and `realesrgan_x4plus_anime.fp16.mnn` (gitignored).

**You produce two files:**

| Style | Source weights | Output `.mnn` (fp16) | ~size |
|-------|----------------|----------------------|-------|
| Photo | `RealESRGAN_x4plus.pth` (23 blocks) | `realesrgan_x4plus.fp16.mnn` | ~33 MB |
| Anime | `RealESRGAN_x4plus_anime_6B.pth` (6 blocks) | `realesrgan_x4plus_anime.fp16.mnn` | ~9 MB |

Each `.mnn` must be: **single NCHW input** RGB `[0,1]`, **single NCHW output** RGB `[0,1]`,
**4× scale**, **dynamic H/W** (the app resizes the session to a 160² tile). The native loader
uses the single-tensor accessor, so **input/output node names don't matter**.

---

## 0. Dev environment (one-time)

`export_esrgan_onnx.py` vendors the RRDBNet arch, so the only deps are **torch + onnx**
(CPU torch is plenty — export is a single forward trace). `MNN` provides `mnnconvert`.

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install onnx MNN
```

## 1. Get the source weights

```bash
wget https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth
wget https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth
```
(If a URL 404s, grab the file from the Real-ESRGAN repo's **Model Zoo** / Releases page.)

## 2. Export to ONNX (dynamic H/W) — uses `export_esrgan_onnx.py` in this dir

```bash
python export_esrgan_onnx.py --model x4plus --pth RealESRGAN_x4plus.pth
python export_esrgan_onnx.py --model anime  --pth RealESRGAN_x4plus_anime_6B.pth
# → realesrgan_x4plus.onnx, realesrgan_x4plus_anime.onnx
```

## 3. ONNX → MNN (fp16)

```bash
mnnconvert -f ONNX --modelFile realesrgan_x4plus.onnx        --MNNModel realesrgan_x4plus.fp16.mnn        --fp16
mnnconvert -f ONNX --modelFile realesrgan_x4plus_anime.onnx  --MNNModel realesrgan_x4plus_anime.fp16.mnn  --fp16
```
- `mnnconvert` ships with `pip install MNN`. If a build wants it, add `--bizCode MNN`.
- If MNNConvert complains about a redundant/odd op, simplify first:
  `pip install onnxsim && onnxsim model.onnx model.sim.onnx` then convert `model.sim.onnx`.
- `--fp16` halves size and matches the native backend (`Precision_Low`).

## 4. (Optional) sanity-check the `.mnn` resizes to the 160 tile

```bash
python verify_mnn.py realesrgan_x4plus.fp16.mnn
```
`verify_mnn.py` loads the model, resizes to the 160² tile and runs it — exactly what the
app's native loader does — then prints the output shape + scale. Expect
`(1, 3, 640, 640)`, `scale x4`. If it prints that, the model is wired correctly.

## 5. Host the two `.mnn` files

The app downloads them with an **unauthenticated** HTTPS GET (OkHttp, follows redirects),
so the URL **must be publicly downloadable** — a private GitHub release will NOT work.

- **Public Hugging Face repo** (recommended — you already use HF):
  `https://huggingface.co/<you>/aura-hd-upscalers/resolve/main/realesrgan_x4plus.fp16.mnn`
- **Public GitHub release** (only if the host repo/release is public):
  ```bash
  gh release create aura-hd-models-v1 \
    realesrgan_x4plus.fp16.mnn realesrgan_x4plus_anime.fp16.mnn \
    --title "Aura HD upscaler models" --notes "Real-ESRGAN x4plus + anime_6B, fp16 MNN"
  # → https://github.com/<you>/<repo>/releases/download/aura-hd-models-v1/<file>
  ```
- Or any public bucket (R2/S3/GCS).

## 6. Get the sha256 + exact byte size

```bash
sha256sum realesrgan_x4plus.fp16.mnn realesrgan_x4plus_anime.fp16.mnn
stat -c '%n  %s bytes' realesrgan_x4plus.fp16.mnn realesrgan_x4plus_anime.fp16.mnn
```

## 7. Fill `UpscalerModels.kt`

Edit `app/src/main/java/com/mrj/fancyai/sd/hd/UpscalerModels.kt` — replace the 6
placeholder/estimate fields (lines 23–25 for Photo, 29–31 for Anime):

```kotlin
val PHOTO = UpscalerModel(
    UpscaleStyle.PHOTO,
    url = "https://<your-host>/realesrgan_x4plus.fp16.mnn",   // ← from step 5
    sha256 = "<64-hex from step 6>",                          // ← from step 6
    sizeBytes = 33_300_000L,                                  // ← exact bytes from step 6
)
val ANIME = UpscalerModel(
    UpscaleStyle.ANIME,
    url = "https://<your-host>/realesrgan_x4plus_anime.fp16.mnn",
    sha256 = "<64-hex>",
    sizeBytes = 9_000_000L,
)
```
> The local cache filename comes from `UpscaleStyle.fileName` (`realesrgan_x4plus.mnn` /
> `realesrgan_x4plus_anime.mnn`); the hosted file + URL can be named anything, the URL just
> has to resolve.

## 8. Verify on device (plan Task 10 smoke)

```bash
./gradlew :app:installDebug   # or assembleDebug + push
```
1. Generate an Aura image (SD 1.5 **MNN/GPU** model).
2. **Enhance to HD** → Photo → 2x. First run downloads (~33 MB, progress shown); the
   result sharpens + gains real detail; it saves as a **new** image, original intact.
3. Repeat 4x and Anime. Inspect tile boundaries at 100% zoom for seams (tune
   `overlap`/`feather`/`refineStrength` in `ImageService.enhanceToHd` if needed).
4. (Optional) a QNN model with no `vae_encoder.bin` → confirm ESRGAN-only result, no crash.

---

## Converting another model later

The toolchain is kept locally in this dir (the `.venv` + scripts). To add another
**RRDBNet** Real-ESRGAN checkpoint (e.g. a different block count, or an x2 variant):

1. Add an entry to `CONFIGS` in `export_esrgan_onnx.py` (set `num_block`).
2. Run steps 1–3 with the new `--model` name, then `python verify_mnn.py <new>.fp16.mnn`.
3. Host it (step 5) and add a `UpscalerModel` + `UpscaleStyle` (and a branch in
   `UpscalerModels.of`) in `UpscalerModels.kt`, then a chip in the HD sheet.

Non-RRDBNet upscalers (e.g. `realesr-general-x4v3`, which is SRVGGNetCompact) need their
own arch added to the exporter — but the convert → verify → host → wire flow is identical.
