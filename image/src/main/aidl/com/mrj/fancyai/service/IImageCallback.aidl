package com.mrj.fancyai.service;

import android.graphics.Bitmap;

oneway interface IImageCallback {
    void onProgress(int percent);
    void onPreview(in Bitmap preview);
    void onComplete(String outputPath, int width, int height);
    void onError(String detail);
}
