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
                .ug-wrap { padding: 0; overflow-y: auto; height: 100%; background: #0a0a0b; display: flex; flex-direction: column; padding-bottom: 100px; }
                .ug-header { background: rgba(255,100,50,0.05); padding: 12px 16px; border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 8px; }
                .ug-header h2 { margin: 0; font-size: 1.1rem; font-weight: 800; background: linear-gradient(135deg, #f58529, #dd2a7b); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
                .ug-controls { padding: 10px 16px; display: flex; gap: 8px; justify-content: center; background: rgba(255,255,255,0.02); border-bottom: 1px solid var(--border); position: sticky; top: 0; z-index: 10; }
                .ug-btn-gen { padding: 10px 20px; background: linear-gradient(135deg, #f58529, #dd2a7b); color: white; border: none; border-radius: 12px; font-weight: 700; cursor: pointer; }
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
        el.innerHTML = posts.length === 0 ? '<div style="padding:40px; text-align:center; color:gray;">No photos yet.</div>' : "";
        
        [...posts].reverse().forEach(async p => {
            const postEl = document.createElement('div');
            postEl.className = 'ug-post';
            const imgId = `ug-img-${p.id}`;
            const avId = `ug-av-${p.id}`;
            
            postEl.innerHTML = `
                <div class="ug-post-header">
                    <div class="ug-post-avatar" id="${avId}">${(p.charName||'A')[0]}</div>
                    <div style="color:white; font-weight:700; font-size:0.9rem; flex:1;">${p.charName || 'Anon'}</div>
                    <div style="display:flex; gap:12px;">
                        <button onclick="UstagramApp.deletePost('${p.id}')" style="background:none; border:none; color:var(--text-muted); font-size:0.9rem; cursor:pointer;">🗑️</button>
                    </div>
                </div>
                <div class="ug-post-img-container" id="${imgId}"></div>
                <div class="ug-post-caption"><b>${p.charName || 'Anon'}</b> ${OS.formatMarkdown(p.caption || '')}</div>
                <div id="comments-${p.id}" style="padding: 0 0 8px 0;"></div>
            `;
            el.appendChild(postEl);

            // Render Comments
            const commentsContainer = document.getElementById(`comments-${p.id}`);
            if (p.comments && p.comments.length > 0) {
                p.comments.forEach(c => {
                    const cDiv = document.createElement('div');
                    cDiv.style.fontSize = '0.82rem';
                    cDiv.style.marginBottom = '2px';
                    cDiv.innerHTML = `<b style="color:white; margin-right:6px;">${c.charName}</b> ${OS.formatMarkdown(c.text)}`;
                    commentsContainer.appendChild(cDiv);
                });
            }

            // Resolve Native Storage
            this.resolveToElement(p.image, imgId, true);
            const char = State.characters.find(c => c.id === p.charId);
            if (char && char.avatar) this.resolveToElement(char.avatar, avId, false);
        });
    },

    resolveToElement: async function(src, containerId, isPost) {
        let finalSrc = src;
        if (src && src.startsWith('db:') && window.ImageDB) {
            finalSrc = await window.ImageDB.get(src);
        }
        const el = document.getElementById(containerId);
        if (el && finalSrc) {
            if (isPost) {
                el.innerHTML = `<img class="ug-post-img" src="${finalSrc}">`;
                el.querySelector('img').onclick = () => {
                    if (window.ImagingApp) ImagingApp.openLocalLightbox(finalSrc, src);
                };
            } else {
                el.innerHTML = `<img src="${finalSrc}">`;
            }
        }
    },

    generatePost: async function() {
        const btn = document.getElementById('ugGenBtn');
        if (btn) { btn.disabled = true; btn.innerText = "⏳ Dreaming..."; }
        const bot = State.characters[Math.floor(Math.random() * State.characters.length)];
        if (!bot) { console.warn("Ustagram: No characters available for auto-post"); if (btn) { btn.disabled = false; btn.innerText = "📸 New Photo"; } return; }
        console.log("Ustagram: Generating post for " + bot.name);

        // Random post category for variety
        const categories = [
            { name: 'selfie', prompt: 'Post a scroll-stopping selfie — smize hard, golden hour light catching your cheekbones, lips slightly parted. That "I woke up like this" energy but flawless. High-end smartphone photography.' },
            { name: 'mirror_selfie', prompt: 'Post a mirror selfie in a luxury bathroom — outfit hugging every curve, hand in your hair, silhouette on full display. Let the mirror show the whole vibe. Minimalist and chic.' },
            { name: 'fitcheck_mirror', prompt: 'Post a "Fit Check" mirror selfie — focusing on how a specific designer set or yoga wear fits every curve. Pop one hip, show off the waist-to-hip ratio. Professional lighting, very aesthetic.' },
            { name: 'luxury_lifestyle', prompt: 'Post a luxury night out — sipping a cocktail on a rooftop, neon city lights reflecting off glossy lips and jewelry. Focus on the high-end "Main Character" vibe. Cinematic night photography.' },
            { name: 'beach_silhouette', prompt: 'Post a beach silhouette — backlit by a massive orange sunset. Focus on the perfect curve of your body against the waves. No explicit detail, just pure, gorgeous shape and wanderlust vibe.' },
            { name: 'food', prompt: 'Post a food photo where you are the main course — taking a slow bite of a strawberry or licking frosting, looking right at the camera. Intimate, candlelight, soft focus on you.' },
            { name: 'outfit', prompt: 'Post a dangerously hot OOTD — bodycon dress or a blazer with nothing underneath. Full body stance, popping one hip, confident gaze. Like a high-fashion magazine spread.' },
            { name: 'nature', prompt: 'Post a sun-kissed nature shot — wind in your hair, flowy silk dress catching the breeze. Ethereal lighting, soft skin textures, looking like a goddess in the wild.' },
            { name: 'candid', prompt: 'Post a "caught off-guard" candid — laughing with your head thrown back, mid-hair-flip, looking over your shoulder with a half-smile. Unposed but flawless. 85mm f/1.8 lens vibe.' },
            { name: 'gym', prompt: 'Post a gym photo that is pure motivation — leggings that fit like a second skin, sweat glistening on your collarbones, that post-workout "flushed" glow. High-energy, fit and sexy.' },
            { name: 'bathrobe', prompt: 'Post a bathrobe photo — white linen loosely tied, damp hair, dewy "morning after" skin. Bedroom eyes, soft natural morning light, very intimate and beautiful.' },
            { name: 'poolside', prompt: 'Post a poolside photo — lounging by the water in a high-cut swimsuit, sunglasses up, tanned skin glistening with water droplets. Pure summer heat energy.' },
            { name: 'bedroom', prompt: 'Post a cozy bedroom shot — oversized sweater falling off one shoulder, legs tucked under you, soft warm lamplight. A private, intimate moment shared with the camera.' },
            { name: 'backless', prompt: 'Post a photo in a backless dress — looking over your shoulder, spine and shoulder blades on display. Elegant, sensual, and sophisticated. Red carpet vibe.' },
            { name: 'sunset', prompt: 'Post a "Golden Hour" masterclass — the sun painting your skin warm and hair glowing. Dreamy, ethereal look, looking away from the camera into the light.' },
            { name: 'legs', prompt: 'Post a shot of your long, smooth legs — crossed elegantly on a velvet sofa or stretched out on a private yacht deck. Focus on the flawless skin and the high-fashion silhouette.' },
            { name: 'heels', prompt: 'Post a high-fashion shoe shot — the arch of your foot in a pair of designer stilettos as you step out of a luxury car. Focus on the elegance of the pose and the expensive vibe.' },
            { name: 'feet', prompt: 'Post a "barefoot luxury" shot — your toes in the white sand of a private beach or resting on a plush rug. Soft focus, high-end pedicure, capturing a moment of pure, relaxed sexiness.' }
        ];

        const category = categories[Math.floor(Math.random() * categories.length)];
        const charCount = State.characters.length;
        const mentionChance = Math.random() < 0.25 && charCount > 1;
        let mentionStr = '';
        if (mentionChance) {
            let otherChar;
            do { otherChar = State.characters[Math.floor(Math.random() * charCount)]; } while (otherChar.id === bot.id);
            mentionStr = ` Tag or mention @${otherChar.handle.replace('@','')} in the caption as if they are in the photo with you or related to the moment.`;
        }

        try {
            const response = await API.sendMessage(bot.id, `You are posting on Instagram right now. ${category.prompt}${mentionStr} Write a natural, engaging caption (emojis welcome, hashtags optional). Then on a new line write "flux prompt:" followed by a detailed visual description of the photo (photorealistic, 85mm lens, professional photography terms, describe lighting, expression, setting, outfit, and mood). Make the photo description feel like a real Instagram photo, not a generic stock image. Vary the angle, lighting, setting, and activity every time.`, (chunk) => {
                if (btn) btn.innerText = "⏳ Thinking...";
            }, false, 'social');

            let caption = "New post!";
            let visualPrompt = `Realistic Instagram photo of ${bot.name}, cinematic, natural lighting, 85mm portrait`;

            if (response.toLowerCase().includes("flux prompt:")) {
                const parts = response.split(/flux prompt:/i);
                caption = parts[0].trim() || "New post!";
                visualPrompt = `${parts[1].trim()}, photorealistic, highly detailed face, natural skin texture, candid moment, Instagram aesthetic, soft natural lighting, sharp focus, 85mm f/1.8, professional photography`;
            } else {
                caption = response.trim();
                visualPrompt = `${response.trim()}, photorealistic portrait of ${bot.name}, natural lighting, 85mm, candid, Instagram style`;
            }

            let b64 = null;
            if (window.ImagingApp) {
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

                // Chance for others to comment
                if (State.characters.length > 1) {
                    setTimeout(() => this.generateComment(dbId), 4000 + Math.random() * 6000);
                }
            }
        } catch(e) { console.error(e); }
        if (btn) { btn.disabled = false; btn.innerText = "📸 New Photo"; }
    },

    generateComment: async function(postId) {
        const post = (State.instagramPosts || []).find(p => p.id === postId);
        if (!post) return;
        const others = State.characters.filter(c => c.id !== post.charId);
        if (others.length === 0) return;

        const commenter = others[Math.floor(Math.random() * others.length)];
        try {
            const api = window.API;
            const prompt = `You are ${commenter.name}. You see a new photo posted by ${post.charName} on Ustagram with the caption: "${post.caption}". Write a short, natural comment (max 12 words, emojis welcome) as yourself. Output ONLY the comment text.`;
            const comment = await api.sendMessage(commenter.id, prompt, null, false, 'social');
            if (comment && comment.length > 1) {
                if (!post.comments) post.comments = [];
                post.comments.push({
                    charId: commenter.id,
                    charName: commenter.name,
                    text: comment.trim(),
                    timestamp: Date.now()
                });
                State.save();
                this.loadPosts();
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
        // Check for API key (not needed for localllm provider)
        const provider = s.provider || 'deepinfra';
        if (provider !== 'localllm' && !s.key) return;
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
                this.generatePost().finally(() => { this._autoPosting = false; });
            }, jitter);
            // Also do the first post now
            if (!this._autoPosting) {
                this._autoPosting = true;
                this.generatePost().finally(() => { this._autoPosting = false; });
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
        const provider = s.provider || 'deepinfra';
        const hasKey = provider === 'localllm' || s.key;
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
