package com.mrj.fancyai.service.voice;

/** Ordered events emitted by the private voice process. */
oneway interface IVoiceCallback {
    void onPartial(long requestId, String text);
    void onComplete(long requestId, String text);
    void onError(
        long requestId,
        int androidError,
        String cloudFailure,
        String detail
    );
    void onCancelled(long requestId);
}
