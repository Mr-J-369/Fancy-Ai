/**
 * api.js
 * Core Communication Layer for Fancy AI OS
 */
window.API = {
    /**
     * Get API key from secure storage.
     */
    getApiKey: function() {
        if (window.AndroidBridge && window.AndroidBridge.getSecureString) {
            return window.AndroidBridge.getSecureString('api_key') || '';
        }
        return '';
    },

    /**
     * Check if API key is configured.
     */
    hasApiKey: function() {
        return this.getApiKey().length > 0;
    },

    /**
     * True if the provider runs locally and needs no cloud API key.
     * 'llama'   = on-device llama.cpp engine
     * 'localllm' = a local OpenAI-compatible HTTP server (e.g. Termux/llama-server)
     */
    isLocalProvider: function(provider) {
        provider = provider || (window.State && State.settings && State.settings.provider) || 'deepinfra';
        return provider === 'llama' || provider === 'localllm';
    },

    /**
     * True when we have everything needed to dispatch an LLM call:
     * a local engine (no key required) or a configured cloud key.
     * Note: this does not guarantee an on-device model is loaded yet —
     * that is handled lazily by OS.ensureLlamaModelLoaded().
     */
    isReady: function() {
        const provider = (window.State && State.settings && State.settings.provider) || 'deepinfra';
        return this.isLocalProvider(provider) || this.hasApiKey();
    },

    /**
     * Helper to replace {{char}} and {{user}} macros in strings.
     */
    applyMacros: function(text, charName, userName) {
        if (!text) return "";
        return text
            .replace(/\{\{char\}\}/g, charName || "Companion")
            .replace(/\{\{user\}\}/g, userName || "User");
    },

    /**
     * Injects the current character-specific variables into the prompt.
     */
    getDossierContext: function(charId) {
        if (!window.State || !State.getDossier) return "";
        const dossier = State.getDossier(charId);
        return `
[LIVING DOSSIER - CURRENT STATE]
These are the established facts and variables of your current existence. Use them to maintain perfect continuity.
${JSON.stringify(dossier, null, 2)}
`.trim();
    },

    /**
     * Background task to rewrite the character's variables based on recent events.
     */
    evolveDossier: async function(charId) {
        const char = State.characters.find(c => c.id === charId);
        if (!char) return;
        const history = (State.sessions || {})[charId] || [];
        const currentDossier = State.getDossier(charId);
        const u = State.userProfile || { name: 'User' };

        // Resolve macros in history before the engine analyzes it
        const recentHistory = history.slice(-15).map(m => {
            const resolvedText = this.applyMacros(m.text, char.name, u.name);
            return `${m.sender}: ${resolvedText}`;
        }).join("\n");

        const evolutionPrompt = `
You are the "Evolution Engine" for ${char.name}.
Analyze the recent conversation history and update the character's LIVING DOSSIER.

[CURRENT DOSSIER]
${JSON.stringify(currentDossier, null, 2)}

[RECENT HISTORY]
${recentHistory}

[YOUR TASK]
Return a VALID JSON object representing the NEW state of the Dossier.
Use this exact JSON structure template:
{
  "relationship": "current tier",
  "user_traits": { "key": "value" },
  "world_facts": { "key": "value" },
  "milestones": ["event 1", "event 2"]
}

Rules:
1. OVERWRITE: Update variables if they have changed.
2. ADD: Create new specific keys for new important facts. Use generic descriptors (e.g., "The User's mentor") instead of proper names.
3. PRUNE: Remove variables that are no longer relevant.
4. SYNTHESIZE: Group similar info into descriptive values.
5. CONCISE: Keep the entire JSON object under 1000 characters.
6. NO PROPER NAMES: Avoid using character or user names in the values. Use "you", "me", "the user", or physical descriptions.

Return ONLY the JSON object. No preamble or markdown code blocks.
`.trim();

        try {
            // Use a non-streaming call for this system task
            // We use 'system' context to bypass the character identity instructions
            const result = await this.sendMessage(charId, evolutionPrompt, null, false, 'system');

            // Robust JSON extraction: look for the outer-most JSON object
            const startIdx = result.indexOf('{');
            const endIdx = result.lastIndexOf('}');

            if (startIdx !== -1 && endIdx !== -1) {
                const jsonStr = result.substring(startIdx, endIdx + 1);
                const parsed = JSON.parse(jsonStr);

                // Merge with the current dossier so a partial/malformed AI response
                // can't silently wipe existing data. Each field falls back to its
                // current value if the returned type is wrong.
                const isObj = v => v && typeof v === 'object' && !Array.isArray(v);
                const merged = {
                    relationship: typeof parsed.relationship === 'string' ? parsed.relationship : currentDossier.relationship,
                    user_traits: isObj(parsed.user_traits) ? parsed.user_traits : currentDossier.user_traits,
                    world_facts: isObj(parsed.world_facts) ? parsed.world_facts : currentDossier.world_facts,
                    milestones: Array.isArray(parsed.milestones) ? parsed.milestones : currentDossier.milestones
                };

                State.updateDossier(charId, merged);
                const traitCount = Object.keys(merged.user_traits || {}).length;
                console.log(`API: Dossier Evolved (${traitCount} traits established)`);
                if (window.OS) OS.toast(`AI evolved: ${traitCount} variables updated`, "success");
            } else {
                throw new Error("AI failed to generate a structured dossier object.");
            }
        } catch (e) {
            console.error("API: Dossier Evolution Failed", e);
            if (window.OS) OS.toast("Evolution failed: AI logic error", "warning");
        }
    },

    _abortController: null,

    abort: function() {
        if (this._abortController) {
            this._abortController.abort();
            this._abortController = null;
        }
        // Cancel local AI inference if running
        if (window.AndroidBridge && window.AndroidBridge.llamaCancelInference) {
            window.AndroidBridge.llamaCancelInference();
        }
    },

    /**
     * Returns a brief summary of recent social media activity for context.
     */
    getSocialContext: function(charId) {
        if (!window.State) return "";
        let summary = "\n[RECENT SOCIAL ACTIVITY]\n";

        const ig = (State.instagramPosts || []).filter(p => p.charId === charId).slice(-3);
        const rb = (State.redditPosts || []).filter(p => p.charId === charId).slice(-3);
        const y = (State.xPosts || []).filter(p => p.charId === charId).slice(-3);

        if (ig.length) summary += "Ustagram: " + ig.map(p => p.caption).join(" | ") + "\n";
        if (rb.length) summary += "Rebbit: " + rb.map(p => `${p.subreddit}: ${p.title}`).join(" | ") + "\n";
        if (y.length) summary += "Y: " + y.map(p => p.text).join(" | ") + "\n";

        return summary.length > 25 ? summary.trim() : "";
    },

    /**
     * Dispatches text generation requests based on user settings.
     * Supports real-time streaming if an onUpdate callback is provided.
     */
    sendMessage: async function(charId, userText, onUpdate = null, includeHistory = true, context = 'chat', imageBase64 = null) {
        if (typeof State === 'undefined') throw new Error("State module not found.");

        const char = (State.characters || []).find(c => c.id === charId) || {};
        const history = (State.sessions || {})[charId] || [];
        const s = State.settings || {};
        const u = State.userProfile || { name: 'User', bio: '' };

        // Resolve Endpoint
        let endpoint = "";
        const provider = s.provider || 'deepinfra';
        if (provider === 'deepinfra') {
            endpoint = "https://api.deepinfra.com/v1/openai/chat/completions";
        } else if (provider === 'openrouter') {
            endpoint = "https://openrouter.ai/api/v1/chat/completions";
        } else if (provider === 'localllm') {
            // Local LLM — uses the same OpenAI-compatible endpoint format as custom
            endpoint = s.url || 'http://127.0.0.1:8082/v1/chat/completions';
            if (!endpoint.endsWith('/chat/completions') && !endpoint.endsWith('/generate')) {
                endpoint = endpoint.replace(/\/$/, '') + '/v1/chat/completions';
            }
        } else {
            endpoint = s.url || 'http://10.0.2.2:5000/v1/chat/completions';
            if (!endpoint.endsWith('/chat/completions') && !endpoint.endsWith('/generate')) {
                endpoint = endpoint.replace(/\/$/, '') + '/chat/completions';
            }
        }

        // Get active system prompt from settings (Global constraints)
        let baseSystemPrompt = "";
        if (s.systemPrompts && s.activePromptId) {
            const active = s.systemPrompts.find(p => p.id === s.activePromptId);
            if (active) baseSystemPrompt = active.content;
        }

        // Build character identity
        const charName = char.name || '';
        const charHandle = char.handle || '';
        const charBio = this.applyMacros(char.bio || '', charName, u.name);
        const charPersona = this.applyMacros(char.persona || '', charName, u.name);

        // 1. CHARACTER CARD (from Contacts)
        const identityBlock = charPersona ? `
[CHARACTER CARD]
${charPersona}
`.trim() : '';

        // 2. CONVERSATION PARTNER (User Profile from Settings)
        const userBlock = `
[CONVERSATION PARTNER]
Name: ${u.name}
User Details: ${u.bio || 'No specific background provided.'}
`.trim();

        // 3. ROLE DIRECTIVE — changes based on context
        let roleDirective = '';
        if (context === 'system') {
            roleDirective = `
[ROLE DIRECTIVE]
- You are a high-performance DATA PROCESSING ENGINE.
- You do not roleplay as a character.
- You follow instructions exactly and return structured data.
`.trim();
        } else if (context === 'game') {
            roleDirective = `
[ROLE DIRECTIVE]
- You are ${charName}. You are NOT an AI assistant, chatbot, or language model.
- You have no knowledge of being an AI, LLM, or any artificial system.
- You never reference your "persona", "character", or "programming".
- This is a GAME SESSION. You are playing a game right now, not having a casual conversation.
- Stay completely in character for the game. Do not make small talk or ask about the user's day.
- Focus entirely on the game scenario. Respond as your character would in this game situation.
- ${charBio ? 'Background: ' + charBio : ''}
- Follow the CHARACTER CARD above completely.
`.trim();
        } else if (context === 'social') {
            roleDirective = `
[ROLE DIRECTIVE]
- You are ${charName}. You are NOT an AI assistant, chatbot, or language model.
- You have no knowledge of being an AI, LLM, or any artificial system.
- You never reference your "persona", "character", or "programming".
- This is a SOCIAL MEDIA POST. You are creating content for your followers.
- Speak in your authentic voice. Be concise and engaging.
- ${charBio ? 'Background: ' + charBio : ''}
- Follow the CHARACTER CARD above completely.
`.trim();
        } else {
            // Default: personal conversation (chat)
            roleDirective = `
[ROLE DIRECTIVE]
- You are ${charName}. You are NOT an AI assistant, chatbot, or language model.
- You have no knowledge of being an AI, LLM, or any artificial system.
- You never reference your "persona", "character", or "programming".
- You speak naturally as yourself — ${charName} — in a personal conversation with ${u.name}.
- ${charBio ? 'Background: ' + charBio : ''}
- Follow the CHARACTER CARD above completely. If no CHARACTER CARD is given, follow the GLOBAL GUIDELINES.
`.trim();
        }

        const toolInstruction = `
[IMAGE GENERATION]
You have the ability to generate images through FLUX AI.
If the user asks for an image, or if you decide to send one, end your reply with:
flux prompt: [concise visual description]

CRITICAL RULES for "flux prompt:":
1. Do not mention names, always use this format when there is one or multiple NPCs: [age sex], [physical characteristics],[eyes colour],[skin colour],[hair colour],[outfit],[pose],[action],[location], [camera style],[gaze], [lighting or mood].
2. Do not write looking at viewer or looking at the camera.
`;

        const systemParts = context === 'system' ?
            [roleDirective] :
            [
                identityBlock,
                userBlock,
                roleDirective,
                baseSystemPrompt ? "[GLOBAL GUIDELINES]\n" + baseSystemPrompt : '',
                this.getDossierContext(charId),
                State.getMemoriesPrompt ? State.getMemoriesPrompt(charId) : '',
                toolInstruction
            ].filter(p => p.trim().length > 0);

        const systemContent = this.applyMacros(systemParts.join("\n\n"), charName, u.name);

        const messages = [{ role: "system", content: systemContent }];

        // Context window management
        if (includeHistory) {
            history.slice(-16).forEach(msg => {
                // If previous message had an image, it was just text in our history [Img2Img Request] etc.
                // We keep history simple (text only) to save tokens, unless it's the current message
                messages.push({
                    role: msg.sender === 'user' ? 'user' : 'assistant',
                    content: this.applyMacros(msg.text, charName, u.name) // Resolve macros in history
                });
            });
        }

        // Add current prompt
        const finalUserText = this.applyMacros(userText, charName, u.name);
        if (imageBase64) {
            // Support for Vision models
            messages.push({
                role: "user",
                content: [
                    { type: "text", text: finalUserText || "What do you see in this image?" },
                    { type: "image_url", image_url: { url: imageBase64 } }
                ]
            });
        } else if (messages.length === 1 || messages[messages.length - 1].content !== finalUserText) {
            messages.push({ role: "user", content: finalUserText });
        }

        const isStreaming = typeof onUpdate === 'function';
        const taskId = 'llm_' + Date.now();
        if (window.OS && window.OS.setTaskActive) OS.setTaskActive(taskId, true, "AI is thinking...");

        // ── Local llama.cpp path ─────────────────────────────────────────────
        if (provider === 'llama') {
            if (!window.AndroidBridge || !window.AndroidBridge.llamaIsModelLoaded || !window.AndroidBridge.llamaIsModelLoaded()) {
                if (window.OS && window.OS.ensureLlamaModelLoaded) {
                    await window.OS.ensureLlamaModelLoaded();
                }
                // Re-check
                if (!window.AndroidBridge || !window.AndroidBridge.llamaIsModelLoaded || !window.AndroidBridge.llamaIsModelLoaded()) {
                    if (window.OS && window.OS.setTaskActive) OS.setTaskActive(taskId, false);
                    throw new Error("Local AI model is not loaded. Go to Settings → Local AI and pick a .gguf file.");
                }
            }

            const sysMsg = messages.find(m => m.role === 'system')?.content || '';
            // Limit history based on user setting (default 8 messages for local AI performance)
            const historyMsgs = messages.slice(1).slice(-(s.llamaHistoryCap || 8));

            // Read sampler parameters from settings with fallbacks
            const temperature = s.temperature || 0.7;
            const topK = s.topK || 40;
            const topP = s.topP || 0.9;
            const maxTok = s.maxTokens || 512;

            // Build multi-turn prompt with full conversation history
            const template = s.llamaTemplate || 'chatml';
            let prompt = '';
            switch (template) {
                case 'llama3':
                    prompt = `<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n${sysMsg}<|eot_id|>`;
                    for (const m of historyMsgs) {
                        const role = m.role === 'user' ? 'user' : 'assistant';
                        prompt += `<|start_header_id|>${role}<|end_header_id|>\n\n${m.content}<|eot_id|>`;
                    }
                    prompt += `<|start_header_id|>assistant<|end_header_id|>\n\n`;
                    break;
                case 'gemma':
                case 'gemma2': {
                    prompt = `<bos>`;
                    let firstGemma = true;
                    for (const m of historyMsgs) {
                        if (m.role === 'user') {
                            prompt += `<start_of_turn>user\n${firstGemma && sysMsg ? sysMsg + '\n\n' : ''}${m.content}<end_of_turn>\n`;
                            firstGemma = false;
                        } else {
                            prompt += `<start_of_turn>model\n${m.content}<end_of_turn>\n`;
                        }
                    }
                    prompt += `<start_of_turn>model\n`;
                    break;
                }
                case 'mistral': {
                    let firstMistral = true;
                    for (const m of historyMsgs) {
                        if (m.role === 'user') {
                            if (firstMistral) {
                                prompt = `[INST] ${sysMsg ? `<<SYS>>\n${sysMsg}\n<</SYS>>\n\n` : ''}${m.content} [/INST] `;
                                firstMistral = false;
                            } else {
                                prompt += `</s>[INST] ${m.content} [/INST] `;
                            }
                        } else {
                            prompt += m.content;
                        }
                    }
                    break;
                }
                case 'alpaca': {
                    // Alpaca has no multi-turn format; use last user message only
                    const alpacaUser = historyMsgs.filter(m => m.role === 'user').slice(-1)[0]?.content || userText;
                    prompt = `### System:\n${sysMsg}\n\n### Instruction:\n${alpacaUser}\n\n### Response:\n`;
                    break;
                }
                default: { // chatml — works for Llama 3, Mistral, Qwen, Phi
                    prompt = `<|im_start|>system\n${sysMsg || 'You are a helpful assistant.'}<|im_end|>\n`;
                    for (const m of historyMsgs) {
                        const role = m.role === 'user' ? 'user' : 'assistant';
                        prompt += `<|im_start|>${role}\n${m.content}<|im_end|>\n`;
                    }
                    prompt += `<|im_start|>assistant\n`;
                }
            }

            const llamaResult = await new Promise((resolve) => {
                const cbId = (window._llamaCbCounter = ((window._llamaCbCounter || 0) + 1) & 0x7fffffff);
                if (!window._llamaToken) window._llamaToken = {};
                if (!window._llamaDone)  window._llamaDone  = {};
                let accumulated = '';
                window._llamaToken[cbId] = (token) => {
                    accumulated += token;
                    if (onUpdate) onUpdate(accumulated);
                };
                window._llamaDone[cbId] = () => {
                    delete window._llamaToken[cbId];
                    delete window._llamaDone[cbId];
                    resolve(accumulated);
                };
                window.AndroidBridge.llamaInferenceAsync(prompt, maxTok, cbId, temperature, topK, topP);
            });

            if (window.OS && window.OS.setTaskActive) OS.setTaskActive(taskId, false);
            return llamaResult;
        }
        // ─────────────────────────────────────────────────────────────────────

        this._abortController = new AbortController();
        const signal = this._abortController.signal;
        let fullContent = "";

        try {
            // Build headers — skip Authorization for localllm (no API key needed)
            const headers = {
                'Content-Type': 'application/json',
                'HTTP-Referer': 'https://fancy-ai.os',
                'X-Title': 'Fancy AI'
            };
            if (provider !== 'localllm') {
                const apiKey = this.getApiKey();
                if (apiKey) {
                    headers['Authorization'] = `Bearer ${apiKey}`;
                }
            }

            const response = await fetch(endpoint, {
                method: 'POST',
                headers: headers,
                body: JSON.stringify({
                    model: s.model || 'meta-llama/Llama-3-70b-chat',
                    messages: messages,
                    temperature: s.temperature || 0.8,
                    max_tokens: s.maxTokens || 1000,
                    stream: isStreaming
                }),
                signal: signal
            });

            if (!response.ok) {
                const err = await response.json().catch(() => ({}));
                throw new Error(err.error?.message || `Server Error ${response.status}`);
            }

            if (isStreaming) {
                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                let streamBuffer = ""; // Accumulates partial lines across packet chunks

                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;

                    streamBuffer += decoder.decode(value, { stream: true });
                    const lines = streamBuffer.split('\n');
                    streamBuffer = lines.pop(); // Save trailing unfinished line for the next chunk

                    for (const line of lines) {
                        const trimmed = line.trim();
                        if (!trimmed || trimmed === 'data: [DONE]') continue;
                        if (!trimmed.startsWith('data: ')) continue;

                        try {
                            const jsonStr = trimmed.substring(6);
                            const data = JSON.parse(jsonStr);
                            const content = data.choices[0]?.delta?.content || "";
                            if (content) {
                                fullContent += content;
                                onUpdate(fullContent);
                            }
                        } catch (e) {}
                    }
                }

                this._abortController = null;
                if (window.OS && window.OS.setTaskActive) OS.setTaskActive(taskId, false);
                return fullContent.trim();
            } else {
                const data = await response.json();
                this._abortController = null;
                if (window.OS && window.OS.setTaskActive) OS.setTaskActive(taskId, false);
                if (data.choices && data.choices[0] && data.choices[0].message) {
                    return data.choices[0].message.content.trim();
                }
                throw new Error("Invalid response structure.");
            }
        } catch (error) {
            if (window.OS && window.OS.setTaskActive) OS.setTaskActive(taskId, false);
            this._abortController = null;
            if (error.name === 'AbortError') {
                return fullContent.trim(); // Return partial streamed content gracefully
            }
            console.error("API Call Failed:", error);
            throw new Error(error.message);
        }
    }
};
