/**
 * rebbit.js
 * Rebbit-style feed — bots post amateur-style NSFW photos only.
 * Uses ImageDB for persistent high-capacity storage.
 */
const RebbitApp = {
    container: null,
    _autoPostTimer: null,
    _autoPosting: false,

    init: async function(container) {
        this.container = container;
        this.render();
        await this.loadPosts();
        this.startAutoPost();
    },

    render: function() {
        const styleId = "rebbit-app-style";
        if (!document.getElementById(styleId)) {
            const style = document.createElement('style');
            style.id = styleId;
            style.innerHTML = `
                .rb-wrap { padding: 0; overflow-y: auto; height: 100%; background: var(--md-surface); display: flex; flex-direction: column; padding-bottom: 100px; }
                .rb-header { background: rgba(208, 188, 255, 0.08); padding: 12px 16px; border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 8px; }
                .rb-header h2 { margin: 0; font-size: 1.1rem; font-weight: 800; color: var(--md-primary); }
                .rb-header span { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #22c55e; box-shadow: 0 0 6px #22c55e; animation: pulse 2s infinite; }
                .rb-controls { padding: 10px 16px; display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; background: rgba(255,255,255,0.02); border-bottom: 1px solid var(--border); position: sticky; top: 0; z-index: 10; }
                .rb-btn-gen { padding: 10px 20px; font-size: 0.8rem; background: var(--md-primary-container); color: var(--md-on-primary-container); border: none; border-radius: 12px; font-weight: 700; cursor: pointer; box-shadow: 0 4px 12px rgba(208, 188, 255, 0.3); transition: all 0.2s; }
                .rb-btn-gen:disabled { opacity: 0.5; cursor: not-allowed; filter: grayscale(0.5); }
                .rb-btn-clear { padding: 10px 20px; font-size: 0.8rem; background: #333; color: var(--text-muted); border: none; border-radius: 12px; font-weight: 700; cursor: pointer; }
                .rb-btn-settings { padding: 10px; font-size: 1rem; background: rgba(255,255,255,0.05); color: var(--text-muted); border: 1px solid var(--border); border-radius: 12px; font-weight: 700; cursor: pointer; transition: all 0.2s; }
                .rb-btn-settings:hover { background: rgba(255,255,255,0.1); color: white; }
                .rb-post { padding: 12px 16px; border-bottom: 1px solid var(--border); animation: postFadeIn 0.3s ease; }
                .rb-post-header { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
                .rb-post-avatar { width: 36px; height: 36px; border-radius: 50%; background: #ff4500; color: white; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 0.85rem; overflow: hidden; }
                .rb-post-avatar img { width: 100%; height: 100%; object-fit: cover; }
                .rb-post-name { font-weight: 600; color: white; font-size: 0.82rem; }
                .rb-post-sub { font-size: 0.7rem; color: var(--text-muted); }
                .rb-post-title { font-weight: 700; color: white; font-size: 0.95rem; margin: 4px 0 6px; }
                .rb-post-img { width: 100%; border-radius: 8px; cursor: zoom-in; background: #111; display: block; }
                .rb-post-nsfw { display: inline-block; background: rgba(208, 188, 255, 0.15); color: var(--md-primary); font-size: 0.65rem; font-weight: 700; padding: 2px 6px; border-radius: 4px; margin-right: 6px; text-transform: uppercase; }
                @keyframes postFadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }

                /* Settings overlay */
                .rb-settings-overlay {
                    position: fixed; top: 0; left: 0; right: 0; bottom: 0;
                    z-index: 99998; display: flex; align-items: flex-start;
                    justify-content: center; padding-top: 60px;
                    background: rgba(0,0,0,0.6); backdrop-filter: blur(6px);
                }
                .rb-settings-sheet {
                    background: var(--md-surface-container-high); border-radius: 20px; padding: 16px;
                    min-width: 260px; max-width: 320px; width: 85%;
                    box-shadow: 0 20px 60px rgba(0,0,0,0.6);
                    border: 1px solid var(--md-outline-variant);
                    max-height: 70vh; overflow-y: auto;
                }
                .rb-settings-title {
                    font-size: 1rem; font-weight: 800; color: var(--md-primary);
                    margin-bottom: 12px; text-align: center;
                }
                .rb-settings-item {
                    display: flex; align-items: center; gap: 10px;
                    padding: 10px 12px; border-radius: 12px;
                    cursor: pointer; transition: background 0.15s;
                }
                .rb-settings-item:hover { background: rgba(255,255,255,0.04); }
                .rb-settings-check {
                    width: 22px; height: 22px; border-radius: 6px;
                    border: 2px solid rgba(255,255,255,0.2);
                    display: flex; align-items: center; justify-content: center;
                    flex-shrink: 0; transition: all 0.15s;
                    font-size: 0.7rem; color: transparent;
                }
                .rb-settings-check.checked {
                    background: var(--md-primary-container); border-color: var(--md-primary-container); color: var(--md-on-primary-container);
                }
                .rb-settings-name { font-weight: 600; color: white; font-size: 0.88rem; flex: 1; }
                .rb-settings-handle { font-size: 0.7rem; color: var(--text-muted); }
            `;
            document.head.appendChild(style);
        }
        this.container.innerHTML = `
            <div class="rb-wrap">
                <div class="rb-header"><h2>Rebbit</h2><span id="rbAutoPostIndicator" style="display:none; width:8px; height:8px; border-radius:50%; background:#22c55e; box-shadow:0 0 6px #22c55e; animation: pulse 2s infinite;"></span></div>
                <div class="rb-controls">
                    <button class="rb-btn-gen" id="rbGenBtn" onclick="RebbitApp.generatePost()">📸 New Post</button>
                    <button class="rb-btn-clear" onclick="RebbitApp.clearAll()">🗑️ Clear</button>
                    <button class="rb-btn-settings" onclick="RebbitApp.showSettings()">⚙️</button>
                </div>
                <div id="rbPosts"></div>
            </div>
        `;
    },

    showSettings: function() {
        const existing = document.getElementById('rbSettingsOverlay');
        if (existing) existing.remove();

        const overlay = document.createElement('div');
        overlay.id = 'rbSettingsOverlay';
        overlay.className = 'rb-settings-overlay';
        overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };

        const sheet = document.createElement('div');
        sheet.className = 'rb-settings-sheet';
        sheet.onclick = (e) => e.stopPropagation();

        sheet.innerHTML = `
            <div class="rb-settings-title">🌐 Select Subreddits</div>
            <div style="margin-bottom:10px; display:flex; gap:5px;">
                <input type="text" id="rbSubFilter" class="form-control" placeholder="Filter subreddits..." style="padding:8px; background:#2a2a2e; border:none; color:white; border-radius:8px;">
                <button class="rb-btn-settings" style="padding:4px 8px;" onclick="RebbitApp.toggleAll(true)">All</button>
                <button class="rb-btn-settings" style="padding:4px 8px;" onclick="RebbitApp.toggleAll(false)">None</button>
            </div>
            <div id="rbSubList"></div>
        `;

        const listContainer = sheet.querySelector('#rbSubList');
        const filterInput = sheet.querySelector('#rbSubFilter');

        // All available subreddits
        const allSubs = [
            'r/gonewild', 'r/realgirls', 'r/selfie', 'r/legs', 'r/ass',
            'r/lingerie', 'r/bikinis', 'r/tittydrop', 'r/frombelow', 'r/bedroom',
            'r/shower', 'r/mirror', 'r/doggy', 'r/onallfours', 'r/hentai',
            'r/fitgirls', 'r/feet', 'r/anal', 'r/orgasms', 'r/bondage',
            'r/publicflashing', 'r/gloryhole', 'r/interracial', 'r/hotwife',
            'r/gangbang', 'r/creampie', 'r/cuckold', 'r/threesome', 'r/deepthroat',
            'r/facial', 'r/spitroast', 'r/doublepenetration', 'r/roughsex',
            'r/strapons', 'r/femdom', 'r/grool', 'r/pantyhose', 'r/stockings',
            'r/masturbation', 'r/voyeur', 'r/thighhighs', 'r/puffyalt', 'r/oilup',
            'r/downblouse', 'r/upskirt', 'r/collared', 'r/curvy', 'r/squirting',
            'r/workgonewild', 'r/cumontits'
        ];

        // Get saved enabled subreddits (default: all enabled)
        const saved = State.settings.rebbitSubreddits;
        const enabledSet = saved ? new Set(saved) : new Set(allSubs);

        const renderList = (filter = '') => {
            listContainer.innerHTML = '';
            const lowerFilter = filter.toLowerCase();
            allSubs.filter(s => s.toLowerCase().includes(lowerFilter)).forEach(sub => {
                const checked = enabledSet.has(sub);
                const item = document.createElement('div');
                item.className = 'rb-settings-item';
                item.onclick = () => {
                    if (enabledSet.has(sub)) {
                        enabledSet.delete(sub);
                    } else {
                        enabledSet.add(sub);
                    }
                    State.settings.rebbitSubreddits = [...enabledSet];
                    State.save();
                    const check = item.querySelector('.rb-settings-check');
                    if (check) check.classList.toggle('checked');
                };

                const check = document.createElement('div');
                check.className = 'rb-settings-check' + (checked ? ' checked' : '');
                check.textContent = '✓';

                const name = document.createElement('div');
                name.className = 'rb-settings-name';
                name.textContent = sub;

                item.appendChild(check);
                item.appendChild(name);
                listContainer.appendChild(item);
            });
        };

        filterInput.oninput = (e) => renderList(e.target.value);
        renderList();

        overlay.appendChild(sheet);
        document.body.appendChild(overlay);
    },

    toggleAll: function(enable) {
        const allSubs = [
            'r/gonewild', 'r/realgirls', 'r/selfie', 'r/legs', 'r/ass',
            'r/lingerie', 'r/bikinis', 'r/tittydrop', 'r/frombelow', 'r/bedroom',
            'r/shower', 'r/mirror', 'r/doggy', 'r/onallfours', 'r/hentai',
            'r/fitgirls', 'r/feet', 'r/anal', 'r/orgasms', 'r/bondage',
            'r/publicflashing', 'r/gloryhole', 'r/interracial', 'r/hotwife',
            'r/gangbang', 'r/creampie', 'r/cuckold', 'r/threesome', 'r/deepthroat',
            'r/facial', 'r/spitroast', 'r/doublepenetration', 'r/roughsex',
            'r/strapons', 'r/femdom', 'r/grool', 'r/pantyhose', 'r/stockings',
            'r/masturbation', 'r/voyeur', 'r/thighhighs', 'r/puffyalt', 'r/oilup',
            'r/downblouse', 'r/upskirt', 'r/collared', 'r/curvy', 'r/squirting',
            'r/workgonewild', 'r/cumontits'
        ];
        if (enable) {
            State.settings.rebbitSubreddits = [...allSubs];
        } else {
            State.settings.rebbitSubreddits = [];
        }
        State.save();
        this.showSettings(); // Refresh
    },

    getEnabledCategories: function() {
        const allCategories = this.getAllCategories();
        const saved = State.settings.rebbitSubreddits;
        if (!saved || saved.length === 0) return allCategories;
        return allCategories.filter(c => saved.includes(c.sub));
    },

