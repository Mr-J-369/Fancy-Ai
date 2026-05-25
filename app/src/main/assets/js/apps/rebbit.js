/**
 * rebbit.js
 * Rebbit-style feed — bots post amateur-style NSFW photos only.
 * Uses ImageDB for persistent high-capacity storage.
 */
const RebbitApp = {
    container: null,

    init: async function(container) {
        this.container = container;
        this.render();
        await this.loadPosts();
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
                .rb-post-nsfw { display: inline-block; background: rgba(255,69,0,0.15); color: #ff4500; font-size: 0.65rem; font-weight: 700; padding: 2px 6px; border-radius: 4px; margin-right: 6px; text-transform: uppercase; }
                @keyframes postFadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }

                /* Settings overlay */
                .rb-settings-overlay {
                    position: fixed; top: 0; left: 0; right: 0; bottom: 0;
                    z-index: 99998; display: flex; align-items: flex-start;
                    justify-content: center; padding-top: 60px;
                    background: rgba(0,0,0,0.6); backdrop-filter: blur(6px);
                }
                .rb-settings-sheet {
                    background: #1a1a1e; border-radius: 20px; padding: 16px;
                    min-width: 260px; max-width: 320px; width: 85%;
                    box-shadow: 0 20px 60px rgba(0,0,0,0.6);
                    border: 1px solid rgba(255,255,255,0.06);
                    max-height: 70vh; overflow-y: auto;
                }
                .rb-settings-title {
                    font-size: 1rem; font-weight: 800; color: #ff4500;
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
                    background: #ff4500; border-color: #ff4500; color: white;
                }
                .rb-settings-name { font-weight: 600; color: white; font-size: 0.88rem; flex: 1; }
                .rb-settings-handle { font-size: 0.7rem; color: var(--text-muted); }
            `;
            document.head.appendChild(style);
        }
        this.container.innerHTML = `
            <div class="rb-wrap">
                <div class="rb-header"><h2>Rebbit</h2></div>
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
            'r/masturbation', 'r/voyeur'
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
            'r/masturbation', 'r/voyeur'
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
        { sub: 'r/gonewild', prompt: 'Post a nude photo spreading yourself open for the camera — pussy glistening, tits out, legs apart, that "look what you\'re missing" expression. Natural light, messy bed, zero filters. Make them ache for you.' },
        { sub: 'r/realgirls', prompt: 'Post a photo that screams "I\'m your neighbor and I\'m naked in my bathroom right now." Soft tummy, real curves, no posing bullshit — just a wet cunt, hard nipples, and a shy but daring look like you might get caught.' },
        { sub: 'r/selfie', prompt: 'Post a selfie that\'s pure sin — lips slightly parted, eyes half-lidded, hand teasing the waistband of your panties. A hint of cleavage, a strand of hair across your face. You\'re not just taking a photo, you\'re seducing whoever looks at it.' },
        { sub: 'r/legs', prompt: 'Post a photo that makes legs the main event — long, smooth thighs squeezed together (or spread apart), sheer stockings digging in at the top, heels on, toes pointed. From the hip down, nothing else matters.' },
        { sub: 'r/ass', prompt: 'Post a photo of that ass spread wide — cheeks pulled apart, pink hole winking at the camera, bent over so deep your back arches. A handprint from spanking still fresh. Make them want to bury their face in it.' },
        { sub: 'r/lingerie', prompt: 'Post a photo in the filthiest lingerie you own — lace digging into your hips, nipples poking through sheer fabric, wet spot forming on the crotch. One strap falling off your shoulder like you\'re about to let it all drop.' },
        { sub: 'r/bikinis', prompt: 'Post a bikini photo that\'s barely holding you in — wedgie pulling the fabric deep between your cheeks, nipples threatening to pop out, a wet patch on the crotch from the pool (or from you). Summer slut energy.' },
        { sub: 'r/tittydrop', prompt: 'Post a titty drop that makes them gasp — shirt lifted slow, then release, those tits bouncing free, nipples hard and ready. The moment of exposure, the weight of them, the way they swing. Tease it, then drop it.' },
        { sub: 'r/frombelow', prompt: 'Post a photo taken from between your legs looking up — tits towering above, chin tilted down, pussy right there in the foreground. The ultimate power angle. They\'re beneath you, and you know it.' },
        { sub: 'r/bedroom', prompt: 'Post a photo from bed that looks like you just fucked — sheets twisted, hair a mess, thighs glistening, one hand between your legs. That post-orgasm glow, lazy smile, still catching your breath.' },
        { sub: 'r/shower', prompt: 'Post a shower photo that\'s dripping wet — water running down your tits, between your ass cheeks, over your clit. Soapy skin, steamed-up mirror, hand cupping your pussy like you\'re about to touch yourself.' },
        { sub: 'r/mirror', prompt: 'Post a full-length mirror selfie that leaves nothing to the imagination — phone covering your face but your body fully bare, arched back pushing your ass out, pussy bare, tits on full display. The kind of photo you\'d send to a lover at 2am.' },
        { sub: 'r/doggy', prompt: 'Post a doggy style photo that makes them want to mount you immediately — ass up, face down, back arched deep, pussy and asshole on full display from behind. Cheeks spread, wet, ready. The perfect breeding position.' },
        { sub: 'r/onallfours', prompt: 'Post a photo on all fours that screams "use me" — back arched like a cat in heat, looking back over your shoulder with a dirty smirk, tits hanging down, pussy dripping and exposed from behind. Submissive but begging for it.' },
        { sub: 'r/hentai', prompt: 'Post an anime-style photo with impossible curves and dripping wet fantasy — huge bouncing tits, a tiny waist, a pussy that\'s literally overflowing. Tentacles, huge cocks, or just exaggerated proportions. Make it look like a wet dream drawn to life.' },
        { sub: 'r/fitgirls', prompt: 'Post a gym photo that\'s pure muscle and sex — glistening with sweat, sports bra peeled down to show hard nipples, leggings pulled aside to reveal a shaved pussy. Veiny arms, toned abs, that post-workout glow with a dirty twist.' },
        { sub: 'r/feet', prompt: 'Post a foot photo that\'s filthy in the best way — toes curled, arches flexed, soles up, maybe a foot hovering right above a hard cock. Oil, sweat, or just bare skin. Make foot fetishists lose their minds.' },
        { sub: 'r/anal', prompt: 'Post a photo that puts that asshole front and center — cheeks spread wide, pink hole puckered and ready, maybe a plug already in or a finger teasing the rim. The kind of photo that says "I want it in the ass."' },
        { sub: 'r/orgasms', prompt: 'Post a photo of you mid-climax — back arched off the bed, mouth open in a silent scream, thighs shaking, cum dripping down your hand. That raw, uncontrollable moment when pleasure takes over. Make them hear it through the image.' },
        { sub: 'r/bondage', prompt: 'Post a bondage photo that\'s artfully cruel — ropes digging into soft skin, tits bound and swollen, a gag in your mouth with drool running down your chin. Helpless, exposed, completely at their mercy. Beautiful and degrading.' },
        { sub: 'r/publicflashing', prompt: 'Post a public flash that\'s risky as fuck — tits out in a park, skirt hiked up in a parking lot, fingers in your pussy in an alley. The thrill of getting caught, heart racing, looking over your shoulder. Exhibitionist\'s dream.' },
        { sub: 'r/gloryhole', prompt: 'Post a gloryhole photo that\'s pure anonymous slut energy — on your knees, mouth wide open, tongue out, waiting. A cock (or two) through the hole, cum dripping down your chin. No face, no name, just a hole to use.' },
        { sub: 'r/interracial', prompt: 'Post an interracial photo that celebrates contrast — pale skin against dark, a thick cock stretching a tight pussy, hands gripping contrasting flesh. The visual of difference, the taboo, the way two bodies look together.' },
        { sub: 'r/hotwife', prompt: 'Post a hotwife photo dripping with confidence and possession — wedding ring still on, dressed slutty as hell, maybe with a lover while hubby watches. That "I\'m someone\'s wife but right now I\'m everyone\'s fantasy" look.' },
        { sub: 'r/gangbang', prompt: 'Post a gangbang photo that\'s pure chaos — one body surrounded by cocks, every hole filled, hands grabbing everywhere, cum covering tits and face. Used, stretched, passed around. The ultimate slut fantasy.' },
        { sub: 'r/creampie', prompt: 'Post a creampie photo that\'s dripping with proof — cum oozing out of a gaping pussy, running down your thighs, pooling on the sheets. That warm, full feeling, the mess of it, the "look what he left inside me" pride.' },
        { sub: 'r/cuckold', prompt: 'Post a cuckold photo that\'s pure humiliation — you on your knees watching her take another cock, or her looking back at you while getting fucked by someone bigger. The mix of arousal and shame, the denial, the "this is your place" energy.' },
        { sub: 'r/threesome', prompt: 'Post a threesome photo that\'s a tangle of limbs and mouths — one in your mouth, one in your pussy, hands everywhere, no one knows who\'s who. Sweaty, messy, every hole accounted for. The kind of night that ruins you for anything less.' },
        { sub: 'r/deepthroat', prompt: 'Post a deepthroat photo that shows your throat bulging — tears streaming, mascara running, nose buried in pubes, gagging but taking it all. That perfect moment of surrender, spit dripping, throat stretched around every inch.' },
        { sub: 'r/facial', prompt: 'Post a facial photo that\'s a mess — cum dripping from your eyelashes, running down your cheeks, pooling on your tongue. Eyes closed, accepting it, proud of the mess. The perfect canvas.' },
        { sub: 'r/spitroast', prompt: 'Post a spitroast photo of being used from both ends — a cock in your mouth, a cock in your pussy, nothing to hold onto but them. Stretched, filled, completely taken. The ultimate double penetration fantasy.' },
        { sub: 'r/doublepenetration', prompt: 'Post a DP photo with both holes stuffed — pussy and ass full at the same time, that stretched-to-the-limit look on your face, hands gripping the sheets. Every inch of you filled, nothing left empty.' },
        { sub: 'r/roughsex', prompt: 'Post a rough sex photo that shows you\'ve been handled — hair yanked back, throat grabbed, red handprints on your ass, bite marks on your tits. That "I got exactly what I deserved" look. Raw, dominant, claimed.' },
        { sub: 'r/strapons', prompt: 'Post a strapon photo that screams power — harness strapped tight, a thick cock jutting out, you in control. Or bent over taking it, that stretch, that surrender. The switch energy, the role reversal, the kink.' },
        { sub: 'r/femdom', prompt: 'Post a femdom photo that makes them weak — you in heels, holding a whip, looking down with pure contempt. A sub at your feet, your cock in their mouth, your foot on their chest. Absolute power, absolute control.' },
        { sub: 'r/grool', prompt: 'Post a grool photo that\'s obscenely wet — a string of arousal connecting your fingers to your pussy, a wet patch spreading on the bed, your thighs slick and shiny. No lube, just you. Proof of how badly you want it.' },
        { sub: 'r/pantyhose', prompt: 'Post a pantyhose photo that\'s classic tease — legs crossed, the sheen of nylon catching the light, a run starting at the thigh, the waistband visible. Maybe a hand pressing between your legs, the fabric dampening. Elegant filth.' },
        { sub: 'r/stockings', prompt: 'Post a stockings and garter photo that\'s pure retro sin — thigh highs with the metal clips biting into your skin, suspenders framing your bare pussy, garter belt digging into your waist. Bend over and the whole world sees everything.' },
        { sub: 'r/masturbation', prompt: 'Post a masturbation photo that\'s caught in the act — fingers deep inside, a toy buzzing against your clit, your other hand squeezing your own tit. Legs spread wide, head thrown back, that "I don\'t care who sees" desperation.' },
        { sub: 'r/voyeur', prompt: 'Post a voyeur-style photo like you\'re being watched without knowing — through a crack in the door, across the street, from the bushes. You\'re touching yourself, or just naked and unaware. The observer\'s thrill, the subject\'s vulnerability.' }
    ];
},

    generatePost: async function() {
        const btn = document.getElementById('rbGenBtn');
        if (btn) { btn.disabled = true; btn.innerText = "⏳ Dreaming..."; }

        const eligibleChars = State.characters.filter(c => c.enableRebbit !== false);
        const bot = eligibleChars[Math.floor(Math.random() * eligibleChars.length)];

        const categories = this.getEnabledCategories();
        const category = categories[Math.floor(Math.random() * categories.length)];

        try {
            const api = window.API;
            if (!api || !State.settings.key) {
                if (btn) { btn.disabled = false; btn.innerText = "📸 New Post"; }
                return;
            }

            const response = await api.sendMessage(bot.id, `You are posting on Rebbit (a Reddit-like NSFW platform) right now. ${category.prompt} Write a catchy, engaging title (just the title text, no "title:" prefix). Then on a new line write "subreddit: ${category.sub}" based on the theme. Then on a new line write "flux prompt:" followed by a highly detailed visual description of the photo — describe the pose, lighting, setting, what body parts are visible, the expression, the mood. Make it feel like a real amateur Reddit post, not a professional shoot. Vary the pose, angle, setting, and activity every time. Be explicit and detailed in the visual description.`, null, false, 'social');

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
        const reversedPosts = [...posts].reverse();

        for (const p of reversedPosts) {
            const postDiv = document.createElement('div');
            postDiv.className = 'rb-post';
            const imgId = `rb-img-${p.id}`;
            const avId = `rb-av-${p.id}`;

            postDiv.innerHTML = `
                <div class="rb-post-header">
                    <div class="rb-post-avatar" id="${avId}">${(p.charName||'R')[0]}</div>
                    <div>
                        <div class="rb-post-name">${p.charName || 'Anon'}</div>
                        <div class="rb-post-sub"><span class="rb-post-nsfw">NSFW</span> ${p.subreddit || 'r/all'}</div>
                    </div>
                </div>
                <div class="rb-post-title">${p.title || ''}</div>
                <img id="${imgId}" class="rb-post-img" src="" alt="">
            `;
            el.appendChild(postDiv);

            // Resolve image from ImageDB
            this.resolveToElement(p.image, imgId);
            // Resolve avatar
            const char = State.characters.find(c => c.id === p.charId);
            if (char && char.avatar) this.resolveAvatar(char.avatar, avId);
        }
    },

    resolveToElement: async function(src, containerId) {
        let finalSrc = src;
        if (src && src.startsWith('db:') && window.ImageDB) {
            finalSrc = await window.ImageDB.get(src);
        }
        const el = document.getElementById(containerId);
        if (el && finalSrc) {
            el.src = finalSrc;
            el.onclick = function() {
                if (window.ImagingApp) ImagingApp.openLocalLightbox(this.src);
            };
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
    }
};
window.RebbitApp = RebbitApp;



