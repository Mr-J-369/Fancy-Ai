const State = {
    characters: [],
    userProfile: { name: 'User', bio: '' },
    settings: {
        systemPrompts: [{ id: 'p1', name: 'Default', content: 'You are a unique individual with your own personality, opinions, and way of speaking. Respond naturally as yourself.' }],
        activePromptId: 'p1'
    },
    sessions: {},
    activeCharId: null,
    instagramPosts: [],
    redditPosts: [],
    xPosts: [],
    maxSessionMessages: 100,

    init: async function() {
        try {
            let saved = null;
            if (window.AndroidBridge && typeof window.AndroidBridge.readFile === 'function') {
                saved = window.AndroidBridge.readFile('state.json');
            }
            
            if (!saved) {
                saved = localStorage.getItem('fancy_ai_state');
            }

            if (saved) {
                const parsed = JSON.parse(saved);
                Object.assign(this, parsed);
            }
            // MIGRATION: Move any raw Base64 blobs out of LocalStorage and into Native Disk
            await this.migrateToNativeStorage();
        } catch (e) { console.error("Load failed:", e); }
        
        if (this.characters.length === 0) {
            this.characters.push({ id: 'c1', name: 'Companion', persona: 'You are a warm, thoughtful companion who speaks naturally and has your own personality. You are not an AI assistant.', follower_count: 0, virtual_gallery: [] });
            this.activeCharId = 'c1';
        }
    },

    migrateToNativeStorage: async function() {
        if (!window.ImageDB) return;
        let changed = false;

        // Clean Characters (Avatars)
        for (let char of this.characters) {
            if (char.avatar && char.avatar.startsWith('data:image')) {
                const dbId = `avatar_${char.id}`;
                await window.ImageDB.save(dbId, char.avatar);
                char.avatar = `db:${dbId}`;
                changed = true;
            }
        }
        // Clean Sessions
        for (let id in this.sessions) {
            for (let msg of this.sessions[id]) {
                if (msg.type === 'image' && msg.text.startsWith('data:image')) {
                    const dbId = `img_${msg.id}`;
                    await window.ImageDB.save(dbId, msg.text);
                    msg.text = `db:${dbId}`;
                    changed = true;
                }
            }
        }
        // Clean Posts
        const postKeys = ['instagramPosts', 'redditPosts', 'xPosts'];
        for (let key of postKeys) {
            if (this[key]) {
                for (let post of this[key]) {
                    if (post.image && post.image.startsWith('data:image')) {
                        const dbId = `post_${post.id}`;
                        await window.ImageDB.save(dbId, post.image);
                        post.image = `db:${dbId}`;
                        changed = true;
                    }
                }
            }
        }
        if (changed) this.save();
    },

    save: function() {
        try {
            // Enforce session message limit before saving
            for (let charId in this.sessions) {
                if (this.sessions[charId].length > this.maxSessionMessages) {
                    this.sessions[charId] = this.sessions[charId].slice(-this.maxSessionMessages);
                }
            }

            const data = {
                characters: this.characters,
                userProfile: this.userProfile,
                settings: this.settings,
                sessions: this.sessions,
                activeCharId: this.activeCharId,
                instagramPosts: this.instagramPosts,
                redditPosts: this.redditPosts,
                xPosts: this.xPosts
            };
            const serialized = JSON.stringify(data);

            if (window.AndroidBridge && typeof window.AndroidBridge.saveToFile === 'function') {
                window.AndroidBridge.saveToFile('state.json', serialized);
                // Save full state to localStorage too as backup (not empty sessions!)
                localStorage.setItem('fancy_ai_state', serialized);
            } else {
                localStorage.setItem('fancy_ai_state', serialized);
            }
        } catch(e) {
            if (e.name === 'QuotaExceededError') {
                console.error("CRITICAL: LocalStorage Full. Emergency Pruning.");
                // Trim all sessions aggressively
                for (let charId in this.sessions) {
                    this.sessions[charId] = this.sessions[charId].slice(-20);
                }
                this.save();
            }
        }
    }
};
State.init();