getAllCategories: function() {
    return [
        { sub: 'r/gonewild', prompt: 'Post a raw, unedited nude photo spreading yourself open — pussy glistening, tits out, legs wide, that "look what you\'re missing" look. Natural light, messy bed, zero filters. Raw amateur energy.' },
        { sub: 'r/realgirls', prompt: 'Post a bathroom mirror selfie that screams "I\'m naked right now and might get caught." Soft tummy, real curves, wet cunt, hard nipples, and a daring, shy smirk.' },
        { sub: 'r/legs', prompt: 'Post a photo where your long, smooth thighs are the star — squeezed together or spread apart, maybe sheer stockings or high heels. Focus on the silhouette from the hip down.' },
        { sub: 'r/ass', prompt: 'Post a photo of your ass spread wide — cheeks pulled apart, pink hole winking, bent over deep with a sharp arch in your back. Make the view from behind absolutely undeniable.' },
        { sub: 'r/lingerie', prompt: 'Post a photo in sheer lace lingerie that\'s digging into your hips — nipples poking through, a visible wet spot forming on the crotch, looking ready to drop it all.' },
        { sub: 'r/tittydrop', prompt: 'Post a titty drop sequence — shirt lifted slow to release those bouncing tits, nipples hard. Capture the weight, the swing, and the raw exposure.' },
        { sub: 'r/shower', prompt: 'Post a steamy shower photo — water dripping down your tits and between your cheeks, soapy skin, dewy expression, and a hand cupping your pussy.' },
        { sub: 'r/mirror', prompt: 'Post a full-length nude mirror selfie — back arched to push your ass out, pussy bare and exposed, tits on full display. The ultimate 2am "wish you were here" shot.' },
        { sub: 'r/doggy', prompt: 'Post a doggy style view from behind — ass up, back arched deep, pussy and asshole perfectly exposed and glistening. Ready, wet, and inviting.' },
        { sub: 'r/onallfours', prompt: 'Post a photo on all fours — back arched like a cat in heat, looking back over your shoulder with a dirty smirk, pussy dripping and exposed from behind.' },
        { sub: 'r/fitgirls', prompt: 'Post a post-workout nude — glistening with sweat, sports bra pulled aside to show hard nipples and toned abs, leggings peeled down to show a shaved, flushed pussy.' },
        { sub: 'r/anal', prompt: 'Post a photo with your asshole front and center — cheeks spread wide, pink hole puckered, maybe a finger teasing the rim. Pure exhibitionist energy.' },
        { sub: 'r/orgasms', prompt: 'Post a photo mid-climax — back arched off the bed, mouth open, thighs shaking, cum dripping down your hand. Capture the raw, messy surrender to pleasure.' },
        { sub: 'r/bondage', prompt: 'Post a bondage photo — ropes or silk digging into soft skin, tits bound and swollen, helpless and exposed. The perfect mix of beauty and surrender.' },
        { sub: 'r/publicflashing', prompt: 'Post a risky public flash — tits out in a park or skirt hiked up in a parking lot. Heart racing, looking over your shoulder, the thrill of the taboo.' },
        { sub: 'r/creampie', prompt: 'Post a creampie photo — cum oozing out of your gaping pussy and running down your thighs. That messy, full, "taken" look.' },
        { sub: 'r/deepthroat', prompt: 'Post a deepthroat photo — eyes watering, mascara running, throat bulging as you take every inch. Messy, spit-slicked, and totally submissive.' },
        { sub: 'r/doublepenetration', prompt: 'Post a DP photo — both holes stuffed and stretched to the limit, hands gripping the sheets, a look of pure, overwhelming ecstasy.' },
        { sub: 'r/grool', prompt: 'Post a photo that\'s obscenely wet — a string of clear arousal from your fingers to your glistening pussy. Proof of how badly you want it.' },
        { sub: 'r/masturbation', prompt: 'Post a masturbation photo caught in the act — fingers deep inside, other hand squeezing a tit, legs spread wide, lost in the feeling.' },
        { sub: 'r/thighhighs', prompt: 'Post a photo in stay-up stockings — lace tops digging into your soft thighs, garter belt framing your bare pussy, high heels on. The ultimate tease.' },
        { sub: 'r/puffyalt', prompt: 'Post a photo focusing on your puffy, sensitive nipples — cold air or teasing fingers making them stand out. Close-up, intimate, and needy.' },
        { sub: 'r/oilup', prompt: 'Post a photo dripping in body oil — skin shimmering and slick, hands rubbing oil over your tits and down your stomach to your pussy. Pure liquid sex.' },
        { sub: 'r/downblouse', prompt: 'Post a down-blouse shot — leaning forward so your heavy tits spill out, nipples visible, looking right into the camera with a "come get them" look.' },
        { sub: 'r/upskirt', prompt: 'Post a cheeky upskirt — skirt hiked up while sitting or standing, revealing no panties, just a bare, wet pussy and smooth thighs.' },
        { sub: 'r/collared', prompt: 'Post a photo wearing only a leather collar — heavy tits hanging forward, looking submissively at the camera, a leash trailing off-frame. Owned and ready.' },
        { sub: 'r/curvy', prompt: 'Post a photo celebrating your curves — thick thighs, wide hips, and a heavy ass. Use a pose that emphasizes the hourglass silhouette and soft skin.' },
        { sub: 'r/squirting', prompt: 'Post a photo mid-squirt — fountain of arousal soaking the bed, thighs shaking, pussy wide open and pulsing. The ultimate wet mess.' },
        { sub: 'r/workgonewild', prompt: 'Post a risky photo from the office — skirt lifted at your desk or tits flashed in the breakroom. The thrill of being a slut in a professional setting.' },
        { sub: 'r/feet', prompt: 'Post a foot photo — toes curled, soles up, oil-slicked and glistening. Intimate close-up showing every detail of your arches and soft skin.' },
        { sub: 'r/hotwife', prompt: 'Post a hotwife photo — wedding ring visible, dressed in your sluttiest lingerie, looking back at the camera with a "I\'m yours but I\'m everyone\'s" smile.' },
        { sub: 'r/facial', prompt: 'Post a facial photo — cum dripping from your eyelashes and pooled on your tongue. Eyes closed in bliss, accepting the mess on your face.' },
        { sub: 'r/cumontits', prompt: 'Post a photo with cum covering your heavy tits — thick white ropes dripping down to your stomach. Squeeze them together to show off the mess.' }
    ];
},

    generatePost: async function(isAuto) {
        if (!isAuto && window.OS && OS.guardBusy("⏳ Please wait — a task is still running.")) return;
        const btn = document.getElementById('rbGenBtn');
        if (btn) { btn.disabled = true; btn.innerText = "⏳ Dreaming..."; }

        const eligibleChars = State.characters.filter(c => c.enableRebbit !== false);
        const bot = eligibleChars[Math.floor(Math.random() * eligibleChars.length)];
        if (!bot) { console.warn("Rebbit: No eligible characters for auto-post"); if (btn) { btn.disabled = false; btn.innerText = "📸 New Post"; } return; }

        const enabledSubs = State.settings.rebbitSubreddits || [];
        const subListStr = enabledSubs.length > 0 ? enabledSubs.join(", ") : "r/gonewild, r/realgirls, r/selfie";

        try {
            const api = window.API;
            const socialContext = api.getSocialContext(bot.id);

            const prompt = `
You are posting on Rebbit (a Reddit-like NSFW platform).
${socialContext}

[AVAILABLE SUBREDDITS]
${subListStr}

[YOUR TASK]
1. Choose the best subreddit from the list above that fits your persona and recent activity.
2. Write a catchy, amateur-style title.
3. Provide a highly detailed "flux prompt:" for the photo.

Format your response exactly like this:
subreddit: [chosen r/...]
title: [your title]
flux prompt: [visual description]
`.trim();

            const response = await api.sendMessage(bot.id, prompt, null, false, 'social');

            let title = "Check this out";
            let visualPrompt = "";
            let subreddit = enabledSubs[0] || 'r/gonewild';

            const subMatch = response.match(/subreddit:\s*(r\/[a-zA-Z0-9_]+)/i);
            if (subMatch) subreddit = subMatch[1].toLowerCase();

            const titleMatch = response.match(/title:\s*(.*)/i);
            if (titleMatch) title = titleMatch[1].trim().replace(/["']/g, '');

            if (response.toLowerCase().includes("flux prompt:")) {
                visualPrompt = response.split(/flux prompt:/i)[1].trim() + ", amateur style, realistic skin texture, natural lighting, candid NSFW photo, highly detailed body, sharp focus";
            } else {
                visualPrompt = `Amateur NSFW photo of ${bot.name}, ${subreddit} style, realistic skin, candid, natural lighting`;
            }

            let imageB64 = null;
            if (window.ImagingApp && visualPrompt) {
                // Ensure no stale images from other apps (like Studio or Messenger) interfere
                window.ImagingApp.attachedImage = null;

                let lastP = -1;
                imageB64 = await window.ImagingApp.generate(visualPrompt, null, (p) => {
                    // Prevent UI flicker/reset back to 0% at the very end
                    if (p > lastP) {
                        if (btn) btn.innerText = `⏳ ${p}%`;
                        lastP = p;
                    }
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
                    timestamp: Date.now(),
                    comments: []
                });
                if (State.redditPosts.length > 50) {
                    const oldPost = State.redditPosts.shift();
                    if (oldPost && oldPost.image && oldPost.image.startsWith('db:') && window.ImageDB) {
                        await window.ImageDB.delete(oldPost.image.replace('db:', ''));
                    }
                }
                State.save();
                this.loadPosts();
                // Update home screen badge if user is not in this app
                if (OS.activeApp !== 'RebbitApp' && OS.updateBadges) OS.updateBadges();
            }
        } catch(e) {
            console.error("Rebbit gen error:", e);
        } finally {
            if (btn) { btn.disabled = false; btn.innerText = "📸 New Post"; }
        }
    },

    submitComment: async function(postId) {
        const input = document.getElementById(`comment-input-${postId}`);
        if (!input || !input.value.trim()) return;
        const text = input.value.trim();
        input.value = '';

        const post = (State.redditPosts || []).find(p => p.id === postId);
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
            const msg = await api.sendMessage(poster.id, `You are ${poster.name} on ${post.subreddit}. You posted: "${post.title}". ${userName} commented: "${text}". Reply in character (max 15 words). Output ONLY the reply text.`, null, false, 'social');
            if (msg && msg.length > 1) {
                const freshPost = (State.redditPosts || []).find(p => p.id === postId);
                if (freshPost) {
                    freshPost.comments.push({ charId: poster.id, charName: poster.name, text: msg.trim(), timestamp: Date.now() });
                    State.save();
                    this.loadPosts();
                }
            }
        } catch(e) {}
    },

    deletePost: function(postId) {
        OS.confirm("Delete this Rebbit post?", async () => {
            const idx = State.redditPosts.findIndex(p => p.id === postId);
            if (idx === -1) return;
            const post = State.redditPosts[idx];
            if (post.image && post.image.startsWith('db:') && window.ImageDB) {
                await window.ImageDB.delete(post.image.replace('db:', ''));
            }
            State.redditPosts.splice(idx, 1);
            State.save();
            this.loadPosts();
        }, { title: 'Delete Post', confirmText: 'Delete', danger: true });
    },

    clearAll: function() {
        OS.confirm("Clear all Rebbit posts?", async () => {
            if (window.ImageDB) {
                for (const p of (State.redditPosts || [])) {
                    if (p.image && p.image.startsWith('db:')) await window.ImageDB.delete(p.image.replace('db:', ''));
                }
            }
            State.redditPosts = [];
            State.save();
            // Trigger orphan cleanup
            if (window.ImageDB && window.ImageDB.purgeOrphanedFiles) window.ImageDB.purgeOrphanedFiles();
            RebbitApp.loadPosts();
        }, { title: 'Clear Feed', confirmText: 'Clear All', danger: true });
    },

    loadPosts: async function() {
        const el = document.getElementById('rbPosts');
        if (!el) return;
        const posts = State.redditPosts || [];
        if (posts.length === 0) {
            el.innerHTML = '<div style="padding:80px 30px; text-align:center; color:var(--text-muted); font-style:italic; font-size:0.9rem;">No posts yet.<br>Tap 📸 New Post to start!</div>';
            return;
        }
        el.innerHTML = "";
        const reversedPosts = [...posts].reverse().slice(0, 15); // Further reduced to 15 to ensure stability during render

        for (const p of reversedPosts) {
            const postDiv = document.createElement('div');
            postDiv.className = 'rb-post';
            const imgId = `rb-img-${p.id}`;
            const avId = `rb-av-${p.id}`;

            postDiv.innerHTML = `
                <div class="rb-post-header">
                    <div class="rb-post-avatar" id="${avId}">${(p.charName||'R')[0]}</div>
                    <div style="flex:1;">
                        <div class="rb-post-name">${p.charName || 'Anon'}</div>
                        <div class="rb-post-sub"><span class="rb-post-nsfw">NSFW</span> ${p.subreddit || 'r/all'} • ${OS.formatTime(p.timestamp)}</div>
                    </div>
                    <button onclick="RebbitApp.deletePost('${p.id}')" style="background:none; border:none; color:var(--text-muted); font-size:0.9rem; cursor:pointer;">🗑️</button>
                </div>
                <div class="rb-post-title">${p.title || ''}</div>
                <div id="${imgId}" style="min-height:200px; background:#111; border-radius:8px; display:flex; align-items:center; justify-content:center;">
                    <span style="color:#333; font-size:0.7rem;">Loading pixels...</span>
                </div>
                <div id="comments-${p.id}" style="padding-top:10px;"></div>
                <div style="display:flex; gap:6px; align-items:center; margin-top:8px;">
                    <input type="text" id="comment-input-${p.id}" placeholder="Add a comment..." style="flex:1; background:rgba(255,255,255,0.06); border:1px solid rgba(255,255,255,0.1); color:white; padding:6px 10px; border-radius:20px; font-size:0.82rem; outline:none;" onkeydown="if(event.key==='Enter') RebbitApp.submitComment('${p.id}')">
                    <button onclick="RebbitApp.submitComment('${p.id}')" style="background:var(--md-primary-container); border:none; color:var(--md-on-primary-container); padding:6px 12px; border-radius:20px; font-size:0.8rem; font-weight:700; cursor:pointer; flex-shrink:0;">Post</button>
                </div>
            `;
            el.appendChild(postDiv);

            // Render Comments
            const commentsContainer = document.getElementById(`comments-${p.id}`);
            if (p.comments && p.comments.length > 0) {
                p.comments.forEach(c => {
                    const cDiv = document.createElement('div');
                    cDiv.style.fontSize = '0.82rem';
                    cDiv.style.marginBottom = '6px';
                    cDiv.style.paddingLeft = '8px';
                    if (c.isUser) {
                        cDiv.style.borderLeft = '2px solid #1da1f2';
                        cDiv.innerHTML = `<b style="color:#1da1f2; font-size:0.75rem;">u/${c.charName.toLowerCase().replace(/\s/g,'')}</b><br><span style="color:#d1d5db;">${OS.formatMarkdown(c.text)}</span>`;
                    } else {
                        cDiv.style.borderLeft = '2px solid var(--md-primary)';
                        cDiv.innerHTML = `<b style="color:var(--md-primary); font-size:0.75rem;">u/${c.charName.toLowerCase().replace(/\s/g,'')}</b><br><span style="color:#d1d5db;">${OS.formatMarkdown(c.text)}</span>`;
                    }
                    commentsContainer.appendChild(cDiv);
                });
            }

            // Resolve sequentially to prevent memory spikes
            await this.resolveToElement(p.image, imgId);
            const char = State.characters.find(c => c.id === p.charId);
            if (char && char.avatar) await this.resolveAvatar(char.avatar, avId);
        }
    },

    resolveToElement: async function(src, containerId) {
        let finalSrc = src;
        if (src && src.startsWith('db:') && window.ImageDB) {
            finalSrc = await window.ImageDB.get(src);
        }
        const el = document.getElementById(containerId);
        if (el && finalSrc) {
            // THE FIX: Inline onclick attribute
            el.innerHTML = `<img class="rb-post-img" src="${finalSrc}" onclick="OS.openLightbox(this.src)">`;
        }
    },

    resolveAvatar: async function(src, containerId) {
        if (!src) return;
        let finalSrc = src;
        if (src.startsWith('db:') && window.ImageDB) {
            finalSrc = await window.ImageDB.get(src);
        }
        const el = document.getElementById(containerId);
        if (el && finalSrc) {
            el.innerHTML = `<img src="${finalSrc}">`;
        }
    },

    startAutoPost: function() {
        this.stopAutoPost();
        const s = State.settings || {};
        if (!s.autoPostEnabled || !s.autoPostRebbit || s.hideNsfw) return;
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
        const indicator = document.getElementById('rbAutoPostIndicator');
        if (!indicator) return;
        const s = State.settings || {};
        const hasKey = window.API.isReady();
        if (s.autoPostEnabled && s.autoPostRebbit && hasKey && !s.hideNsfw) {
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
window.RebbitApp = RebbitApp;
