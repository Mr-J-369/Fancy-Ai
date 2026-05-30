/**
 * imaging.js
 * Consolidated Image Generation Engine & Parameter Studio Workspace Module
 */
const ImagingApp = {
    container: null,
    // Touch gesture tracking parameters
    lightboxScale: 1,
    startX: 0,
    startY: 0,
    translateX: 0,
    translateY: 0,
    lastDist: 0,
    lastTap: 0,
    isClosingLightbox: false,
    attachedImage: null,

    // Generation queue — serializes requests so only one hits the server at a time
    // This prevents concurrent requests from crashing Local Dream / NPU servers
    _genQueue: [],
    _genRunning: false,

    getSettings: function() {
        if (window.State && State.settings) {
            return State.settings.imagingStudio || {};
        }
        try {
            const local = localStorage.getItem('ds_settings');
            return local ? JSON.parse(local) : {};
        } catch(e) {
            return {};
        }
    },

    saveSettings: function(settings) {
        if (window.State && State.settings) {
            State.settings.imagingStudio = settings;
            State.save();
        } else {
            localStorage.setItem('ds_settings', JSON.stringify(settings));
        }
    },

    init: function(container, params) {
        this.container = container;
        this.render();
        this.setupEventListeners();
        this.loadSettingsToForm();

        // Check if we should clear or set image
        if (params && params.img2img) {
            this.setImg2ImgSource(params.img2img);
        } else if (params && params.clearImage) {
            this.clearForgeImage();
        }
    },

    setImg2ImgSource: function(src) {
        this.attachedImage = src;
        const thumb = document.getElementById('forgeImageThumb');
        const preview = document.getElementById('forgeImagePreview');
        const clearBtn = document.getElementById('btnClearForgeImage');
        const denoising = document.getElementById('denoisingStrengthGroup');
        
        if (thumb) thumb.src = src;
        if (preview) preview.style.display = 'block';
        if (clearBtn) clearBtn.style.display = 'inline-block';
        if (denoising) denoising.style.display = 'block';
    },

    render: function() {
        const styleId = "imaging-app-style";
        if (!document.getElementById(styleId)) {
            const style = document.createElement('style');
            style.id = styleId;
            style.innerHTML = `
                .imaging-wrap { padding: 20px; overflow-y: auto; height: 100%; background: #0a0a0b; display: flex; flex-direction: column; gap: 16px; padding-bottom: 110px; }
                .imaging-card { background: var(--bg-card); padding: 16px; border-radius: 14px; border: 1px solid var(--border); display: flex; flex-direction: column; gap: 12px; }
                .studio-title { font-size: 1rem; font-weight: 700; color: var(--accent); margin: 0; }
                .token-badge { font-size: 0.75rem; color: var(--text-muted); background: var(--bg-input); padding: 2px 8px; border-radius: 12px; border: 1px solid var(--border); font-family: monospace; }
                .preview-frame { width: 100%; border-radius: 10px; border: 1px solid var(--border); overflow: hidden; background: #111113; text-align: center; position: relative; }
                .preview-frame img { width: 100%; display: block; object-fit: contain; }
                
                .compare-view { display: grid; grid-template-columns: 1fr 1fr; gap: 4px; background: #111; border-radius: 10px; overflow: hidden; border: 1px solid var(--border); }
                .compare-pane { position: relative; }
                .compare-pane img { width: 100%; height: auto; display: block; }
                .compare-tag { position: absolute; top: 8px; left: 8px; background: rgba(0,0,0,0.7); color: white; padding: 2px 8px; border-radius: 6px; font-size: 0.65rem; font-weight: 800; text-transform: uppercase; letter-spacing: 0.5px; }

                .form-group { display: flex; flex-direction: column; gap: 4px; }
                .form-group label { color: var(--text-muted); font-size: 0.72rem; text-transform: uppercase; font-weight: 600; }
                .form-control { background: var(--bg-input); border: 1px solid var(--border); color: var(--text-main); padding: 11px; border-radius: 10px; outline: none; font-size: 0.92rem; font-family: inherit; width: 100%; }
                .params-grid-row { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; width: 100%; }
                .imaging-btn { background: var(--bg-input); color: var(--text-main); border: 1px solid var(--border); padding: 11px; border-radius: 10px; cursor: pointer; font-weight: 600; text-align: center; font-size: 0.92rem; width: 100%; transition: background 0.2s; }
                .imaging-btn-primary { background: var(--accent) !important; color: white !important; border: none !important; }
            `;
            document.head.appendChild(style);
        }

        this.container.innerHTML = `
            <div class="imaging-wrap">
                <div class="imaging-card">
                    <h3 class="studio-title">Active Render Pipeline</h3>
                    <div class="form-group">
                        <select id="forgeProviderSelect" class="form-control">
                            <option value="forge">Forge / A1111 Distributed Server</option>
                            <option value="localdream">Local Dream (Snapdragon On-Device NPU)</option>
                        </select>
                    </div>
                </div>

                <div class="imaging-card">
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <h3 class="studio-title">Generation Prompts</h3>
                        <span id="tokenCountBadge" class="token-badge" style="display: none;">Tokens: 0/77</span>
                    </div>
                    <textarea id="forgePrompt" class="form-control" rows="3" placeholder="Describe the target frame details..."></textarea>
                </div>

                <div id="localDreamSuite" class="imaging-card" style="display: none;">
                    <h3 class="studio-title">Local Dream Parameters</h3>
                    <div class="form-group">
                        <label for="cfgLocalDreamUrl">Local Dream Server URL</label>
                        <input type="text" id="cfgLocalDreamUrl" class="form-control" placeholder="http://127.0.0.1:8081">
                    </div>
                    <div class="form-group">
                        <label for="cfgLocalDreamScheduler">Scheduler Mapping</label>
                        <select id="cfgLocalDreamScheduler" class="form-control">
                            <option value="dpm">DPM++ 2M (Default)</option>
                            <option value="dpm_karras">DPM++ 2M + Karras</option>
                            <option value="dpm_sde">DPM++ 2M SDE</option>
                            <option value="dpm_sde_karras">DPM++ 2M SDE + Karras</option>
                            <option value="euler_a">Euler A</option>
                            <option value="euler_a_karras">Euler A + Karras</option>
                            <option value="euler">Euler</option>
                            <option value="euler_karras">Euler + Karras</option>
                            <option value="lcm">LCM</option>
                        </select>
                    </div>
                    <div class="form-group">
                        <label for="cfgLocalDreamNegPrompt">Negative Space Tokens</label>
                        <input type="text" id="cfgLocalDreamNegPrompt" class="form-control">
                    </div>
                    <div class="params-grid-row">
                        <div class="form-group"><label for="cfgLocalDreamSeed">Seed</label><input type="number" id="cfgLocalDreamSeed" class="form-control" placeholder="Random"></div>
                        <div class="form-group"><label for="cfgLocalDreamShowStride">Preview Stride</label><input type="number" id="cfgLocalDreamShowStride" class="form-control" min="1" value="1"></div>
                    </div>
                    <div style="display: flex; flex-direction: column; gap: 8px; margin-top: 4px; font-size: 0.85rem;">
                        <label style="display:flex; align-items:center; gap:6px;"><input type="checkbox" id="cfgLocalDreamUseSquareSize"> Use square dimension shortcuts</label>
                        <label style="display:flex; align-items:center; gap:6px;"><input type="checkbox" id="cfgLocalDreamUseOpenCL"> Enable OpenCL GPU fallback</label>
                        <label style="display:flex; align-items:center; gap:6px;"><input type="checkbox" id="cfgLocalDreamShowProcess"> Intermediate previews</label>
                    </div>
                </div>

                <div id="forgeSuite" class="imaging-card">
                    <h3 class="studio-title">Forge Client Parameters</h3>
                    <div class="form-group">
                        <label for="cfgForge">Forge Endpoint URL</label>
                        <input type="text" id="cfgForge" class="form-control" placeholder="http://127.0.0.1:7860">
                    </div>
                    <div class="form-group">
                        <label for="cfgForgeJsonOverride">JSON Overrides</label>
                        <textarea id="cfgForgeJsonOverride" class="form-control" rows="2" style="font-family:monospace; font-size:0.8rem;" placeholder='{"alwayson_scripts": {}}'></textarea>
                    </div>
                </div>

                <div class="imaging-card">
                    <h3 class="studio-title">Shared Dimension Parameters</h3>
                    <div class="params-grid-row">
                        <div class="form-group"><label for="forgeImgWidth">Width</label><input type="number" id="forgeImgWidth" class="form-control" value="512"></div>
                        <div class="form-group"><label for="forgeImgHeight">Height</label><input type="number" id="forgeImgHeight" class="form-control" value="512"></div>
                    </div>
                    <div class="params-grid-row">
                        <div class="form-group"><label for="forgeImgSteps">Steps</label><input type="number" id="forgeImgSteps" class="form-control" value="20"></div>
                        <div class="form-group"><label for="forgeImgCfg">CFG Scale</label><input type="number" step="0.5" id="forgeImgCfg" class="form-control" value="7"></div>
                    </div>
                </div>

                <div class="imaging-card">
                    <h3 class="studio-title">Image-to-Image Parameters</h3>
                    <div style="display: flex; gap: 8px; align-items: center;">
                        <button type="button" id="btnSelectForgeImage" class="imaging-btn" style="flex: 1;">📷 Select Source Image</button>
                        <button type="button" id="btnClearForgeImage" class="imaging-btn" style="width: auto; display: none; color: #ef4444; border-color: rgba(239, 68, 68, 0.2);">✕</button>
                    </div>
                    <input type="file" id="forgeImageInput" accept="image/*" style="display: none;">
                    <div id="forgeImagePreview" style="margin-top: 8px; display: none;" class="preview-frame"><img id="forgeImageThumb" src="" alt="Source Preview"></div>
                    <div id="denoisingStrengthGroup" class="form-group" style="display: none; margin-top:4px;">
                        <label for="cfgDenoising">Denoising Strength: <span id="lblDenoising" style="color:var(--text-main);">0.75</span></label>
                        <input type="range" id="cfgDenoising" min="0.05" max="1.0" step="0.05" value="0.75" class="form-control" oninput="document.getElementById('lblDenoising').innerText = this.value">
                    </div>
                </div>

                <button type="button" id="btnManualForgeGenerate" class="imaging-btn imaging-btn-primary" style="padding: 14px; font-size: 1rem;">Generate Studio Output Matrix</button>
                <div id="forgePreview" style="margin-top: 10px; display: flex; flex-direction: column; gap: 10px;"></div>

                <button type="button" class="imaging-btn imaging-btn-primary" style="margin-top: 8px; padding: 16px;" onclick="ImagingApp.saveAndClose()">Save & Close</button>
            </div>
        `;
    },

    setupEventListeners: function() {
        const promptEl = document.getElementById('forgePrompt');
        if (promptEl) promptEl.addEventListener('input', () => this.updateTokenCount());
        
        const fileInput = document.getElementById('forgeImageInput');
        if (fileInput) fileInput.addEventListener('change', (e) => this.handleForgeImageAttachment(e));
        
        const providerSelect = document.getElementById('forgeProviderSelect');
        if (providerSelect) providerSelect.addEventListener('change', () => this.handleEngineChange());
        
        const selectBtn = document.getElementById('btnSelectForgeImage');
        if (selectBtn) selectBtn.addEventListener('click', () => { document.getElementById('forgeImageInput').click(); });
        
        const clearBtn = document.getElementById('btnClearForgeImage');
        if (clearBtn) clearBtn.addEventListener('click', () => { this.clearForgeImage(); });
        
        const generateBtn = document.getElementById('btnManualForgeGenerate');
        if (generateBtn) generateBtn.addEventListener('click', () => { this.manualForgeGenerate(); });
    },

    openLocalLightbox: function(src, dbRef = null) {
        if (window.OS && window.OS.openLightbox) {
            window.OS.openLightbox(src);
        }
    },

    closeLocalLightbox: function(preventHistoryPop) {
        if (window.OS && window.OS.closeLightbox) {
            window.OS.closeLightbox(preventHistoryPop);
        }
    },

    downloadCurrentLocalLightbox: function() {
        if (window.OS && window.OS.downloadLightboxImg) {
            window.OS.downloadLightboxImg();
        }
    },

    shareCurrentLocalLightbox: function() {
        if (window.OS && window.OS.shareLightboxImg) {
            window.OS.shareLightboxImg();
        }
    },

    loadSettingsToForm: function() {
        const s = this.getSettings();
        const providerEl = document.getElementById('forgeProviderSelect');
        if (providerEl) providerEl.value = s.useLocalDream ? 'localdream' : 'forge';
        
        const setVal = (id, val) => { const el = document.getElementById(id); if (el) el.value = val; };
        setVal('cfgForge', s.forge || 'http://127.0.0.1:7860');
        setVal('cfgForgeJsonOverride', s.forgeJsonOverride || '');
        setVal('cfgLocalDreamUrl', s.localDreamUrl || 'http://127.0.0.1:8081');
        setVal('cfgLocalDreamScheduler', s.localDreamScheduler || 'dpm');
        setVal('cfgLocalDreamNegPrompt', s.localDreamNegPrompt !== undefined ? s.localDreamNegPrompt : 'blurry, distorted, low quality');
        setVal('cfgLocalDreamSeed', s.localDreamSeed || '');
        setVal('cfgLocalDreamShowStride', s.localDreamShowStride !== undefined ? s.localDreamShowStride : 1);
        
        const setChecked = (id, val) => { const el = document.getElementById(id); if (el) el.checked = val; };
        setChecked('cfgLocalDreamUseSquareSize', s.localDreamUseSquareSize !== undefined ? s.localDreamUseSquareSize : true);
        setChecked('cfgLocalDreamUseOpenCL', s.localDreamUseOpenCL || false);
        setChecked('cfgLocalDreamShowProcess', s.localDreamShowProcess !== undefined ? s.localDreamShowProcess : true);
        
        setVal('forgeImgWidth', s.imgWidth || 512);
        setVal('forgeImgHeight', s.imgHeight || 512);
        setVal('forgeImgSteps', s.imgSteps || 20);
        setVal('forgeImgCfg', s.imgCfg || 7);
        setVal('cfgDenoising', s.imgDenoising !== undefined ? s.imgDenoising : 0.75);
        
        const lblDenoising = document.getElementById('lblDenoising');
        if (lblDenoising) lblDenoising.innerText = s.imgDenoising !== undefined ? s.imgDenoising : 0.75;
        
        this.toggleViewSuites(s.useLocalDream);
    },

    handleEngineChange: function() {
        const isLD = (document.getElementById('forgeProviderSelect').value === 'localdream');
        this.toggleViewSuites(isLD);
    },

    collectSettingsFromForm: function() {
        const getVal = (id) => { const el = document.getElementById(id); return el ? el.value : ''; };
        const getInt = (id, def) => { const v = parseInt(getVal(id), 10); return isNaN(v) ? def : v; };
        const getFloat = (id, def) => { const v = parseFloat(getVal(id)); return isNaN(v) ? def : v; };
        const getCheck = (id) => { const el = document.getElementById(id); return el ? el.checked : false; };

        return {
            useLocalDream: (getVal('forgeProviderSelect') === 'localdream'),
            forge: getVal('cfgForge').trim().replace(/\/$/, ''),
            forgeJsonOverride: getVal('cfgForgeJsonOverride').trim(),
            localDreamUrl: getVal('cfgLocalDreamUrl').trim().replace(/\/$/, ''),
            localDreamScheduler: getVal('cfgLocalDreamScheduler'),
            localDreamNegPrompt: getVal('cfgLocalDreamNegPrompt').trim(),
            localDreamSeed: getVal('cfgLocalDreamSeed').trim(),
            localDreamShowStride: getInt('cfgLocalDreamShowStride', 1),
            localDreamUseSquareSize: getCheck('cfgLocalDreamUseSquareSize'),
            localDreamUseOpenCL: getCheck('cfgLocalDreamUseOpenCL'),
            localDreamShowProcess: getCheck('cfgLocalDreamShowProcess'),
            imgWidth: getInt('forgeImgWidth', 512),
            imgHeight: getInt('forgeImgHeight', 512),
            imgSteps: getInt('forgeImgSteps', 20),
            imgCfg: getFloat('forgeImgCfg', 7),
            imgDenoising: getFloat('cfgDenoising', 0.75)
        };
    },

    saveAndClose: function() {
        const s = this.collectSettingsFromForm();
        this.saveSettings(s);
        OS.toast("Imaging settings saved!", 'success');
        OS.goHome();
    },

    toggleViewSuites: function(isLD) {
        const ldSuite = document.getElementById('localDreamSuite');
        const fSuite = document.getElementById('forgeSuite');
        if (ldSuite) ldSuite.style.display = isLD ? 'flex' : 'none';
        if (fSuite) fSuite.style.display = isLD ? 'none' : 'flex';
        this.updateTokenCount();
    },

    updateTokenCount: async function() {
        const badge = document.getElementById('tokenCountBadge');
        if (!badge) return;
        const s = this.getSettings();
        if (!s.useLocalDream) { badge.style.display = 'none'; return; }
        const prompt = document.getElementById('forgePrompt').value.trim();
        if (!prompt) { badge.style.display = 'none'; return; }
        try {
            let baseUrl = s.localDreamUrl || 'http://127.0.0.1:8081';
            baseUrl = baseUrl.replace(/\/generate\/?$/, '').replace(/\/$/, '');
            const res = await fetch(`${baseUrl}/tokenize`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ prompt }) });
            if (res.ok) {
                const data = await res.json(); const count = data.count || 0;
                badge.innerText = `Tokens: ${count}/77`; badge.style.display = 'inline-block';
                badge.style.color = count > 77 ? '#ef4444' : 'var(--accent)';
            }
        } catch(e) { badge.style.display = 'none'; }
    },

    handleForgeImageAttachment: function(event) {
        const file = event.target.files[0];
        if (!file) return;
        const reader = new FileReader(); reader.onload = (e) => { this.setImg2ImgSource(e.target.result); };
        reader.readAsDataURL(file);
    },

    clearForgeImage: function() {
        this.attachedImage = null;
        const preview = document.getElementById('forgeImagePreview');
        const clearBtn = document.getElementById('btnClearForgeImage');
        const denoising = document.getElementById('denoisingStrengthGroup');
        const input = document.getElementById('forgeImageInput');
        
        if (preview) preview.style.display = 'none';
        if (clearBtn) clearBtn.style.display = 'none';
        if (denoising) denoising.style.display = 'none';
        if (input) input.value = '';
    },

    manualForgeGenerate: async function() {
        const promptText = document.getElementById('forgePrompt').value.trim();
        if (!promptText) return;
        if (window.OS && OS.guardBusy("⏳ An image is already generating…")) return;

        const s = this.collectSettingsFromForm();
        this.saveSettings(s);

        const container = document.getElementById('forgePreview');
        if (container) container.innerHTML = `<div id="forgeProgress" style="text-align: center; padding: 20px; color: var(--accent); font-style: italic;">Rendering...</div>`;

        try {
            const sourceBackup = this.attachedImage;
            const finalImageB64 = await this.generate(promptText, s, (p) => {
                const pDiv = document.getElementById('forgeProgress');
                if (pDiv) pDiv.innerText = `⏳ Processing: ${p}%`;
            });

            if (container) {
                if (sourceBackup) {
                    container.innerHTML = `
                        <div class="compare-view">
                            <div class="compare-pane">
                                <span class="compare-tag">Before</span>
                                <img src="${sourceBackup}" onclick="OS.openLightbox(this.src)">
                            </div>
                            <div class="compare-pane">
                                <span class="compare-tag">After</span>
                                <img src="${finalImageB64}" onclick="OS.openLightbox(this.src)">
                            </div>
                        </div>
                    `;
                } else {
                    container.innerHTML = `<div class="preview-frame"><img src="${finalImageB64}" onclick="OS.openLightbox(this.src)"></div>`;
                }
            }

            if (typeof ImageDB !== 'undefined') await ImageDB.save(`manual_${Date.now()}`, finalImageB64);
        } catch(e) { if (container) container.innerHTML = `<span style="color:#ef4444; font-size:0.9rem;">Error: ${e.message}</span>`; }
    },

    // Queued generation — ensures only one image generation request hits the server at a time
    // This prevents concurrent requests from crashing Local Dream / NPU servers
    // Callers still use ImagingApp.generate(promptText, settings, onProgress) — the API is unchanged.
    generate: async function(promptText, s = null, onProgress = null) {
        // Signal native layer to keep app alive
        if (window.OS && window.OS.setTaskActive) {
            window.OS.setTaskActive('imaging_gen', true, "Generating AI image...");
        }

        // If a generation is already running, wait for it to finish before starting this one
        if (this._genRunning) {
            console.log("ImagingApp: Queuing generation request (another is in progress)");
            return new Promise((resolve, reject) => {
                this._genQueue.push({ promptText, s, onProgress, resolve, reject });
            });
        }
        this._genRunning = true;
        try {
            const result = await this._doGenerate(promptText, s, onProgress);
            return result;
        } finally {
            this._genRunning = false;
            // Process next queued request if any
            if (this._genQueue.length > 0) {
                const next = this._genQueue.shift();
                // Run next in background — don't block the current caller's return
                this.generate(next.promptText, next.s, next.onProgress)
                    .then(next.resolve)
                    .catch(next.reject);
            } else {
                // All items in queue done
                if (window.OS && window.OS.setTaskActive) {
                    window.OS.setTaskActive('imaging_gen', false);
                }
            }
        }
    },

    // The actual generation logic (previously the body of generate())
    _doGenerate: async function(promptText, s = null, onProgress = null) {
        if (!s) s = this.getSettings();
        const strength = s.imgDenoising !== undefined ? s.imgDenoising : 0.75;
        let finalImageB64 = null;

        if (s.useLocalDream) {
            let baseUrl = s.localDreamUrl || 'http://127.0.0.1:8081';
            baseUrl = baseUrl.replace(/\/generate\/?$/, '').replace(/\/$/, '');
            const payload = {
                prompt: promptText,
                negative_prompt: s.localDreamNegPrompt || "blurry",
                steps: parseInt(s.imgSteps) || 20,
                cfg: parseFloat(s.imgCfg) || 7.0,
                scheduler: s.localDreamScheduler || "dpm",
                use_opencl: s.localDreamUseOpenCL || false,
                show_diffusion_process: s.localDreamShowProcess || false,
                show_diffusion_stride: parseInt(s.localDreamShowStride) || 1
            };
            if (s.localDreamUseSquareSize) payload.size = parseInt(s.imgWidth) || 512;
            else { payload.width = parseInt(s.imgWidth) || 512; payload.height = parseInt(s.imgHeight) || 512; }
            if (s.localDreamSeed) payload.seed = parseInt(s.localDreamSeed);

            // Resolve image if it's a reference (FOR SERVER PIPE: Get Base64)
            let effectiveImage = this.attachedImage;
            if (effectiveImage && effectiveImage.startsWith('db:') && window.ImageDB) {
                effectiveImage = await window.ImageDB.get(effectiveImage, true);
            }

            if (effectiveImage) {
                const cleanB64 = effectiveImage.replace(/^data:image\/(png|jpeg|jpg|webp);base64,/, '');
                payload.image = cleanB64; payload.init_image = cleanB64; payload.strength = parseFloat(strength);
            }
            const timeoutPromise = new Promise((_, reject) => {
                setTimeout(() => reject(new Error('Image generation timeout. Server not responding at ' + baseUrl)), 30000);
            });
            const fetchPromise = fetch(`${baseUrl}/generate`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const res = await Promise.race([fetchPromise, timeoutPromise]);

            if (!res.ok) throw new Error(`Imaging error: ${res.status}`);
            const reader = res.body.getReader(); const decoder = new TextDecoder(); let buffer = "";
            while (true) {
                const { done, value } = await reader.read();
                if (value) {
                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split(/\r?\n/); buffer = lines.pop() || "";
                    for (const line of lines) {
                        if (!line.startsWith("data: ")) continue;
                        try {
                            const json = JSON.parse(line.slice(6).trim());
                            if (json.type === 'progress' && onProgress) onProgress(Math.round((json.step / (json.total_steps || payload.steps)) * 100));
                            if (json.type === 'complete') {
                                const canvas = document.createElement('canvas'); canvas.width = json.width; canvas.height = json.height;
                                const ctx = canvas.getContext('2d'); const imgData = ctx.createImageData(json.width, json.height);
                                const bytes = Uint8Array.from(atob(json.image), c => c.charCodeAt(0));
                                for (let i = 0, j = 0; i < bytes.length; i += 3, j += 4) { imgData.data[j] = bytes[i]; imgData.data[j+1] = bytes[i+1]; imgData.data[j+2] = bytes[i+2]; imgData.data[j+3] = 255; }
                                ctx.putImageData(imgData, 0, 0); finalImageB64 = canvas.toDataURL('image/png');
                            }
                        } catch(e) { console.error("Process JSON parse error", e); }
                    }
                }
                if (done) break;
            }
        } else {
            const forgeUrl = (s.forge || '').replace(/\/$/, '');

            // Resolve image if it's a reference (FOR SERVER PIPE: Get Base64)
            let effectiveImage = this.attachedImage;
            if (effectiveImage && effectiveImage.startsWith('db:') && window.ImageDB) {
                effectiveImage = await window.ImageDB.get(effectiveImage, true);
            }

            const endpoint = effectiveImage ? `${forgeUrl}/sdapi/v1/img2img` : `${forgeUrl}/sdapi/v1/txt2img`;
            const payload = { prompt: promptText, steps: s.imgSteps || 20, width: s.imgWidth || 512, height: s.imgHeight || 512, cfg_scale: s.imgCfg || 7 };
            if (effectiveImage) { payload.init_images = [effectiveImage.replace(/^data:image\/(png|jpeg|jpg|webp);base64,/, '')]; payload.denoising_strength = parseFloat(strength); }
            
            let interval = null;
            if (onProgress) {
                interval = setInterval(async () => {
                    try {
                        const progRes = await fetch(`${forgeUrl}/sdapi/v1/progress`);
                        if (progRes.ok) { const progData = await progRes.json(); onProgress(Math.round(progData.progress * 100)); }
                    } catch(e) {}
                }, 1000);
            }

            try {
                const timeoutPromise = new Promise((_, reject) => {
                    setTimeout(() => reject(new Error('Forge request timeout. Server not responding at ' + forgeUrl)), 180000);
                });
                const fetchPromise = fetch(endpoint, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const res = await Promise.race([fetchPromise, timeoutPromise]);
                if (!res.ok) throw new Error("Forge API Error: " + res.status);
                const data = await res.json();
                if (!data.images || !data.images[0]) throw new Error("Forge returned no image");
                finalImageB64 = `data:image/png;base64,${data.images[0]}`;
            } finally { if (interval) clearInterval(interval); }
        }
        return finalImageB64;
    }
};

// Global registration for lightbox handling
window.ImagingApp = ImagingApp;
