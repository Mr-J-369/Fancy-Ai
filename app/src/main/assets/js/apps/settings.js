/**
 * settings.js
 * Text LLM Core Configuration & System Maintenance Module
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
                    padding-bottom: 100px;
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
            `;
            document.head.appendChild(style);
        }

        this.container.innerHTML = `
            <div class="settings-wrap">
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
                            <option value="deepinfra">DeepInfra</option>
                            <option value="openrouter">OpenRouter</option>
                            <option value="localllm">Local LLM (On-Device)</option>
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

                <!-- Backup Section -->
                <div class="settings-section">
                    <div class="section-title">Data & Privacy</div>
                    <div style="display: flex; gap: 10px;">
                        <button class="btn" onclick="SettingsApp.exportData()" style="flex: 1;">📥 Export</button>
                        <label class="btn" style="flex: 1; cursor: pointer; margin: 0;">
                            📤 Import
                            <input type="file" style="display:none" accept=".zip,.json" onchange="SettingsApp.importData(event)">
                        </label>
                    </div>
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

                <!-- Autonomous Life Section -->
                <div class="settings-section">
                    <div class="section-title">Autonomous Life</div>
                    <div class="form-group">
                        <label style="display:flex; align-items:center; gap:10px; cursor:pointer;">
                            <input type="checkbox" id="cfgAutonomousEnabled" style="width:18px; height:18px; accent-color:var(--accent);">
                            <span style="color:var(--text-main); font-size:0.88rem; font-weight:600;">Enable Autonomous Thoughts</span>
                        </label>
                        <div style="font-size:0.72rem; color:var(--text-muted); padding-left:28px;">Bots will occasionally think privately or message you when you are away. (Increases API usage)</div>
                    </div>
                </div>

                <!-- Troubleshooting Section -->
                <div class="settings-section">
                    <div class="section-title">System Health & Maintenance</div>

                    <div class="form-group" style="border-bottom: 1px solid var(--border); padding-bottom: 12px; margin-bottom: 4px;">
                        <label style="color:var(--text-main); font-weight:700;">System Check</label>
                        <div style="font-size:0.7rem; color:var(--text-muted); margin-bottom:8px;">View technical stats about your characters, messages, and app state.</div>
                        <button class="btn" onclick="SettingsApp.diagnoseSystem()">🔍 View System Report</button>
                    </div>

                    <div class="form-group" style="border-bottom: 1px solid var(--border); padding-bottom: 12px; margin-bottom: 4px;">
                        <label style="color:var(--text-main); font-weight:700;">Fix Gallery Issues</label>
                        <div style="font-size:0.7rem; color:var(--text-muted); margin-bottom:8px;">If images are missing from your gallery but still on your phone, this will find them.</div>
                        <button class="btn" onclick="SettingsApp.restoreRegistry()" style="color:var(--accent);">🔧 Recover Missing Images</button>
                    </div>

                    <div class="form-group" style="border-bottom: 1px solid var(--border); padding-bottom: 12px; margin-bottom: 4px;">
                        <label style="color:var(--text-main); font-weight:700;">Reset Notifications</label>
                        <div style="font-size:0.7rem; color:var(--text-muted); margin-bottom:8px;">Clears all red unread count bubbles from the home screen icons.</div>
                        <button class="btn" onclick="SettingsApp.resetBadges()" style="color:var(--danger);">🧼 Clear All Badges</button>
                    </div>

                    <div class="form-group">
                        <label style="color:var(--text-main); font-weight:700;">Clean Up Storage</label>
                        <div style="font-size:0.7rem; color:var(--text-muted); margin-bottom:8px;">Deletes leftover data from characters you have already deleted.</div>
                        <button class="btn" onclick="SettingsApp.clearOrphanData()" style="color:var(--danger);">🧹 Clean Deleted Character Data</button>
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

        // Show URL field for custom and localllm
        urlGroup.style.display = (val === 'custom' || val === 'localllm') ? 'flex' : 'none';

        // Show setup guide only for localllm
        if (guide) guide.style.display = (val === 'localllm') ? 'block' : 'none';

        // Hide API key for localllm (not needed)
        if (apiKeyGroup) apiKeyGroup.style.display = (val === 'localllm') ? 'none' : 'flex';

        // Auto-fill default URL for Local LLM if field is empty or still has default
        const urlInput = document.getElementById('cfgUrl');
        if (val === 'localllm' && urlInput && (!urlInput.value || urlInput.value === '')) {
            urlInput.value = 'http://127.0.0.1:8082';
            urlInput.placeholder = 'http://127.0.0.1:8082';
        } else if (val === 'custom') {
            urlInput.placeholder = 'https://api.example.com/v1';
        } else if (val === 'localllm') {
            urlInput.placeholder = 'http://127.0.0.1:8082';
        }
    },

    loadSettingsToForm: function() {
        const s = State.settings || {};
        const u = State.userProfile || { name: 'User', bio: '' };
        
        document.getElementById('cfgUserName').value = u.name || '';
        document.getElementById('cfgUserBio').value = u.bio || '';
        document.getElementById('cfgProvider').value = s.provider || 'deepinfra';
        document.getElementById('cfgUrl').value = s.url || '';
        document.getElementById('cfgKey').value = s.key || '';
        document.getElementById('cfgModel').value = s.model || '';

        // Auto-post settings — load INTO form
        const apEnabled = document.getElementById('cfgAutoPostEnabled');
        const apDetails = document.getElementById('autoPostDetails');
        const apInterval = document.getElementById('cfgAutoPostInterval');
        const apIntervalLabel = document.getElementById('lblAutoPostInterval');
        if (apEnabled) {
            apEnabled.checked = s.autoPostEnabled || false;
            apEnabled.onchange = () => {
                if (apDetails) apDetails.style.display = apEnabled.checked ? 'flex' : 'none';
            };
        }
        if (apDetails) apDetails.style.display = (s.autoPostEnabled) ? 'flex' : 'none';
        if (apInterval) apInterval.value = s.autoPostInterval || 5;
        if (apIntervalLabel) apIntervalLabel.innerText = s.autoPostInterval || 5;

        const setChecked = (id, val) => { const el = document.getElementById(id); if (el) el.checked = val; };
        setChecked('cfgAutoPostUstagram', s.autoPostUstagram !== false);
        setChecked('cfgAutoPostRebbit', s.autoPostRebbit !== false);
        setChecked('cfgAutoPostY', s.autoPostY !== false);
        setChecked('cfgAutonomousEnabled', s.autonomousEnabled === true);

        this.renderPromptList();
        this.handleProviderChange();
    },

    renderPromptList: function() {
        const s = State.settings;
        const select = document.getElementById('cfgActivePrompt');
        select.innerHTML = s.systemPrompts.map(p => `<option value="${p.id}" ${p.id === s.activePromptId ? 'selected' : ''}>${p.name}</option>`).join('');
        this.onPromptSelectChange();
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
        State.userProfile = {
            name: document.getElementById('cfgUserName').value || 'User',
            bio: document.getElementById('cfgUserBio').value || ''
        };

        State.settings.provider = document.getElementById('cfgProvider').value;
        State.settings.url = document.getElementById('cfgUrl').value.replace(/\/$/, '');
        State.settings.key = document.getElementById('cfgKey').value;
        State.settings.model = document.getElementById('cfgModel').value;

        // Auto-post settings
        State.settings.autoPostEnabled = document.getElementById('cfgAutoPostEnabled').checked;
        State.settings.autoPostInterval = parseInt(document.getElementById('cfgAutoPostInterval').value) || 5;
        State.settings.autoPostUstagram = document.getElementById('cfgAutoPostUstagram').checked;
        State.settings.autoPostRebbit = document.getElementById('cfgAutoPostRebbit').checked;
        State.settings.autoPostY = document.getElementById('cfgAutoPostY').checked;
        State.settings.autonomousEnabled = document.getElementById('cfgAutonomousEnabled').checked;

        State.save();
        OS.toast("Settings saved!", 'success');
        OS.goHome();
    },

    diagnoseSystem: function() {
        const charCount = (State.characters || []).length;
        const sessionCount = Object.keys(State.sessions || {}).length;

        let totalMsgs = 0;
        for (let id in State.sessions) totalMsgs += (State.sessions[id] || []).length;

        let report = `Your system is running normally.\n\n`;
        report += `• Characters Created: ${charCount}\n`;
        report += `• Active Chat Sessions: ${sessionCount}\n`;
        report += `• Total Recent Messages: ${totalMsgs}\n`;
        report += `• AI Provider: ${State.settings?.provider || 'Not Set'}\n`;
        report += `• Auto-Posting: ${State.settings?.autoPostEnabled ? 'Active' : 'Disabled'}\n`;

        // Find orphans
        const validCharIds = new Set((State.characters || []).map(c => c.id));
        let orphans = 0;
        for (const id in State.sessions) { if (!validCharIds.has(id)) orphans++; }

        if (orphans > 0) {
            report += `\nNote: Found data for ${orphans} deleted characters. You can use "Clean Up Storage" to remove this.`;
        }

        OS.confirm(report, null, { title: 'System Report', confirmText: 'Done' });
    },

    resetBadges: function() {
        State.lastReadTimestamps = {};
        State.chatReadTimestamps = {};
        State.save();
        if (window.OS && OS.updateBadges) OS.updateBadges();
        OS.toast("All badges cleared", 'success');
    },

    restoreRegistry: async function() {
        if (!window.ImageDB || !window.ImageDB.rebuildRegistryFromDisk) {
            OS.toast("Media engine not ready", 'error');
            return;
        }

        OS.confirm("This will scan your disk for orphaned images and add them back to your gallery. Proceed?", async () => {
            const count = await window.ImageDB.rebuildRegistryFromDisk();
            if (count > 0) {
                OS.toast(`Restored ${count} missing images!`, 'success');
            } else {
                OS.toast("No missing images found.", 'success');
            }
        }, { title: 'Rebuild Registry' });
    },

    clearOrphanData: function() {
        const validCharIds = new Set((State.characters || []).map(c => c.id));
        let cleaned = 0;

        for (const id in State.sessions) {
            if (!validCharIds.has(id)) {
                delete State.sessions[id];
                cleaned++;
            }
        }

        for (const id in State.memories) {
            if (!validCharIds.has(id)) {
                delete State.memories[id];
            }
        }

        if (cleaned > 0) {
            State.save();
            if (window.OS && OS.updateBadges) OS.updateBadges();
            OS.toast(`Cleaned ${cleaned} orphaned records`, 'success');
        } else {
            OS.toast("No orphaned data found", 'success');
        }
    },

    fetchAvailableModels: async function() {
        const provider = document.getElementById('cfgProvider').value;
        const key = document.getElementById('cfgKey').value || State.settings.key || "";
        const customUrl = document.getElementById('cfgUrl').value || State.settings.url || "";
        const modal = document.getElementById('settingsAppModelModal');
        const container = document.getElementById('settingsAppModelsContainer');
        modal.style.display = 'flex';
        container.innerHTML = `<div style="text-align:center; padding:20px; color:var(--text-muted);">Fetching profiles...</div>`;
        let url = "";
        let headers = { "Content-Type": "application/json" };
        if (provider === 'openrouter') { url = "https://openrouter.ai/api/v1/models"; }
        else if (provider === 'deepinfra') { url = "https://api.deepinfra.com/v1/openai/models"; if (key) headers["Authorization"] = `Bearer ${key}`; }
        else if (provider === 'localllm') {
            // Local LLM — try to fetch models from the local server
            const localUrl = customUrl || 'http://127.0.0.1:8082';
            url = `${localUrl.replace(/\/$/, '')}/v1/models`;
        }
        else { if (!customUrl) { container.innerHTML = `<span style="color:red">Base URL required</span>`; return; } url = `${customUrl.replace(/\/$/, '')}/models`; if (key) headers["Authorization"] = `Bearer ${key}`; }
        try {
            const res = await fetch(url, { method: 'GET', headers });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const data = await res.json();
            let models = [];
            if (Array.isArray(data.data)) models = data.data.map(m => ({ id: m.id, name: m.name || m.id }));
            else if (Array.isArray(data)) models = data.map(m => ({ id: m.id || m, name: m.name || m.id || m }));
            State.fetchedModels = models;
            this.renderModelsList(models);
        } catch(e) { container.innerHTML = `<span style="color:red">Error: ${e.message}</span>`; }
    },
    renderModelsList: function(models) {
        const container = document.getElementById('settingsAppModelsContainer');
        container.innerHTML = "";
        models.forEach(m => {
            const item = document.createElement('div');
            item.style = "cursor: pointer; padding: 12px; border:1px solid var(--border); border-radius:10px; background: rgba(255,255,255,0.02);";
            item.innerHTML = `<div style="font-weight:600; color:white; font-size:0.85rem;">${m.name}</div><div style="font-family:monospace; font-size:0.7rem; color:var(--text-muted);">${m.id}</div>`;
            item.onclick = () => { document.getElementById('cfgModel').value = m.id; document.getElementById('settingsAppModelModal').style.display = 'none'; };
            container.appendChild(item);
        });
    },
    filterModelsList: function() {
        const q = document.getElementById('settingsAppModelSearch').value.toLowerCase().trim();
        if (!State.fetchedModels) return;
        this.renderModelsList(State.fetchedModels.filter(m => m.name.toLowerCase().includes(q) || m.id.toLowerCase().includes(q)));
    },
    exportData: async function() {
        try {
            if (typeof JSZip === 'undefined') throw new Error("JSZip not loaded");
            const data = {
                settings: State.settings, chars: State.characters, userProfile: State.userProfile,
                activeChar: State.activeCharId, sessions: State.sessions,
                memories: State.memories || {},
                instagramPosts: State.instagramPosts || [], redditPosts: State.redditPosts || [], xPosts: State.xPosts || []
            };
            const zip = new JSZip();
            zip.file('backup.json', JSON.stringify(data, null, 2));
            let images = [];
            if (window.ImageDB) images = await window.ImageDB.getAll();
            if (images && images.length > 0) {
                const imgFolder = zip.folder('images');
                for (const img of images) {
                    let content = img.data; let ext = 'png';
                    const match = content.match(/^data:image\/(\w+);base64,(.+)$/);
                    if (match) { ext = match[1]; content = match[2]; }
                    imgFolder.file(`${img.id}.${ext}`, content, { base64: true });
                }
            }
            const base64Zip = await zip.generateAsync({ type: 'base64', compression: 'DEFLATE' });
            const dataUrl = 'data:application/zip;base64,' + base64Zip;
            if (window.AndroidBridge && typeof window.AndroidBridge.startBackup === 'function') {
                const backupId = window.AndroidBridge.startBackup();
                const chunkSize = 300 * 1024;
                for (let i = 0; i < base64Zip.length; i += chunkSize) window.AndroidBridge.appendBackupChunk(backupId, base64Zip.substring(i, i + chunkSize));
                window.AndroidBridge.finishBackup(backupId, '.zip');
            } else {
                const a = document.createElement('a'); a.href = dataUrl; a.download = `fancy_ai_backup_${Date.now()}.zip`;
                document.body.appendChild(a); a.click(); document.body.removeChild(a);
            }
            OS.toast("Backup exported!", 'success');
        } catch(e) { OS.toast("Export failed: " + e.message, 'error'); }
    },
    importData: function(event) {
        const file = event.target.files[0]; if(!file) return;
        const reader = new FileReader();
        reader.onload = async (e) => {
            try {
                if (file.name.endsWith('.zip')) {
                    const zip = await JSZip.loadAsync(e.target.result);
                    const backupFile = zip.file('backup.json');
                    if (!backupFile) throw new Error('Invalid Backup');
                    const data = JSON.parse(await backupFile.async('text'));
                    if (data.settings) State.settings = data.settings;
                    if (data.chars) State.characters = data.chars;
                    if (data.userProfile) State.userProfile = data.userProfile;
                    if (data.activeChar) State.activeCharId = data.activeChar;
                    if (data.sessions) State.sessions = data.sessions;
                    if (data.memories) State.memories = data.memories;
                    State.save();
                } else {
                    Object.assign(State, JSON.parse(e.target.result)); State.save();
                }
                OS.toast("Import Successful!", 'success'); location.reload();
            } catch(err) { OS.toast("Import Failed: " + err.message, 'error'); }
        };
        if (file.name.endsWith('.zip')) reader.readAsArrayBuffer(file); else reader.readAsText(file);
    }
};

window.SettingsApp = SettingsApp;
