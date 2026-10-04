package com.mrj.fancyai.service;

oneway interface IImageCallback {
    void onProgress(int percent);
    void onComplete(String outputPath, int width, int height);
    void onError(String detail);
}
