/**
 * ustagram.js
 * Ustagram-style feed — bots post SFW photos only.
 * Uses Native Storage via ImageDB to bypass memory limits.
 */
const UstagramApp = {
    container: null,

    init: function(container) {
        this.container = container;
        this.render();
        this.loadPosts();
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
                <div class="ug-header"><h2>Ustagram</h2></div>
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
                    <div style="color:white; font-weight:700; font-size:0.9rem;">${p.charName || 'Anon'}</div>
                </div>
                <div class="ug-post-img-container" id="${imgId}"></div>
                <div class="ug-post-caption"><b>${p.charName || 'Anon'}</b> ${OS.formatMarkdown(p.caption || '')}</div>
            `;
            el.appendChild(postEl);

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
            el.innerHTML = isPost ? `<img class="ug-post-img" src="${finalSrc}" onclick="ImagingApp.openLocalLightbox(this.src)">` : `<img src="${finalSrc}">`;
        }
    },

    generatePost: async function() {
        const btn = document.getElementById('ugGenBtn');
        if (!btn) return;
        btn.disabled = true; btn.innerText = "⏳ Dreaming...";
        const bot = State.characters[Math.floor(Math.random() * State.characters.length)];

        // Random post category for variety
        const categories = [
            { name: 'selfie', prompt: 'Take a mirror selfie or front-facing selfie showing your outfit, makeup, or just your mood today. Make it feel candid and personal.' },
            { name: 'mirror_selfie', prompt: 'Post a bathroom mirror or hallway mirror selfie. Casual outfit check, maybe showing your full body look for the day.' },
            { name: 'food', prompt: 'Post a photo of a meal you just made or are about to eat. Show the food, the plating, the vibe. Coffee shop aesthetic, brunch, or homemade dinner.' },
            { name: 'outfit', prompt: 'Post an outfit-of-the-day (OOTD) photo. Full body shot showing your style, what you are wearing, the fit.' },
            { name: 'nature', prompt: 'Post a photo from a walk outside — a scenic view, sunset, trees, ocean, or cityscape. Caption your feelings about the moment.' },
            { name: 'pet', prompt: 'Post a photo with your pet or an animal you encountered. Cute, candid, wholesome moment.' },
            { name: 'night_out', prompt: 'Post a photo from a night out — maybe at a restaurant, bar, party, or just city lights at night. Show your look and the atmosphere.' },
            { name: 'candid', prompt: 'Post a candid photo of yourself — someone else took it or it is a behind-the-scenes moment. Natural, unposed, real life.' },
            { name: 'gym', prompt: 'Post a gym selfie or workout photo. Show your progress, your fit, or just that post-workout glow.' },
            { name: 'art', prompt: 'Post a photo of something creative you made or found — art, music, a book, a record, a tattoo. Show your personality through your interests.' },
            { name: 'travel', prompt: 'Post a travel or vacation photo. A hotel room view, an airport moment, a landmark, or just exploring somewhere new.' },
            { name: 'throwback', prompt: 'Post a throwback photo. An old memory, a past version of yourself, a nostalgic moment. Reflect on how things have changed.' }
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
                btn.innerText = "⏳ Thinking...";
            });

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
                    btn.innerText = `⏳ ${p}%`;
                });
            }
            if (b64 && window.ImageDB) {
                const dbId = 'ig_' + Date.now();
                await window.ImageDB.save(dbId, b64);
                if (!State.instagramPosts) State.instagramPosts = [];
                State.instagramPosts.push({ id: dbId, charId: bot.id, charName: bot.name, image: `db:${dbId}`, caption, timestamp: Date.now() });
                State.save();
                this.loadPosts();
            }
        } catch(e) { console.error(e); }
        btn.disabled = false; btn.innerText = "📸 New Photo";
    },

    clearAll: async function() {
        if (!confirm("Clear all photos?")) return;
        if (window.ImageDB) {
            for (const p of (State.instagramPosts || [])) {
                if (p.image.startsWith('db:')) await window.ImageDB.delete(p.image.replace('db:', ''));
            }
        }
        State.instagramPosts = [];
        State.save();
        this.loadPosts();
    }
};
window.UstagramApp = UstagramApp;
