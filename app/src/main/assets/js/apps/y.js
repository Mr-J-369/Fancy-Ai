/**
 * y.js
 * Y (Twitter-style) feed — bots post text-only statuses and interact.
 */
const YApp = {
    container: null,
    _autoPostTimer: null,
    _autoPosting: false,

    init: function(container) {
        this.container = container;
        this.render();
        this.loadPosts();
        this.startAutoPost();
    },

    render: function() {
        const styleId = "y-app-style";
        if (!document.getElementById(styleId)) {
            const style = document.createElement('style');
            style.id = styleId;
            style.innerHTML = `
                .y-wrap { padding: 0; overflow-y: auto; height: 100%; background: var(--md-surface); display: flex; flex-direction: column; padding-bottom: 100px; }
                .y-header { background: rgba(208, 188, 255, 0.08); padding: 12px 16px; border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 8px; }
                .y-header h2 { margin: 0; font-size: 1.1rem; font-weight: 800; color: var(--md-primary); }
                .y-header span { display: none; width: 8px; height: 8px; border-radius: 50%; background: #22c55e; box-shadow: 0 0 6px #22c55e; animation: pulse 2s infinite; }
                .y-controls { padding: 10px 16px; display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; background: rgba(255,255,255,0.02); border-bottom: 1px solid var(--border); position: sticky; top: 0; z-index: 10; }
                .y-btn-gen { padding: 10px 20px; font-size: 0.8rem; background: var(--md-primary-container); color: var(--md-on-primary-container); border: none; border-radius: 20px; font-weight: 700; cursor: pointer; box-shadow: 0 4px 12px rgba(208, 188, 255, 0.3); transition: all 0.2s; }
                .y-btn-gen:disabled { opacity: 0.5; cursor: not-allowed; filter: grayscale(0.5); }
                .y-btn-clear { padding: 10px 20px; font-size: 0.8rem; background: rgba(239,68,68,0.15); color: #ef4444; border: 1px solid rgba(239,68,68,0.3); border-radius: 20px; font-weight: 700; cursor: pointer; }
                .y-tweet { padding: 14px 16px; border-bottom: 1px solid var(--border); animation: postFadeIn 0.3s ease; }
                .y-tweet-header { display: flex; align-items: flex-start; gap: 10px; }
                .y-tweet-avatar { width: 40px; height: 40px; border-radius: 50%; background: var(--md-primary-container); color: var(--md-on-primary-container); display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 0.95rem; flex-shrink: 0; overflow: hidden; }
                .y-tweet-avatar img { width: 100%; height: 100%; object-fit: cover; }
                .y-tweet-body { flex: 1; min-width: 0; }
                .y-tweet-name { font-weight: 700; color: white; font-size: 0.88rem; }
                .y-tweet-handle { font-size: 0.75rem; color: var(--text-muted); margin-left: 4px; }
                .y-tweet-text { font-size: 0.92rem; color: #e7e9ea; line-height: 1.45; margin-top: 2px; white-space: pre-wrap; }
                .y-tweet-time { font-size: 0.72rem; color: var(--text-muted); margin-top: 4px; }
                .y-tweet-reply { margin-left: 50px; margin-top: 10px; padding: 10px 14px; background: rgba(255,255,255,0.02); border-radius: 12px; border-left: 2px solid var(--md-primary); }
                @keyframes postFadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
            `;
            document.head.appendChild(style);
        }
        this.container.innerHTML = `
            <div class="y-wrap">
                <div class="y-header"><h2>Y</h2><span id="yAutoPostIndicator" style="display:none; width:8px; height:8px; border-radius:50%; background:#22c55e; box-shadow:0 0 6px #22c55e; animation: pulse 2s infinite;"></span></div>
                <div class="y-controls">
                    <button class="y-btn-gen" id="yBtnGen" onclick="YApp.generatePost()">🐦 New Post</button>
                    <button class="y-btn-clear" onclick="YApp.clearAll()">🗑️ Clear</button>
                </div>
                <div id="yPosts"></div>
            </div>
        `;
    },

    loadPosts: async function() {
        const el = document.getElementById('yPosts');
        if (!el) return;
        const posts = State.xPosts || [];
        if (posts.length === 0) {
            el.innerHTML = `<div style="padding:80px 30px; text-align:center; color:var(--text-muted); font-style:italic; font-size:0.9rem;">No posts yet.<br>Tap 🐦 New Post to start!</div>`;
            return;
        }

        el.innerHTML = "";
        const reversedPosts = [...posts].reverse();
        
        for (const p of reversedPosts) {
            const tweetDiv = document.createElement('div');
            tweetDiv.className = 'y-tweet';
            const avId = `y-av-${p.id}`;
            
            tweetDiv.innerHTML = `
                <div class="y-tweet-header">
                    <div class="y-tweet-avatar" id="${avId}">${(p.charName||'B')[0]}</div>
                    <div class="y-tweet-body">
                        <div style="display:flex; justify-content:space-between; align-items:flex-start;">
                            <div><span class="y-tweet-name">${p.charName||'Bot'}</span><span class="y-tweet-handle">@${(p.charName||'bot').toLowerCase().replace(/\s/g,'')}</span></div>
                            <button onclick="YApp.deletePost('${p.id}')" style="background:none; border:none; color:var(--text-muted); font-size:0.8rem; cursor:pointer; padding:0 4px;">🗑️</button>
                        </div>
                        <div class="y-tweet-text">${OS.formatMarkdown(p.text)}</div>
                        <div class="y-tweet-time">${OS.formatTime(p.timestamp)}</div>
                    </div>
                </div>
                <div id="replies-${p.id}"></div>
                <div style="margin-left:50px; margin-top:8px; display:flex; gap:6px; align-items:center;">
                    <input type="text" id="reply-input-${p.id}" placeholder="Reply to ${p.charName}..." style="flex:1; background:rgba(255,255,255,0.06); border:1px solid rgba(255,255,255,0.1); color:white; padding:6px 10px; border-radius:20px; font-size:0.82rem; outline:none;" onkeydown="if(event.key==='Enter') YApp.submitReply('${p.id}')">
                    <button onclick="YApp.submitReply('${p.id}')" style="background:var(--md-primary-container); border:none; color:var(--md-on-primary-container); padding:6px 12px; border-radius:20px; font-size:0.8rem; font-weight:700; cursor:pointer; flex-shrink:0;">↩</button>
                </div>
            `;
            el.appendChild(tweetDiv);

            // Resolve Avatar
            const char = State.characters.find(c => c.id === p.charId);
            if (char && char.avatar) {
                this.resolveAvatar(char.avatar, avId);
            }

            // Render Replies
            const repliesContainer = document.getElementById(`replies-${p.id}`);
            if (p.replies && p.replies.length > 0) {
                for (const r of p.replies) {
                    const rDiv = document.createElement('div');
                    rDiv.className = 'y-tweet-reply';
                    if (r.isUser) {
                        rDiv.innerHTML = `
                            <div style="display:flex; align-items:flex-start; gap:8px;">
                                <div style="width:28px; height:28px; border-radius:50%; background:var(--md-primary-container); display:flex; align-items:center; justify-content:center; font-weight:800; font-size:0.55rem; color:var(--md-on-primary-container); flex-shrink:0;">YOU</div>
                                <div><span style="font-weight:700; color:var(--md-primary); font-size:0.82rem;">${r.charName}</span><div class="y-tweet-text">${OS.formatMarkdown(r.text)}</div></div>
                            </div>`;
                        repliesContainer.appendChild(rDiv);
                    } else {
                        const rAvId = `y-av-reply-${p.id}-${Math.random().toString(36).substr(2, 9)}`;
                        rDiv.innerHTML = `
                            <div class="y-tweet-header">
                                <div class="y-tweet-avatar" id="${rAvId}" style="width:28px;height:28px;font-size:0.7rem;">${(r.charName||'B')[0]}</div>
                                <div class="y-tweet-body">
                                    <div><span class="y-tweet-name">${r.charName||'Bot'}</span><span class="y-tweet-handle">@${(r.charName||'bot').toLowerCase().replace(/\s/g,'')}</span></div>
                                    <div class="y-tweet-text">${OS.formatMarkdown(r.text)}</div>
                                </div>
                            </div>`;
                        repliesContainer.appendChild(rDiv);
                        const rChar = State.characters.find(c => c.id === r.charId);
                        if (rChar && rChar.avatar) this.resolveAvatar(rChar.avatar, rAvId);
                    }
                }
            }
        }
    },

    resolveAvatar: async function(src, containerId) {
        if (!src) return;
        let finalSrc = src;
        if (src.startsWith('db:') && window.ImageDB) {
            finalSrc = await window.ImageDB.get(src);
        }
        const container = document.getElementById(containerId);
        if (container && finalSrc) {
            container.innerHTML = `<img src="${finalSrc}">`;
        }
    },

    formatTime: function(ts) {
        const diff = Date.now() - (ts || Date.now());
        const mins = Math.floor(diff / 60000);
        if (mins < 1) return 'just now';
        if (mins < 60) return mins + 'm';
        const hrs = Math.floor(mins / 60);
        if (hrs < 24) return hrs + 'h';
        return Math.floor(hrs / 24) + 'd';
    },

    generatePost: async function(isAuto) {
        if (!isAuto && window.OS && OS.guardBusy("⏳ Please wait — a task is still running.")) return;
        const btn = document.getElementById('yBtnGen');
        if (btn) { btn.disabled = true; btn.innerText = "⏳ Generating..."; }

        const bot = State.characters[Math.floor(Math.random() * State.characters.length)];
        if (!bot) { console.warn("Y: No characters available for auto-post"); if (btn) { btn.disabled = false; btn.innerText = "🐦 New Post"; } return; }

        try {
            const api = window.API;
            const socialContext = api.getSocialContext(bot.id);

            const prompt = `
You are posting on Y (a Twitter-like platform).
${socialContext}

[YOUR TASK]
Write a short, engaging status update that fits your personality and recent activity. Max 20 words. No hashtags. Output ONLY the post text.
`.trim();

            let text = "Hello world";

            if (api && api.isReady()) {
                try {
                    const msg = await api.sendMessage(bot.id, prompt, null, false, 'social');
                    if (msg && msg.length > 3) {
                        text = msg.replace(/\{"action":\s*"generate_image".*?\}/g, '').replace(/["""'']/g, '').trim();
                    }
                } catch(e) {}
            }

            if (!State.xPosts) State.xPosts = [];
            const post = {
                id: 'x_' + Date.now(),
                charId: bot.id,
                charName: bot.name,
                text: text,
                timestamp: Date.now(),
                replies: []
            };
            State.xPosts.push(post);
            if (State.xPosts.length > 50) State.xPosts.shift();
            State.save();
            this.loadPosts();
            if (OS.activeApp !== 'YApp' && OS.updateBadges) OS.updateBadges();
        } catch(e) {
            console.error("Y gen error:", e);
        } finally {
            if (btn) { btn.disabled = false; btn.innerText = "🐦 New Post"; }
        }
    },

    submitReply: async function(postId) {
        const input = document.getElementById(`reply-input-${postId}`);
        if (!input || !input.value.trim()) return;
        const text = input.value.trim();
        input.value = '';

        const post = (State.xPosts || []).find(p => p.id === postId);
        if (!post) return;

        const userName = (State.userProfile && State.userProfile.name) || 'You';
        if (!post.replies) post.replies = [];
        post.replies.push({ charId: 'user', charName: userName, text, isUser: true, timestamp: Date.now() });
        State.save();
        this.loadPosts();

        const poster = State.characters.find(c => c.id === post.charId);
        if (!poster) return;
        try {
            const api = window.API;
            if (!api || !api.isReady()) return;
            const msg = await api.sendMessage(poster.id, `You are ${poster.name} on Y (Twitter). You posted: "${post.text}". ${userName} replied to you: "${text}". Write a short in-character reply (max 15 words). Output ONLY the reply text.`, null, false, 'social');
            if (msg && msg.length > 2) {
                const freshPost = (State.xPosts || []).find(p => p.id === postId);
                if (freshPost) {
                    freshPost.replies.push({ charId: poster.id, charName: poster.name, text: msg.replace(/["""'']/g, '').trim(), timestamp: Date.now() });
                    State.save();
                    this.loadPosts();
                }
            }
        } catch(e) {}
    },

    deletePost: function(postId) {
        OS.confirm("Delete this Y post?", () => {
            const idx = State.xPosts.findIndex(p => p.id === postId);
            if (idx === -1) return;
            State.xPosts.splice(idx, 1);
            State.save();
            this.loadPosts();
        }, { title: 'Delete Post', confirmText: 'Delete', danger: true });
    },

    clearAll: function() {
        OS.confirm("Clear all Y posts?", () => {
            State.xPosts = [];
            State.save();
            YApp.loadPosts();
        }, { title: 'Clear Feed', confirmText: 'Clear All', danger: true });
    },

    cleanup: function() {
        this.stopAutoPost();
    },

    startAutoPost: function() {
        this.stopAutoPost();
        const s = State.settings || {};
        if (!s.autoPostEnabled || !s.autoPostY) return;
        // Local providers need no key; cloud providers need one.
        if (!window.API.isReady()) return;
        const interval = (s.autoPostInterval || 5) * 60 * 1000;
        const jitter = interval * (0.7 + Math.random() * 0.6);
        // Stagger the first post: wait 0-60s so multiple feeds don't all hit the server at once
        const initialDelay = Math.floor(Math.random() * 60000);
        this._autoPostTimer = setTimeout(() => {
            // After initial delay, switch to regular interval
            this._autoPostTimer = setInterval(() => {
                if (this._autoPosting) return;
                this._autoPosting = true;
                this.generatePost(true).finally(() => { this._autoPosting = false; });
            }, jitter);
            // Also do the first post now
            if (!this._autoPosting) {
                this._autoPosting = true;
                this.generatePost(true).finally(() => { this._autoPosting = false; });
            }
        }, initialDelay);
        this.updateAutoPostIndicator();
    },

    stopAutoPost: function() {
        if (this._autoPostTimer) {
            clearInterval(this._autoPostTimer);
            clearTimeout(this._autoPostTimer);
            this._autoPostTimer = null;
        }
        this._autoPosting = false;
    },

    updateAutoPostIndicator: function() {
        const indicator = document.getElementById('yAutoPostIndicator');
        if (!indicator) return;
        const s = State.settings || {};
        const hasKey = window.API.isReady();
        if (s.autoPostEnabled && s.autoPostY && hasKey) {
            indicator.style.display = 'inline-block';
            indicator.title = `Auto-posting every ${s.autoPostInterval || 5} min`;
        } else {
            indicator.style.display = 'none';
        }
    }
};
window.YApp = YApp;
