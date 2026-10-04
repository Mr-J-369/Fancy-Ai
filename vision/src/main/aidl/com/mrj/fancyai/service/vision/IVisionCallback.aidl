package com.mrj.fancyai.service.vision;

/** Ordered events emitted by the private vision process. */
oneway interface IVisionCallback {
    void onReady(long requestId);
    void onChunk(long requestId, String text);
    void onComplete(long requestId);
    void onError(long requestId, int error, String detail);
    void onCancelled(long requestId);
}
