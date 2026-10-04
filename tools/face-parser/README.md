# Face parser (ADetailer) — model conversion & host-and-fill checklist

The "Enhance faces" feature (auto ADetailer) ships **no model in the APK**. On first use it
downloads a BiSeNet face-parsing `.mnn` and caches it under `filesDir/faceparser/`. This is the
one-time offline prep to produce, host, and wire that model.

> **Already done (this session):** the `.mnn` is converted + verified, its **sha256 + size are
> filled into `FaceParserModel.kt`**, and it is **hosted on HF** (commit `1361e49`, round-trip
> checksum-verified against the descriptor). To ship you only need to **verify on device** (step 5),
> then commit. Steps 1–4 are the reproducible recipe. The converted file is in this dir as
> `face_parsing_resnet18.fp16.mnn` (gitignored).

**The model:** BiSeNet, **ResNet18** backbone, **CelebAMask-HQ 19 classes**, **512×512**, from
[yakhyo/face-parsing](https://github.com/yakhyo/face-parsing) (**MIT** license).

| Field | Value |
|-------|-------|
| Hosted file name | `face_parsing_resnet18.fp16.mnn` (must match `FaceParserModel.FILE_NAME`) |
| sha256 | `d79ee34f19c7cd855dee133760a66da7959d68595d37091bf423a38d30237402` |
| size (bytes) | `26328320` |
| MNN I/O | single input `[1,3,512,512]` → single output `[1,19,512,512]` logits (NCHW) |

The native loader (`mnn_segmenter.cpp`) uses the single-tensor accessor, so **node names don't
matter** — but the model must have exactly **one output** (the main head). The upstream ONNX has 3
outputs (main + 2 aux training heads); step 2 strips it to the main head only.

---

## 0. Dev environment (one-time)

`onnx` + the `MNN` pip package (which provides `mnnconvert`):

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install onnx MNN
```

## 1. Get the source ONNX

```bash
curl -fL -o resnet18.onnx \
  https://github.com/yakhyo/face-parsing/releases/download/weights/resnet18.onnx
```

## 2. Strip to the single main output, then convert to fp16 MNN

The export has 3 outputs (`output`, `414`, `424`); keep only `output`:

```bash
python - <<'PY'
import onnx
onnx.utils.extract_model('resnet18.onnx', 'resnet18_main.onnx',
                         input_names=['input'], output_names=['output'])
PY
mnnconvert -f ONNX --modelFile resnet18_main.onnx \
           --MNNModel face_parsing_resnet18.fp16.mnn --fp16 --bizCode biz
```

## 3. Verify, then record sha256 + size

```bash
python - <<'PY'
import numpy as np, MNN
net = MNN.Interpreter('face_parsing_resnet18.fp16.mnn'); sess = net.createSession({})
inp = net.getSessionInput(sess); net.resizeTensor(inp, (1,3,512,512)); net.resizeSession(sess)
t = MNN.Tensor((1,3,512,512), MNN.Halide_Type_Float,
               np.random.rand(1,3,512,512).astype(np.float32), MNN.Tensor_DimensionType_Caffe)
inp.copyFrom(t); net.runSession(sess)
print("output", tuple(net.getSessionOutput(sess).getShape()))   # expect (1, 19, 512, 512)
PY
sha256sum face_parsing_resnet18.fp16.mnn   # → FaceParserModel.SHA256
stat -c%s face_parsing_resnet18.fp16.mnn   # → FaceParserModel.SIZE_BYTES
```

## 4. Host the file

Upload `face_parsing_resnet18.fp16.mnn` to HF `Mr-J-369/Fancy-AI` (same repo as the SD/upscaler
models) so the URL in `FaceParserModel.URL` resolves:

```
https://huggingface.co/Mr-J-369/Fancy-AI/resolve/main/face_parsing_resnet18.fp16.mnn
```

## 5. Verify on device

Build + install from **Android Studio** (Shift+F10) — *not* `adb install` over the published app.
Generate a portrait with Aura, then:

```bash
adb logcat -s fancysdseg fancysd
```

Expect `face-parser loaded (OpenCL, side=512)` and a visibly sharper face. Toggle
**Settings → Enhance faces** off → the parser no longer loads. Generate a landscape (no face) →
parser loads, coverage below threshold, returns the original quickly.
