#!/usr/bin/env python3
"""Export a Real-ESRGAN RRDBNet checkpoint to ONNX with DYNAMIC H/W axes.

The official scripts/pytorch2onnx.py exports a FIXED 64x64 input with no dynamic
axes. Aura's on-device upscaler runs the net on padded 160x160 tiles
(UpscalerModels.TILE_SIDE) via MNN's resizeTensor, so the ONNX input must allow
arbitrary spatial size. This script fixes that.

The RRDBNet architecture is vendored inline (from XPixelGroup/BasicSR
rrdbnet_arch.py, with the basicsr registry + helpers reduced to standalone
functions) so the ONLY dependency is torch — no basicsr, no torchvision, none of
the `functional_tensor` import breakage on newer torchvision.

Model contract produced (matches app/src/main/cpp/sd/mnn_upscaler.cpp):
  - single input  "input"  : NCHW float, RGB in [0,1], dynamic H/W
  - single output "output" : NCHW float, RGB in [0,1], 4x H/W
  - no ImageNet mean/std normalization (RRDBNet works in [0,1] directly)

Usage:
  python export_esrgan_onnx.py --model x4plus --pth RealESRGAN_x4plus.pth
  python export_esrgan_onnx.py --model anime  --pth RealESRGAN_x4plus_anime_6B.pth

Deps:  pip install torch onnx
Then convert the ONNX to MNN (fp16):  see README.md step 3.
"""
import argparse
import os
import torch
from torch import nn
from torch.nn import functional as F


def make_layer(basic_block, num_basic_block, **kwarg):
    return nn.Sequential(*[basic_block(**kwarg) for _ in range(num_basic_block)])


def pixel_unshuffle(x, scale):
    b, c, hh, hw = x.size()
    h, w = hh // scale, hw // scale
    return x.view(b, c, h, scale, w, scale).permute(0, 1, 3, 5, 2, 4).reshape(b, c * scale * scale, h, w)


class ResidualDenseBlock(nn.Module):
    def __init__(self, num_feat=64, num_grow_ch=32):
        super().__init__()
        self.conv1 = nn.Conv2d(num_feat, num_grow_ch, 3, 1, 1)
        self.conv2 = nn.Conv2d(num_feat + num_grow_ch, num_grow_ch, 3, 1, 1)
        self.conv3 = nn.Conv2d(num_feat + 2 * num_grow_ch, num_grow_ch, 3, 1, 1)
        self.conv4 = nn.Conv2d(num_feat + 3 * num_grow_ch, num_grow_ch, 3, 1, 1)
        self.conv5 = nn.Conv2d(num_feat + 4 * num_grow_ch, num_feat, 3, 1, 1)
        self.lrelu = nn.LeakyReLU(negative_slope=0.2, inplace=True)

    def forward(self, x):
        x1 = self.lrelu(self.conv1(x))
        x2 = self.lrelu(self.conv2(torch.cat((x, x1), 1)))
        x3 = self.lrelu(self.conv3(torch.cat((x, x1, x2), 1)))
        x4 = self.lrelu(self.conv4(torch.cat((x, x1, x2, x3), 1)))
        x5 = self.conv5(torch.cat((x, x1, x2, x3, x4), 1))
        return x5 * 0.2 + x


class RRDB(nn.Module):
    def __init__(self, num_feat, num_grow_ch=32):
        super().__init__()
        self.rdb1 = ResidualDenseBlock(num_feat, num_grow_ch)
        self.rdb2 = ResidualDenseBlock(num_feat, num_grow_ch)
        self.rdb3 = ResidualDenseBlock(num_feat, num_grow_ch)

    def forward(self, x):
        out = self.rdb1(x)
        out = self.rdb2(out)
        out = self.rdb3(out)
        return out * 0.2 + x


class RRDBNet(nn.Module):
    def __init__(self, num_in_ch, num_out_ch, scale=4, num_feat=64, num_block=23, num_grow_ch=32):
        super().__init__()
        self.scale = scale
        if scale == 2:
            num_in_ch = num_in_ch * 4
        elif scale == 1:
            num_in_ch = num_in_ch * 16
        self.conv_first = nn.Conv2d(num_in_ch, num_feat, 3, 1, 1)
        self.body = make_layer(RRDB, num_block, num_feat=num_feat, num_grow_ch=num_grow_ch)
        self.conv_body = nn.Conv2d(num_feat, num_feat, 3, 1, 1)
        self.conv_up1 = nn.Conv2d(num_feat, num_feat, 3, 1, 1)
        self.conv_up2 = nn.Conv2d(num_feat, num_feat, 3, 1, 1)
        self.conv_hr = nn.Conv2d(num_feat, num_feat, 3, 1, 1)
        self.conv_last = nn.Conv2d(num_feat, num_out_ch, 3, 1, 1)
        self.lrelu = nn.LeakyReLU(negative_slope=0.2, inplace=True)

    def forward(self, x):
        if self.scale == 2:
            feat = pixel_unshuffle(x, scale=2)
        elif self.scale == 1:
            feat = pixel_unshuffle(x, scale=4)
        else:
            feat = x
        feat = self.conv_first(feat)
        body_feat = self.conv_body(self.body(feat))
        feat = feat + body_feat
        feat = self.lrelu(self.conv_up1(F.interpolate(feat, scale_factor=2, mode='nearest')))
        feat = self.lrelu(self.conv_up2(F.interpolate(feat, scale_factor=2, mode='nearest')))
        out = self.conv_last(self.lrelu(self.conv_hr(feat)))
        return out


# num_block is the only arch difference: x4plus = 23 RRDB blocks, anime_6B = 6.
CONFIGS = {
    "x4plus": dict(num_block=23, onnx="realesrgan_x4plus.onnx"),
    "anime": dict(num_block=6, onnx="realesrgan_x4plus_anime.onnx"),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=CONFIGS.keys(), required=True)
    ap.add_argument("--pth", required=True, help="path to the RealESRGAN .pth weights")
    ap.add_argument("--opset", type=int, default=11)
    ap.add_argument("--size", type=int, default=160, help="dummy trace size (dynamic axes make it resizable)")
    args = ap.parse_args()
    cfg = CONFIGS[args.model]

    model = RRDBNet(num_in_ch=3, num_out_ch=3, scale=4, num_feat=64, num_block=cfg["num_block"], num_grow_ch=32)
    sd = torch.load(args.pth, map_location="cpu")
    # Release weights store EMA params under "params_ema"; fall back to "params" or a bare dict.
    state = sd.get("params_ema", sd.get("params", sd))
    model.load_state_dict(state, strict=True)
    model.eval()

    out_path = os.path.join(os.path.dirname(os.path.abspath(args.pth)), cfg["onnx"])
    x = torch.rand(1, 3, args.size, args.size)
    torch.onnx.export(
        model, x, out_path,
        opset_version=args.opset,
        export_params=True,
        input_names=["input"],
        output_names=["output"],
        dynamic_axes={"input": {2: "h", 3: "w"}, "output": {2: "h4", 3: "w4"}},
        dynamo=False,  # torch>=2.9 defaults to the dynamo exporter (needs onnxscript); legacy honors dynamic_axes
    )
    print(f"wrote {out_path}  (num_block={cfg['num_block']}, dynamic H/W, opset {args.opset})")


if __name__ == "__main__":
    main()
