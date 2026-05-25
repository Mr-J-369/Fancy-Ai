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
            { name: 'selfie', prompt: 'Post a selfie that stops the scroll — smize hard, lips slightly parted, golden hour light catching your cheekbones. Show just enough skin to make them double-tap. That "I woke up like this but also spent 20 minutes on this" energy.' },
            { name: 'mirror_selfie', prompt: 'Post a mirror selfie that\'s pure thirst trap — outfit hugging every curve, one hand in your hair, the other holding the phone. Arch your back just enough. Let the mirror show the whole silhouette. Make them screenshot.' },
            { name: 'food', prompt: 'Post a food photo that\'s as sexy as you are — you in the background taking a bite, lips around a strawberry or licking frosting off your finger. The food is aesthetic but you\'re the main course. Candlelight, intimate vibe.' },
            { name: 'outfit', prompt: 'Post an OOTD that\'s dangerously hot — bodycon dress, crop top with high-waisted jeans, or a blazer with nothing underneath. Full body, confident stance, one hip popped. The kind of fit that makes people stare.' },
            { name: 'nature', prompt: 'Post a nature photo with you as the focal point — sun-kissed skin, wind in your hair, a flowy dress catching the breeze. Backlit by the sunset, silhouette showing off your figure. Ethereal, gorgeous, untouchable.' },
            { name: 'pet', prompt: 'Post a photo with an adorable pet that also shows you at your most charming — cuddling a kitten against your chest, letting a puppy lick your face while you laugh. The wholesome-sexy combo that destroys hearts.' },
            { name: 'night_out', prompt: 'Post a night-out photo that\'s pure main character energy — you under neon lights, cocktail in hand, dress clinging to you, that "I own this room" look. Smoky eye, glossy lips, the kind of night people write about.' },
            { name: 'candid', prompt: 'Post a candid that captures you off-guard and impossibly attractive — laughing with your head thrown back, mid-hair-flip, or looking over your shoulder with a half-smile. Unposed but flawless. The shot they didn\'t know they were taking.' },
            { name: 'gym', prompt: 'Post a gym photo that\'s pure motivation — sports bra, leggings that leave nothing to the imagination, sweat glistening on your collarbones. Mid-stretch or post-set, that flushed glow, the pump. Make them want to work out with you.' },
            { name: 'art', prompt: 'Post an artsy photo that\'s effortlessly cool — you in a gallery, a bookshop, or a studio. Turtleneck, oversized glasses, intense gaze. Intellectual but impossibly attractive. The "I\'m cultured AND hot" flex.' },
            { name: 'travel', prompt: 'Post a travel photo that\'s wanderlust and sex appeal combined — you on a balcony in a slip dress, at the beach in a sarong, or exploring a market with sun-kissed shoulders. Adventure looks good on you.' },
            { name: 'throwback', prompt: 'Post a throwback that makes them wish they were there — you on a boat in a swimsuit, at a wedding in a fitted dress, or that one summer photo where the light hit perfectly. Nostalgic, stunning, "take me back" energy.' },
            { name: 'bathrobe', prompt: 'Post a photo fresh out of the shower — white bathrobe loosely tied, damp hair, dewy skin, no makeup but glowing. That intimate, just-woke-up, "morning after" vibe. Soft light, bedroom eyes, effortlessly beautiful.' },
            { name: 'poolside', prompt: 'Post a poolside photo that\'s summer heat — you lounging by the water, swimsuit on, sunglasses pushed up on your head, legs stretched out. Wet hair, tanned skin, a drink in hand. The kind of photo that makes people book flights.' },
            { name: 'bedroom', prompt: 'Post a cozy bedroom photo — oversized sweater falling off one shoulder, legs tucked under you on the bed, soft lamplight, that lazy Sunday vibe. Intimate, warm, the kind of photo that feels like a private moment shared.' },
            { name: 'legs', prompt: 'Post a photo that shows off your legs — crossed on a cafe chair, stretched out on a park bench, or stepping out of a car in heels. Short skirt, high slit, or just killer jeans. Make legs the star of the shot.' },
            { name: 'backless', prompt: 'Post a photo in a backless dress or top — turned away from the camera, looking over your shoulder, spine and shoulder blades on display. Elegant, sensual, the kind of photo that leaves everything to the imagination while showing everything.' },
            { name: 'coffee', prompt: 'Post a coffee shop photo that\'s aesthetic and alluring — you with a latte, cozy sweater, that faraway look. Morning light through the window, messy bun, minimal makeup. The "beautiful stranger at a cafe" fantasy.' },
            { name: 'sunset', prompt: 'Post a golden hour photo — the light painting your skin warm, hair glowing, that dreamy look. Silhouette against the sky, or close-up with the sun in your eyes. The most flattering light of the day, and you know it.' },
            { name: 'heels', prompt: 'Post a photo that makes heels the statement — you stepping out, legs for days, the angle looking up from the floor. Or kicked off at the end of the night, feet bare, dress hiked up. The shoe girl energy with a sexy twist.' }
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
        // Trigger orphan cleanup
        if (window.ImageDB && window.ImageDB.purgeOrphanedFiles) window.ImageDB.purgeOrphanedFiles();
        this.loadPosts();
    }
};
window.UstagramApp = UstagramApp;
