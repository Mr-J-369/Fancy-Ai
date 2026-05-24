/**
 * rebbit.js
 * Rebbit-style feed — bots post amateur-style NSFW photos only.
 * Uses ImageDB for persistent high-capacity storage.
 */
const RebbitApp = {
    container: null,

    init: function(container) {
        this.container = container;
        this.render();
        this.loadPosts();
    },

    render: function() {
        const styleId = "rebbit-app-style";
        if (!document.getElementById(styleId)) {
            const style = document.createElement('style');
            style.id = styleId;
            style.innerHTML = `
                .rb-wrap { padding: 0; overflow-y: auto; height: 100%; background: #0a0a0b; display: flex; flex-direction: column; padding-bottom: 100px; }
                .rb-header { background: linear-gradient(135deg, rgba(255,69,0,0.1), rgba(200,50,0,0.05)); padding: 12px 16px; border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 8px; }
                .rb-header h2 { margin: 0; font-size: 1.1rem; font-weight: 800; color: #ff4500; }
                .rb-controls { padding: 10px 16px; display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; background: rgba(255,255,255,0.02); border-bottom: 1px solid var(--border); position: sticky; top: 0; z-index: 10; }
                .rb-btn-gen { padding: 10px 20px; font-size: 0.8rem; background: #ff4500; color: white; border: none; border-radius: 12px; font-weight: 700; cursor: pointer; box-shadow: 0 4px 12px rgba(255,69,0,0.3); transition: all 0.2s; }
                .rb-btn-gen:disabled { opacity: 0.5; cursor: not-allowed; filter: grayscale(0.5); }
                .rb-btn-clear { padding: 10px 20px; font-size: 0.8rem; background: rgba(239,68,68,0.15); color: #ef4444; border: 1px solid rgba(239,68,68,0.3); border-radius: 12px; font-weight: 700; cursor: pointer; }
                .rb-post { padding: 12px 16px; border-bottom: 1px solid var(--border); animation: postFadeIn 0.3s ease; }
                .rb-post-header { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
                .rb-post-avatar { width: 36px; height: 36px; border-radius: 50%; background: #ff4500; color: white; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 0.85rem; overflow: hidden; }
                .rb-post-avatar img { width: 100%; height: 100%; object-fit: cover; }
                .rb-post-name { font-weight: 600; color: white; font-size: 0.82rem; }
                .rb-post-sub { font-size: 0.7rem; color: var(--text-muted); }
                .rb-post-title { font-weight: 700; color: white; font-size: 0.95rem; margin: 4px 0 6px; }
                .rb-post-img { width: 100%; border-radius: 8px; cursor: zoom-in; background: #111; display: block; }
                .rb-post-nsfw { display: inline-block; background: rgba(255,69,0,0.15); color: #ff4500; font-size: 0.65rem; font-weight: 700; padding: 2px 6px; border-radius: 4px; margin-right: 6px; text-transform: uppercase; }
                @keyframes postFadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
            `;
            document.head.appendChild(style);
        }
        this.container.innerHTML = `
            <div class="rb-wrap">
                <div class="rb-header"><h2>Rebbit</h2></div>
                <div class="rb-controls">
                    <button class="rb-btn-gen" id="rbGenBtn" onclick="RebbitApp.generatePost()">📸 New Post</button>
                    <button class="rb-btn-clear" onclick="RebbitApp.clearAll()">🗑️ Clear</button>
                </div>
                <div id="rbPosts"></div>
            </div>
        `;
    },

    loadPosts: function() {
        const el = document.getElementById('rbPosts');
        if (!el) return;
        const posts = State.redditPosts || [];
        if (posts.length === 0) {
            el.innerHTML = `<div style="padding:80px 30px; text-align:center; color:var(--text-muted); font-style:italic; font-size:0.9rem;">No posts yet.<br>Tap 📸 New Post to start!</div>`;
            return;
        }
        el.innerHTML = "";
        [...posts].reverse().forEach(p => {
            const postEl = document.createElement('div');
            postEl.className = 'rb-post';
            const imgId = `rb-img-${p.id}`;
            const avatarId = `rb-av-${p.id}`;

            postEl.innerHTML = `
                <div class="rb-post-header">
                    <div class="rb-post-avatar" id="${avatarId}">${(p.charName||'A')[0]}</div>
                    <div>
                        <div class="rb-post-name">${p.charName || 'Anon'}</div>
                        <div class="rb-post-sub">${p.subreddit || 'r/ai_nsfw'} • ${Math.floor(Math.random()*500)+1} upvotes</div>
                    </div>
                </div>
                <div class="rb-post-title"><span class="rb-post-nsfw">NSFW</span> ${OS.formatMarkdown(p.title || 'Untitled')}</div>
                <div id="${imgId}" style="min-height:200px; background:#111; border-radius:8px;"></div>
            `;
            el.appendChild(postEl);

            // Resolve Image
            this.resolveMedia(p.image, imgId, true);

            // Resolve Avatar if character exists
            const char = State.characters.find(c => c.id === p.charId);
            if (char && char.avatar) {
                this.resolveMedia(char.avatar, avatarId, false);
            }
        });
    },

    resolveMedia: async function(src, containerId, isPostImg) {
        if (!src) return;
        let finalSrc = src;
        if (src.startsWith('db:') && window.ImageDB) {
            finalSrc = await window.ImageDB.get(src);
        }
        const container = document.getElementById(containerId);
        if (!container || !finalSrc) return;

        if (isPostImg) {
            container.innerHTML = `<img class="rb-post-img" src="${finalSrc}" onclick="if(window.ImagingApp) ImagingApp.openLocalLightbox(this.src)">`;
        } else {
            container.innerHTML = `<img src="${finalSrc}">`;
        }
    },

    generatePost: async function() {
        const btn = document.getElementById('rbGenBtn');
        if (btn) { btn.disabled = true; btn.innerText = "⏳ Dreaming..."; }

        const bot = State.characters[Math.floor(Math.random() * State.characters.length)];

        // Diverse NSFW post categories — real Reddit variety
        const categories = [
            { sub: 'r/gonewild', prompt: 'Post a nude or semi-nude photo of yourself — full body, natural lighting, candid bedroom or bathroom setting. Show your confidence.' },
            { sub: 'r/realgirls', prompt: 'Post an amateur-style photo that looks like a real girl next door. Natural, no professional lighting, just you being sexy in a casual setting.' },
            { sub: 'r/selfie', prompt: 'Post a sexy selfie — close-up, flirty expression, maybe biting your lip or a seductive look. Show your face and upper body.' },
            { sub: 'r/legs', prompt: 'Post a photo focused on your legs — maybe in stockings, heels, shorts, or just bare legs stretched out. Highlight the legs.' },
            { sub: 'r/ass', prompt: 'Post a photo showing off your ass — bent over, on all fours, or a mirror shot from behind. Make it look spontaneous.' },
            { sub: 'r/lingerie', prompt: 'Post a photo in lingerie or underwear. Show off the set, the fit, the colors. Seductive pose, bedroom lighting.' },
            { sub: 'r/bikinis', prompt: 'Post a bikini or swimsuit photo. Poolside, beach, or just posing at home. Summer vibes, confident body display.' },
            { sub: 'r/tittydrop', prompt: 'Post a titty drop or top-removal reveal. Start clothed, show the moment of exposure. Playful and teasing.' },
            { sub: 'r/frombelow', prompt: 'Post a photo taken from below looking up at your body. Powerful angle, shows curves and confidence.' },
            { sub: 'r/bedroom', prompt: 'Post a photo in bed — sheets tangled, morning light, sleepy but sexy vibe. Intimate and personal.' },
            { sub: 'r/shower', prompt: 'Post a shower or bath photo. Wet skin, steam, water droplets. Sensual and steamy without being explicit.' },
            { sub: 'r/mirror', prompt: 'Post a full-length mirror selfie. Phone covering your face or not, showing your entire body and outfit (or lack of it).' },
            { sub: 'r/doggy', prompt: 'Post a photo from behind — doggy style pose, on the bed or floor. Shows your back, ass, and the arch of your spine.' },
            { sub: 'r/onallfours', prompt: 'Post a photo on all fours. Looking back at the camera, arched back, submissive but powerful pose.' },
            { sub: 'r/hentai', prompt: 'Post an anime-style or hentai-inspired photo. Exaggerated proportions, stylized aesthetic, fantasy vibe.' },
            { sub: 'r/fitgirls', prompt: 'Post a gym or fitness themed NSFW photo. Toned body, sports bra, leggings, sweat. Show off your hard work.' },
            { sub: 'r/feet', prompt: 'Post a photo focused on your feet — maybe in stockings, bare, or with heels. Toes, arches, sole shots.' },
            { sub: 'r/anal', prompt: 'Post a photo that hints at or shows anal play. Spread cheeks, a plug, or just a suggestive pose from behind.' },
            { sub: 'r/orgasms', prompt: 'Post a photo of yourself mid-orgasm or just after. Flushed skin, messy hair, satisfied expression. Intense and real.' },
            { sub: 'r/bondage', prompt: 'Post a bondage or BDSM-themed photo. Ropes, cuffs, blindfold, or just a restrained pose. Kinky and artistic.' },
            { sub: 'r/publicflashing', prompt: 'Post a public flashing photo — lifting your shirt in a park, pulling down your pants in a parking lot, or exposing yourself in a semi-public place like an alley or stairwell. Risky, thrilling, candid.' },
            { sub: 'r/gloryhole', prompt: 'Post a gloryhole themed photo. Kneeling by a wall with a hole, mouth open, or just the setup and anticipation. Anonymous, slutty, dark lighting.' },
            { sub: 'r/interracial', prompt: 'Post an interracial photo. Show contrast — different skin tones together. Could be oral, missionary, doggy, or just a teasing side-by-side comparison.' },
            { sub: 'r/hotwife', prompt: 'Post a hotwife themed photo. Dressed sexy alone or with a partner, wedding ring visible, caption about being shared or wanted by others. Confident, mature, possessive vibe.' },
            { sub: 'r/gangbang', prompt: 'Post a gangbang fantasy photo. One person surrounded by multiple partners, bukkake, or just the setup with multiple bodies. Intense, crowded, explicit.' },
            { sub: 'r/creampie', prompt: 'Post a creampie photo. Cum leaking out, filled up, just after sex. Messy, raw, intimate aftermath.' },
            { sub: 'r/cuckold', prompt: 'Post a cuckold themed photo. A partner with someone else while you watch or wait nearby. Humiliation, denial, teasing.' },
            { sub: 'r/threesome', prompt: 'Post a threesome photo. Two people pleasing one, or all three intertwined. MFF, MFM, or any combo. Energetic, hands everywhere.' },
            { sub: 'r/deepthroat', prompt: 'Post a deepthroat photo. Face being fucked, tears running, gagging, or just the angle looking down at a mouth stuffed full.' },
            { sub: 'r/facial', prompt: 'Post a facial photo. Cum covering the face, eyes closed, tongue out. Messy, degrading, proud.' },
            { sub: 'r/spitroast', prompt: 'Post a spitroast photo. Being penetrated from both ends — one in front, one behind. Full use, stretched, filled.' },
            { sub: 'r/doublepenetration', prompt: 'Post a double penetration photo. Both holes filled at once — vaginal and anal. Stretched, intense, full.' },
            { sub: 'r/roughsex', prompt: 'Post a rough sex photo. Hair pulling, being pinned down, choked lightly, or manhandled. Raw, dominant, powerful.' },
            { sub: 'r/strapons', prompt: 'Post a strapon or pegging photo. Wearing a strap-on, getting pegged, or just the harness and toy ready to go. Power dynamic, kinky.' },
            { sub: 'r/femdom', prompt: 'Post a femdom themed photo. You in control — commanding a submissive, using toys, or just a powerful pose with a whip or heels. Dominant energy.' },
            { sub: 'r/grool', prompt: 'Post a grool or wetness photo. Up close, messy, dripping. Shows arousal in a raw, real way.' },
            { sub: 'r/pantyhose', prompt: 'Post a pantyhose or nylons photo. Legs crossed, ripped hose, shiny fabric. Classic, elegant, teasing.' },
            { sub: 'r/stockings', prompt: 'Post a stockings and garter belt photo. Vintage pin-up vibe, thigh highs, suspenders. Retro sexy, posed.' },
            { sub: 'r/masturbation', prompt: 'Post a masturbation photo. Toys or fingers, mid-play, legs spread, faces of pleasure. Solo fun, intimate.' },
            { sub: 'r/voyeur', prompt: 'Post a voyeur-style photo. Taken from a distance, through a doorway, or as if someone is watching you without you knowing. Hidden, sneaky, real.' }
        ];

        const category = categories[Math.floor(Math.random() * categories.length)];
        const charCount = State.characters.length;
        const mentionChance = Math.random() < 0.2 && charCount > 1;
        let mentionStr = '';
        if (mentionChance) {
            let otherChar;
            do { otherChar = State.characters[Math.floor(Math.random() * charCount)]; } while (otherChar.id === bot.id);
            mentionStr = ` Mention or tag u/${otherChar.handle.replace('@','')} in the title or description as if they are involved or related to the post.`;
        }

        try {
            const api = window.API;
            if (!api || !State.settings.key) {
                if (btn) { btn.disabled = false; btn.innerText = "📸 New Post"; }
                return;
            }

            const response = await api.sendMessage(bot.id, `You are posting on Rebbit (a Reddit-like NSFW platform) right now. ${category.prompt}${mentionStr} Write a catchy, engaging title (just the title text, no "title:" prefix). Then on a new line write "subreddit: ${category.sub}" based on the theme. Then on a new line write "flux prompt:" followed by a highly detailed visual description of the photo — describe the pose, lighting, setting, what body parts are visible, the expression, the mood. Make it feel like a real amateur Reddit post, not a professional shoot. Vary the pose, angle, setting, and activity every time. Be explicit and detailed in the visual description.`, null, false, 'social');

            let title = "";
            let visualPrompt = "";
            let subreddit = category.sub;

            const subMatch = response.match(/subreddit:\s*(r\/[a-zA-Z0-9_]+)/i);
            if (subMatch) subreddit = subMatch[1].toLowerCase();

            if (response.toLowerCase().includes("flux prompt:")) {
                const parts = response.split(/flux prompt:/i);
                title = parts[0].split(/subreddit:/i)[0].trim().replace(/["']/g, '').split('\n')[0] || "Check this out";
                visualPrompt = `${parts[1].trim()}, amateur style, realistic skin texture, natural lighting, candid NSFW photo, highly detailed body, sharp focus,真实的 amateur photography`;
            } else {
                title = response.replace(/["']/g, '').split('\n')[0];
                visualPrompt = `Amateur NSFW photo of ${bot.name}, ${category.sub} style, ${bot.persona || 'sexy and confident'}, realistic skin, candid, natural lighting`;
            }

            let imageB64 = null;
            if (window.ImagingApp && visualPrompt) {
                imageB64 = await window.ImagingApp.generate(visualPrompt, null, (p) => {
                    if (btn) btn.innerText = `⏳ ${p}%`;
                });
            }

            if (imageB64) {
                let storagePath = imageB64;
                if (window.ImageDB) {
                    const dbId = 'rb_' + Date.now();
                    await window.ImageDB.save(dbId, imageB64);
                    storagePath = `db:${dbId}`;
                }

                if (!State.redditPosts) State.redditPosts = [];
                State.redditPosts.push({
                    id: 'rd_' + Date.now(),
                    charId: bot.id,
                    charName: bot.name,
                    title: title,
                    subreddit: subreddit,
                    image: storagePath,
                    timestamp: Date.now()
                });
                if (State.redditPosts.length > 50) {
                    const oldPost = State.redditPosts.shift();
                    if (oldPost && oldPost.image && oldPost.image.startsWith('db:') && window.ImageDB) {
                        await window.ImageDB.delete(oldPost.image.replace('db:', ''));
                    }
                }
                State.save();
                this.loadPosts();
            }
        } catch(e) {
            console.error("Rebbit gen error:", e);
        } finally {
            if (btn) { btn.disabled = false; btn.innerText = "📸 New Post"; }
        }
    },

    clearAll: async function() {
        if (!confirm("Clear all Rebbit posts?")) return;
        if (window.ImageDB) {
            for (const p of (State.redditPosts || [])) {
                if (p.image && p.image.startsWith('db:')) await window.ImageDB.delete(p.image.replace('db:', ''));
            }
        }
        State.redditPosts = [];
        State.save();
        // Trigger orphan cleanup
        if (window.ImageDB && window.ImageDB.purgeOrphanedFiles) window.ImageDB.purgeOrphanedFiles();
        this.loadPosts();
    }
};
window.RebbitApp = RebbitApp;
