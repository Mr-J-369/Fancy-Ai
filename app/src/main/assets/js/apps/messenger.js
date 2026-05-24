/**
 * messenger.js
 * Standalone Messenger Module for Fancy AI OS
 * Features: Real-Time Streaming, Multi-line Input, Native Storage, Integrated Img2Img.
 */
const MessengerApp = {
    container: null,
    activeCharId: null,
    attachedImage: null,

    init: async function(container, params) {
        this.container = container;
        this.activeCharId = (params && params.charId) ? params.charId : State.activeCharId;
        if (!this.activeCharId && State.characters.length > 0) this.activeCharId = State.characters[0].id;
        State.activeCharId = this.activeCharId;
        State.save();

        this.injectStyles();
        this.render();
        await this.renderChatLog();
    },

    injectStyles: function() {
        const styleId = "messenger-app-style";
        if (document.getElementById(styleId)) return;
        const style = document.createElement('style');
        style.id = styleId;
        style.innerHTML = `
            .chat-wrapper { display: flex; flex-direction: column; height: 100%; background: #0a0a0b; }
            .chat-header-bar { padding: 12px 16px; background: rgba(20,20,22,0.85); backdrop-filter: blur(20px); border-bottom: 1px solid rgba(255,255,255,0.06); display: flex; align-items: center; gap: 12px; z-index: 10; }
            .chat-avatar { width: 40px; height: 40px; border-radius: 14px; background: linear-gradient(135deg, #8b5cf6, #6366f1); color: white; display: flex; align-items: center; justify-content: center; font-weight: 800; overflow: hidden; }
            .chat-avatar img { width: 100%; height: 100%; object-fit: cover; }
            .chat-viewport { flex: 1; padding: 16px; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; }

            /* Bubble rows — each bubble gets its own row with avatar */
            .bubble-row { display: flex; align-items: flex-end; gap: 8px; max-width: 90%; }
            .bubble-row.bubble-row-user { align-self: flex-end; flex-direction: row-reverse; }
            .bubble-row.bubble-row-ai { align-self: flex-start; }

            .bubble-row-avatar { width: 32px; height: 32px; border-radius: 50%; flex-shrink: 0; display: flex; align-items: center; justify-content: center; font-size: 0.7rem; font-weight: 700; overflow: hidden; }
            .bubble-row-avatar img { width: 100%; height: 100%; object-fit: cover; }
            .bubble-row-avatar.user-avatar { background: linear-gradient(135deg, #8b5cf6, #6366f1); color: white; }
            .bubble-row-avatar.ai-avatar { background: rgba(255,255,255,0.08); color: var(--text-muted); border: 1px solid rgba(255,255,255,0.1); }

            .bubble-row-content { display: flex; flex-direction: column; gap: 2px; }
            .bubble-row-name { font-size: 0.65rem; font-weight: 700; color: var(--text-muted); letter-spacing: 0.02em; }
            .bubble-row-name.user-name { text-align: right; color: #a78bfa; }

            .bubble { padding: 12px 18px; border-radius: 20px; font-size: 0.95rem; line-height: 1.5; word-break: break-word; white-space: pre-wrap; position: relative; }
            .bubble-user { background: linear-gradient(135deg, #8b5cf6, #7c3aed); color: white; border-bottom-right-radius: 4px; }
            .bubble-ai { background: rgba(255,255,255,0.04); color: var(--text-main); border-bottom-left-radius: 4px; border: 1px solid rgba(255,255,255,0.06); }

            /* Typing indicator animation */
            .typing-indicator { display: flex; align-items: center; gap: 4px; padding: 4px 0; }
            .typing-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); animation: typingBounce 1.4s infinite ease-in-out both; }
            .typing-dot:nth-child(1) { animation-delay: -0.32s; }
            .typing-dot:nth-child(2) { animation-delay: -0.16s; }
            .typing-dot:nth-child(3) { animation-delay: 0s; }
            @keyframes typingBounce {
                0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
                40% { transform: scale(1); opacity: 1; }
            }
            .bubble-typing { background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.06); border-bottom-left-radius: 4px; padding: 16px 20px; min-width: 70px; display: flex; align-items: center; justify-content: center; }

            /* Img2Img Comparison Styles */
            .img-container { position: relative; width: 100%; margin-top: 4px; border-radius: 12px; overflow: hidden; background: #000; }
            .compare-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 2px; background: rgba(255,255,255,0.1); border-radius: 12px; overflow: hidden; }
            .compare-item { position: relative; }
            .compare-label { position: absolute; top: 6px; left: 6px; background: rgba(0,0,0,0.6); color: white; font-size: 0.6rem; padding: 2px 6px; border-radius: 4px; font-weight: 700; text-transform: uppercase; }

            .img-actions { position: absolute; bottom: 8px; right: 8px; display: flex; gap: 6px; }
            .btn-img-action { background: rgba(0,0,0,0.6); color: white; border: none; padding: 4px 8px; border-radius: 6px; font-size: 0.7rem; font-weight: 700; cursor: pointer; backdrop-filter: blur(4px); }

            .chat-input-area { padding: 12px 16px; background: rgba(20,20,22,0.95); border-top: 1px solid rgba(255,255,255,0.06); display: flex; flex-direction: column; gap: 8px; }
            .input-row { display: flex; gap: 10px; align-items: flex-end; }
            .chat-field { flex: 1; background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.08); padding: 12px 18px; border-radius: 20px; color: white; outline: none; font-family: inherit; font-size: 0.95rem; resize: none; max-height: 150px; min-height: 44px; }
            .btn-send, .btn-attach { background: linear-gradient(135deg, #8b5cf6, #6366f1); color: white; border: none; width: 44px; height: 44px; border-radius: 50%; cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 1.1rem; flex-shrink: 0; margin-bottom: 2px; }
            .btn-attach { background: rgba(255,255,255,0.1); border: 1px solid rgba(255,255,255,0.1); }

            /* Attachment Preview */
            .attachment-preview { position: relative; width: 60px; height: 60px; border-radius: 10px; overflow: hidden; border: 2px solid var(--accent); }
            .attachment-preview img { width: 100%; height: 100%; object-fit: cover; }
            .btn-remove-attach { position: absolute; top: 0; right: 0; background: rgba(255,0,0,0.8); color: white; border: none; width: 20px; height: 20px; font-size: 12px; border-bottom-left-radius: 8px; cursor: pointer; }

            /* Denoising Slider */
            .denoise-control { display: flex; align-items: center; gap: 10px; background: rgba(255,255,255,0.04); padding: 8px 12px; border-radius: 12px; margin-bottom: 4px; }
            .denoise-control label { font-size: 0.7rem; color: var(--text-muted); font-weight: 700; white-space: nowrap; }
            .denoise-control input { flex: 1; accent-color: var(--accent); height: 4px; }

            /* Message action buttons (delete, copy, regenerate) — always visible */
            .bubble-actions { display: flex; gap: 6px; margin-top: 6px; justify-content: flex-end; opacity: 1; }
            .bubble-actions.bubble-actions-left { justify-content: flex-start; }
            .btn-msg-action { background: rgba(255,255,255,0.08); color: var(--text-muted); border: none; width: 28px; height: 28px; border-radius: 8px; font-size: 0.7rem; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: all 0.15s ease; }
            .btn-msg-action:hover { background: rgba(255,255,255,0.15); color: white; }
            .btn-msg-action.btn-msg-danger:hover { background: rgba(239,68,68,0.25); color: #ef4444; }
            .btn-msg-action.btn-msg-copy:hover { background: rgba(34,197,94,0.2); color: #22c55e; }
            .btn-msg-action.btn-msg-regen:hover { background: rgba(139,92,246,0.25); color: #a78bfa; }
            .bubble-user .btn-msg-action { background: rgba(0,0,0,0.2); color: rgba(255,255,255,0.7); }
            .bubble-user .btn-msg-action:hover { background: rgba(0,0,0,0.35); color: white; }
            .toast-copied { position: fixed; bottom: 80px; left: 50%; transform: translateX(-50%); background: rgba(0,0,0,0.85); color: white; padding: 8px 16px; border-radius: 10px; font-size: 0.8rem; font-weight: 600; z-index: 9999; animation: toastFade 1.5s ease forwards; pointer-events: none; }
            @keyframes toastFade { 0% { opacity: 0; transform: translateX(-50%) translateY(10px); } 15% { opacity: 1; transform: translateX(-50%) translateY(0); } 85% { opacity: 1; } 100% { opacity: 0; } }
        `;
        document.head.appendChild(style);
    },

    render: function() {
        const char = State.characters.find(c => c.id === this.activeCharId) || { name: "Assistant" };
        this.container.innerHTML = `
            <div class="chat-wrapper">
                <div class="chat-header-bar">
                    <button onclick="OS.goBack()" style="background:none; border:none; color:white; font-size:1.5rem; padding-right:8px; cursor:pointer;">‹</button>
                    <div class="chat-avatar" id="chatHeaderAvatar" onclick="MessengerApp.nextCharacter()" style="cursor:pointer;">${char.name[0]}</div>
                    <div style="flex: 1; display:flex; flex-direction:column; cursor:pointer; overflow:hidden;" onclick="MessengerApp.nextCharacter()">
                        <div style="font-weight: 700; color: white; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">${char.name}</div>
                        <div style="font-size: 0.6rem; color: var(--accent); font-weight:700;">Tap to Switch Character</div>
                    </div>
                    <button onclick="MessengerApp.deleteCurrentChat()" style="background:none; border:none; color:#ef4444; font-size:0.7rem; font-weight:700;">Clear</button>
                </div>
                <div id="chatViewport" class="chat-viewport"></div>

                <div class="chat-input-area">
                    <div id="img2imgControls" style="display: none;">
                        <div class="denoise-control">
                            <label>Denoising: <span id="valDenoise">0.75</span></label>
                            <input type="range" id="inputDenoise" min="0.05" max="1.0" step="0.05" value="0.75" oninput="document.getElementById('valDenoise').innerText = this.value">
                        </div>
                        <div style="display: flex; gap: 8px; margin-bottom: 8px;">
                            <div class="attachment-preview" id="attachPreview">
                                <img id="attachImg" src="">
                                <button class="btn-remove-attach" onclick="MessengerApp.clearAttachment()">✕</button>
                            </div>
                            <div style="flex:1; color: var(--accent); font-size: 0.75rem; font-style: italic; display: flex; align-items: center;">
                                Img2Img Mode Active
                            </div>
                        </div>
                    </div>

                    <div class="input-row">
                        <button class="btn-attach" onclick="document.getElementById('chatFileInput').click()">📷</button>
                        <input type="file" id="chatFileInput" hidden accept="image/*" onchange="MessengerApp.handleFileSelect(event)">
                        <textarea id="chatInputField" class="chat-field" placeholder="Message..." rows="1" enterkeyhint="enter"></textarea>
                        <button class="btn-send" onclick="MessengerApp.submitUserMessage()">→</button>
                    </div>
                </div>
            </div>
        `;
        const input = document.getElementById('chatInputField');
        if (input) {
            input.addEventListener('input', function() {
                this.style.height = 'auto';
                this.style.height = (this.scrollHeight) + 'px';
            });
            // Enter = new line. Only the send button submits.
        }
        this.updateHeaderAvatar();
    },

    nextCharacter: function() {
        const chars = State.characters || [];
        if (chars.length <= 1) return;

        // Remove any existing popup
        const existing = document.getElementById('charSelectorPopup');
        if (existing) existing.remove();

        const popup = document.createElement('div');
        popup.id = 'charSelectorPopup';
        popup.style.cssText = `
            position: fixed; top: 0; left: 0; right: 0; bottom: 0;
            z-index: 99998; display: flex; align-items: flex-start;
            justify-content: center; padding-top: 80px;
            background: rgba(0,0,0,0.5); backdrop-filter: blur(4px);
        `;
        popup.onclick = (e) => { if (e.target === popup) popup.remove(); };

        const sheet = document.createElement('div');
        sheet.style.cssText = `
            background: #1a1a1e; border-radius: 20px; padding: 8px;
            min-width: 240px; max-width: 300px; width: 80%;
            box-shadow: 0 20px 60px rgba(0,0,0,0.6);
            border: 1px solid rgba(255,255,255,0.06);
            max-height: 60vh; overflow-y: auto;
        `;

        chars.forEach(char => {
            const item = document.createElement('div');
            item.style.cssText = `
                display: flex; align-items: center; gap: 12px;
                padding: 12px 14px; border-radius: 14px;
                cursor: pointer; transition: background 0.15s;
                ${char.id === this.activeCharId ? 'background: rgba(139,92,246,0.15);' : ''}
            `;
            item.onmouseenter = () => { item.style.background = 'rgba(255,255,255,0.06)'; };
            item.onmouseleave = () => { item.style.background = char.id === this.activeCharId ? 'rgba(139,92,246,0.15)' : 'transparent'; };
            item.onclick = () => {
                this.activeCharId = char.id;
                State.activeCharId = this.activeCharId;
                State.save();
                popup.remove();
                this.render();
                this.renderChatLog();
            };

            const avatar = document.createElement('div');
            avatar.style.cssText = `
                width: 36px; height: 36px; border-radius: 12px;
                background: linear-gradient(135deg, #8b5cf6, #6366f1);
                color: white; display: flex; align-items: center;
                justify-content: center; font-weight: 800; font-size: 0.9rem;
                flex-shrink: 0; overflow: hidden;
            `;
            if (char.avatar) {
                (async () => {
                    let src = char.avatar;
                    if (src.startsWith('db:') && window.ImageDB) src = await window.ImageDB.get(src);
                    if (src) avatar.innerHTML = `<img src="${src}" style="width:100%;height:100%;object-fit:cover;">`;
                })();
            } else {
                avatar.textContent = char.name[0];
            }

            const info = document.createElement('div');
            info.style.cssText = 'display:flex; flex-direction:column; gap:1px; min-width:0;';
            const name = document.createElement('div');
            name.style.cssText = 'font-weight:700; color:white; font-size:0.9rem; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;';
            name.textContent = char.name;
            const handle = document.createElement('div');
            handle.style.cssText = 'font-size:0.7rem; color:var(--text-muted); font-weight:600;';
            handle.textContent = char.handle || '@ai';

            if (char.id === this.activeCharId) {
                const check = document.createElement('span');
                check.textContent = ' ✓';
                check.style.cssText = 'color: var(--accent);';
                name.appendChild(check);
            }

            info.appendChild(name);
            info.appendChild(handle);
            item.appendChild(avatar);
            item.appendChild(info);
            sheet.appendChild(item);
        });

        popup.appendChild(sheet);
        document.body.appendChild(popup);
    },

    handleFileSelect: function(e) {
        const file = e.target.files[0];
        if (!file) return;
        const reader = new FileReader();
        reader.onload = (event) => {
            this.attachedImage = event.target.result;
            document.getElementById('attachImg').src = this.attachedImage;
            document.getElementById('img2imgControls').style.display = 'block';
        };
        reader.readAsDataURL(file);
    },

    clearAttachment: function() {
        this.attachedImage = null;
        const controls = document.getElementById('img2imgControls');
        const fileInput = document.getElementById('chatFileInput');
        if (controls) controls.style.display = 'none';
        if (fileInput) fileInput.value = '';
    },

    updateHeaderAvatar: async function() {
        const char = State.characters.find(c => c.id === this.activeCharId);
        if (!char || !char.avatar) return;
        let src = char.avatar;
        if (src.startsWith('db:') && window.ImageDB) {
            src = await window.ImageDB.get(src);
        }
        if (src) {
            const avatarDiv = document.getElementById('chatHeaderAvatar');
            if (avatarDiv) avatarDiv.innerHTML = `<img src="${src}">`;
        }
    },

    renderChatLog: async function() {
        const viewport = document.getElementById('chatViewport');
        if (!viewport) return;
        viewport.innerHTML = "";
        const session = State.sessions[this.activeCharId] || [];
        const char = State.characters.find(c => c.id === this.activeCharId) || { name: "Assistant" };
        const userName = (State.userProfile && State.userProfile.name) || "User";
        const charName = char.name || "Assistant";

        for (const msg of session) {
            const isUser = msg.sender === 'user';
            const row = document.createElement('div');
            row.className = `bubble-row bubble-row-${isUser ? 'user' : 'ai'}`;

            // Avatar circle
            const avatarDiv = document.createElement('div');
            avatarDiv.className = `bubble-row-avatar ${isUser ? 'user-avatar' : 'ai-avatar'}`;
            avatarDiv.textContent = isUser ? userName[0].toUpperCase() : charName[0].toUpperCase();

            // Content wrapper (name label + bubble)
            const contentWrap = document.createElement('div');
            contentWrap.className = 'bubble-row-content';

            const nameLabel = document.createElement('div');
            nameLabel.className = `bubble-row-name ${isUser ? 'user-name' : ''}`;
            nameLabel.textContent = isUser ? userName : charName;
            contentWrap.appendChild(nameLabel);

            const bubble = document.createElement('div');
            bubble.className = `bubble bubble-${isUser ? 'user' : 'ai'}`;

            if (msg.type === 'img2img') {
                let srcBefore = msg.source;
                let srcAfter = msg.text;
                if (srcBefore && srcBefore.startsWith('db:') && window.ImageDB) srcBefore = await window.ImageDB.get(srcBefore);
                if (srcAfter && srcAfter.startsWith('db:') && window.ImageDB) srcAfter = await window.ImageDB.get(srcAfter);
                bubble.innerHTML = `
                    <div class="compare-grid">
                        <div class="compare-item">
                            <span class="compare-label">Source</span>
                            <img src="${srcBefore}" style="width:100%; display:block;" onclick="if(window.ImagingApp) ImagingApp.openLocalLightbox(this.src)">
                        </div>
                        <div class="compare-item">
                            <span class="compare-label">Result</span>
                            <img src="${srcAfter}" style="width:100%; display:block;" onclick="if(window.ImagingApp) ImagingApp.openLocalLightbox(this.src)">
                        </div>
                    </div>
                `;
            } else if (msg.type === 'image') {
                let src = msg.text;
                if (src.startsWith('db:') && window.ImageDB) {
                    src = await window.ImageDB.get(src);
                }
                bubble.innerHTML = `
                    <div class="img-container">
                        <img src="${src}" style="width:100%; display:block;" onclick="if(window.ImagingApp) ImagingApp.openLocalLightbox(this.src)">
                        <div class="img-actions">
                            <button class="btn-img-action" onclick="MessengerApp.remixImage('${msg.text}')">✨ Remix</button>
                        </div>
                    </div>
                `;
            } else {
                bubble.innerHTML = OS.formatMarkdown(msg.text);
            }

            contentWrap.appendChild(bubble);

            // Action buttons: Copy (always) + Delete (always) + Regenerate (AI only)
            const actionsDiv = document.createElement('div');
            actionsDiv.className = `bubble-actions ${!isUser ? 'bubble-actions-left' : ''}`;

            // Copy button
            const copyBtn = document.createElement('button');
            copyBtn.className = 'btn-msg-action btn-msg-copy';
            copyBtn.title = 'Copy message';
            copyBtn.textContent = '📋';
            copyBtn.onclick = () => MessengerApp.copyMessage(msg);
            actionsDiv.appendChild(copyBtn);

            // Regenerate button (AI messages only)
            if (!isUser && msg.type !== 'image' && msg.type !== 'img2img') {
                const regenBtn = document.createElement('button');
                regenBtn.className = 'btn-msg-action btn-msg-regen';
                regenBtn.title = 'Regenerate response';
                regenBtn.textContent = '🔄';
                regenBtn.onclick = () => MessengerApp.regenerateMessage(msg.id);
                actionsDiv.appendChild(regenBtn);
            }

            // Delete button
            const delBtn = document.createElement('button');
            delBtn.className = 'btn-msg-action btn-msg-danger';
            delBtn.title = 'Delete message';
            delBtn.textContent = '🗑';
            delBtn.onclick = () => MessengerApp.deleteMessage(msg.id);
            actionsDiv.appendChild(delBtn);

            contentWrap.appendChild(actionsDiv);
            row.appendChild(avatarDiv);
            row.appendChild(contentWrap);
            viewport.appendChild(row);
        }
        viewport.scrollTop = viewport.scrollHeight;
    },

    remixImage: async function(imgRef) {
        let src = imgRef;
        if (imgRef.startsWith('db:') && window.ImageDB) {
            src = await window.ImageDB.get(imgRef);
        }
        this.attachedImage = src;
        const attachImg = document.getElementById('attachImg');
        const controls = document.getElementById('img2imgControls');
        if (attachImg) attachImg.src = src;
        if (controls) controls.style.display = 'block';
        const input = document.getElementById('chatInputField');
        if (input) input.focus();
    },

    submitUserMessage: async function() {
        const input = document.getElementById('chatInputField');
        const text = input.value;
        const denoiseInput = document.getElementById('inputDenoise');
        const denoise = denoiseInput ? parseFloat(denoiseInput.value) : 0.75;

        if (!text.trim() && !this.attachedImage) return;

        const isImg2Img = !!this.attachedImage;
        const currentAttach = this.attachedImage;

        input.value = ""; input.style.height = 'auto';
        this.clearAttachment();

        const msg = {
            id: 'm'+Date.now(),
            sender: 'user',
            text: text || (isImg2Img ? "[Img2Img Request]" : ""),
            timestamp: Date.now()
        };

        if (!State.sessions[this.activeCharId]) State.sessions[this.activeCharId] = [];
        State.sessions[this.activeCharId].push(msg);
        State.save();
        await this.renderChatLog();

        if (isImg2Img) {
            this.handleImg2ImgRequest(text, currentAttach, denoise);
        } else {
            this.generateAIResponse(text);
        }
    },

    handleImg2ImgRequest: async function(prompt, sourceB64, denoise) {
        const viewport = document.getElementById('chatViewport');
        const progDiv = document.createElement('div');
        progDiv.className = 'bubble bubble-ai';
        progDiv.style.fontStyle = 'italic';
        progDiv.style.color = 'var(--accent)';
        progDiv.innerText = "⏳ Initializing Img2Img: 0%";
        viewport.appendChild(progDiv);
        viewport.scrollTop = viewport.scrollHeight;

        try {
            if (!window.ImagingApp) throw new Error("Imaging engine not loaded.");

            const originalSettings = ImagingApp.getSettings ? ImagingApp.getSettings() : {};
            const tempSettings = Object.assign({}, originalSettings, {
                imgDenoising: denoise
            });

            window.ImagingApp.attachedImage = sourceB64;

            const b64Result = await window.ImagingApp.generate(prompt, tempSettings, (percent) => {
                progDiv.innerText = `⏳ Transform: ${percent}%`;
            });

            progDiv.remove();

            if (b64Result && window.ImageDB) {
                const sourceRef = await window.ImageDB.save('src_' + Date.now(), sourceB64);
                const resultRef = await window.ImageDB.save('res_' + Date.now(), b64Result);

                const imgMsg = {
                    id: 'm'+Date.now(),
                    sender: 'ai',
                    type: 'img2img',
                    source: sourceRef,
                    text: resultRef,
                    timestamp: Date.now()
                };
                State.sessions[this.activeCharId].push(imgMsg);
                this.renderChatLog();
            }
        } catch (e) {
            progDiv.innerText = "Error: " + e.message;
            progDiv.style.color = "#ef4444";
        }
    },

    generateAIResponse: async function(userText) {
        const viewport = document.getElementById('chatViewport');
        const aiId = 'ai' + Date.now();
        const char = State.characters.find(c => c.id === this.activeCharId) || { name: "Assistant" };
        const charName = char.name || "Assistant";

        // Build a proper bubble-row with typing indicator
        const row = document.createElement('div');
        row.className = 'bubble-row bubble-row-ai';
        row.id = `stream-row-${aiId}`;

        const avatarDiv = document.createElement('div');
        avatarDiv.className = 'bubble-row-avatar ai-avatar';
        avatarDiv.textContent = charName[0].toUpperCase();

        const contentWrap = document.createElement('div');
        contentWrap.className = 'bubble-row-content';

        const nameLabel = document.createElement('div');
        nameLabel.className = 'bubble-row-name';
        nameLabel.textContent = charName;
        contentWrap.appendChild(nameLabel);

        const bubble = document.createElement('div');
        bubble.className = 'bubble bubble-ai bubble-typing';
        bubble.id = `stream-${aiId}`;
        // Animated typing dots
        bubble.innerHTML = '<div class="typing-indicator"><span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span></div>';

        contentWrap.appendChild(bubble);
        row.appendChild(avatarDiv);
        row.appendChild(contentWrap);
        viewport.appendChild(row);
        viewport.scrollTop = viewport.scrollHeight;

        try {
            const response = await API.sendMessage(this.activeCharId, userText, (chunk) => {
                // On first streaming chunk, remove typing indicator class and show content
                bubble.classList.remove('bubble-typing');
                bubble.innerHTML = OS.formatMarkdown(chunk);
                viewport.scrollTop = viewport.scrollHeight;
            });

            // If streaming never fired (empty response), ensure typing indicator is removed
            bubble.classList.remove('bubble-typing');

            let finalText = response;
            let imagePrompt = null;

            if (response.toLowerCase().includes("flux prompt:")) {
                const parts = response.split(/flux prompt:/i);
                finalText = parts[0].trim();
                imagePrompt = parts[1].trim();
            }

            // Show final text in the streaming bubble
            bubble.innerHTML = OS.formatMarkdown(finalText);

            // Save AI message to state immediately (before any renderChatLog call)
            const aiMsg = { id: aiId, sender: 'ai', text: finalText, timestamp: Date.now() };
            State.sessions[this.activeCharId].push(aiMsg);
            State.save();

            // Handle image generation after saving the AI message
            if (imagePrompt && window.ImagingApp) {
                const progRow = document.createElement('div');
                progRow.className = 'bubble-row bubble-row-ai';

                const progAvatar = document.createElement('div');
                progAvatar.className = 'bubble-row-avatar ai-avatar';
                progAvatar.textContent = charName[0].toUpperCase();

                const progWrap = document.createElement('div');
                progWrap.className = 'bubble-row-content';

                const progName = document.createElement('div');
                progName.className = 'bubble-row-name';
                progName.textContent = charName;
                progWrap.appendChild(progName);

                const progBubble = document.createElement('div');
                progBubble.className = 'bubble bubble-ai';
                progBubble.style.fontStyle = 'italic';
                progBubble.style.color = 'var(--accent)';
                progBubble.innerText = "⏳ Generating image: 0%";

                progWrap.appendChild(progBubble);
                progRow.appendChild(progAvatar);
                progRow.appendChild(progWrap);
                viewport.appendChild(progRow);
                viewport.scrollTop = viewport.scrollHeight;

                const b64 = await window.ImagingApp.generate(imagePrompt, null, (percent) => {
                    progBubble.innerText = `⏳ Generating image: ${percent}%`;
                });

                progRow.remove();

                if (b64 && window.ImageDB) {
                    const dbId = 'img_' + Date.now();
                    const dbRef = await window.ImageDB.save(dbId, b64);
                    const imgMsg = { id: 'm'+Date.now(), sender: 'ai', type: 'image', text: dbRef, timestamp: Date.now() };
                    State.sessions[this.activeCharId].push(imgMsg);

                    const charIndex = State.characters.findIndex(c => c.id === this.activeCharId);
                    if (charIndex !== -1) {
                        State.characters[charIndex].avatar = dbRef;
                    }

                    // Re-render from state — AI message is already saved, so it won't be lost
                    this.renderChatLog();
                }
            }
        } catch (e) {
            bubble.classList.remove('bubble-typing');
            bubble.innerHTML = "Error: " + e.message;
        }
    },

    copyMessage: function(msg) {
        let textToCopy = msg.text;
        // For image/img2img messages, copy the image type description
        if (msg.type === 'image') textToCopy = '[Generated Image]';
        else if (msg.type === 'img2img') textToCopy = '[Img2Img Transform]';
        else if (msg.text) textToCopy = msg.text;

        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(textToCopy).catch(() => {
                this._fallbackCopy(textToCopy);
            });
        } else {
            this._fallbackCopy(textToCopy);
        }

        // Show toast notification
        const existing = document.querySelector('.toast-copied');
        if (existing) existing.remove();
        const toast = document.createElement('div');
        toast.className = 'toast-copied';
        toast.textContent = '📋 Copied!';
        document.body.appendChild(toast);
        setTimeout(() => toast.remove(), 1600);
    },

    _fallbackCopy: function(text) {
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        try { document.execCommand('copy'); } catch(e) {}
        document.body.removeChild(textarea);
    },

    deleteMessage: function(msgId) {
        const session = State.sessions[this.activeCharId] || [];
        const idx = session.findIndex(m => m.id === msgId);
        if (idx === -1) return;
        session.splice(idx, 1);
        State.save();
        this.renderChatLog();
    },

    regenerateMessage: function(msgId) {
        const session = State.sessions[this.activeCharId] || [];
        const idx = session.findIndex(m => m.id === msgId);
        if (idx === -1) return;

        // Find the last user message before this AI message to use as prompt
        let userPrompt = "";
        for (let i = idx - 1; i >= 0; i--) {
            if (session[i].sender === 'user') {
                userPrompt = session[i].text;
                break;
            }
        }

        // Remove this AI message and all messages after it
        session.splice(idx);
        State.save();
        this.renderChatLog();

        // Regenerate if we have a prompt
        if (userPrompt) {
            this.generateAIResponse(userPrompt);
        }
    },

    deleteCurrentChat: function() {
        if (!confirm("Clear history?")) return;
        State.sessions[this.activeCharId] = [];
        State.save();
        this.renderChatLog();
    }
};
window.MessengerApp = MessengerApp;
