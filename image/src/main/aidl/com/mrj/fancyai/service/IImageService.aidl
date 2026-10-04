package com.mrj.fancyai.service;

import com.mrj.fancyai.service.IImageCallback;

interface IImageService {
    void generate(
        String modelDir,
        String outputPath,
        String sourcePath,
        String prompt,
        String negativePrompt,
        int width,
        int height,
        int steps,
        float cfg,
        long seed,
        float denoising,
        String sampler,
        String schedule,
        boolean vPred,
        String mnnBackend,
        String mnnMemoryPolicy,
        boolean imageRefineEnabled,
        float imageRefineStrength,
        String imageRefinePrompt,
        IImageCallback callback
    );
    void enhance(
        String sourcePath,
        String upscalerModelPath,
        String outputPath,
        float refineStrength,
        String modelDir,
        String prompt,
        String negativePrompt,
        int steps,
        float cfg,
        String sampler,
        String schedule,
        boolean vPred,
        String mnnBackend,
        String mnnMemoryPolicy,
        IImageCallback callback
    );
    int countTokens(String modelDir, String text);
    void unloadModels();
    void cancel();
}
