/**
 * messenger.js
 * Standalone Messenger Module for Fancy AI OS
 * Features: Real-Time Streaming, Multi-line Input, Native Storage, Integrated Img2Img.
 */
const MessengerApp = {
    container: null,
    activeCharId: null,
    attachedImage: null,
    currentView: 'list', // 'list' or 'chat'

    init: async function(container, params) {
        this.container = container;
        this.injectStyles();

        // Ensure root state is set for back-navigation
        if (!history.state || history.state.app !== 'MessengerApp') {
            history.replaceState({ app: 'MessengerApp' }, "", "#MessengerApp");
        }

        if (params && params.charId) {
            this.openChat(params.charId);
        } else {
            this.renderConversationList();
        }
    },

    injectStyles: function() {
        const styleId = "messenger-app-style";
        if (document.getElementById(styleId)) return;
        const style = document.createElement('style');
        style.id = styleId;
        style.innerHTML = `
            .chat-wrapper { display: flex; flex-direction: column; height: 100%; background: #0b141a; }
            .chat-header-bar { padding: 10px 16px; background: #202c33; border-bottom: none; display: flex; align-items: center; gap: 12px; z-index: 10; padding-top: calc(10px + env(safe-area-inset-top)); }
            .chat-avatar { width: 38px; height: 38px; border-radius: 50%; background: #6b7c7c; color: white; display: flex; align-items: center; justify-content: center; font-weight: 800; overflow: hidden; }
            .chat-avatar img { width: 100%; height: 100%; object-fit: cover; }
            .chat-viewport { flex: 1; padding: 10px 16px; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; background-image: url('data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSI0MCIgaGVpZ2h0PSI0MCI+PHJlY3Qgd2lkdGg9IjQwIiBoZWlnaHQ9IjQwIiBmaWxsPSIjMGIxNDFhIi8+PGNpcmNsZSBjeD0iMjAiIGN5PSIyMCIgcj0iMC41IiBmaWxsPSIjMWYyYjMzIi8+PC9zdmc+'); }

            /* Conv List Styles */
            .conv-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; padding-bottom: 80px; background: #0b141a; }
            .conv-item { display: flex; align-items: center; gap: 14px; padding: 12px 16px; border-bottom: 1px solid #222d34; cursor: pointer; transition: background 0.2s; }
            .conv-item:active { background: #202c33; }
            .conv-avatar { width: 50px; height: 52px; border-radius: 50%; background: #6b7c7c; color: white; display: flex; align-items: center; justify-content: center; font-size: 1.2rem; font-weight: 800; overflow: hidden; flex-shrink: 0; }
            .conv-avatar img { width: 100%; height: 100%; object-fit: cover; }
            .conv-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
            .conv-name-row { display: flex; justify-content: space-between; align-items: center; }
            .conv-name { color: #e9edef; font-weight: 600; font-size: 1.05rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
            .conv-time { color: #8696a0; font-size: 0.72rem; }
            .conv-msg-row { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
            .conv-last-msg { color: #8696a0; font-size: 0.88rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; line-height: 1.2; }
            .conv-unread { background: #00a884; color: #0b141a; min-width: 20px; height: 20px; border-radius: 10px; display: flex; align-items: center; justify-content: center; font-size: 0.7rem; font-weight: 800; padding: 0 6px; flex-shrink: 0; }

            /* WhatsApp style bubbles */
            .bubble-row { display: flex; margin-bottom: 2px; width: 100%; }
            .bubble-row-user { justify-content: flex-end; }
            .bubble-row-ai { justify-content: flex-start; }

            .bubble { padding: 6px 10px 8px; border-radius: 8px; font-size: 0.95rem; line-height: 1.45; word-break: break-word; position: relative; max-width: 85%; box-shadow: 0 1px 0.5px rgba(0,0,0,0.13); }
            .bubble-user { background: #005c4b; color: #e9edef; border-top-right-radius: 0; }
            .bubble-ai { background: #202c33; color: #e9edef; border-top-left-radius: 0; }

            .bubble-row-avatar, .bubble-row-name { display: none; } /* WhatsApp doesn't show these in chat */

            .bubble-time { font-size: 0.65rem; color: rgba(233, 237, 239, 0.6); margin-top: 4px; text-align: right; display: block; }

            .chat-input-area { padding: 8px 10px; background: #202c33; display: flex; flex-direction: column; gap: 8px; padding-bottom: calc(8px + env(safe-area-inset-bottom)); }
            .input-row { display: flex; gap: 8px; align-items: flex-end; }
            .chat-field { flex: 1; background: #2a3942; border: none; padding: 10px 15px; border-radius: 20px; color: #d1d7db; outline: none; font-family: inherit; font-size: 0.95rem; resize: none; max-height: 150px; min-height: 40px; }
            .btn-send, .btn-mic { background: #00a884; color: #0b141a; border: none; width: 42px; height: 42px; border-radius: 50%; cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 1.1rem; flex-shrink: 0; }
            .btn-attach { background: none; color: #8696a0; border: none; width: 32px; height: 42px; cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 1.3rem; flex-shrink: 0; }
            .btn-mic.listening { background: #ea0038; animation: pulse 1.5s infinite; color: white; }

            /* Typing indicator animation */
            .typing-indicator { display: flex; align-items: center; gap: 4px; padding: 4px 0; }
            .typing-dot { width: 6px; height: 6px; border-radius: 50%; background: #00a884; animation: typingBounce 1.4s infinite ease-in-out both; }
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
            .btn-send, .btn-attach, .btn-mic { background: linear-gradient(135deg, #8b5cf6, #6366f1); color: white; border: none; width: 44px; height: 44px; border-radius: 50%; cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 1.1rem; flex-shrink: 0; margin-bottom: 2px; }
            .btn-attach { background: rgba(255,255,255,0.1); border: 1px solid rgba(255,255,255,0.1); }
            .btn-mic.listening { background: var(--danger); animation: pulse 1.5s infinite; }

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

    renderConversationList: function() {
        this.activeCharId = null;
        this.currentView = 'list';

        if (document.getElementById('os-app-title')) {
            document.getElementById('os-app-title').innerText = "Messages";
        }

        this.container.innerHTML = `
            <div class="chat-wrapper">
                <div class="chat-header-bar">
                    <div style="font-weight: 800; font-size: 1.2rem; color: white; flex:1;">All Chats</div>
                    <button onclick="OS.launch('ContactsApp')" style="background:none; border:none; font-size:1.3rem; cursor:pointer;">👤</button>
                </div>
                <div class="conv-list" id="convList"></div>
            </div>
        `;

        const listEl = document.getElementById('convList');
        const chars = [...State.characters].sort((a, b) => {
            const lastA = (State.sessions[a.id] || []).slice(-1)[0]?.timestamp || 0;
            const lastB = (State.sessions[b.id] || []).slice(-1)[0]?.timestamp || 0;
            return lastB - lastA;
        });

        chars.forEach(char => {
            const session = State.sessions[char.id] || [];
            const lastMsg = session[session.length - 1];
            const unreadCount = this.getUnreadCount(char.id);

            const item = document.createElement('div');
            item.className = 'conv-item';
            item.onclick = () => this.openChat(char.id);

            const avId = `conv-av-${char.id}`;
            item.innerHTML = `
                <div class="conv-avatar" id="${avId}">${char.name[0]}</div>
                <div class="conv-info">
                    <div class="conv-name-row">
                        <div class="conv-name">${char.name}</div>
                        <div class="conv-time">${lastMsg ? this.formatTime(lastMsg.timestamp) : ''}</div>
                    </div>
                    <div class="conv-msg-row">
                        <div class="conv-last-msg">${lastMsg ? (lastMsg.type === 'image' ? '📷 Photo' : lastMsg.text) : 'Start a conversation'}</div>
                        ${unreadCount > 0 ? `<div class="conv-unread">${unreadCount}</div>` : ''}
                    </div>
                </div>
            `;
            listEl.appendChild(item);

            if (char.avatar) {
                (async () => {
                    let src = char.avatar;
                    if (src.startsWith('db:') && window.ImageDB) src = await window.ImageDB.get(src);
                    const el = document.getElementById(avId);
                    if (el && src) el.innerHTML = `<img src="${src}">`;
                })();
            }
        });
    },

    getUnreadCount: function(charId) {
        if (!State.chatReadTimestamps) State.chatReadTimestamps = {};
        const lastRead = State.chatReadTimestamps[charId] || 0;
        const session = State.sessions[charId] || [];
        return session.filter(m => (m.sender === 'ai' || m.role === 'assistant') && m.timestamp > lastRead).length;
    },

    formatTime: function(ts) {
        if (!ts) return "";
        const now = new Date();
        const date = new Date(ts);
        const diffDays = Math.floor((now - date) / (1000 * 60 * 60 * 24));

        if (diffDays === 0) return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        if (diffDays === 1) return "Yesterday";
        if (diffDays < 7) return date.toLocaleDateString([], { weekday: 'long' });
        return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
    },

    openChat: function(charId) {
        this.activeCharId = charId;
        this.currentView = 'chat';
        State.activeCharId = charId;

        // Mark as read
        if (!State.chatReadTimestamps) State.chatReadTimestamps = {};
        State.chatReadTimestamps[charId] = Date.now();
        State.save();

        if (window.OS && typeof OS.pushView === 'function') {
            const oldTitle = document.getElementById('os-app-title').innerText;
            const char = State.characters.find(c => c.id === charId);
            document.getElementById('os-app-title').innerText = char ? char.name : "Chat";

            OS.pushView(() => {
                document.getElementById('os-app-title').innerText = oldTitle;
                this.activeCharId = null;
                this.currentView = 'list';
                // Mark AGAIN when returning to list to ensure it clears badges
                if (!State.chatReadTimestamps) State.chatReadTimestamps = {};
                State.chatReadTimestamps[charId] = Date.now();
                State.save();
                this.renderConversationList();
            });
        }

        this.render();
        this.renderChatLog();

        // Also update main app badge immediately
        if (window.OS && OS.updateBadges) OS.updateBadges();
    },

    render: function() {
        const char = State.characters.find(c => c.id === this.activeCharId) || { name: "Assistant" };
        this.container.innerHTML = `
            <div class="chat-wrapper">
                <div class="chat-header-bar">
                    <div class="chat-avatar" id="chatHeaderAvatar" onclick="MessengerApp.showCharacterProfile()" style="cursor:pointer;">${char.name[0]}</div>
                    <div style="flex: 1; display:flex; flex-direction:column; cursor:pointer; overflow:hidden;" onclick="MessengerApp.showCharacterProfile()">
                        <div style="font-weight: 700; color: #e9edef; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">${char.name}</div>
                        <div style="font-size: 0.65rem; color: #8696a0;">online</div>
                    </div>
                    <div style="display:flex; gap:16px; align-items:center; color:#8696a0;">
                        <button onclick="MessengerApp.showMemories()" style="background:none; border:none; font-size:1.1rem; cursor:pointer; color:inherit;">🧠</button>
                        <button onclick="MessengerApp.showChatMenu()" style="background:none; border:none; font-size:1.1rem; cursor:pointer; color:inherit;">⋮</button>
                    </div>
                </div>
                <div id="chatViewport" class="chat-viewport"></div>

                <div class="chat-input-area">
                    <div id="img2imgControls" style="display: none;">
                        <div id="denoiseSliderRow" class="denoise-control">
                            <label>Denoising: <span id="valDenoise">0.75</span></label>
                            <input type="range" id="inputDenoise" min="0.05" max="1.0" step="0.05" value="0.75" oninput="document.getElementById('valDenoise').innerText = this.value">
                        </div>
                        <div style="display: flex; gap: 8px; margin-bottom: 8px; align-items: center;">
                            <div class="attachment-preview" id="attachPreview">
                                <img id="attachImg" src="">
                                <button class="btn-remove-attach" onclick="MessengerApp.clearAttachment()">✕</button>
                            </div>
                            <div style="flex:1; display:flex; flex-direction:column; gap:4px;">
                                <div style="color: #00a884; font-size: 0.75rem; font-style: italic; font-weight:700;" id="imgModeLabel">
                                    Img2Img Mode Active
                                </div>
                                <label style="display:flex; align-items:center; gap:6px; cursor:pointer;">
                                    <input type="checkbox" id="chkVisionMode" style="width:14px; height:14px; accent-color:#00a884;" onchange="MessengerApp.toggleImgMode()">
                                    <span style="color:#8696a0; font-size:0.7rem; font-weight:700;">Vision (AI looks at image)</span>
                                </label>
                            </div>
                        </div>
                    </div>

                    <div class="input-row">
                        <button class="btn-attach" onclick="document.getElementById('chatFileInput').click()">+</button>
                        <input type="file" id="chatFileInput" hidden accept="image/*" onchange="MessengerApp.handleFileSelect(event)">
                        <textarea id="chatInputField" class="chat-field" placeholder="Message" rows="1" enterkeyhint="enter"></textarea>
                        <button id="btnMic" class="btn-mic" onclick="MessengerApp.toggleVoiceInput()">🎙️</button>
                        <button id="btnSend" class="btn-send" style="display:none;" onclick="MessengerApp.submitUserMessage()">➤</button>
                    </div>
                </div>
            </div>
        `;
        const input = document.getElementById('chatInputField');
        if (input) {
            input.addEventListener('input', function() {
                this.style.height = 'auto';
                this.style.height = (this.scrollHeight) + 'px';

                const sendBtn = document.getElementById('btnSend');
                const micBtn = document.getElementById('btnMic');
                if (this.value.trim().length > 0) {
                    sendBtn.style.display = 'flex';
                    micBtn.style.display = 'none';
                } else {
                    sendBtn.style.display = 'none';
                    micBtn.style.display = 'flex';
                }
            });
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

    showChatMenu: function() {
        const char = State.characters.find(c => c.id === this.activeCharId) || { name: 'Chat' };
        OS.confirm(`Clear all messages with ${char.name}?`, () => {
            State.sessions[this.activeCharId] = [];
            State.save();
            this.renderChatLog();
            OS.toast("Chat cleared", 'success');
        }, { title: 'Clear Chat', confirmText: 'Clear', danger: true });
    },

    showCharacterProfile: function() {
        if (window.ContactsApp) {
            const oldTitle = document.getElementById('os-app-title').innerText;
            OS.pushView(() => {
                // Restore messenger chat view
                document.getElementById('os-app-title').innerText = oldTitle;
                this.render();
                this.renderChatLog();
            });
            window.ContactsApp.init(this.container);
            window.ContactsApp.showProfile(this.activeCharId);
        }
    },

    renderChatLog: async function(includeArchive = false) {
        const viewport = document.getElementById('chatViewport');
        if (!viewport) return;

        // Save scroll position
        const wasAtBottom = viewport.scrollHeight - viewport.scrollTop <= viewport.clientHeight + 50;
        const oldScrollHeight = viewport.scrollHeight;

        viewport.innerHTML = "";

        let session = State.sessions[this.activeCharId] || [];

        if (includeArchive) {
            const archive = State.getArchive(this.activeCharId);
            session = archive.concat(session);
        } else if (State.hasArchive(this.activeCharId)) {
            const btn = document.createElement('button');
            btn.className = 'btn';
            btn.style.margin = '10px auto';
            btn.style.width = 'auto';
            btn.style.fontSize = '0.75rem';
            btn.style.padding = '6px 16px';
            btn.innerText = "Load Older Messages";
            btn.onclick = () => this.renderChatLog(true);
            viewport.appendChild(btn);
        }

        for (const msg of session) {
            const isUser = msg.sender === 'user';
            const row = document.createElement('div');
            row.className = `bubble-row bubble-row-${isUser ? 'user' : 'ai'}`;

            const bubble = document.createElement('div');
            bubble.className = `bubble bubble-${isUser ? 'user' : 'ai'}`;

            if (isUser && msg.type === 'vision' && msg.attachment) {
                bubble.innerHTML = `
                    <div class="img-container" style="margin-bottom:8px;">
                        <img src="${msg.attachment}" style="width:100%; display:block; border-radius:4px;" onclick="if(window.ImagingApp) ImagingApp.openLocalLightbox(this.src)">
                    </div>
                    ${OS.formatMarkdown(msg.text)}
                `;
            } else if (msg.type === 'img2img') {
                let srcBefore = msg.source;
                let srcAfter = msg.text;
                if (srcBefore && srcBefore.startsWith('db:') && window.ImageDB) srcBefore = await window.ImageDB.get(srcBefore);
                if (srcAfter && srcAfter.startsWith('db:') && window.ImageDB) srcAfter = await window.ImageDB.get(srcAfter);
                bubble.innerHTML = `
                    <div class="compare-grid">
                        <div class="compare-item">
                            <span class="compare-label">Source</span>
                            <img src="${srcBefore}" style="width:100%; display:block;" onclick="if(window.ImagingApp) ImagingApp.openLocalLightbox(this.src, '${msg.source}')">
                        </div>
                        <div class="compare-item">
                            <span class="compare-label">Result</span>
                            <img src="${srcAfter}" style="width:100%; display:block;" onclick="if(window.ImagingApp) ImagingApp.openLocalLightbox(this.src, '${msg.text}')">
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
                        <img src="${src}" style="width:100%; display:block;" onclick="if(window.ImagingApp) ImagingApp.openLocalLightbox(this.src, '${msg.text}')">
                        <div class="img-actions">
                            <button class="btn-img-action" onclick="MessengerApp.remixImage('${msg.text}')">✨ Remix</button>
                        </div>
                    </div>
                `;
            } else {
                bubble.innerHTML = OS.formatMarkdown(msg.text);
            }

            // Time and actions
            const timeStr = new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            bubble.innerHTML += `<span class="bubble-time">${timeStr}</span>`;

            // Long press for actions would be better for WhatsApp style, but let's keep visible actions for now but style them subtly
            const actionsDiv = document.createElement('div');
            actionsDiv.className = `bubble-actions ${!isUser ? 'bubble-actions-left' : ''}`;
            actionsDiv.style.opacity = "0.5";
            actionsDiv.style.marginTop = "4px";

            // Speak
            if (!isUser && msg.type !== 'image' && msg.type !== 'img2img') {
                const speakBtn = document.createElement('button');
                speakBtn.className = 'btn-msg-action';
                speakBtn.textContent = '🔊';
                speakBtn.onclick = () => OS.speak(msg.text);
                actionsDiv.appendChild(speakBtn);
            }

            // Copy
            const copyBtn = document.createElement('button');
            copyBtn.className = 'btn-msg-action';
            copyBtn.textContent = '📋';
            copyBtn.onclick = () => MessengerApp.copyMessage(msg);
            actionsDiv.appendChild(copyBtn);

            // Regen
            if (!isUser && msg.type !== 'image' && msg.type !== 'img2img') {
                const regenBtn = document.createElement('button');
                regenBtn.className = 'btn-msg-action';
                regenBtn.textContent = '🔄';
                regenBtn.onclick = () => MessengerApp.regenerateMessage(msg.id);
                actionsDiv.appendChild(regenBtn);
            }

            // Delete
            const delBtn = document.createElement('button');
            delBtn.className = 'btn-msg-action btn-msg-danger';
            delBtn.textContent = '🗑';
            delBtn.onclick = () => MessengerApp.deleteMessage(msg.id);
            actionsDiv.appendChild(delBtn);

            bubble.appendChild(actionsDiv);
            row.appendChild(bubble);
            viewport.appendChild(row);
        }

        if (includeArchive) {
            // If we loaded archive, stay near the top or where we were
            viewport.scrollTop = viewport.scrollHeight - oldScrollHeight;
        } else if (wasAtBottom || session.length < 10) {
            viewport.scrollTop = viewport.scrollHeight;
        }
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

    // --- Voice Input ---
    isListening: false,
    toggleVoiceInput: function() {
        if (this.isListening) {
            OS.stopListening();
            this.setListeningUI(false);
        } else {
            const input = document.getElementById('chatInputField');
            input.placeholder = "Listening...";
            this.setListeningUI(true);
            OS.listen(
                (text) => {
                    input.value = text;
                    input.placeholder = "Message...";
                    this.setListeningUI(false);
                    this.submitUserMessage();
                },
                (status, code) => {
                    if (status === 'error' || status === 'end') {
                        this.setListeningUI(false);
                        input.placeholder = "Message...";
                    }
                }
            );
        }
    },

    setListeningUI: function(active) {
        this.isListening = active;
        const btn = document.getElementById('btnMic');
        if (!btn) return;
        if (active) {
            btn.classList.add('listening');
            btn.style.background = 'var(--danger)';
            btn.innerHTML = '🛑';
        } else {
            btn.classList.remove('listening');
            btn.style.background = 'rgba(255,255,255,0.1)';
            btn.innerHTML = '🎙️';
        }
    },

    toggleImgMode: function() {
        const isVision = document.getElementById('chkVisionMode').checked;
        const sliderRow = document.getElementById('denoiseSliderRow');
        const label = document.getElementById('imgModeLabel');
        if (isVision) {
            if (sliderRow) sliderRow.style.display = 'none';
            if (label) { label.innerText = "Vision Mode Active"; label.style.color = "#22c55e"; }
        } else {
            if (sliderRow) sliderRow.style.display = 'flex';
            if (label) { label.innerText = "Img2Img Mode Active"; label.style.color = "var(--accent)"; }
        }
    },

    submitUserMessage: async function() {
        const input = document.getElementById('chatInputField');
        const text = input.value;
        const denoiseInput = document.getElementById('inputDenoise');
        const denoise = denoiseInput ? parseFloat(denoiseInput.value) : 0.75;
        const isVision = document.getElementById('chkVisionMode')?.checked || false;

        if (!text.trim() && !this.attachedImage) return;

        const isImg2Img = this.attachedImage && !isVision;
        const currentAttach = this.attachedImage;

        input.value = ""; input.style.height = 'auto';
        this.clearAttachment();

        const msg = {
            id: 'm'+Date.now(),
            sender: 'user',
            text: text || (isImg2Img ? "[Img2Img Request]" : "[Vision Request]"),
            timestamp: Date.now()
        };

        if (currentAttach) {
            msg.type = isVision ? 'vision' : 'img2img';
            // For vision, we might want to show the image in the chat bubble too
            if (isVision) msg.attachment = currentAttach;
        }

        if (!State.sessions[this.activeCharId]) State.sessions[this.activeCharId] = [];
        State.sessions[this.activeCharId].push(msg);
        State.save();
        await this.renderChatLog();

        if (isImg2Img) {
            this.handleImg2ImgRequest(text, currentAttach, denoise);
        } else if (currentAttach && isVision) {
            this.generateAIResponse(text, currentAttach);
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

    generateAIResponse: async function(userText, imageBase64 = null) {
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
            }, true, 'chat', imageBase64);

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

            // Mark messenger as read since user is actively viewing
            if (OS.activeApp === 'MessengerApp' && OS.markAppRead) OS.markAppRead('MessengerApp');

            // Update home screen badge if user is NOT in messenger (background chat)
            if (OS.activeApp !== 'MessengerApp' && OS.updateBadges) OS.updateBadges();

            // Extract memories from this conversation turn (fire-and-forget)
            this.extractMemories(userText, finalText);

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

    // --- Memory Extraction ---
    // After each conversation turn, extract memorable facts about the user
    extractMemories: function(userText, aiResponse) {
        // Only extract if there's meaningful content
        if (!userText || userText.length < 10) return;
        // Don't extract from img2img requests
        if (userText === '[Img2Img Request]') return;

        // Throttle: only extract every ~5 messages to reduce API calls
        const session = State.sessions[this.activeCharId] || [];
        const recentUserMsgs = session.filter(m => m.sender === 'user').length;
        if (recentUserMsgs % 3 !== 0) return; // Extract every 3rd user message

        const char = State.characters.find(c => c.id === this.activeCharId);
        if (!char) return;

        // Fire-and-forget: extract memories via a lightweight LLM call
        const doExtract = async () => {
            try {
                const api = window.API;
                if (!api || !State.settings.key) return;

                const existingMemories = State.getMemories(this.activeCharId);
                const memoryContext = existingMemories.length > 0
                    ? `\n\nExisting memories (don't repeat these):\n${existingMemories.map(m => '- ' + m.text).join('\n')}`
                    : '';

                const extractionPrompt = `Analyze this conversation exchange and extract any NEW memorable facts about the user that the character should remember for future conversations. Focus on: personal preferences, important life events, relationships, personality traits, hobbies, work/school details, health info, goals, or significant experiences.

User said: "${userText}"
Character replied: "${aiResponse}"${memoryContext}

Rules:
- Only extract facts about the USER, not the character
- Each fact should be a short, specific sentence (max 15 words)
- Output each fact on a new line
- Prefix each line with a category tag: [preference], [fact], [event], [relationship], or [general]
- If nothing memorable was shared, output exactly: NONE
- Do NOT repeat existing memories
- Do NOT include generic observations like "user is talking" or "user asked a question"

Examples:
[preference] Loves spicy food and Thai cuisine
[fact] Works as a software engineer at a startup
[event] Moving to a new apartment next week
[relationship] Has a younger sister named Maria
[general] Plays guitar in free time`;

                const result = await api.sendMessage(this.activeCharId, extractionPrompt, null, false, 'chat');
                if (!result || result.trim().toUpperCase() === 'NONE') return;

                // Parse the extracted memories
                const lines = result.split('\n').filter(l => l.trim());
                for (const line of lines) {
                    const match = line.match(/\[(preference|fact|event|relationship|general)\]\s*(.+)/i);
                    if (match) {
                        const category = match[1].toLowerCase();
                        const text = match[2].trim();
                        if (text && text.length > 5 && text.length < 100) {
                            State.addMemory(this.activeCharId, text, category);
                        }
                    }
                }
            } catch(e) {
                console.warn("Memory extraction failed:", e);
            }
        };

        // Delay extraction to not interfere with the main conversation flow
        setTimeout(doExtract, 2000);
    },

    // --- Memory Viewer ---
    showMemories: function() {
        const existing = document.getElementById('memoryOverlay');
        if (existing) existing.remove();

        const char = State.characters.find(c => c.id === this.activeCharId) || { name: 'Character' };
        const memories = State.getMemories(this.activeCharId);

        const categoryEmoji = {
            preference: '💜',
            fact: '📌',
            event: '📅',
            relationship: '❤️',
            general: '🧠'
        };

        let memoriesHtml = '';
        if (memories.length === 0) {
            memoriesHtml = '<div style="text-align:center; color:var(--text-muted); padding:30px; font-style:italic;">No memories yet.<br>Chat more and memories will form automatically!</div>';
        } else {
            memoriesHtml = memories.map(m => {
                const emoji = categoryEmoji[m.category] || '🧠';
                const age = State.formatMemoryAge(m.timestamp);
                return `
                    <div style="display:flex; align-items:flex-start; gap:8px; padding:10px 12px; border-radius:10px; background:rgba(255,255,255,0.02); border:1px solid var(--border);">
                        <span style="font-size:1rem; flex-shrink:0;">${emoji}</span>
                        <div style="flex:1; min-width:0;">
                            <div style="font-size:0.85rem; color:white; line-height:1.4;">${m.text}</div>
                            <div style="font-size:0.65rem; color:var(--text-muted); margin-top:3px;">${m.category} · ${age}</div>
                        </div>
                        <button onclick="MessengerApp.deleteMemory('${m.id}')" style="background:none; border:none; color:#ef4444; font-size:0.8rem; cursor:pointer; padding:2px 6px; flex-shrink:0;">✕</button>
                    </div>
                `;
            }).join('');
        }

        const overlay = document.createElement('div');
        overlay.id = 'memoryOverlay';
        overlay.style.cssText = 'position:fixed; top:0; left:0; right:0; bottom:0; z-index:99998; display:flex; align-items:flex-start; justify-content:center; padding-top:60px; background:rgba(0,0,0,0.6); backdrop-filter:blur(6px);';
        overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };

        const sheet = document.createElement('div');
        sheet.style.cssText = 'background:#1a1a1e; border-radius:20px; padding:16px; min-width:260px; max-width:360px; width:85%; box-shadow:0 20px 60px rgba(0,0,0,0.6); border:1px solid rgba(255,255,255,0.06); max-height:70vh; overflow-y:auto;';
        sheet.onclick = (e) => e.stopPropagation();

        sheet.innerHTML = `
            <div style="font-size:1rem; font-weight:800; color:var(--accent); margin-bottom:4px; text-align:center;">🧠 Memories</div>
            <div style="font-size:0.75rem; color:var(--text-muted); text-align:center; margin-bottom:14px;">What ${char.name} remembers about you</div>
            <div style="display:flex; gap:8px; margin-bottom:14px;">
                <button onclick="MessengerApp.addMemoryManual()" style="flex:1; padding:8px; background:var(--accent); color:white; border:none; border-radius:10px; font-weight:700; font-size:0.8rem; cursor:pointer;">+ Add Memory</button>
                <button onclick="MessengerApp.clearAllMemories()" style="padding:8px 12px; background:rgba(239,68,68,0.1); color:#ef4444; border:1px solid rgba(239,68,68,0.2); border-radius:10px; font-weight:700; font-size:0.8rem; cursor:pointer;">🗑️</button>
            </div>
            <div style="display:flex; flex-direction:column; gap:6px; max-height:50vh; overflow-y:auto;">
                ${memoriesHtml}
            </div>
        `;

        overlay.appendChild(sheet);
        document.body.appendChild(overlay);
    },

    addMemoryManual: function() {
        OS.prompt("Add a memory (what should this character remember about you?):", "", (text) => {
            if (!text || text.length < 3) return;
            State.addMemory(this.activeCharId, text, 'general');
            this.showMemories(); // Refresh the view
        });
    },

    deleteMemory: function(memoryId) {
        State.deleteMemory(this.activeCharId, memoryId);
        this.showMemories(); // Refresh
    },

    clearAllMemories: function() {
        OS.confirm("Clear all memories for this character?", () => {
            State.clearMemories(this.activeCharId);
            this.showMemories(); // Refresh
        }, { title: 'Clear Memories', confirmText: 'Clear All', danger: true });
    },

    // --- Clear Chat ---
    deleteCurrentChat: function() {
        const char = State.characters.find(c => c.id === this.activeCharId) || { name: 'Chat' };
        OS.confirm(`Clear all messages with ${char.name}?`, () => {
            State.sessions[this.activeCharId] = [];
            State.save();
            this.renderChatLog();
            OS.toast("Chat cleared", 'success');
        }, { title: 'Clear Chat', confirmText: 'Clear', danger: true });
    },

    // --- Delete Single Message ---
    deleteMessage: function(msgId) {
        const session = State.sessions[this.activeCharId];
        if (!session) return;
        const idx = session.findIndex(m => m.id === msgId);
        if (idx === -1) return;
        session.splice(idx, 1);
        State.save();
        this.renderChatLog();
    },

    // --- Copy Message ---
    copyMessage: function(msg) {
        const text = msg.text || '';
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(() => {
                OS.toast("Copied!", 'success');
            }).catch(() => {
                this._fallbackCopy(text);
            });
        } else {
            this._fallbackCopy(text);
        }
    },

    _fallbackCopy: function(text) {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.select();
        try {
            document.execCommand('copy');
            OS.toast("Copied!", 'success');
        } catch(e) {
            OS.toast("Copy failed", 'error');
        }
        document.body.removeChild(ta);
    },

    // --- Regenerate AI Message ---
    regenerateMessage: function(msgId) {
        const session = State.sessions[this.activeCharId];
        if (!session) return;
        const idx = session.findIndex(m => m.id === msgId);
        if (idx === -1) return;

        // Find the user message that preceded this AI message
        let userText = '';
        for (let i = idx - 1; i >= 0; i--) {
            if (session[i].sender === 'user') {
                userText = session[i].text;
                break;
            }
        }

        // Remove the AI message from state
        session.splice(idx, 1);
        State.save();
        this.renderChatLog();

        // Re-generate the response
        if (userText) {
            this.generateAIResponse(userText);
        }
    },

    cleanup: function() {
        if (this.isListening) {
            OS.stopListening();
            this.isListening = false;
        }
    }
};
window.MessengerApp = MessengerApp;
