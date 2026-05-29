/**
 * ustagram.js
 * Ustagram-style feed — bots post SFW photos only.
 * Uses Native Storage via ImageDB to bypass memory limits.
 */
const UstagramApp = {
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
        const styleId = "ustagram-app-style";
        if (!document.getElementById(styleId)) {
            const style = document.createElement('style');
            style.id = styleId;
            style.innerHTML = `
                .ug-wrap { padding: 0; overflow-y: auto; height: 100%; background: var(--md-surface); display: flex; flex-direction: column; padding-bottom: 100px; }
                .ug-header { background: rgba(208, 188, 255, 0.08); padding: 12px 16px; border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 8px; }
                .ug-header h2 { margin: 0; font-size: 1.1rem; font-weight: 800; color: var(--md-primary); }
                .ug-controls { padding: 10px 16px; display: flex; gap: 8px; justify-content: center; background: rgba(255,255,255,0.02); border-bottom: 1px solid var(--border); position: sticky; top: 0; z-index: 10; }
                .ug-btn-gen { padding: 10px 20px; background: var(--md-primary-container); color: var(--md-on-primary-container); border: none; border-radius: 12px; font-weight: 700; cursor: pointer; }
                .ug-post { padding: 12px 16px; border-bottom: 1px solid var(--border); }
                .ug-post-header { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
                .ug-post-avatar { width: 36px; height: 36px; border-radius: 50%; background: #333; overflow: hidden; display: flex; align-items: center; justify-content: center; color: white; font-weight: 700; }
                .ug-post-avatar img { width: 100%; height: 100%; object-fit: cover; }
                .ug-post-img-container { width: 100%; border-radius: 8px; overflow: hidden; background: #111; min-height: 250px; }
                .ug-post-img { width: 100%; display: block; cursor: zoom-in; }
                .ug-post-caption { font-size: 0.88rem; color: #d1d5db; padding: 8px 0; }
            `;
            document.head.appendChild(style);
        }
        this.container.innerHTML = `
            <div class="ug-wrap">
                <div class="ug-header"><h2>Ustagram</h2><span id="ugAutoPostIndicator" style="display:none; width:8px; height:8px; border-radius:50%; background:#22c55e; box-shadow:0 0 6px #22c55e; animation: pulse 2s infinite;"></span></div>
                <div class="ug-controls">
                    <button class="ug-btn-gen" id="ugGenBtn" onclick="UstagramApp.generatePost()">📸 New Photo</button>
                    <button class="ug-btn-gen" style="background:#333" onclick="UstagramApp.clearAll()">🗑️</button>
                </div>
                <div id="ugPosts"></div>
            </div>
        `;
    },

    loadPosts: async function() {
        const el = document.getElementById('ugPosts');
        if (!el) return;
        const posts = State.instagramPosts || [];
        if (posts.length === 0) {
            el.innerHTML = '<div style="padding:40px; text-align:center; color:gray;">No photos yet.</div>';
            return;
        }
        el.innerHTML = "";
        
        const reversedPosts = [...posts].reverse().slice(0, 15); // Limit to 15 most recent for stability

        for (const p of reversedPosts) {
            const postEl = document.createElement('div');
            postEl.className = 'ug-post';
            const imgId = `ug-img-${p.id}`;
            const avId = `ug-av-${p.id}`;
            
            postEl.innerHTML = `
                <div class="ug-post-header">
                    <div class="ug-post-avatar" id="${avId}">${(p.charName||'A')[0]}</div>
                    <div style="color:white; font-weight:700; font-size:0.9rem; flex:1;">${p.charName || 'Anon'} <span style="font-weight:400; color:var(--text-muted); font-size:0.75rem; margin-left:4px;">• ${OS.formatTime(p.timestamp)}</span></div>
                    <div style="display:flex; gap:12px;">
                        <button onclick="event.stopPropagation(); UstagramApp.deletePost('${p.id}')" style="background:none; border:none; color:var(--text-muted); font-size:0.9rem; cursor:pointer;">🗑️</button>
                    </div>
                </div>
                <div class="ug-post-img-container" id="${imgId}"></div>
                <div class="ug-post-caption"><b>${p.charName || 'Anon'}</b> ${OS.formatMarkdown(p.caption || '')}</div>
                <div id="comments-${p.id}" style="padding: 0 0 4px 0;"></div>
                <div style="display:flex; gap:6px; align-items:center; padding: 4px 0 8px 0;">
                    <input type="text" id="comment-input-${p.id}" placeholder="Add a comment..." style="flex:1; background:rgba(255,255,255,0.06); border:1px solid rgba(255,255,255,0.1); color:white; padding:6px 10px; border-radius:20px; font-size:0.82rem; outline:none;" onkeydown="if(event.key==='Enter') UstagramApp.submitComment('${p.id}')">
                    <button onclick="UstagramApp.submitComment('${p.id}')" style="background:var(--md-primary-container); border:none; color:var(--md-on-primary-container); padding:6px 12px; border-radius:20px; font-size:0.8rem; font-weight:700; cursor:pointer; flex-shrink:0;">Post</button>
                </div>
            `;
            el.appendChild(postEl);

            // Render Comments
            const commentsContainer = document.getElementById(`comments-${p.id}`);
            if (p.comments && p.comments.length > 0) {
                p.comments.forEach(c => {
                    const cDiv = document.createElement('div');
                    cDiv.style.fontSize = '0.82rem';
                    cDiv.style.marginBottom = '2px';
                    if (c.isUser) {
                        cDiv.innerHTML = `<b style="color:var(--md-primary); margin-right:6px;">${c.charName}</b> <span style="color:#e7e9ea;">${OS.formatMarkdown(c.text)}</span>`;
                    } else {
                        cDiv.innerHTML = `<b style="color:white; margin-right:6px;">${c.charName}</b> ${OS.formatMarkdown(c.text)}`;
                    }
                    commentsContainer.appendChild(cDiv);
                });
            }

            // Resolve Native Storage SEQUENTIALLY
            await this.resolveToElement(p.image, imgId, true);
            const char = State.characters.find(c => c.id === p.charId);
            if (char && char.avatar) await this.resolveToElement(char.avatar, avId, false);
        }
    },

    resolveToElement: async function(src, containerId, isPost) {
        let finalSrc = src;
        if (src && src.startsWith('db:') && window.ImageDB) {
            finalSrc = await window.ImageDB.get(src);
        }
        const el = document.getElementById(containerId);
        if (el && finalSrc) {
            if (isPost) {
                // THE FIX: Inline onclick attribute
                el.innerHTML = `<img class="ug-post-img" src="${finalSrc}" onclick="OS.openLightbox(this.src)">`;
            } else {
                el.innerHTML = `<img src="${finalSrc}">`;
            }
        }
    },

    generatePost: async function(isAuto) {
        if (!isAuto && window.OS && OS.guardBusy("⏳ Please wait — a task is still running.")) return;
        const btn = document.getElementById('ugGenBtn');
        if (btn) { btn.disabled = true; btn.innerText = "⏳ Dreaming..."; }
        const bot = State.characters[Math.floor(Math.random() * State.characters.length)];
        if (!bot) { console.warn("Ustagram: No characters available for auto-post"); if (btn) { btn.disabled = false; btn.innerText = "📸 New Photo"; } return; }

        try {
            const api = window.API;
            const socialContext = api.getSocialContext(bot.id);

            const prompt = `
You are posting on Ustagram (an Instagram-like platform).
${socialContext}

[YOUR TASK]
1. Write a natural, engaging, and classy caption (emojis welcome).
2. Ensure the content is SFW and elegant.
3. Provide a highly detailed "flux prompt:" for the photo.

Format your response exactly like this:
caption: [your caption]
flux prompt: [visual description]
`.trim();

            const response = await api.sendMessage(bot.id, prompt, null, false, 'social');

            let caption = "New post!";
            let visualPrompt = `Realistic Instagram photo of ${bot.name}, cinematic, natural lighting, fully clothed, classy, 85mm portrait`;

            const capMatch = response.match(/caption:\s*([\s\S]*?)(?=flux prompt:|$)/i);
            if (capMatch) { const t = capMatch[1].trim(); if (t) caption = t; }

            if (response.toLowerCase().includes("flux prompt:")) {
                visualPrompt = response.split(/flux prompt:/i)[1].trim() + ", photorealistic, highly detailed face, natural skin texture, fully clothed, elegant outfit, candid moment, soft natural lighting, sharp focus, 85mm f/1.8";
            }

            let b64 = null;
            if (window.ImagingApp) {
                // Ensure no stale images from other apps interfere
                window.ImagingApp.attachedImage = null;

                b64 = await window.ImagingApp.generate(visualPrompt, null, (p) => {
                    if (btn) btn.innerText = `⏳ ${p}%`;
                });
            }
            if (b64 && window.ImageDB) {
                const dbId = 'ig_' + Date.now();
                await window.ImageDB.save(dbId, b64);
                if (!State.instagramPosts) State.instagramPosts = [];
                State.instagramPosts.push({ id: dbId, charId: bot.id, charName: bot.name, image: `db:${dbId}`, caption, timestamp: Date.now(), comments: [] });
                State.save();
                this.loadPosts();
                // Update home screen badge if user is not in this app
                if (OS.activeApp !== 'UstagramApp' && OS.updateBadges) OS.updateBadges();
            }
        } catch(e) { console.error(e); }
        if (btn) { btn.disabled = false; btn.innerText = "📸 New Photo"; }
    },

    submitComment: async function(postId) {
        const input = document.getElementById(`comment-input-${postId}`);
        if (!input || !input.value.trim()) return;
        const text = input.value.trim();
        input.value = '';

        const post = (State.instagramPosts || []).find(p => p.id === postId);
        if (!post) return;

        const userName = (State.userProfile && State.userProfile.name) || 'You';
        if (!post.comments) post.comments = [];
        post.comments.push({ charId: 'user', charName: userName, text, isUser: true, timestamp: Date.now() });
        State.save();
        this.loadPosts();

        const poster = State.characters.find(c => c.id === post.charId);
        if (!poster) return;
        try {
            const api = window.API;
            if (!api || !api.isReady()) return;
            const msg = await api.sendMessage(poster.id, `You are ${poster.name} on Ustagram. You posted a photo with caption: "${post.caption}". ${userName} commented: "${text}". Write a short, in-character reply to their comment (max 12 words, emojis welcome). Output ONLY the reply text.`, null, false, 'social');
            if (msg && msg.length > 1) {
                const freshPost = (State.instagramPosts || []).find(p => p.id === postId);
                if (freshPost) {
                    freshPost.comments.push({ charId: poster.id, charName: poster.name, text: msg.trim(), timestamp: Date.now() });
                    State.save();
                    this.loadPosts();
                }
            }
        } catch(e) {}
    },

    deletePost: function(postId) {
        OS.confirm("Delete this post?", async () => {
            const idx = State.instagramPosts.findIndex(p => p.id === postId);
            if (idx === -1) return;
            const post = State.instagramPosts[idx];
            if (post.image.startsWith('db:') && window.ImageDB) {
                await window.ImageDB.delete(post.image.replace('db:', ''));
            }
            State.instagramPosts.splice(idx, 1);
            State.save();
            this.loadPosts();
        }, { title: 'Delete Post', confirmText: 'Delete', danger: true });
    },

    clearAll: async function() {
        OS.confirm("Clear all photos?", async () => {
            if (window.ImageDB) {
                for (const p of (State.instagramPosts || [])) {
                    if (p.image.startsWith('db:')) await window.ImageDB.delete(p.image.replace('db:', ''));
                }
            }
            State.instagramPosts = [];
            State.save();
            // Trigger orphan cleanup
            if (window.ImageDB && window.ImageDB.purgeOrphanedFiles) window.ImageDB.purgeOrphanedFiles();
            UstagramApp.loadPosts();
        }, { title: 'Clear Feed', confirmText: 'Clear All', danger: true });
    },

    startAutoPost: function() {
        this.stopAutoPost();
        const s = State.settings || {};
        if (!s.autoPostEnabled || !s.autoPostUstagram) return;
        // Local providers need no key; cloud providers need one.
        if (!window.API.isReady()) return;
        const interval = (s.autoPostInterval || 5) * 60 * 1000;
        // Add random jitter ±30% so feeds don't all post at the same time
        const jitter = interval * (0.7 + Math.random() * 0.6);
        // Stagger the first post: wait 0-60s so multiple feeds don't all hit the server at once
        const initialDelay = Math.floor(Math.random() * 60000);
        this._autoPostTimer = setTimeout(() => {
            // After initial delay, switch to regular interval
            this._autoPostTimer = setInterval(() => {
                if (this._autoPosting) return; // Don't stack if previous is still running
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
        const indicator = document.getElementById('ugAutoPostIndicator');
        if (!indicator) return;
        const s = State.settings || {};
        const hasKey = window.API.isReady();
        if (s.autoPostEnabled && s.autoPostUstagram && hasKey) {
            indicator.style.display = 'inline-block';
            indicator.title = `Auto-posting every ${s.autoPostInterval || 5} min`;
        } else {
            indicator.style.display = 'none';
        }
    },

    cleanup: function() {
        this.stopAutoPost();
    }
};
window.UstagramApp = UstagramApp;
