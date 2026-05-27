/**
 * phone.js
 * Voice-First Communication Module for Fancy AI OS.
 * Features: Local STT (Ears), Local TTS (Voice), and AI Brain Integration.
 */
const PhoneApp = {
    container: null,
    activeCharId: null,
    callState: 'idle', // idle, calling, active
    isProcessing: false,

    init: function(container) {
        this.container = container;
        this.injectStyles();
        this.renderSelection();
    },

    injectStyles: function() {
        const styleId = "phone-app-style";
        if (document.getElementById(styleId)) return;
        const style = document.createElement('style');
        style.id = styleId;
        style.innerHTML = `
            .phone-wrap { height: 100%; display: flex; flex-direction: column; background: #0a0a0b; color: white; padding-bottom: 100px; }
            .phone-header { padding: 20px; text-align: center; }
            .phone-header h2 { font-size: 1.5rem; font-weight: 900; color: var(--accent); letter-spacing: 2px; margin: 0; }

            .char-list { padding: 10px 20px; display: flex; flex-direction: column; gap: 12px; overflow-y: auto; }
            .call-item { background: var(--bg-card); border: 1px solid var(--border); border-radius: 20px; padding: 16px; display: flex; align-items: center; gap: 16px; cursor: pointer; transition: 0.2s; }
            .call-item:active { transform: scale(0.97); background: #1a1a1e; }
            .call-av { width: 54px; height: 54px; border-radius: 50%; background: var(--bg-input); display: flex; align-items: center; justify-content: center; font-weight: 800; font-size: 1.2rem; overflow: hidden; }
            .call-av img { width: 100%; height: 100%; object-fit: cover; }
            .call-info { flex: 1; }
            .call-name { font-weight: 700; font-size: 1rem; }
            .call-status { font-size: 0.75rem; color: #22c55e; font-weight: 600; }

            /* CALL SCREEN */
            .active-call-screen { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 40px; padding: 20px; position: relative; }
            .call-avatar-main { width: 180px; height: 180px; border-radius: 50%; background: var(--bg-card); border: 4px solid var(--border); overflow: hidden; position: relative; z-index: 2; transition: all 0.5s cubic-bezier(0.4, 0, 0.2, 1); }
            .call-avatar-main img { width: 100%; height: 100%; object-fit: cover; }

            .vocal-rings { position: absolute; width: 180px; height: 180px; border-radius: 50%; border: 2px solid var(--accent); opacity: 0; z-index: 1; pointer-events: none; }
            .vocal-rings.pulsing { animation: sonar 2s infinite; }
            @keyframes sonar {
                0% { transform: scale(1); opacity: 0.8; }
                100% { transform: scale(2.5); opacity: 0; }
            }

            .call-label { text-align: center; }
            .call-target-name { font-size: 1.8rem; font-weight: 900; margin-bottom: 8px; }
            .call-timer { font-family: monospace; color: var(--text-muted); font-size: 1rem; }

            .vocal-indicator { font-size: 0.85rem; font-weight: 700; color: var(--accent); text-transform: uppercase; letter-spacing: 1px; height: 20px; }

            .call-actions { width: 100%; display: flex; justify-content: center; gap: 40px; padding-bottom: 60px; }
            .btn-call-end { width: 72px; height: 72px; border-radius: 50%; background: #ef4444; border: none; color: white; font-size: 1.8rem; cursor: pointer; box-shadow: 0 8px 24px rgba(239, 68, 68, 0.4); display: flex; align-items: center; justify-content: center; }
        `;
        document.head.appendChild(style);
    },

    renderSelection: function() {
        this.activeCharId = null;
        this.callState = 'idle';
        this.container.innerHTML = `
            <div class="phone-wrap">
                <div class="phone-header"><h2>PHONE</h2></div>
                <div class="char-list" id="charList"></div>
            </div>
        `;

        const list = document.getElementById('charList');
        State.characters.forEach(char => {
            const item = document.createElement('div');
            item.className = 'call-item';
            item.onclick = () => this.startCall(char.id);

            const avId = `call-av-${char.id}`;
            item.innerHTML = `
                <div class="call-av" id="${avId}">${char.name[0]}</div>
                <div class="call-info">
                    <div class="call-name">${char.name}</div>
                    <div class="call-status">available</div>
                </div>
                <div style="font-size:1.5rem; color:#22c55e;">📞</div>
            `;
            list.appendChild(item);

            if (char.avatar) {
                (async () => {
                    const src = await window.ImageDB.get(char.avatar);
                    const el = document.getElementById(avId);
                    if (el && src) el.innerHTML = `<img src="${src}">`;
                })();
            }
        });
    },

    startCall: async function(charId) {
        this.activeCharId = charId;
        this.callState = 'calling';
        const char = State.characters.find(c => c.id === charId);

        OS.pushView(() => {
            this.hangUp();
        });

        this.container.innerHTML = `
            <div class="phone-wrap">
                <div class="active-call-screen">
                    <div class="vocal-rings" id="rings1"></div>
                    <div class="vocal-rings" id="rings2" style="animation-delay: 1s;"></div>
                    <div class="call-avatar-main" id="callAvMain">
                        ${char.name[0]}
                    </div>
                    <div class="call-label">
                        <div class="call-target-name">${char.name}</div>
                        <div class="vocal-indicator" id="vocalStatus">connecting...</div>
                    </div>
                    <div class="call-actions">
                        <button class="btn-call-end" onclick="OS.goBack()">
                            <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor">
                                <path d="M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.58.9-.9.41-1.72.96-2.45 1.62-.2.21-.51.33-.8.33-.29 0-.58-.11-.79-.33L.33 13.01c-.21-.22-.33-.51-.33-.81 0-.29.12-.58.33-.8 2.73-2.6 6.13-4.14 10.17-4.14s7.44 1.54 10.17 4.14c.21.22.33.51.33.8 0 .3-.12.59-.33.81l-2.45 2.45c-.21.22-.5.33-.79.33-.29 0-.59-.12-.8-.33-.73-.66-1.55-1.21-2.45-1.62-.35-.16-.58-.51-.58-.9v-3.1C15.15 9.25 13.6 9 12 9z"/>
                            </svg>
                        </button>
                    </div>
                </div>
            </div>
        `;

        if (char.avatar) {
            const src = await window.ImageDB.get(char.avatar);
            const el = document.getElementById('callAvMain');
            if (el && src) el.innerHTML = `<img src="${src}">`;
        }

        setTimeout(() => {
            this.vocalLoop();
        }, 1500);
    },

    vocalLoop: async function() {
        if (this.callState === 'idle') return;
        this.callState = 'active';

        document.getElementById('rings1').classList.add('pulsing');
        document.getElementById('rings2').classList.add('pulsing');

        this.aiSpeak("Hello? I'm listening.");
    },

    aiSpeak: async function(text) {
        this.isProcessing = false;
        if (this.callState === 'idle') return;

        document.getElementById('vocalStatus').innerText = "speaking...";
        document.getElementById('callAvMain').style.transform = "scale(1.1)";

        OS.speak(text);

        // Android TTS doesn't give a "finished" callback easily to JS,
        // so we estimate duration based on word count
        const words = text.split(' ').length;
        const duration = Math.max(2500, words * 550);

        setTimeout(() => {
            if (this.callState !== 'idle') this.startListening();
        }, duration);
    },

    startListening: function() {
        if (this.callState === 'idle' || this.isProcessing) return;

        const status = document.getElementById('vocalStatus');
        status.innerText = "listening...";
        document.getElementById('callAvMain').style.transform = "scale(1.0)";

        OS.stopSpeaking(); // Ensure clean audio path

        OS.listen(
            (result) => {
                if (result && result.trim() && !this.isProcessing) {
                    this.processUserVoice(result);
                }
            },
            (state) => {
                if (this.callState === 'active' && !this.isProcessing && (state === 'error' || state === 'end')) {
                    // If they stopped talking but didn't say anything, wait a bit then retry
                    setTimeout(() => {
                        if (this.callState === 'active' && !this.isProcessing && status.innerText === "listening...") {
                             this.startListening();
                        }
                    }, 2000);
                }
            }
        );
    },

    processUserVoice: async function(text) {
        if (this.callState === 'idle' || this.isProcessing) return;

        this.isProcessing = true;
        OS.stopListening();
        document.getElementById('vocalStatus').innerText = "thinking...";

        try {
            const response = await API.sendMessage(this.activeCharId, text, null, true, 'chat');
            // Clean response of flux prompts or JSON
            const cleanText = response.split('flux prompt:')[0].split('{')[0].trim();
            this.aiSpeak(cleanText);
        } catch (e) {
            this.aiSpeak("I'm sorry, I lost the connection for a moment.");
        }
    },

    hangUp: function() {
        this.callState = 'idle';
        OS.stopSpeaking();
        OS.stopListening();
        this.renderSelection();
    }
};

window.PhoneApp = PhoneApp;
