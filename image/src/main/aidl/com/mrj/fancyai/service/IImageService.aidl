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
        String ditCacheMode,
        String ditCacheOption,
        float guidance,
        String maskPath,
        in List<String> referenceImagePaths,
        boolean livePreview,
        in List<String> loraPaths,
        in float[] loraStrengths,
        IImageCallback callback
    );
    void upscale(
        String upscalerModelPath,
        String inputImagePath,
        String outputPath,
        IImageCallback callback
    );
    int countTokens(String modelDir, String text);
    void unloadModels();
    void cancel();
}
