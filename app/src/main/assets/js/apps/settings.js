/**
 * settings.js
 * Text LLM Core Configuration Module
 * Handles system prompts, user profile, cloud text providers, and ecosystem data backups.
 */
const SettingsApp = {
    container: null,

    init: function(container) {
        this.container = container;
        this.render();
        this.loadSettingsToForm();
    },

    render: function() {
        const styleId = "settings-app-style";
        if (!document.getElementById(styleId)) {
            const style = document.createElement('style');
            style.id = styleId;
            style.innerHTML = `
                .settings-wrap {
                    padding: 20px;
                    overflow-y: auto;
                    height: 100%;
                    background: #0a0a0b;
                    display: flex; flex-direction: column; gap: 16px;
                    padding-bottom: 120px;
                }
                .settings-section {
                    background: var(--bg-card);
                    padding: 16px;
                    border-radius: 12px;
                    border: 1px solid var(--border);
                    display: flex; flex-direction: column; gap: 12px;
                }
                .section-title {
                    font-size: 0.85rem; font-weight: 800; color: var(--accent);
                    text-transform: uppercase; letter-spacing: 1px; margin-bottom: 4px;
                }
                .form-group { display: flex; flex-direction: column; gap: 6px; }
                .form-group label { color: var(--text-muted); font-size: 0.75rem; font-weight: 600; }
                .form-control {
                    background: var(--bg-input); border: 1px solid var(--border);
                    color: var(--text-main); padding: 12px; border-radius: 10px;
                    font-size: 0.9rem; font-family: inherit; width: 100%; outline: none;
                }
                .form-control:focus { border-color: var(--accent); }
                .btn {
                    background: var(--bg-input); color: var(--text-main);
                    border: 1px solid var(--border); padding: 12px 16px;
                    border-radius: 10px; font-weight: 600; cursor: pointer;
                    text-align: center; font-size: 0.9rem; width: 100%;
                }
                .btn-primary { background: var(--accent); color: white; border: none; }
                
                .prompt-item {
                    display: flex; gap: 8px; align-items: center; margin-bottom: 8px;
                }

                .model-item {
                    background: rgba(255,255,255,0.03);
                    border: 1px solid var(--border);
                    border-radius: 10px;
                    padding: 10px 12px;
                    display: flex; justify-content: space-between; align-items: center;
                }
                .model-info { display: flex; flex-direction: column; gap: 2px; flex: 1; overflow: hidden; }
                .model-name { font-size: 0.82rem; font-weight: 600; color: var(--text-main); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                .model-actions { display: flex; gap: 8px; }
                .action-btn { background: none; border: none; cursor: pointer; padding: 4px; font-size: 1.1rem; }
            `;
            document.head.appendChild(style);
        }

        this.container.innerHTML = `
            <div class="settings-wrap">
                <!-- Help & Tips (new users) -->
                <div class="settings-section">
                    <div style="display:flex; justify-content:space-between; align-items:center; cursor:pointer;" onclick="SettingsApp.toggleHelp()">
                        <div class="section-title" style="margin:0;">❓ Help &amp; Tips</div>
                        <div id="helpChevron" style="color:var(--text-muted); font-size:0.85rem;">▶</div>
                    </div>
                    <div id="helpPanel" style="display:none; flex-direction:column; gap:12px; padding-top:12px; font-size:0.8rem; color:var(--text-muted); line-height:1.55;">
                        <div style="color:var(--text-main); font-size:0.78rem;">New here? A quick tour of what everything does:</div>
                        <div><b style="color:var(--accent);">👥 Contacts</b> — Your AI characters. Tap one to chat. Inside a chat, tap their name at the top to edit their personality, see what they remember about you (🧠), or update it (🌀). Use <b>+ New</b> or <b>📥 Import</b> (PNG/JSON character cards) to add more.</div>
                        <div><b style="color:var(--accent);">📸 Ustagram · 🔞 Rebbit · ✕ Y</b> — Social feeds your characters post to. Tap <b>New Post</b> to make one now, or turn on <b>Auto-Post</b> below to let them post on their own while the app is open.</div>
                        <div><b style="color:var(--accent);">🎨 Imaging</b> — Where pictures are created. Pick <b>Forge</b> (a server you run on a PC) or <b>Local Dream</b> (runs on your phone). Set this up before asking for images.</div>
                        <div><b style="color:var(--accent);">🖼️ Gallery</b> — Every image the app has made, sorted into folders.</div>
                        <div><b style="color:var(--accent);">📞 Phone</b> — Hands-free voice calls with a character, using your phone's speech.</div>
                        <div style="border-top:1px solid var(--border); padding-top:10px;"><b style="color:var(--accent);">Text Engine (the AI brain)</b> — Choose where the AI runs:
                            <div style="padding-left:10px; margin-top:4px;">• <b>DeepInfra / OpenRouter</b>: powerful cloud AI. Needs a paid API key. Tap 🔍 to list models.</div>
                            <div style="padding-left:10px;">• <b>Local AI (on-device)</b>: runs entirely on your phone. Free &amp; private, but slower. Import a <b>.gguf</b> model below first.</div>
                            <div style="padding-left:10px;">• <b>Local LLM (HTTP)</b>: connect to an AI server you run yourself.</div>
                        </div>
                        <div><b style="color:var(--accent);">Temperature / Max Tokens</b> — Temperature is creativity (higher = wilder). Max Tokens is how long replies can get. Defaults are fine if unsure.</div>
                        <div><b style="color:var(--accent);">On-Device LLM</b> — Import a model file to chat without internet. Bigger models are smarter but need more phone memory (RAM).</div>
                        <div><b style="color:var(--accent);">Content → Hide NSFW</b> — Removes the 🔞 Rebbit app from the home screen.</div>
                        <div><b style="color:var(--accent);">Backup</b> — Save everything (chats, characters, images, settings) to a file you can restore later or move to a new phone.</div>
                    </div>
                </div>

                <!-- User Profile Section -->
                <div class="settings-section">
                    <div class="section-title">User Profile</div>
                    <div class="form-group">
                        <label for="cfgUserName">Your Name</label>
                        <input type="text" id="cfgUserName" class="form-control" placeholder="What should characters call you?">
                    </div>
                    <div class="form-group">
                        <label for="cfgUserBio">Your Bio / Description</label>
                        <textarea id="cfgUserBio" class="form-control" rows="2" placeholder="Tell the characters about yourself..."></textarea>
                    </div>
                </div>

                <!-- AI System Section -->
                <div class="settings-section">
                    <div class="section-title">System Guidance (Prompts)</div>
                    <div class="form-group">
                        <label>Active System Prompt</label>
                        <select id="cfgActivePrompt" class="form-control" onchange="SettingsApp.onPromptSelectChange()">
                        </select>
                    </div>
                    <div id="promptEditor" style="display:none; flex-direction:column; gap:8px; border-top:1px solid var(--border); padding-top:12px;">
                         <div class="form-group">
                            <label>Prompt Name</label>
                            <input type="text" id="editPromptName" class="form-control">
                        </div>
                        <div class="form-group">
                            <label>Prompt Content</label>
                            <textarea id="editPromptContent" class="form-control" rows="4"></textarea>
                        </div>
                        <div style="display:flex; gap:8px;">
                            <button class="btn" onclick="SettingsApp.saveCurrentPrompt()">Update Current</button>
                            <button class="btn" onclick="SettingsApp.deleteCurrentPrompt()" style="color:var(--danger)">Delete</button>
                        </div>
                    </div>
                    <button class="btn" style="margin-top:8px;" onclick="SettingsApp.addNewPrompt()">+ Add New Prompt</button>
                </div>

                <!-- API Connectivity Section -->
                <div class="settings-section">
                    <div class="section-title">Text Engine (LLM)</div>
                    <div class="form-group">
                        <label for="cfgProvider">Provider</label>
                        <select id="cfgProvider" class="form-control" onchange="SettingsApp.handleProviderChange()">
                            <option value="llama">Local AI (llama.cpp — On-Device)</option>
                            <option value="deepinfra">DeepInfra</option>
                            <option value="openrouter">OpenRouter</option>
                            <option value="localllm">Local LLM (HTTP)</option>
                            <option value="custom">Custom Endpoint</option>
                        </select>
                    </div>
                    <div class="form-group" id="customUrlGroup">
                        <label for="cfgUrl">Base URL</label>
                        <input type="text" id="cfgUrl" class="form-control" placeholder="https://api.example.com/v1">
                    </div>
                    <div id="localLlmGuide" style="display:none; background:rgba(139,92,246,0.06); border:1px solid rgba(139,92,246,0.15); border-radius:12px; padding:14px; font-size:0.82rem; line-height:1.55; color:var(--text-muted);">
                        <div style="font-weight:800; color:var(--accent); margin-bottom:8px; font-size:0.9rem;">🖥️ Local LLM Setup Guide</div>
                        <div style="margin-bottom:8px;">Run an OpenAI-compatible server on your device. No API key needed — everything stays on your phone.</div>
                        <div style="background:var(--bg-input); border-radius:8px; padding:10px; margin-bottom:8px; font-family:monospace; font-size:0.75rem; color:#a78bfa; word-break:break-all;">
                            <div style="color:var(--text-muted); margin-bottom:4px;">Option 1 — Termux + llama.cpp:</div>
                            <div>$ pkg install git cmake</div>
                            <div>$ git clone https://github.com/ggml-org/llama.cpp</div>
                            <div>$ cd llama.cpp && cmake -B build && cmake --build build --config Release</div>
                            <div>$ ./build/bin/llama-server -m model.gguf --port 8082 --host 0.0.0.0</div>
                        </div>
                        <div style="background:var(--bg-input); border-radius:8px; padding:10px; margin-bottom:8px; font-family:monospace; font-size:0.75rem; color:#a78bfa; word-break:break-all;">
                            <div style="color:var(--text-muted); margin-bottom:4px;">Option 2 — MLC Chat (Android APK):</div>
                            <div>1. Install MLC Chat from Play Store / GitHub</div>
                            <div>2. Download a model inside the app</div>
                            <div>3. Enable the local server in MLC Chat settings</div>
                            <div>4. Set the URL below to the MLC Chat server address</div>
                        </div>
                        <div style="background:var(--bg-input); border-radius:8px; padding:10px; font-family:monospace; font-size:0.75rem; color:#a78bfa; word-break:break-all;">
                            <div style="color:var(--text-muted); margin-bottom:4px;">Option 3 — Any OpenAI-compatible server:</div>
                            <div>Run ollama, text-generation-webui, vLLM, etc.</div>
                            <div>Just make sure it exposes /v1/chat/completions</div>
                        </div>
                        <div style="margin-top:10px; padding-top:8px; border-top:1px solid rgba(139,92,246,0.1);">
                            <span style="color:#22c55e; font-weight:700;">✓ Zero API cost</span> ·
                            <span style="color:#22c55e; font-weight:700;">✓ Fully private</span> ·
                            <span style="color:#f59e0b; font-weight:700;">⚠ Requires 4-8GB RAM</span> ·
                            <span style="color:#f59e0b; font-weight:700;">⚠ Slower than cloud</span>
                        </div>
                    </div>
                    <div class="form-group" id="apiKeyGroup">
                        <label for="cfgKey">API Key</label>
                        <input type="password" id="cfgKey" class="form-control" placeholder="sk-...">
                    </div>
                    <div class="form-group">
                        <label for="cfgModel">Model ID</label>
                        <div style="display: flex; gap: 8px;">
                            <input type="text" id="cfgModel" class="form-control" style="flex: 1;">
                            <button class="btn" style="width: auto; padding: 0 12px;" onclick="SettingsApp.fetchAvailableModels()">🔍</button>
                        </div>
                    </div>
                </div>

                <!-- Generation Parameters Section -->
                <div class="settings-section">
                    <div class="section-title">Generation Parameters</div>
                    <div class="form-group">
                        <label for="cfgTemperature">Temperature: <span id="lblTemperature" style="color:var(--text-main);">0.7</span></label>
                        <div style="font-size:0.72rem; color:var(--text-muted); margin-bottom:8px;">Randomness. Lower = more focused, higher = more creative.</div>
                        <input type="range" id="cfgTemperature" min="0" max="2" step="0.05" value="0.7" class="form-control" style="padding:4px; accent-color:var(--accent);" oninput="document.getElementById('lblTemperature').innerText = parseFloat(this.value).toFixed(2)">
                    </div>
                    <div class="form-group">
                        <label for="cfgMaxTokens">Max Tokens: <span id="lblMaxTokens" style="color:var(--text-main);">512</span></label>
                        <div style="font-size:0.72rem; color:var(--text-muted); margin-bottom:8px;">Maximum reply length. Longer responses = slower generation.</div>
                        <input type="range" id="cfgMaxTokens" min="64" max="4096" step="64" value="512" class="form-control" style="padding:4px; accent-color:var(--accent);" oninput="document.getElementById('lblMaxTokens').innerText = this.value">
                    </div>
                    <div id="localAiParamsGroup">
                        <div class="form-group">
                            <label for="cfgTopK">Top-K: <span id="lblTopK" style="color:var(--text-main);">40</span></label>
                            <div style="font-size:0.72rem; color:var(--text-muted); margin-bottom:8px;">Limits token pool to the K most likely next words. Lower = more precise.</div>
                            <input type="range" id="cfgTopK" min="1" max="100" step="1" value="40" class="form-control" style="padding:4px; accent-color:var(--accent);" oninput="document.getElementById('lblTopK').innerText = this.value">
                        </div>
                        <div class="form-group">
                            <label for="cfgTopP">Top-P: <span id="lblTopP" style="color:var(--text-main);">0.90</span></label>
                            <div style="font-size:0.72rem; color:var(--text-muted); margin-bottom:8px;">Nucleus sampling. Works together with Top-K.</div>
                            <input type="range" id="cfgTopP" min="0" max="1" step="0.05" value="0.9" class="form-control" style="padding:4px; accent-color:var(--accent);" oninput="document.getElementById('lblTopP').innerText = parseFloat(this.value).toFixed(2)">
                        </div>
                    </div>
                </div>

                <!-- On-Device LLM Model Section -->
                <div class="settings-section">
                    <div class="section-title">On-Device LLM (llama.cpp)</div>
                    <div id="llamaStatusContainer" style="background:rgba(139,92,246,0.06); border:1px solid rgba(139,92,246,0.15); border-radius:12px; padding:14px; margin-bottom:12px;">
                        <div style="font-size:0.82rem; color:var(--text-main); line-height:1.6;">
                            <div style="margin-bottom:8px;">
                                <span style="font-weight:700;">Status:</span>
                                <span id="llamaStatusLabel" style="color:var(--accent); font-weight:600;">Loading...</span>
                            </div>
                            <div id="llamaPathDisplay" style="margin-bottom:8px; word-break:break-all; font-family:monospace; font-size:0.75rem; color:var(--text-muted);"></div>
                            <div id="llamaModelNameDisplay" style="margin-bottom:8px; font-size:0.8rem;"></div>
                        </div>
                    </div>
                    <!-- Engine Settings (collapsible) -->
                    <div style="border-top:1px solid var(--border); padding-top:14px; margin-top:2px;">
                        <div style="display:flex; justify-content:space-between; align-items:center; cursor:pointer; margin-bottom:4px;" onclick="SettingsApp.toggleEngineSettings()">
                            <div style="font-size:0.8rem; font-weight:700; color:var(--text-muted); text-transform:uppercase; letter-spacing:0.5px;">⚙️ Engine Settings</div>
                            <div id="engineSettingsChevron" style="color:var(--text-muted); font-size:0.85rem; transition:transform 0.2s;">▶</div>
                        </div>
                        <div id="engineSettingsPanel" style="display:none; flex-direction:column; gap:14px; padding-top:12px;">

                            <div class="form-group">
                                <label for="cfgLlamaTemplate">Chat Template</label>
                                <div style="font-size:0.72rem; color:var(--text-muted); margin-bottom:8px;">Prompt format. Must match your model. ChatML works for most Llama/Qwen/Phi/Mistral models.</div>
                                <select id="cfgLlamaTemplate" class="form-control">
                                    <option value="chatml">ChatML (Llama 3, Mistral, Qwen, Phi)</option>
                                    <option value="llama3">Llama 3 (Alternative)</option>
                                    <option value="gemma">Gemma</option>
                                    <option value="gemma2">Gemma 2</option>
                                    <option value="mistral">Mistral</option>
                                    <option value="alpaca">Alpaca</option>
                                </select>
                            </div>

                            <div class="form-group">
                                <label>CPU Threads: <span id="lblLlamaThreads" style="color:var(--text-main);">6</span></label>
                                <div style="font-size:0.72rem; color:var(--text-muted); margin-bottom:8px;">How many CPU cores the AI uses. Try 4 for budget phones, 6–8 for flagships. Too many threads can actually slow things down.</div>
                                <input type="range" id="cfgLlamaThreads" min="1" max="12" step="1" value="4" class="form-control" style="padding:4px; accent-color:var(--accent);" oninput="document.getElementById('lblLlamaThreads').innerText = this.value">
                            </div>

                            <div class="form-group">
                                <label for="cfgLlamaBackend">Inference Hardware</label>
                                <div style="font-size:0.72rem; color:var(--text-muted); margin-bottom:8px;">Where the model runs. <b>CPU</b> works on every device. <b>GPU</b> uses the Adreno (OpenCL, falls back to Vulkan) — usually fastest. <b>NPU</b> uses the Hexagon DSP (experimental). <span style="color:#f59e0b;">Switching restarts the app.</span></div>
                                <select id="cfgLlamaBackend" class="form-control" onchange="SettingsApp.onBackendChange(this.value)">
                                    <option value="cpu">CPU — works on every device</option>
                                    <option value="gpu">GPU — Adreno (OpenCL / Vulkan)</option>
                                    <option value="npu">NPU — Hexagon (experimental)</option>
                                </select>
                            </div>

                            <div class="form-group">
                                <label>Offload Layers (NPU/GPU): <span id="lblLlamaGpu" style="color:var(--text-main);">99</span></label>
                                <div style="font-size:0.72rem; color:var(--text-muted); margin-bottom:8px;">When NPU or GPU is selected, how many model layers to move onto it. Higher = more on the accelerator (use 99 for "all"). Ignored on CPU. <span style="color:#f59e0b;">Reload model to apply.</span></div>
                                <input type="range" id="cfgLlamaGpu" min="0" max="100" step="1" value="99" class="form-control" style="padding:4px; accent-color:var(--accent);" oninput="document.getElementById('lblLlamaGpu').innerText = this.value">
                            </div>

                            <div class="form-group">
                                <label for="cfgLlamaCtx">Context Window</label>
                                <div style="font-size:0.72rem; color:var(--text-muted); margin-bottom:8px;">How many tokens (words) the model can see at once. Larger means it can read longer conversations but uses more RAM and is slower to start. 2048 is safe for most devices under 8 GB RAM.</div>
                                <select id="cfgLlamaCtx" class="form-control">
                                    <option value="512">512 — Ultra-fast, very short memory</option>
                                    <option value="1024">1024 — Fast, low RAM</option>
                                    <option value="2048">2048 — Balanced (recommended for 4–6 GB)</option>
                                    <option value="4096">4096 — Long conversations (8 GB+ recommended)</option>
                                    <option value="8192">8192 — Extended context (needs ~12 GB RAM)</option>
                                </select>
                            </div>

                            <div class="form-group">
                                <label for="cfgLlamaKvType">KV Cache Precision</label>
                                <div style="font-size:0.72rem; color:var(--text-muted); margin-bottom:8px;">How the attention cache is stored. <b>F16</b> = best quality (default). <b>Q8_0</b> ≈ half the cache RAM, <b>Q4_0</b> ≈ a quarter — lets longer context fit on less memory, with a small quality cost. Best on CPU. <span style="color:#f59e0b;">Reload model to apply.</span></div>
                                <select id="cfgLlamaKvType" class="form-control">
                                    <option value="f16">F16 — full precision (recommended)</option>
                                    <option value="q8_0">Q8_0 — ~½ cache RAM, tiny quality cost</option>
                                    <option value="q4_0">Q4_0 — ~¼ cache RAM, more quality cost</option>
                                </select>
                            </div>

                            <div class="form-group">
                                <label>Chat History Cap: <span id="lblLlamaHistory" style="color:var(--text-main);">8</span> messages</label>
                                <div style="font-size:0.72rem; color:var(--text-muted); margin-bottom:8px;">How many past messages are included in each AI prompt. Reduced history = faster responses. Increase for better continuity.</div>
                                <input type="range" id="cfgLlamaHistory" min="2" max="16" step="2" value="8" class="form-control" style="padding:4px; accent-color:var(--accent);" oninput="document.getElementById('lblLlamaHistory').innerText = this.value">
                            </div>

                            <div class="form-group">
                                <label style="display:flex; align-items:center; gap:10px; cursor:pointer;">
                                    <input type="checkbox" id="cfgLlamaFlashAttn" style="width:18px; height:18px; accent-color:var(--accent);">
                                    <span style="color:var(--text-main); font-size:0.88rem; font-weight:600;">Flash Attention</span>
                                </label>
                                <div style="font-size:0.72rem; color:var(--text-muted); padding-left:28px; margin-top:4px;">Speeds up attention math. Enable for modern models (Llama 3, Gemma 2). Disable if you get garbled output. Applies immediately.</div>
                            </div>

                            <div class="form-group">
                                <label style="display:flex; align-items:center; gap:10px; cursor:pointer;">
                                    <input type="checkbox" id="cfgLlamaMmap" style="width:18px; height:18px; accent-color:var(--accent);" checked>
                                    <span style="color:var(--text-main); font-size:0.88rem; font-weight:600;">Memory-map model (mmap)</span>
                                </label>
                                <div style="font-size:0.72rem; color:var(--text-muted); padding-left:28px; margin-top:4px;">Loads model on-demand. Lower RAM usage. Keep ON unless you get errors. <span style="color:#f59e0b;">Reload model to apply.</span></div>
                            </div>

                        </div>
                    </div>

                    <div style="display:flex; flex-direction:column; gap:10px; margin-top:12px;">
                        <button class="btn" onclick="SettingsApp.testLlamaInference()" id="llamaTestBtn" style="display:none;">🧪 Test Inference</button>
                        <button class="btn" onclick="SettingsApp.openWeightPicker()">📂 Import GGUF Model (.gguf)</button>
                    </div>

                    <div id="llamaModelList" style="display:flex; flex-direction:column; gap:8px; margin-top:12px; border-top:1px solid var(--border); padding-top:12px;">
                        <div style="font-size:0.8rem; font-weight:700; color:var(--text-muted); text-transform:uppercase; letter-spacing:0.5px; margin-bottom:4px;">📦 Local Models</div>
                        <div id="llamaModelsContainer" style="display:flex; flex-direction:column; gap:8px;"></div>
                    </div>

                    <div id="llamaTestResult" style="display:none; margin-top:12px; background:var(--bg-input); border-radius:10px; padding:12px; font-size:0.8rem; color:var(--text-muted); max-height:150px; overflow-y:auto; word-break:break-word;"></div>
                </div>

                <!-- Content Section -->
                <div class="settings-section">
                    <div class="section-title">Content</div>
                    <div class="form-group">
                        <label style="display:flex; align-items:center; gap:10px; cursor:pointer;">
                            <input type="checkbox" id="cfgHideNsfw" style="width:18px; height:18px; accent-color:var(--accent);">
                            <span style="color:var(--text-main); font-size:0.88rem; font-weight:600;">Hide NSFW (Rebbit)</span>
                        </label>
                        <div style="font-size:0.72rem; color:var(--text-muted); padding-left:28px;">Removes the 🔞 Rebbit app from the home screen and stops its auto-posting. Your existing Rebbit posts are kept.</div>
                    </div>
                </div>

                <!-- Backup Section -->
                <div class="settings-section">
                    <div class="section-title">Data & Privacy (Backup)</div>
                    <div style="display: flex; gap: 10px;">
                        <button class="btn" onclick="SettingsApp.exportData()" style="flex: 1;">📥 Export Everything</button>
                        <label class="btn" style="flex: 1; cursor: pointer; margin: 0;">
                            📤 Import Backup
                            <input type="file" style="display:none" accept=".zip,.json" onchange="SettingsApp.importData(event)">
                        </label>
                    </div>
                    <div style="font-size:0.68rem; color:var(--text-muted); text-align:center;">Backups include all chats, characters, images, and OS settings.</div>
                </div>

                <!-- Social Auto-Post Section -->
                <div class="settings-section">
                    <div class="section-title">Social Auto-Post</div>
                    <div class="form-group">
                        <label style="display:flex; align-items:center; gap:10px; cursor:pointer;">
                            <input type="checkbox" id="cfgAutoPostEnabled" style="width:18px; height:18px; accent-color:var(--accent);">
                            <span style="color:var(--text-main); font-size:0.88rem; font-weight:600;">Enable Auto-Posting</span>
                        </label>
                        <div style="font-size:0.72rem; color:var(--text-muted); padding-left:28px;">Bots will automatically post to social feeds while the app is open</div>
                    </div>
                    <div id="autoPostDetails" style="display:none; flex-direction:column; gap:10px; padding-top:4px; border-top:1px solid var(--border);">
                        <div class="form-group">
                            <label for="cfgAutoPostInterval">Post Interval: <span id="lblAutoPostInterval" style="color:var(--text-main);">5</span> min</label>
                            <input type="range" id="cfgAutoPostInterval" min="1" max="30" step="1" value="5" class="form-control" style="padding:4px; accent-color:var(--accent);" oninput="document.getElementById('lblAutoPostInterval').innerText = this.value">
                        </div>
                        <div style="display:flex; flex-direction:column; gap:8px;">
                            <label style="display:flex; align-items:center; gap:10px; cursor:pointer;">
                                <input type="checkbox" id="cfgAutoPostUstagram" style="width:18px; height:18px; accent-color:#dd2a7b;">
                                <span style="color:var(--text-main); font-size:0.85rem;">📸 Ustagram</span>
                            </label>
                            <label style="display:flex; align-items:center; gap:10px; cursor:pointer;">
                                <input type="checkbox" id="cfgAutoPostRebbit" style="width:18px; height:18px; accent-color:#ff4500;">
                                <span style="color:var(--text-main); font-size:0.85rem;">🔞 Rebbit</span>
                            </label>
                            <label style="display:flex; align-items:center; gap:10px; cursor:pointer;">
                                <input type="checkbox" id="cfgAutoPostY" style="width:18px; height:18px; accent-color:#1da1f2;">
                                <span style="color:var(--text-main); font-size:0.85rem;">✕ Y</span>
                            </label>
                        </div>
                    </div>
                </div>

                <button class="btn btn-primary" style="margin-top: 8px; padding: 16px;" onclick="SettingsApp.saveSettings()">Save & Close</button>
            </div>

            <!-- Model Selection Modal -->
            <div id="settingsAppModelModal" style="display:none; position:fixed; top:0; left:0; width:100vw; height:100vh; background:rgba(0,0,0,0.85); z-index:9999; justify-content:center; align-items:center; padding:20px;">
                <div style="background:var(--bg-card); border:1px solid var(--border); width:100%; max-width:400px; border-radius:16px; display:flex; flex-direction:column; max-height:80vh; overflow:hidden;">
                    <div style="display:flex; justify-content:space-between; padding:16px; border-bottom:1px solid var(--border); align-items:center;">
                        <h3 style="margin:0; font-size:1rem;">Select Model</h3>
                        <button class="btn" style="width:auto; padding:4px 10px; border:none;" onclick="document.getElementById('settingsAppModelModal').style.display='none'">✕</button>
                    </div>
                    <div style="padding:12px 16px; border-bottom:1px solid var(--border);">
                        <input type="text" id="settingsAppModelSearch" class="form-control" placeholder="Search..." oninput="SettingsApp.filterModelsList()">
                    </div>
                    <div id="settingsAppModelsContainer" style="padding:16px; overflow-y:auto; display:flex; flex-direction:column; gap:8px;"></div>
                </div>
            </div>
        `;
    },

    handleProviderChange: function() {
        const provSel = document.getElementById('cfgProvider');
        const val = provSel.value;
        const urlGroup = document.getElementById('customUrlGroup');
        const guide = document.getElementById('localLlmGuide');
        const apiKeyGroup = document.getElementById('apiKeyGroup');
        const localParamsGroup = document.getElementById('localAiParamsGroup');

        urlGroup.style.display = (val === 'custom' || val === 'localllm') ? 'flex' : 'none';
        if (guide) guide.style.display = (val === 'localllm') ? 'block' : 'none';
        if (apiKeyGroup) apiKeyGroup.style.display = (val === 'localllm' || val === 'llama') ? 'none' : 'flex';
        if (localParamsGroup) localParamsGroup.style.display = (val === 'llama') ? 'block' : 'none';

        const urlInput = document.getElementById('cfgUrl');
        if (val === 'localllm' && urlInput && (!urlInput.value || urlInput.value === '')) {
            urlInput.value = 'http://127.0.0.1:8082';
            urlInput.placeholder = 'http://127.0.0.1:8082';
        } else if (val === 'custom') {
            urlInput.placeholder = 'https://api.example.com/v1';
        }
    },

    // ─── Cloud model discovery (the 🔍 button next to "Model ID") ───
    _fetchedModels: [],
    fetchAvailableModels: async function() {
        const provider = document.getElementById('cfgProvider').value;
        if (provider === 'llama') {
            OS.toast("On-device models are managed in the 'On-Device LLM' section below.", 'info');
            return;
        }

        // Prefer the key currently typed in the form; fall back to the saved secure key.
        const key = (document.getElementById('cfgKey').value || '').trim() ||
                    (window.API ? API.getApiKey() : '');

        let url = '';
        const headers = { 'Content-Type': 'application/json' };
        if (provider === 'deepinfra') {
            url = 'https://api.deepinfra.com/v1/openai/models';
            if (key) headers['Authorization'] = 'Bearer ' + key;
        } else if (provider === 'openrouter') {
            url = 'https://openrouter.ai/api/v1/models';
            if (key) headers['Authorization'] = 'Bearer ' + key;
        } else {
            // localllm or custom — derive /models from the configured base URL
            let base = (document.getElementById('cfgUrl').value || '').trim();
            if (!base) { OS.toast("Enter the Base URL first.", 'warning'); return; }
            base = base.replace(/\/$/, '').replace(/\/chat\/completions$/, '').replace(/\/$/, '');
            if (!/\/v\d+$/.test(base)) base += '/v1';
            url = base + '/models';
            if (key) headers['Authorization'] = 'Bearer ' + key;
        }

        OS.toast("Fetching models…", 'info');
        try {
            const res = await fetch(url, { headers });
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const data = await res.json();
            const raw = Array.isArray(data) ? data : (data.data || data.models || []);
            const models = raw
                .map(m => (typeof m === 'string' ? m : (m && (m.id || m.name))))
                .filter(Boolean)
                .sort((a, b) => a.localeCompare(b));
            if (!models.length) throw new Error('No models returned');
            this._fetchedModels = models;
            this.renderModelsList(models);
            const modal = document.getElementById('settingsAppModelModal');
            const search = document.getElementById('settingsAppModelSearch');
            if (search) search.value = '';
            if (modal) modal.style.display = 'flex';
            OS.toast(`Found ${models.length} models`, 'success');
        } catch (e) {
            OS.toast("Fetch failed: " + e.message, 'error');
        }
    },

    renderModelsList: function(models) {
        const c = document.getElementById('settingsAppModelsContainer');
        if (!c) return;
        if (!models || !models.length) {
            c.innerHTML = `<div style="color:var(--text-muted); text-align:center; padding:20px; font-size:0.85rem;">No matching models</div>`;
            return;
        }
        const current = (document.getElementById('cfgModel').value || '').trim();
        c.innerHTML = models.map(m => {
            const safe = m.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            const attr = m.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
            const active = m === current;
            return `
                <div class="model-item" style="cursor:pointer; ${active ? 'border-color:var(--accent); background:rgba(139,92,246,0.1);' : ''}" onclick="SettingsApp.selectModel('${attr}')">
                    <div class="model-info"><div class="model-name">${safe}</div></div>
                    <div class="model-actions"><span style="color:var(--accent); font-size:1.2rem; line-height:1;">${active ? '✓' : '＋'}</span></div>
                </div>`;
        }).join('');
    },

    filterModelsList: function() {
        const q = (document.getElementById('settingsAppModelSearch').value || '').toLowerCase().trim();
        const all = this._fetchedModels || [];
        this.renderModelsList(q ? all.filter(m => m.toLowerCase().includes(q)) : all);
    },

    selectModel: function(id) {
        const input = document.getElementById('cfgModel');
        if (input) input.value = id;
        const modal = document.getElementById('settingsAppModelModal');
        if (modal) modal.style.display = 'none';
        OS.toast("Model selected", 'success');
    },

    loadSettingsToForm: function() {
        const s = State.settings || {};
        const u = State.userProfile || { name: 'User', bio: '' };

        document.getElementById('cfgUserName').value = u.name || '';
        document.getElementById('cfgUserBio').value = u.bio || '';
        document.getElementById('cfgProvider').value = s.provider || 'deepinfra';
        document.getElementById('cfgUrl').value = s.url || '';
        document.getElementById('cfgModel').value = s.model || '';

        // Load API key from secure storage
        if (window.AndroidBridge && window.AndroidBridge.getSecureString) {
            const apiKey = window.AndroidBridge.getSecureString('api_key') || '';
            document.getElementById('cfgKey').value = apiKey;
        }

        const temp = s.temperature !== undefined ? s.temperature : 0.7;
        const maxTok = s.maxTokens !== undefined ? s.maxTokens : 512;
        const topK = s.topK !== undefined ? s.topK : 40;
        const topP = s.topP !== undefined ? s.topP : 0.9;
        const template = s.llamaTemplate || 'chatml';

        document.getElementById('cfgTemperature').value = temp;
        document.getElementById('lblTemperature').innerText = temp.toFixed(2);
        document.getElementById('cfgMaxTokens').value = maxTok;
        document.getElementById('lblMaxTokens').innerText = maxTok;
        document.getElementById('cfgTopK').value = topK;
        document.getElementById('lblTopK').innerText = topK;
        document.getElementById('cfgTopP').value = topP;
        document.getElementById('lblTopP').innerText = topP.toFixed(2);
        document.getElementById('cfgLlamaTemplate').value = template;

        const threads = s.llamaThreads !== undefined ? s.llamaThreads : 8;
        const gpuLayers = s.llamaGpuLayers !== undefined ? s.llamaGpuLayers : 99;
        const ctx     = s.llamaCtx || 2048;
        const histCap = s.llamaHistoryCap || 6;
        const kvType  = s.llamaKvType || 'f16';
        document.getElementById('cfgLlamaThreads').value = threads;
        document.getElementById('lblLlamaThreads').innerText = threads;
        document.getElementById('cfgLlamaGpu').value = gpuLayers;
        document.getElementById('lblLlamaGpu').innerText = gpuLayers;
        document.getElementById('cfgLlamaCtx').value = ctx;
        document.getElementById('cfgLlamaHistory').value = histCap;
        document.getElementById('lblLlamaHistory').innerText = histCap;
        document.getElementById('cfgLlamaKvType').value = kvType;
        document.getElementById('cfgLlamaFlashAttn').checked = s.llamaFlashAttn !== false;
        document.getElementById('cfgLlamaMmap').checked = s.llamaMmap !== false;
        document.getElementById('cfgLlamaBackend').value = s.llamaBackend || 'cpu';

        const apEnabled = document.getElementById('cfgAutoPostEnabled');
        const apDetails = document.getElementById('autoPostDetails');
        if (apEnabled) {
            apEnabled.checked = s.autoPostEnabled || false;
            apEnabled.onchange = () => { if (apDetails) apDetails.style.display = apEnabled.checked ? 'flex' : 'none'; };
        }
        if (apDetails) apDetails.style.display = (s.autoPostEnabled) ? 'flex' : 'none';

        const apInterval = document.getElementById('cfgAutoPostInterval');
        if (apInterval) apInterval.value = s.autoPostInterval || 5;
        const apIntervalLabel = document.getElementById('lblAutoPostInterval');
        if (apIntervalLabel) apIntervalLabel.innerText = s.autoPostInterval || 5;

        const setChecked = (id, val) => { const el = document.getElementById(id); if (el) el.checked = val; };
        setChecked('cfgAutoPostUstagram', s.autoPostUstagram !== false);
        setChecked('cfgAutoPostRebbit', s.autoPostRebbit !== false);
        setChecked('cfgAutoPostY', s.autoPostY !== false);
        setChecked('cfgHideNsfw', s.hideNsfw || false);

        this.renderPromptList();
        this.handleProviderChange();
        setTimeout(() => this.updateLlamaStatus(), 100);
    },

    renderPromptList: function() {
        const s = State.settings;
        const select = document.getElementById('cfgActivePrompt');
        select.innerHTML = s.systemPrompts.map(p => `<option value="${p.id}" ${p.id === s.activePromptId ? 'selected' : ''}>${p.name}</option>`).join('');
        this.onPromptSelectChange();
        // Re-skin dropdowns (the prompt list options just changed).
        if (window.OS && OS.enhanceSelects) OS.enhanceSelects(this.container);
    },

    onPromptSelectChange: function() {
        const select = document.getElementById('cfgActivePrompt');
        const editor = document.getElementById('promptEditor');
        const prompt = State.settings.systemPrompts.find(p => p.id === select.value);
        if (prompt) {
            editor.style.display = 'flex';
            document.getElementById('editPromptName').value = prompt.name;
            document.getElementById('editPromptContent').value = prompt.content;
            State.settings.activePromptId = prompt.id;
        } else {
            editor.style.display = 'none';
        }
    },

    saveCurrentPrompt: function() {
        const id = document.getElementById('cfgActivePrompt').value;
        const prompt = State.settings.systemPrompts.find(p => p.id === id);
        if (prompt) {
            prompt.name = document.getElementById('editPromptName').value;
            prompt.content = document.getElementById('editPromptContent').value;
            this.renderPromptList();
        }
    },

    addNewPrompt: function() {
        OS.prompt("Enter prompt name:", "New Prompt", (name) => {
            if (!name) return;
            const id = 'p' + Date.now();
            State.settings.systemPrompts.push({ id, name, content: "You are a helpful assistant." });
            State.settings.activePromptId = id;
            this.renderPromptList();
        });
    },

    deleteCurrentPrompt: function() {
        if (State.settings.systemPrompts.length <= 1) { OS.toast("You must have at least one prompt.", 'warning'); return; }
        const id = document.getElementById('cfgActivePrompt').value;
        State.settings.systemPrompts = State.settings.systemPrompts.filter(p => p.id !== id);
        State.settings.activePromptId = State.settings.systemPrompts[0].id;
        this.renderPromptList();
    },

    saveSettings: function() {
        const prevProvider = (State.settings && State.settings.provider) || 'deepinfra';
        State.userProfile = {
            name: document.getElementById('cfgUserName').value || 'User',
            bio: document.getElementById('cfgUserBio').value || ''
        };

        State.settings.provider = document.getElementById('cfgProvider').value;
        State.settings.url = document.getElementById('cfgUrl').value.replace(/\/$/, '');
        State.settings.model = document.getElementById('cfgModel').value;

        // Store API key securely in native storage (never in state.json)
        const apiKey = document.getElementById('cfgKey').value;
        if (window.AndroidBridge && window.AndroidBridge.setSecureString) {
            window.AndroidBridge.setSecureString('api_key', apiKey);
        }

        State.settings.temperature = parseFloat(document.getElementById('cfgTemperature').value) || 0.7;
        State.settings.maxTokens = parseInt(document.getElementById('cfgMaxTokens').value) || 512;
        State.settings.topK = parseInt(document.getElementById('cfgTopK').value) || 40;
        State.settings.topP = parseFloat(document.getElementById('cfgTopP').value) || 0.9;
        State.settings.llamaTemplate = document.getElementById('cfgLlamaTemplate').value || 'chatml';

        State.settings.llamaThreads     = parseInt(document.getElementById('cfgLlamaThreads').value) || 4;
        const _gv = parseInt(document.getElementById('cfgLlamaGpu').value);
        State.settings.llamaGpuLayers   = isNaN(_gv) ? 99 : _gv;
        State.settings.llamaCtx         = parseInt(document.getElementById('cfgLlamaCtx').value) || 2048;
        State.settings.llamaHistoryCap  = parseInt(document.getElementById('cfgLlamaHistory').value) || 8;
        State.settings.llamaFlashAttn   = document.getElementById('cfgLlamaFlashAttn').checked;
        State.settings.llamaMmap        = document.getElementById('cfgLlamaMmap').checked;
        State.settings.llamaMlock       = false;  // mlock control removed; never lock model in RAM
        State.settings.llamaBackend     = document.getElementById('cfgLlamaBackend').value || 'cpu';
        State.settings.llamaKvType      = document.getElementById('cfgLlamaKvType').value || 'f16';

        // Effective offload layers + backend id (0=CPU, 1=NPU/Hexagon, 2=GPU/Vulkan).
        let _effGpu = 0, _backend = 0;
        if (State.settings.llamaBackend === 'npu') {
            _effGpu = State.settings.llamaGpuLayers || 99; _backend = 1;
        } else if (State.settings.llamaBackend === 'gpu') {
            _effGpu = State.settings.llamaGpuLayers || 99; _backend = 2;
        }

        // kvType id: 0=F16, 1=Q8_0, 2=Q4_0
        let _kv = 0;
        if (State.settings.llamaKvType === 'q8_0') _kv = 1;
        else if (State.settings.llamaKvType === 'q4_0') _kv = 2;

        if (window.AndroidBridge && window.AndroidBridge.llamaSetEngineParams) {
            window.AndroidBridge.llamaSetEngineParams(
                State.settings.llamaCtx || 2048,
                State.settings.llamaThreads || 4,
                State.settings.llamaFlashAttn || false,
                State.settings.llamaMmap !== false,
                State.settings.llamaMlock || false,
                _effGpu,
                _backend,
                _kv
            );
        }

        State.settings.autoPostEnabled = document.getElementById('cfgAutoPostEnabled').checked;
        State.settings.autoPostInterval = parseInt(document.getElementById('cfgAutoPostInterval').value) || 5;
        State.settings.autoPostUstagram = document.getElementById('cfgAutoPostUstagram').checked;
        State.settings.autoPostRebbit = document.getElementById('cfgAutoPostRebbit').checked;
        State.settings.autoPostY = document.getElementById('cfgAutoPostY').checked;
        State.settings.hideNsfw = document.getElementById('cfgHideNsfw').checked;

        // Free phone RAM when switching away from the on-device engine.
        if (prevProvider === 'llama' && State.settings.provider !== 'llama'
            && window.AndroidBridge && window.AndroidBridge.llamaUnloadModel) {
            try { window.AndroidBridge.llamaUnloadModel(); } catch (e) {}
        }

        State.save();
        OS.toast("Settings saved!", 'success');
        OS.goHome();
    },

    updateLlamaStatus: function() {
        const statusLabel = document.getElementById('llamaStatusLabel');
        const pathDisplay = document.getElementById('llamaPathDisplay');
        const testBtn = document.getElementById('llamaTestBtn');

        if (!window.AndroidBridge || !window.AndroidBridge.llamaIsModelLoaded) {
            statusLabel.innerText = "❌ Bridge unavailable";
            statusLabel.style.color = "#ef4444";
            return;
        }

        const isLoaded = window.AndroidBridge.llamaIsModelLoaded();
        const modelPath = window.AndroidBridge.llamaGetLoadedModelPath();

        if (isLoaded) {
            statusLabel.innerText = "✓ Loaded";
            statusLabel.style.color = "#22c55e";
            if (modelPath) {
                const name = modelPath.split('/').pop();
                pathDisplay.innerHTML = `<span style="color:var(--text-muted);">Active:</span> ${name}`;
            }
            if (testBtn) testBtn.style.display = 'block';
        } else {
            statusLabel.innerText = "⊘ Not loaded";
            statusLabel.style.color = "#f59e0b";
            pathDisplay.innerHTML = "";
            if (testBtn) testBtn.style.display = 'none';
        }

        this.refreshLlamaModels();
    },

    refreshLlamaModels: function() {
        if (!window.AndroidBridge || !window.AndroidBridge.llamaListModels) return;
        const container = document.getElementById('llamaModelsContainer');
        if (!container) return;

        try {
            const modelsJson = window.AndroidBridge.llamaListModels();
            const models = JSON.parse(modelsJson);
            const activePath = window.AndroidBridge.llamaGetLoadedModelPath() || "";
            const activeName = activePath.split('/').pop();

            if (models.length === 0) {
                container.innerHTML = `<div style="color:var(--text-muted); font-size:0.75rem; text-align:center; padding:10px;">No local models imported</div>`;
                return;
            }

            const selected = (State.settings.lastLlamaModel && models.includes(State.settings.lastLlamaModel))
                ? State.settings.lastLlamaModel
                : (activeName || models[0]);

            // Active-model picker. Switching restarts the app so the new model loads
            // from a clean process (avoids double-load / poisoned-backend state).
            const dropdown = `
                <label for="cfgLlamaModelSelect" style="font-size:0.8rem; color:var(--text-muted); display:block; margin-bottom:4px;">Active Model</label>
                <select id="cfgLlamaModelSelect" class="form-control" onchange="SettingsApp.onModelChange(this.value)">
                    ${models.map(m => {
                        const bad = /q4_0_(4_4|4_8|8_8)/i.test(m);  // deprecated repack formats — can't load
                        const tag = bad ? '  ⚠ unsupported format' : (m === activeName ? '  ✓ loaded' : '');
                        return `<option value="${m}" ${m === selected ? 'selected' : ''}>${m}${tag}</option>`;
                    }).join('')}
                </select>
                <div style="font-size:0.72rem; color:var(--text-muted); margin:6px 0 10px;">Switching restarts the app to load the new model cleanly.</div>
            `;

            const list = models.map(m => {
                const isActive = m === activeName;
                return `
                    <div class="model-item" style="${isActive ? 'border-color:var(--accent); background:rgba(139,92,246,0.1);' : ''}">
                        <div class="model-info">
                            <div class="model-name">${m}</div>
                            ${isActive ? '<div style="font-size:0.65rem; color:var(--accent); font-weight:700;">ACTIVE</div>' : ''}
                        </div>
                        <div class="model-actions">
                            <button class="action-btn" onclick="SettingsApp.deleteLlamaModel('${m}')" title="Delete" style="color:var(--danger)">🗑</button>
                        </div>
                    </div>
                `;
            }).join('');

            container.innerHTML = dropdown + list;
            // The dropdown is injected dynamically (after the app-launch enhanceSelects
            // pass), so theme it now or it renders as a raw native Android <select>.
            if (window.OS && OS.enhanceSelects) {
                setTimeout(() => { try { OS.enhanceSelects(container, true); } catch (e) {} }, 0);
            }
        } catch (e) {
            console.error("Failed to list models", e);
        }
    },

    // Switch the active GGUF model. Restarts the app (like the hardware switch) so
    // the new model loads from a clean process instead of an in-place reload.
    onModelChange: function(name) {
        const cur = State.settings.lastLlamaModel || '';
        if (!name || name === cur) return;
        OS.confirm(
            `Switch active model to <b>${name}</b>?<br><br>This restarts the app to load it cleanly.`,
            (ok) => {
                if (!ok) { this.refreshLlamaModels(); return; }
                State.settings.lastLlamaModel = name;
                State.save();
                if (window.AndroidBridge && window.AndroidBridge.restartApp) {
                    OS.toast(`Loading ${name}…`, 'info');
                    setTimeout(() => window.AndroidBridge.restartApp(), 400);
                } else {
                    OS.toast('Restart the app to load the new model.', 'warning');
                }
            },
            { title: 'Switch model?', confirmText: 'Restart' }
        );
    },

    onBackendChange: function(newBackend) {
        const prev = State.settings.llamaBackend || 'cpu';
        if (newBackend === prev) return;
        // The Hexagon NPU can only run Q4_0 / Q8_0 / MXFP4 weights. A k-quant model
        // (Q4_K_M, Q5_K, Q6_K, …) makes the DSP hang (FastRPC 0xc). Warn early so the
        // user doesn't think it's broken — it's a model-format limitation.
        let kvNote = '';
        if (newBackend === 'npu') {
            const m = (State.settings.lastLlamaModel || '');
            if (/q[2-8]_k/i.test(m)) {
                kvNote = `<br><br>⚠️ <b>Your model looks like a k-quant</b> (<code>${m}</code>). The NPU only runs <b>Q4_0 / Q8_0</b> models — k-quants will hang. Use a Q4_0 GGUF for NPU.`;
            } else {
                kvNote = `<br><br>Note: the NPU only supports <b>Q4_0 / Q8_0 / MXFP4</b> models.`;
            }
        }
        OS.confirm(
            `Switching inference hardware to <b>${newBackend.toUpperCase()}</b> re-initializes the engine, which requires <b>restarting the app</b>.${kvNote}<br><br>Any unsaved settings changes will be lost. Restart now?`,
            (ok) => {
                if (!ok) { document.getElementById('cfgLlamaBackend').value = prev; return; }
                State.settings.llamaBackend = newBackend;
                State.save();
                if (window.AndroidBridge && window.AndroidBridge.restartApp) {
                    OS.toast(`Restarting on ${newBackend.toUpperCase()}…`, 'info');
                    setTimeout(() => window.AndroidBridge.restartApp(), 400);
                } else {
                    OS.toast('Restart the app to apply the new hardware.', 'warning');
                }
            },
            { title: 'Switch hardware?', confirmText: 'Restart', danger: true }
        );
    },

    autoDetectTemplate: function(modelName) {
        const lower = modelName.toLowerCase();
        let detected = "";
        if (lower.includes("gemma")) detected = "gemma2";
        else if (/llama[-_ ]?3/.test(lower)) detected = "llama3";
        else if (lower.includes("llama")) detected = "mistral";
        else if (lower.includes("mistral")) detected = "mistral";
        else if (lower.includes("qwen") || lower.includes("phi")) detected = "chatml";

        if (detected) {
            const current = document.getElementById('cfgLlamaTemplate').value;
            if (current !== detected) {
                OS.toast(`Detected ${modelName} → switching to ${detected} template`, 'info');
                document.getElementById('cfgLlamaTemplate').value = detected;
                State.settings.llamaTemplate = detected;
                State.save();
                if (window.OS && OS.syncSelect) OS.syncSelect('cfgLlamaTemplate');
            }
        }
    },

    loadLlamaModel: function(name) {
        if (!window.AndroidBridge || !window.AndroidBridge.llamaLoadModelByName) return;

        this.autoDetectTemplate(name);

        // Apply current engine params (incl. NPU/CPU offload) BEFORE loading,
        // so the ⚡ button respects the Inference Hardware selection even on a
        // fresh session where Save hasn't run yet.
        if (window.AndroidBridge.llamaSetEngineParams) {
            const s = State.settings || {};
            let effGpu = 0, backend = 0;
            if (s.llamaBackend === 'npu') { effGpu = s.llamaGpuLayers || 99; backend = 1; }
            else if (s.llamaBackend === 'gpu') { effGpu = s.llamaGpuLayers || 99; backend = 2; }

            let kv = 0;
            if (s.llamaKvType === 'q8_0') kv = 1;
            else if (s.llamaKvType === 'q4_0') kv = 2;

            window.AndroidBridge.llamaSetEngineParams(
                s.llamaCtx || 2048, s.llamaThreads || 4, s.llamaFlashAttn !== false,
                s.llamaMmap !== false, s.llamaMlock || false, effGpu, backend, kv);
        }

        OS.toast(`Loading ${name}...`, 'info');
        setTimeout(() => {
            const ok = window.AndroidBridge.llamaLoadModelByName(name);
            if (ok) {
                State.settings.lastLlamaModel = name;
                State.save();
                OS.toast("Model loaded!", 'success');
                // Prefer the model's own chat template over the filename guess.
                const det = OS.syncLlamaTemplateFromModel && OS.syncLlamaTemplateFromModel();
                if (det) OS.toast("Chat template (from model): " + det, 'info');
                this.updateLlamaStatus();
            } else {
                OS.toast("Failed to load model. File might be too large for your RAM.", 'error');
            }
        }, 100);
    },

    deleteLlamaModel: function(name) {
        OS.confirm(`Delete ${name}? This cannot be undone.`, (ok) => {
            if (ok === false) return; // Handle explicitly if ok is passed as false
            if (window.AndroidBridge && window.AndroidBridge.llamaDeleteModel) {
                const deleted = window.AndroidBridge.llamaDeleteModel(name);
                if (deleted) {
                    OS.toast("Model deleted", 'success');
                    this.updateLlamaStatus();
                } else {
                    OS.toast("Failed to delete", 'error');
                }
            }
        });
    },

    testLlamaInference: function() {
        const resultDiv = document.getElementById('llamaTestResult');
        resultDiv.style.display = 'block';
        resultDiv.innerHTML = '<div style="color:var(--accent); font-weight:600;">⏳ Generating response...</div>';

        if (!window.AndroidBridge || !window.AndroidBridge.llamaIsModelLoaded || !window.AndroidBridge.llamaIsModelLoaded()) {
            resultDiv.innerHTML = '<span style="color:#ef4444;">Error: model not loaded</span>';
            return;
        }

        const s = State.settings || {};
        const template = s.llamaTemplate || 'chatml';
        let testPrompt = '';

        // Generate test prompt based on selected template
        switch (template) {
            case 'llama3':
                testPrompt = `<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nYou are a helpful assistant.\n<|eot_id|><|start_header_id|>user<|end_header_id|>\n\nTranslate to Spanish: Hello, how are you?\n<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n`;
                break;
            case 'gemma':
            case 'gemma2':
                testPrompt = `<bos><start_of_turn>user\nYou are a helpful assistant.\n\nTranslate to Spanish: Hello, how are you?<end_of_turn>\n<start_of_turn>model\n`;
                break;
            case 'mistral':
                testPrompt = `[INST] <<SYS>>\nYou are a helpful assistant.\n<</SYS>>\n\nTranslate to Spanish: Hello, how are you? [/INST] `;
                break;
            case 'alpaca':
                testPrompt = `### System:\nYou are a helpful assistant.\n\n### Instruction:\nTranslate to Spanish: Hello, how are you?\n\n### Response:\n`;
                break;
            default: // chatml
                testPrompt = `<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\nTranslate to Spanish: Hello, how are you?<|im_end|>\n<|im_start|>assistant\n`;
        }

        const cbId = (window._llamaCbCounter = ((window._llamaCbCounter || 0) + 1) & 0x7fffffff);
        if (!window._llamaToken) window._llamaToken = {};
        if (!window._llamaDone)  window._llamaDone  = {};

        let accumulated = '';
        let tokenCount = 0;
        const startTime = Date.now();

        window._llamaToken[cbId] = (token) => {
            accumulated += token;
            tokenCount++;
            resultDiv.innerHTML = `<div style="color:var(--text-muted); font-size:0.85rem; line-height:1.5;">${accumulated}</div>`;
        };
        window._llamaDone[cbId] = () => {
            const elapsed = (Date.now() - startTime) / 1000;
            const tokensPerSec = (tokenCount / elapsed).toFixed(2);
            const html = `
                <div style="border-bottom:1px solid var(--border); padding-bottom:12px; margin-bottom:12px;">
                    <div style="color:var(--text-main); font-size:0.85rem; line-height:1.5; word-break:break-word;">${accumulated || '(empty)'}</div>
                </div>
                <div style="display:grid; grid-template-columns:1fr 1fr; gap:12px; font-size:0.8rem;">
                    <div style="background:rgba(139,92,246,0.1); border-radius:8px; padding:10px;">
                        <div style="color:var(--text-muted); font-size:0.7rem; margin-bottom:4px;">⏱ Time</div>
                        <div style="color:var(--accent); font-weight:700; font-size:0.9rem;">${elapsed.toFixed(2)}s</div>
                    </div>
                    <div style="background:rgba(139,92,246,0.1); border-radius:8px; padding:10px;">
                        <div style="color:var(--text-muted); font-size:0.7rem; margin-bottom:4px;">📊 Tokens/sec</div>
                        <div style="color:var(--accent); font-weight:700; font-size:0.9rem;">${tokensPerSec}</div>
                    </div>
                </div>
            `;
            resultDiv.innerHTML = html;
        };

        window.AndroidBridge.llamaInferenceAsync(testPrompt, 80, cbId, s.temperature || 0.7, s.topK || 40, s.topP || 0.9);
    },

    toggleHelp: function() {
        const panel = document.getElementById('helpPanel');
        const chevron = document.getElementById('helpChevron');
        if (!panel) return;
        const open = panel.style.display !== 'none';
        panel.style.display = open ? 'none' : 'flex';
        if (chevron) chevron.innerText = open ? '▶' : '▼';
    },

    toggleEngineSettings: function() {
        const panel = document.getElementById('engineSettingsPanel');
        const chevron = document.getElementById('engineSettingsChevron');
        if (!panel) return;
        const isOpen = panel.style.display !== 'none';
        panel.style.display = isOpen ? 'none' : 'flex';
        chevron.innerText = isOpen ? '▶' : '▼';
    },

    openWeightPicker: function() {
        if (!window.AndroidBridge || !window.AndroidBridge.llamaOpenFilePicker) {
            OS.toast("Bridge not available", "error");
            return;
        }
        window.AndroidBridge.llamaOpenFilePicker();
    },

    exportData: async function() {
        try {
            if (typeof JSZip === 'undefined') throw new Error("JSZip not loaded");
            OS.toast("Preparing backup...", "info");
            const data = JSON.parse(JSON.stringify(State));
            const zip = new JSZip();
            zip.file('backup.json', JSON.stringify(data, null, 2));
            let images = [];
            if (window.ImageDB) images = await window.ImageDB.getAll();
            if (images && images.length > 0) {
                const imgFolder = zip.folder('images');
                for (const img of images) {
                    let content = img.data;
                    let ext = 'png';
                    const match = content.match(/^data:image\/(\w+);base64,(.+)$/);
                    if (match) { ext = match[1]; content = match[2]; }
                    imgFolder.file(`${img.id}.${ext}`, content, { base64: true });
                }
            }
            const base64Zip = await zip.generateAsync({ type: 'base64', compression: 'DEFLATE' });
            if (window.AndroidBridge && typeof window.AndroidBridge.startBackup === 'function') {
                const backupId = window.AndroidBridge.startBackup();
                const chunkSize = 300 * 1024;
                for (let i = 0; i < base64Zip.length; i += chunkSize) {
                    window.AndroidBridge.appendBackupChunk(backupId, base64Zip.substring(i, i + chunkSize));
                }
                window.AndroidBridge.finishBackup(backupId, '.zip');
            } else {
                const dataUrl = 'data:application/zip;base64,' + base64Zip;
                const a = document.createElement('a');
                a.href = dataUrl; a.download = `fancy_ai_backup_${Date.now()}.zip`;
                document.body.appendChild(a); a.click(); document.body.removeChild(a);
            }
            OS.toast("Backup exported successfully", 'success');
        } catch(e) { console.error("Export failed", e); OS.toast("Export failed: " + e.message, 'error'); }
    },
    importData: function(event) {
        const file = event.target.files[0];
        if(!file) return;
        OS.toast("Importing data...", "info");
        const reader = new FileReader();
        reader.onload = async (e) => {
            try {
                if (file.name.endsWith('.zip')) {
                    const zip = await JSZip.loadAsync(e.target.result);
                    const backupFile = zip.file('backup.json');
                    if (!backupFile) throw new Error('Invalid Backup: backup.json missing');
                    const data = JSON.parse(await backupFile.async('text'));
                    Object.assign(State, data);
                    const imagesFolder = zip.folder('images');
                    if (imagesFolder && window.ImageDB) {
                        const files = Object.keys(imagesFolder.files);
                        for (const filename of files) {
                            const zipFile = imagesFolder.files[filename];
                            if (zipFile.dir) continue;
                            const id = filename.split('/').pop().split('.')[0];
                            const ext = filename.split('.').pop();
                            const b64 = await zipFile.async('base64');
                            await window.ImageDB.save(id, `data:image/${ext};base64,${b64}`);
                        }
                    }
                    State.save();
                    OS.toast("Import Successful! Reloading...", 'success');
                    setTimeout(() => location.reload(), 1500);
                } else {
                    const data = JSON.parse(e.target.result);
                    Object.assign(State, data);
                    State.save();
                    OS.toast("Import Successful!", 'success');
                    setTimeout(() => location.reload(), 1000);
                }
            } catch(err) { console.error("Import error", err); OS.toast("Import Failed: " + err.message, 'error'); }
        };
        if (file.name.endsWith('.zip')) reader.readAsArrayBuffer(file);
        else reader.readAsText(file);
    }
};

window.SettingsApp = SettingsApp;
