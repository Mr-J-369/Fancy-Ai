package com.mrj.fancyai.service;

oneway interface IConvertCallback {
    void onProgress(int percent, String message);
    void onComplete(String outputDir);
    void onError(String detail);
}
