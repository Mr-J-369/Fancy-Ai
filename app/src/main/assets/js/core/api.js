/**
 * api.js
 * Core Communication Layer for Fancy AI OS
 */
window.API = {
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
        const charBio = char.bio || '';
        const charPersona = char.persona || '';

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
        if (context === 'game') {
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
flux prompt: [detailed visual description]

CRITICAL RULES for "flux prompt:":
1. PHYSICAL CONSISTENCY: Describe your physical attributes as per your persona including ages, ethnicity etc. consistently in every prompt.
2. NO META-WORDS: Do NOT use words like "persona", "character", or "AI".
3. HUMAN ONLY: Unless your persona explicitly states otherwise, describe yourself as 100% human.
4. PHOTOREALISM: Use photography terms like "85mm lens", "candid shot".
5. NO MARKDOWN: Do not wrap the flux prompt in code blocks.
`;

        const systemParts = [
            identityBlock,
            userBlock,
            roleDirective,
            baseSystemPrompt ? "[GLOBAL GUIDELINES]\n" + baseSystemPrompt : '',
            State.getMemoriesPrompt ? State.getMemoriesPrompt(charId) : '',
            toolInstruction
        ].filter(p => p.trim().length > 0);

        const systemContent = systemParts.join("\n\n");

        const messages = [{ role: "system", content: systemContent }];

        // Context window management
        if (includeHistory) {
            history.slice(-16).forEach(msg => {
                // If previous message had an image, it was just text in our history [Img2Img Request] etc.
                // We keep history simple (text only) to save tokens, unless it's the current message
                messages.push({
                    role: msg.sender === 'user' ? 'user' : 'assistant',
                    content: msg.text
                });
            });
        }

        // Add current prompt
        if (imageBase64) {
            // Support for Vision models
            messages.push({
                role: "user",
                content: [
                    { type: "text", text: userText || "What do you see in this image?" },
                    { type: "image_url", image_url: { url: imageBase64 } }
                ]
            });
        } else if (messages.length === 1 || messages[messages.length - 1].content !== userText) {
            messages.push({ role: "user", content: userText });
        }

        const isStreaming = typeof onUpdate === 'function';
        const taskId = 'llm_' + Date.now();
        if (window.OS && window.OS.setTaskActive) OS.setTaskActive(taskId, true, "AI is thinking...");

        try {
            // Build headers — skip Authorization for localllm (no API key needed)
            const headers = {
                'Content-Type': 'application/json',
                'HTTP-Referer': 'https://fancy-ai.os',
                'X-Title': 'Fancy AI'
            };
            if (provider !== 'localllm' && s.key) {
                headers['Authorization'] = `Bearer ${s.key}`;
            }

            const response = await fetch(endpoint, {
                method: 'POST',
                headers: headers,
                body: JSON.stringify({
                    model: s.model || 'meta-llama/Llama-3-70b-chat',
                    messages: messages,
                    temperature: 0.8,
                    max_tokens: 1000,
                    stream: isStreaming
                })
            });

            if (!response.ok) {
                const err = await response.json().catch(() => ({}));
                throw new Error(err.error?.message || `Server Error ${response.status}`);
            }

            if (isStreaming) {
                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                let fullContent = "";
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
                if (window.OS && window.OS.setTaskActive) OS.setTaskActive(taskId, false);
                return fullContent.trim();
            } else {
                const data = await response.json();
                if (window.OS && window.OS.setTaskActive) OS.setTaskActive(taskId, false);
                if (data.choices && data.choices[0] && data.choices[0].message) {
                    return data.choices[0].message.content.trim();
                }
                throw new Error("Invalid response structure.");
            }
        } catch (error) {
            if (window.OS && window.OS.setTaskActive) OS.setTaskActive(taskId, false);
            console.error("API Call Failed:", error);
            throw new Error(error.message);
        }
    }
};
