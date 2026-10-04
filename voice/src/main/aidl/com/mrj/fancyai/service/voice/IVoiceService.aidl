package com.mrj.fancyai.service.voice;

import com.mrj.fancyai.service.voice.IVoiceCallback;

/** Non-blocking speech commands for the private voice process. */
oneway interface IVoiceService {
    void listen(
        long requestId,
        String provider,
        String apiKey,
        String model,
        IVoiceCallback callback
    );
    void speak(
        long requestId,
        String provider,
        String apiKey,
        String model,
        String voice,
        String text,
        IVoiceCallback callback
    );
    void cancel(long requestId);
    void finishSample(long requestId);
}
