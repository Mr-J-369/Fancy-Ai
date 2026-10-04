#!/usr/bin/env python3
"""Sanity-check a converted upscaler .mnn the way the app's native loader uses it:
load → resizeTensor to a square tile → run → report output shape, scale, value range.

Usage:  python verify_mnn.py realesrgan_x4plus.fp16.mnn [--size 160]

A healthy x4 upscaler at --size 160 prints output (1, 3, 640, 640), scale x4, and a
value range around [0, 1]. Works for any single-in/single-out NCHW upscaler.
"""
import argparse
import numpy as np
import MNN


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("--size", type=int, default=160, help="square input tile (app uses 160)")
    args = ap.parse_args()

    net = MNN.Interpreter(args.model)
    sess = net.createSession({})
    inp = net.getSessionInput(sess)                    # single input → name not required
    net.resizeTensor(inp, (1, 3, args.size, args.size))
    net.resizeSession(sess)

    data = np.random.rand(1, 3, args.size, args.size).astype(np.float32)
    t = MNN.Tensor((1, 3, args.size, args.size), MNN.Halide_Type_Float, data, MNN.Tensor_DimensionType_Caffe)
    inp.copyFrom(t)
    net.runSession(sess)

    out = net.getSessionOutput(sess)
    shape = tuple(out.getShape())
    ot = MNN.Tensor(shape, MNN.Halide_Type_Float, np.zeros(shape, np.float32), MNN.Tensor_DimensionType_Caffe)
    out.copyToHostTensor(ot)
    arr = np.array(ot.getData(), dtype=np.float32)

    scale = shape[2] / args.size if len(shape) == 4 else 0
    print(args.model)
    print(f"  input (1,3,{args.size},{args.size}) -> output {shape}   scale x{scale:g}")
    print(f"  output range: min={arr.min():.3f} max={arr.max():.3f}")
    assert len(shape) == 4 and shape[:2] == (1, 3) and shape[2] % args.size == 0, "unexpected output shape"
    print("  OK")


if __name__ == "__main__":
    main()
