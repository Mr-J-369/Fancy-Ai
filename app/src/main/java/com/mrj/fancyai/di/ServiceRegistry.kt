package com.mrj.fancyai.di

import com.mrj.fancyai.domain.inference.LlamaEngine
import com.mrj.fancyai.service.VoiceService
import com.mrj.fancyai.service.VisionService
import com.mrj.fancyai.service.AgentService

/**
 * A plug-and-play registry for system services.
 * This allows swapping implementations (e.g., different LLM engines or TTS providers)
 * without modifying the core UI or ViewModel logic.
 */
object ServiceRegistry {
    private val services = mutableMapOf<Class<*>, Any>()

    fun <T : Any> register(serviceClass: Class<T>, implementation: T) {
        services[serviceClass] = implementation
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(serviceClass: Class<T>): T {
        return services[serviceClass] as? T 
            ?: throw IllegalStateException("Service ${serviceClass.simpleName} not registered")
    }

    // Convenience accessors for core services
    fun getLlamaEngine(): LlamaEngine = get(LlamaEngine::class.java)
    fun getVoiceService(): VoiceService = get(VoiceService::class.java)
    fun getVisionService(): VisionService = get(VisionService::class.java)
    fun getAgentService(): AgentService = get(AgentService::class.java)
}
