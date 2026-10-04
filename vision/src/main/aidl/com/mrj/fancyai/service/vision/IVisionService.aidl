package com.mrj.fancyai.service.vision;

import com.mrj.fancyai.service.vision.IVisionCallback;

/** Non-blocking image-and-text inference commands for the private vision process. */
oneway interface IVisionService {
    void project(
        long requestId,
        String modelPath,
        String imagePath,
        String prompt,
        IVisionCallback callback
    );
    void cancel(long requestId);
}
