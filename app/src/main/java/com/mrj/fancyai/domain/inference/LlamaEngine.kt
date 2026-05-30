package com.mrj.fancyai.domain.inference

import com.mrj.fancyai.LlamaInference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Coroutine-friendly wrapper around the native llama.cpp streaming engine.
 *
 * The native side reports progress through a single [StreamBridge] callback pair
 * (token / done), keyed by a per-request callback id. We surface that as one cold
 * [generateStream] flow so callers never juggle callback ids or a hot collector that
 * never terminates.
 */
class LlamaEngine {

    sealed interface Event {
        data class Token(val cbId: Int, val text: String) : Event
        data class Done(val cbId: Int) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 512)
    val events: SharedFlow<Event> = _events

    private var nextCbId = 1

    init {
        LlamaInference.streamBridge = object : LlamaInference.StreamBridge {
            override fun onToken(cbId: Int, token: String) {
                _events.tryEmit(Event.Token(cbId, token))
            }

            override fun onDone(cbId: Int) {
                _events.tryEmit(Event.Done(cbId))
            }
        }
    }

    fun isModelLoaded(): Boolean = LlamaInference.isModelLoaded()

    /**
     * Runs one streaming inference and emits generated tokens in order, completing
     * when the engine signals done for this request.
     *
     * The native decode loop is **blocking and long-running**, so it runs on its own
     * thread — never on the collector's dispatcher (which is usually Main; running it
     * there freezes the UI and ANRs). The collector subscribes to [events] first (via
     * [onSubscription]) and only then is inference triggered, so no early tokens are
     * lost to a subscribe/emit race. If the model isn't loaded the native layer emits
     * an immediate done, so the flow completes empty instead of hanging. Cancelling
     * the collection (e.g. leaving the chat) stops the native generation.
     */
    fun generateStream(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.95f
    ): Flow<String> = channelFlow {
        val cbId = synchronized(this@LlamaEngine) { nextCbId++ }

        val collector = launch {
            _events
                .onSubscription {
                    // Subscription is now active; kick off the blocking native run on a
                    // dedicated thread so tokens stream live and Main is never blocked.
                    thread(isDaemon = true, name = "llama-infer-$cbId") {
                        LlamaInference.inferenceStream(prompt, maxTokens, cbId, temperature, topK, topP)
                    }
                }
                .collect { event ->
                    when (event) {
                        is Event.Token -> if (event.cbId == cbId) trySend(event.text)
                        is Event.Done  -> if (event.cbId == cbId) close()
                    }
                }
        }

        awaitClose {
            // Downstream completed or was cancelled — stop the native loop and collector.
            LlamaInference.cancelInference()
            collector.cancel()
        }
    }

    fun cancel() = LlamaInference.cancelInference()
}
