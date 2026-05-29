/**
 * messenger.js
 * Messenger Module for Fancy AI OS
 * Features: Real-Time Streaming, Stop Generation, Multi-line Input, Img2Img.
 */
const MessengerApp = {
    container: null,
    activeCharId: null,
    attachedImage: null,
    currentView: 'list',

    init: async function(container, params) {
        this.container = container;
        this.injectStyles();

        // Contacts management (profile / new / import / dossier) is delegated to
        // ContactsApp, which is no longer launched directly from home.
        if (!window.ContactsApp) { try { await OS._loadScript('js/apps/contacts.js'); } catch (e) {} }

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
            /* ─── Layout ─── */
            .chat-wrapper { display: flex; flex-direction: column; height: 100%; background: #0b141a; }
            .chat-header-bar { padding: 10px 16px; background: #1f2c34; display: flex; align-items: center; gap: 12px; padding-top: calc(10px + env(safe-area-inset-top)); border-bottom: 1px solid rgba(255,255,255,0.04); }
            .chat-avatar { width: 40px; height: 40px; border-radius: 50%; background: #2d3f4a; color: #8ea8b5; display: flex; align-items: center; justify-content: center; font-weight: 800; font-size: 1rem; overflow: hidden; flex-shrink: 0; }
            .chat-avatar img { width: 100%; height: 100%; object-fit: cover; }
            .chat-viewport { flex: 1; padding: 10px 12px; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; background: #0b141a; }

            /* ─── Conversation List ─── */
            .conv-list-header { padding: 14px 16px; background: #1f2c34; display: flex; align-items: center; justify-content: space-between; padding-top: calc(14px + env(safe-area-inset-top)); border-bottom: 1px solid rgba(255,255,255,0.04); }
            .conv-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; padding-bottom: 80px; background: #0b141a; }
            .conv-item { display: flex; align-items: center; gap: 14px; padding: 11px 16px; border-bottom: 1px solid #111c22; cursor: pointer; transition: background 0.1s; }
            .conv-item:active { background: #1f2c34; }
            .conv-avatar { width: 52px; height: 52px; border-radius: 50%; background: #2d3f4a; color: #8ea8b5; display: flex; align-items: center; justify-content: center; font-size: 1.25rem; font-weight: 800; overflow: hidden; flex-shrink: 0; }
            .conv-avatar img { width: 100%; height: 100%; object-fit: cover; }
            .conv-info { flex: 1; min-width: 0; }
            .conv-name-row { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 3px; gap: 8px; }
            .conv-name { color: #e9edef; font-weight: 600; font-size: 1rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
            .conv-time { color: #6a7e8a; font-size: 0.7rem; flex-shrink: 0; }
            .conv-msg-row { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
            .conv-last-msg { color: #6a7e8a; font-size: 0.83rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
            .conv-unread { background: #00a884; color: #0b141a; min-width: 20px; height: 20px; border-radius: 10px; display: flex; align-items: center; justify-content: center; font-size: 0.66rem; font-weight: 800; padding: 0 5px; flex-shrink: 0; }

            /* ─── Bubbles ─── */
            .bubble-row { display: flex; margin-bottom: 2px; width: 100%; }
            .bubble-row-user { justify-content: flex-end; }
            .bubble-row-ai { justify-content: flex-start; }
            .bubble { padding: 7px 10px 4px; border-radius: 8px; font-size: 0.9rem; line-height: 1.5; word-break: break-word; position: relative; max-width: 82%; }
            .bubble-user { background: #005c4b; color: #e9edef; border-top-right-radius: 2px; }
            .bubble-ai { background: #1f2c34; color: #e9edef; border-top-left-radius: 2px; }
            .bubble-row-avatar, .bubble-row-name, .bubble-row-content { display: none; }
            .bubble-time { font-size: 0.6rem; color: rgba(233,237,239,0.45); margin-top: 3px; text-align: right; display: block; }

            /* ─── Typing Indicator ─── */
            .typing-indicator { display: flex; align-items: center; gap: 5px; padding: 6px 4px; min-width: 52px; }
            .typing-dot { width: 7px; height: 7px; border-radius: 50%; background: #6a7e8a; animation: typingBounce 1.4s infinite ease-in-out both; }
            .typing-dot:nth-child(1) { animation-delay: -0.32s; }
            .typing-dot:nth-child(2) { animation-delay: -0.16s; }
            .typing-dot:nth-child(3) { animation-delay: 0; }
            @keyframes typingBounce { 0%, 80%, 100% { transform: scale(0.5); opacity: 0.3; } 40% { transform: scale(1); opacity: 1; } }

            /* ─── Input Area ─── */
            .chat-input-area { padding: 8px 10px; background: #1f2c34; display: flex; flex-direction: column; gap: 6px; padding-bottom: calc(8px + env(safe-area-inset-bottom)); border-top: 1px solid rgba(255,255,255,0.04); }
            .input-row { display: flex; gap: 8px; align-items: flex-end; }
            .chat-field { flex: 1; background: #2a3942; border: none; padding: 10px 14px; border-radius: 22px; color: #d1d7db; outline: none; font-family: inherit; font-size: 0.9rem; resize: none; max-height: 150px; min-height: 42px; line-height: 1.45; }
            .chat-field::placeholder { color: #4a5e6a; }
            .btn-send, .btn-mic, .btn-stop { width: 44px; height: 44px; border-radius: 50%; border: none; cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 1.1rem; flex-shrink: 0; transition: transform 0.1s, opacity 0.1s; }
            .btn-send:active, .btn-mic:active, .btn-stop:active { transform: scale(0.9); }
            .btn-send { background: #00a884; color: white; }
            .btn-mic { background: #2a3942; color: #6a7e8a; }
            .btn-stop { background: #ef4444; color: white; display: none; }
            .btn-attach { background: none; color: #6a7e8a; border: none; width: 36px; height: 44px; cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 1.3rem; flex-shrink: 0; }
            .btn-mic.listening { background: #c0392b; color: white; animation: pulseAnim 1.2s infinite; }

            /* ─── Attachment & Img2Img ─── */
            .attachment-preview { position: relative; width: 56px; height: 56px; border-radius: 10px; overflow: hidden; border: 2px solid #00a884; }
            .attachment-preview img { width: 100%; height: 100%; object-fit: cover; }
            .btn-remove-attach { position: absolute; top: 0; right: 0; background: rgba(0,0,0,0.7); color: white; border: none; width: 18px; height: 18px; font-size: 9px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
            .denoise-control { display: flex; align-items: center; gap: 10px; background: #2a3942; padding: 8px 12px; border-radius: 10px; }
            .denoise-control label { font-size: 0.7rem; color: #6a7e8a; font-weight: 700; white-space: nowrap; }
            .denoise-control input { flex: 1; accent-color: #00a884; height: 4px; }

            /* ─── Image Bubbles ─── */
            .img-container { position: relative; border-radius: 8px; overflow: hidden; background: #000; margin: 2px 0; max-width: 260px; }
            .img-container img { width: 100%; display: block; }
            .compare-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 2px; background: rgba(255,255,255,0.06); border-radius: 8px; overflow: hidden; }
            .compare-item { position: relative; }
            .compare-label { position: absolute; top: 4px; left: 4px; background: rgba(0,0,0,0.7); color: white; font-size: 0.58rem; padding: 2px 5px; border-radius: 3px; font-weight: 700; text-transform: uppercase; }
            .img-actions { position: absolute; bottom: 6px; right: 6px; }
            .btn-img-action { background: rgba(0,0,0,0.65); color: white; border: none; padding: 4px 10px; border-radius: 5px; font-size: 0.7rem; font-weight: 700; cursor: pointer; }

            /* ─── Message Actions ─── */
            .bubble-actions { display: flex; gap: 4px; margin-top: 3px; }
            .bubble-actions-left { justify-content: flex-start; }
            .bubble-actions-right { justify-content: flex-end; }
            .btn-msg-action { background: rgba(255,255,255,0.04); color: rgba(233,237,239,0.4); border: none; width: 24px; height: 24px; border-radius: 6px; font-size: 0.65rem; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: all 0.1s; }
            .btn-msg-action:hover { background: rgba(255,255,255,0.1); color: #e9edef; }
            .btn-msg-action.btn-msg-danger:hover { background: rgba(239,68,68,0.15); color: #f87171; }

            /* ─── Animations ─── */
            @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
            @keyframes pulseAnim { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }
            .spinning { animation: spin 2s linear infinite !important; display: inline-block !important; }
        `;
        document.head.appendChild(style);
    },

    renderConversationList: function() {
        this.activeCharId = null;
        this.currentView = 'list';

        if (document.getElementById('os-app-title')) {
            document.getElementById('os-app-title').innerText = "Contacts";
        }

        this.container.innerHTML = `
            <div class="chat-wrapper">
                <div class="conv-list-header">
                    <div style="font-weight: 800; font-size: 1.15rem; color: white;">Contacts</div>
                    <div style="display:flex; gap:8px; align-items:center;">
                        <label style="background:#2a3942; color:#8ea8b5; padding:7px 12px; border-radius:20px; font-size:0.8rem; font-weight:700; cursor:pointer; margin:0;">
                            📥 Import
                            <input type="file" style="display:none" accept=".json,.png" onchange="MessengerApp.importCharacter(event)">
                        </label>
                        <button onclick="MessengerApp.newCharacter()" style="background:#00a884; border:none; color:white; padding:7px 13px; border-radius:20px; font-size:0.8rem; font-weight:700; cursor:pointer;">+ New</button>
                    </div>
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
                        <div class="conv-last-msg">${lastMsg ? (lastMsg.type === 'image' ? '📷 Photo' : (lastMsg.text || '').substring(0, 60)) : 'Start a conversation'}</div>
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
        if (diffDays < 7) return date.toLocaleDateString([], { weekday: 'short' });
        return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
    },

    openChat: function(charId) {
        this.activeCharId = charId;
        this.currentView = 'chat';
        State.activeCharId = charId;

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
                if (!State.chatReadTimestamps) State.chatReadTimestamps = {};
                State.chatReadTimestamps[charId] = Date.now();
                State.save();
                this.renderConversationList();
            });
        }

        this.render();
        this.renderChatLog();

        if (window.OS && OS.updateBadges) OS.updateBadges();
    },

    render: function() {
        const char = State.characters.find(c => c.id === this.activeCharId) || { name: "Assistant" };
        this.container.innerHTML = `
            <div class="chat-wrapper">
                <div class="chat-header-bar">
                    <div class="chat-avatar" id="chatHeaderAvatar" onclick="MessengerApp.showCharacterProfile()" style="cursor:pointer;">${char.name[0]}</div>
                    <div style="flex: 1; display:flex; flex-direction:column; cursor:pointer; overflow:hidden;" onclick="MessengerApp.showCharacterProfile()">
                        <div style="font-weight: 700; color: #e9edef; font-size: 0.95rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">${char.name}</div>
                        <div id="chatHeaderStatus" style="font-size: 0.62rem; color: #6a7e8a; font-weight: 500;"></div>
                    </div>
                    <div style="display:flex; gap:4px; align-items:center;">
                        <button id="btnEvolve" onclick="MessengerApp.triggerManualEvolution()" style="background:none; border:none; font-size:1.05rem; cursor:pointer; color:#6a7e8a; width:36px; height:36px; border-radius:50%; display:flex; align-items:center; justify-content:center;" title="Evolve Dossier">🌀</button>
                        <button onclick="MessengerApp.showMemories()" style="background:none; border:none; font-size:1.05rem; cursor:pointer; color:#6a7e8a; width:36px; height:36px; border-radius:50%; display:flex; align-items:center; justify-content:center;" title="Memories">🧠</button>
                        <button onclick="MessengerApp.showChatMenu()" style="background:none; border:none; font-size:1.2rem; cursor:pointer; color:#6a7e8a; width:36px; height:36px; border-radius:50%; display:flex; align-items:center; justify-content:center;" title="Menu">⋮</button>
                    </div>
                </div>

                <div id="chatViewport" class="chat-viewport"></div>

                <div class="chat-input-area">
                    <div id="img2imgControls" style="display: none;">
                        <div id="denoiseSliderRow" class="denoise-control" style="margin-bottom:6px;">
                            <label>Denoise: <span id="valDenoise">0.75</span></label>
                            <input type="range" id="inputDenoise" min="0.05" max="1.0" step="0.05" value="0.75" oninput="document.getElementById('valDenoise').innerText = this.value">
                        </div>
                        <div style="display: flex; gap: 10px; margin-bottom: 6px; align-items: center;">
                            <div class="attachment-preview" id="attachPreview">
                                <img id="attachImg" src="">
                                <button class="btn-remove-attach" onclick="MessengerApp.clearAttachment()">✕</button>
                            </div>
                            <div style="flex:1; display:flex; flex-direction:column; gap:6px;">
                                <div style="color:#00a884; font-size:0.75rem; font-weight:700;" id="imgModeLabel">Img2Img Mode</div>
                                <label style="display:flex; align-items:center; gap:6px; cursor:pointer;">
                                    <input type="checkbox" id="chkVisionMode" style="width:14px; height:14px; accent-color:#00a884;" onchange="MessengerApp.toggleImgMode()">
                                    <span style="color:#6a7e8a; font-size:0.72rem; font-weight:600;">Vision (describe image)</span>
                                </label>
                            </div>
                        </div>
                    </div>
                    <div class="input-row">
                        <button class="btn-attach" onclick="document.getElementById('chatFileInput').click()" title="Attach image">📎</button>
                        <input type="file" id="chatFileInput" hidden accept="image/*" onchange="MessengerApp.handleFileSelect(event)">
                        <textarea id="chatInputField" class="chat-field" placeholder="Message" rows="1"></textarea>
                        <button id="btnStop" class="btn-stop" onclick="MessengerApp.stopGeneration()" title="Stop generating">⏹</button>
                        <button id="btnMic" class="btn-mic" onclick="MessengerApp.toggleVoiceInput()" title="Voice input">🎙️</button>
                        <button id="btnSend" class="btn-send" style="display:none;" onclick="MessengerApp.submitUserMessage()" title="Send">➤</button>
                    </div>
                </div>
            </div>
        `;

        const input = document.getElementById('chatInputField');
        if (input) {
            input.addEventListener('input', function() {
                this.style.height = 'auto';
                this.style.height = Math.min(this.scrollHeight, 150) + 'px';

                const sendBtn = document.getElementById('btnSend');
                const micBtn = document.getElementById('btnMic');
                if (this.value.trim().length > 0) {
                    if (sendBtn) sendBtn.style.display = 'flex';
                    if (micBtn) micBtn.style.display = 'none';
                } else {
                    if (sendBtn) sendBtn.style.display = 'none';
                    if (micBtn) micBtn.style.display = 'flex';
                }
            });
        }
        this.updateHeaderAvatar();
    },

    stopGeneration: function() {
        if (window.API) API.abort();
    },

    _showStopButton: function() {
        const stopBtn = document.getElementById('btnStop');
        const sendBtn = document.getElementById('btnSend');
        const micBtn = document.getElementById('btnMic');
        if (stopBtn) stopBtn.style.display = 'flex';
        if (sendBtn) sendBtn.style.display = 'none';
        if (micBtn) micBtn.style.display = 'none';
    },

    _hideStopButton: function() {
        const stopBtn = document.getElementById('btnStop');
        const micBtn = document.getElementById('btnMic');
        if (stopBtn) stopBtn.style.display = 'none';
        const input = document.getElementById('chatInputField');
        const sendBtn = document.getElementById('btnSend');
        if (input && input.value.trim()) {
            if (sendBtn) sendBtn.style.display = 'flex';
        } else {
            if (micBtn) micBtn.style.display = 'flex';
        }
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
        if (src.startsWith('db:') && window.ImageDB) src = await window.ImageDB.get(src);
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
        if (!window.ContactsApp) return;
        const oldTitle = document.getElementById('os-app-title').innerText;
        ContactsApp.container = this.container;
        ContactsApp.injectStyles();
        // Open the editable profile as a sub-view; back from it returns to this chat.
        ContactsApp.showProfile(this.activeCharId, () => {
            document.getElementById('os-app-title').innerText = oldTitle;
            this.render();
            this.renderChatLog();
        });
    },

    // --- Contact management (delegated to ContactsApp, refreshing this hub) ---
    newCharacter: async function() {
        if (!window.ContactsApp) { try { await OS._loadScript('js/apps/contacts.js'); } catch (e) {} }
        if (!window.ContactsApp) { OS.toast("Could not load contacts module", 'error'); return; }
        ContactsApp.container = this.container;
        ContactsApp.newCharacter(() => this.renderConversationList());
    },

    importCharacter: async function(event) {
        if (!window.ContactsApp) { try { await OS._loadScript('js/apps/contacts.js'); } catch (e) {} }
        if (!window.ContactsApp) { OS.toast("Could not load contacts module", 'error'); return; }
        ContactsApp.container = this.container;
        ContactsApp.importCharacter(event, () => this.renderConversationList());
    },

    renderChatLog: async function(includeArchive = false) {
        const viewport = document.getElementById('chatViewport');
        if (!viewport) return;

        const wasAtBottom = viewport.scrollHeight - viewport.scrollTop <= viewport.clientHeight + 60;
        const oldScrollHeight = viewport.scrollHeight;

        viewport.innerHTML = "";

        let session = State.sessions[this.activeCharId] || [];

        if (includeArchive) {
            const archive = State.getArchive(this.activeCharId);
            session = archive.concat(session);
        } else if (State.hasArchive(this.activeCharId)) {
            const btn = document.createElement('button');
            btn.style.cssText = 'margin: 10px auto; display: block; background: #1f2c34; color: #6a7e8a; border: 1px solid #2a3942; padding: 6px 16px; border-radius: 20px; font-size: 0.75rem; font-weight: 600; cursor: pointer;';
            btn.innerText = "Load older messages";
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
                    <div class="img-container" style="margin-bottom:6px; max-width:200px;">
                        <img src="${msg.attachment}" style="width:100%; display:block; border-radius:6px;" onclick="OS.openLightbox(this.src)">
                    </div>
                    ${OS.formatMarkdown(msg.text)}
                `;
            } else if (msg.type === 'img2img') {
                let srcBefore = msg.source;
                let srcAfter = msg.text;
                if (srcBefore && srcBefore.startsWith('db:') && window.ImageDB) srcBefore = await window.ImageDB.get(srcBefore);
                if (srcAfter && srcAfter.startsWith('db:') && window.ImageDB) srcAfter = await window.ImageDB.get(srcAfter);
                bubble.innerHTML = `
                    <div class="compare-grid" style="max-width:260px;">
                        <div class="compare-item">
                            <span class="compare-label">Before</span>
                            <img src="${srcBefore}" style="width:100%; display:block;" onclick="OS.openLightbox(this.src)">
                        </div>
                        <div class="compare-item">
                            <span class="compare-label">After</span>
                            <img src="${srcAfter}" style="width:100%; display:block;" onclick="OS.openLightbox(this.src)">
                        </div>
                    </div>
                `;
            } else if (msg.type === 'image') {
                let src = msg.text;
                if (src && src.startsWith('db:') && window.ImageDB) src = await window.ImageDB.get(src);
                bubble.innerHTML = `
                    <div class="img-container">
                        <img src="${src}" style="width:100%; display:block;" onclick="OS.openLightbox(this.src)">
                        <div class="img-actions">
                            <button class="btn-img-action" onclick="MessengerApp.remixImage('${msg.text}')">✨ Remix</button>
                        </div>
                    </div>
                `;
            } else {
                bubble.innerHTML = OS.formatMarkdown(msg.text || '');
            }

            const timeStr = new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            bubble.innerHTML += `<span class="bubble-time">${timeStr}</span>`;

            const actionsDiv = document.createElement('div');
            actionsDiv.className = `bubble-actions ${isUser ? 'bubble-actions-right' : 'bubble-actions-left'}`;

            if (!isUser && msg.type !== 'image' && msg.type !== 'img2img') {
                const speakBtn = document.createElement('button');
                speakBtn.className = 'btn-msg-action';
                speakBtn.title = 'Speak';
                speakBtn.textContent = '🔊';
                speakBtn.onclick = () => OS.speak(msg.text);
                actionsDiv.appendChild(speakBtn);
            }

            const copyBtn = document.createElement('button');
            copyBtn.className = 'btn-msg-action';
            copyBtn.title = 'Copy';
            copyBtn.textContent = '📋';
            copyBtn.onclick = () => MessengerApp.copyMessage(msg);
            actionsDiv.appendChild(copyBtn);

            if (!isUser && msg.type !== 'image' && msg.type !== 'img2img') {
                const regenBtn = document.createElement('button');
                regenBtn.className = 'btn-msg-action';
                regenBtn.title = 'Regenerate';
                regenBtn.textContent = '🔄';
                regenBtn.onclick = () => MessengerApp.regenerateMessage(msg.id);
                actionsDiv.appendChild(regenBtn);
            }

            const delBtn = document.createElement('button');
            delBtn.className = 'btn-msg-action btn-msg-danger';
            delBtn.title = 'Delete';
            delBtn.textContent = '🗑';
            delBtn.onclick = () => MessengerApp.deleteMessage(msg.id);
            actionsDiv.appendChild(delBtn);

            bubble.appendChild(actionsDiv);
            row.appendChild(bubble);
            viewport.appendChild(row);
        }

        if (includeArchive) {
            viewport.scrollTop = viewport.scrollHeight - oldScrollHeight;
        } else if (wasAtBottom || session.length < 10) {
            viewport.scrollTop = viewport.scrollHeight;
        }
    },

    remixImage: async function(imgRef) {
        let src = imgRef;
        if (imgRef.startsWith('db:') && window.ImageDB) src = await window.ImageDB.get(imgRef);
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
                    input.placeholder = "Message";
                    this.setListeningUI(false);
                    this.submitUserMessage();
                },
                (status) => {
                    if (status === 'error' || status === 'end') {
                        this.setListeningUI(false);
                        input.placeholder = "Message";
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
            btn.innerHTML = '🛑';
        } else {
            btn.classList.remove('listening');
            btn.innerHTML = '🎙️';
        }
    },

    toggleImgMode: function() {
        const isVision = document.getElementById('chkVisionMode').checked;
        const sliderRow = document.getElementById('denoiseSliderRow');
        const label = document.getElementById('imgModeLabel');
        if (isVision) {
            if (sliderRow) sliderRow.style.display = 'none';
            if (label) { label.innerText = "Vision Mode"; label.style.color = "#22c55e"; }
        } else {
            if (sliderRow) sliderRow.style.display = 'flex';
            if (label) { label.innerText = "Img2Img Mode"; label.style.color = "#00a884"; }
        }
    },

    submitUserMessage: async function() {
        const input = document.getElementById('chatInputField');
        const text = input.value;
        const denoiseInput = document.getElementById('inputDenoise');
        const denoise = denoiseInput ? parseFloat(denoiseInput.value) : 0.75;
        const isVision = document.getElementById('chkVisionMode')?.checked || false;

        if (!text.trim() && !this.attachedImage) return;
        if (OS.guardBusy("⏳ Please wait — finishing the current task…")) return;

        const isImg2Img = this.attachedImage && !isVision;
        const currentAttach = this.attachedImage;

        input.value = "";
        input.style.height = 'auto';
        this.clearAttachment();

        const msg = {
            id: 'm' + Date.now(),
            sender: 'user',
            text: text || (isImg2Img ? "[Img2Img Request]" : "[Vision Request]"),
            timestamp: Date.now()
        };

        if (currentAttach) {
            msg.type = isVision ? 'vision' : 'img2img';
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
        progDiv.style.cssText = 'font-style:italic; color:#00a884; margin: 2px 0;';
        progDiv.innerText = "⏳ Img2Img: 0%";

        const progRow = document.createElement('div');
        progRow.className = 'bubble-row bubble-row-ai';
        progRow.appendChild(progDiv);
        viewport.appendChild(progRow);
        viewport.scrollTop = viewport.scrollHeight;

        try {
            if (!window.ImagingApp) throw new Error("Imaging engine not loaded.");
            window.ImagingApp.attachedImage = sourceB64;

            const tempSettings = Object.assign({}, ImagingApp.getSettings ? ImagingApp.getSettings() : {}, { imgDenoising: denoise });
            const b64Result = await window.ImagingApp.generate(prompt, tempSettings, (percent) => {
                progDiv.innerText = `⏳ Transform: ${percent}%`;
            });

            progRow.remove();

            if (b64Result && window.ImageDB) {
                const sourceRef = await window.ImageDB.save('src_' + Date.now(), sourceB64);
                const resultRef = await window.ImageDB.save('res_' + Date.now(), b64Result);
                State.sessions[this.activeCharId].push({
                    id: 'm' + Date.now(), sender: 'ai', type: 'img2img',
                    source: sourceRef, text: resultRef, timestamp: Date.now()
                });
                this.renderChatLog();
            }
        } catch (e) {
            progDiv.innerText = "Error: " + e.message;
            progDiv.style.color = "#f87171";
        }
    },

    generateAIResponse: async function(userText, imageBase64 = null) {
        const viewport = document.getElementById('chatViewport');
        const aiId = 'ai' + Date.now();

        const row = document.createElement('div');
        row.className = 'bubble-row bubble-row-ai';

        const bubble = document.createElement('div');
        bubble.className = 'bubble bubble-ai';
        bubble.innerHTML = '<div class="typing-indicator"><span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span></div>';

        row.appendChild(bubble);
        viewport.appendChild(row);
        viewport.scrollTop = viewport.scrollHeight;

        this._showStopButton();

        const statusEl = document.getElementById('chatHeaderStatus');
        const startTs = Date.now();
        let firstTokenTs = 0, chunkCount = 0;

        try {
            const response = await API.sendMessage(this.activeCharId, userText, (chunk) => {
                if (!firstTokenTs) firstTokenTs = Date.now();
                chunkCount++;
                bubble.innerHTML = OS.formatMarkdown(chunk);
                // Live tokens/sec — exact for on-device (one callback per token),
                // approximate for cloud streaming (chars/4 floor).
                const secs = (Date.now() - firstTokenTs) / 1000;
                if (statusEl && secs > 0.25) {
                    const approxTokens = Math.max(chunkCount, Math.round(chunk.length / 4));
                    statusEl.textContent = `typing… ${(approxTokens / secs).toFixed(1)} tok/s`;
                }
                viewport.scrollTop = viewport.scrollHeight;
            }, true, 'chat', imageBase64);

            // Text finished streaming — leave the final tok/s shown in the header.
            if (statusEl) {
                const secs = (Date.now() - (firstTokenTs || startTs)) / 1000;
                const approxTokens = Math.max(chunkCount, Math.round((response || '').length / 4));
                if (secs > 0.2 && approxTokens > 0) {
                    statusEl.textContent = `${(approxTokens / secs).toFixed(1)} tok/s`;
                }
            }

            let finalText = response;
            let imagePrompt = null;

            if (finalText && finalText.toLowerCase().includes("flux prompt:")) {
                const parts = finalText.split(/flux prompt:/i);
                finalText = parts[0].trim();
                imagePrompt = parts[1].trim();
            }

            bubble.innerHTML = OS.formatMarkdown(finalText);

            if (!finalText.trim()) {
                // Abort with no content — remove the bubble silently
                row.remove();
                return;
            }

            const aiMsg = { id: aiId, sender: 'ai', text: finalText, timestamp: Date.now() };
            State.sessions[this.activeCharId].push(aiMsg);
            State.save();

            if (OS.activeApp === 'MessengerApp' && OS.markAppRead) OS.markAppRead('MessengerApp');
            if (OS.activeApp !== 'MessengerApp' && OS.updateBadges) OS.updateBadges();

            this.extractMemories(userText, finalText);

            if (imagePrompt && window.ImagingApp) {
                const progRow = document.createElement('div');
                progRow.className = 'bubble-row bubble-row-ai';
                const progBubble = document.createElement('div');
                progBubble.className = 'bubble bubble-ai';
                progBubble.style.cssText = 'font-style:italic; color:#00a884;';
                progBubble.innerText = "⏳ Generating image: 0%";
                progRow.appendChild(progBubble);
                viewport.appendChild(progRow);
                viewport.scrollTop = viewport.scrollHeight;

                window.ImagingApp.attachedImage = null;
                const b64 = await window.ImagingApp.generate(imagePrompt, null, (percent) => {
                    progBubble.innerText = `⏳ Generating image: ${percent}%`;
                });

                progRow.remove();

                if (b64 && window.ImageDB) {
                    const dbRef = await window.ImageDB.save('img_' + Date.now(), b64);
                    const imgMsg = { id: 'm' + Date.now(), sender: 'ai', type: 'image', text: dbRef, timestamp: Date.now() };
                    State.sessions[this.activeCharId].push(imgMsg);

                    // Update character avatar with the new generated image
                    const charIndex = State.characters.findIndex(c => c.id === this.activeCharId);
                    if (charIndex !== -1) State.characters[charIndex].avatar = dbRef;

                    this.renderChatLog();
                }
            }
        } catch (e) {
            bubble.innerHTML = `<span style="color:#f87171;">Error: ${e.message}</span>`;
            const s = document.getElementById('chatHeaderStatus');
            if (s) s.textContent = '';
        } finally {
            this._hideStopButton();
        }
    },

    // --- Memory Extraction ---
    extractMemories: function(userText, aiResponse) {
        if (!userText || userText.length < 10) return;
        if (userText === '[Img2Img Request]' || userText === '[Vision Request]') return;

        const session = State.sessions[this.activeCharId] || [];
        const recentUserMsgs = session.filter(m => m.sender === 'user').length;
        if (recentUserMsgs % 3 !== 0) return;

        const char = State.characters.find(c => c.id === this.activeCharId);
        if (!char) return;

        const doExtract = async () => {
            try {
                const api = window.API;
                if (!api || !api.hasApiKey()) return;

                const existingMemories = State.getMemories(this.activeCharId);
                const memoryContext = existingMemories.length > 0
                    ? `\n\nExisting memories (don't repeat these):\n${existingMemories.map(m => '- ' + m.text).join('\n')}`
                    : '';

                const extractionPrompt = `Analyze this conversation exchange and extract NEW memorable facts about the user for future conversations. Focus on: preferences, life events, relationships, personality, hobbies, work, goals.

User said: "${userText}"
Character replied: "${aiResponse}"${memoryContext}

Rules:
- Only extract facts about the USER
- Each fact: short specific sentence (max 15 words)
- One fact per line, prefixed with category: [preference], [fact], [event], [relationship], or [general]
- If nothing memorable, output exactly: NONE
- Do NOT repeat existing memories

Examples:
[preference] Loves spicy food and Thai cuisine
[fact] Works as a software engineer at a startup`;

                const result = await api.sendMessage(this.activeCharId, extractionPrompt, null, false, 'system');
                if (!result || result.trim().toUpperCase() === 'NONE') return;

                const lines = result.split('\n').filter(l => l.trim());
                for (const line of lines) {
                    const match = line.match(/\[(preference|fact|event|relationship|general)\]\s*(.+)/i);
                    if (match) {
                        const text = match[2].trim();
                        if (text && text.length > 5 && text.length < 100) {
                            State.addMemory(this.activeCharId, text, match[1].toLowerCase());
                        }
                    }
                }
            } catch (e) {
                console.warn("Memory extraction failed:", e);
            }
        };

        setTimeout(doExtract, 2000);
    },

    // --- Memory Viewer ---
    showMemories: function() {
        const existing = document.getElementById('memoryOverlay');
        if (existing) existing.remove();

        const char = State.characters.find(c => c.id === this.activeCharId) || { name: 'Character' };
        const memories = State.getMemories(this.activeCharId);

        const categoryEmoji = { preference: '💜', fact: '📌', event: '📅', relationship: '❤️', general: '🧠' };

        let memoriesHtml = '';
        if (memories.length === 0) {
            memoriesHtml = '<div style="text-align:center; color:var(--text-muted); padding:30px; font-style:italic;">No memories yet.<br>Chat more and memories will form automatically.</div>';
        } else {
            memoriesHtml = memories.map(m => `
                <div style="display:flex; align-items:flex-start; gap:8px; padding:10px 12px; border-radius:10px; background:rgba(255,255,255,0.02); border:1px solid var(--border);">
                    <span style="font-size:1rem; flex-shrink:0;">${categoryEmoji[m.category] || '🧠'}</span>
                    <div style="flex:1; min-width:0;">
                        <div style="font-size:0.85rem; color:white; line-height:1.4;">${m.text}</div>
                        <div style="font-size:0.65rem; color:var(--text-muted); margin-top:3px;">${m.category} · ${State.formatMemoryAge(m.timestamp)}</div>
                    </div>
                    <button onclick="MessengerApp.deleteMemory('${m.id}')" style="background:none; border:none; color:#f87171; font-size:0.8rem; cursor:pointer; padding:2px 6px; flex-shrink:0;">✕</button>
                </div>
            `).join('');
        }

        const overlay = document.createElement('div');
        overlay.id = 'memoryOverlay';
        overlay.style.cssText = 'position:fixed; top:0; left:0; right:0; bottom:0; z-index:99998; display:flex; align-items:flex-start; justify-content:center; padding-top:60px; background:rgba(0,0,0,0.65); backdrop-filter:blur(8px);';
        overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };

        const sheet = document.createElement('div');
        sheet.style.cssText = 'background:#12181d; border-radius:20px; padding:16px; width:88%; max-width:360px; box-shadow:0 20px 60px rgba(0,0,0,0.7); border:1px solid rgba(255,255,255,0.06); max-height:72vh; overflow-y:auto;';
        sheet.onclick = (e) => e.stopPropagation();
        sheet.innerHTML = `
            <div style="font-size:1rem; font-weight:800; color:var(--accent); margin-bottom:4px; text-align:center;">🧠 Memories</div>
            <div style="font-size:0.75rem; color:var(--text-muted); text-align:center; margin-bottom:14px;">What ${char.name} remembers about you</div>
            <div style="display:flex; gap:8px; margin-bottom:14px;">
                <button onclick="MessengerApp.addMemoryManual()" style="flex:1; padding:8px; background:var(--accent); color:white; border:none; border-radius:10px; font-weight:700; font-size:0.8rem; cursor:pointer;">+ Add</button>
                <button onclick="MessengerApp.clearAllMemories()" style="padding:8px 12px; background:rgba(239,68,68,0.08); color:#f87171; border:1px solid rgba(239,68,68,0.2); border-radius:10px; font-weight:700; font-size:0.8rem; cursor:pointer;">🗑️ Clear</button>
            </div>
            <div style="display:flex; flex-direction:column; gap:6px;">${memoriesHtml}</div>
        `;

        overlay.appendChild(sheet);
        document.body.appendChild(overlay);
    },

    addMemoryManual: function() {
        OS.prompt("Add memory (what should this character remember about you?):", "", (text) => {
            if (!text || text.length < 3) return;
            State.addMemory(this.activeCharId, text, 'general');
            this.showMemories();
        });
    },

    deleteMemory: function(memoryId) {
        State.deleteMemory(this.activeCharId, memoryId);
        this.showMemories();
    },

    clearAllMemories: function() {
        OS.confirm("Clear all memories for this character?", () => {
            State.clearMemories(this.activeCharId);
            this.showMemories();
        }, { title: 'Clear Memories', confirmText: 'Clear All', danger: true });
    },

    deleteMessage: function(msgId) {
        const session = State.sessions[this.activeCharId];
        if (!session) return;
        const idx = session.findIndex(m => m.id === msgId);
        if (idx === -1) return;
        session.splice(idx, 1);
        State.save();
        this.renderChatLog();
    },

    copyMessage: function(msg) {
        const text = msg.text || '';
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(() => OS.toast("Copied!", 'success')).catch(() => this._fallbackCopy(text));
        } else {
            this._fallbackCopy(text);
        }
    },

    _fallbackCopy: function(text) {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.cssText = 'position:fixed; left:-9999px; top:-9999px;';
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); OS.toast("Copied!", 'success'); } catch (e) { OS.toast("Copy failed", 'error'); }
        document.body.removeChild(ta);
    },

    regenerateMessage: function(msgId) {
        if (OS.guardBusy("⏳ Please wait — a task is still running.")) return;
        const session = State.sessions[this.activeCharId];
        if (!session) return;
        const idx = session.findIndex(m => m.id === msgId);
        if (idx === -1) return;

        let userText = '';
        for (let i = idx - 1; i >= 0; i--) {
            if (session[i].sender === 'user') { userText = session[i].text; break; }
        }

        session.splice(idx, 1);
        State.save();
        this.renderChatLog();
        if (userText) this.generateAIResponse(userText);
    },

    cleanup: function() {
        if (this.isListening) { OS.stopListening(); this.isListening = false; }
        if (window.API) API.abort();
    },

    triggerManualEvolution: async function() {
        if (!this.activeCharId) return;
        if (OS.guardBusy("⏳ Please wait — a task is still running.")) return;
        const btn = document.getElementById('btnEvolve');
        if (btn) { btn.classList.add('spinning'); btn.disabled = true; }

        OS.toast("Evolving dossier...", "info");

        const timeout = setTimeout(() => {
            if (btn && btn.classList.contains('spinning')) {
                btn.classList.remove('spinning');
                btn.disabled = false;
            }
        }, 15000);

        try {
            if (window.API && API.evolveDossier) {
                await API.evolveDossier(this.activeCharId);
                OS.toast("Evolution complete!", "success");
            }
        } catch (e) {
            OS.toast("Evolution failed: " + e.message, "error");
        } finally {
            clearTimeout(timeout);
            if (btn) { btn.classList.remove('spinning'); btn.disabled = false; }
        }
    }
};
window.MessengerApp = MessengerApp;
