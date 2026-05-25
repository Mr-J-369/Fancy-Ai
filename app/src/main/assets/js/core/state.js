const State = {
    characters: [],
    userProfile: { name: 'User', bio: '' },
    settings: {
        systemPrompts: [{ id: 'p1', name: 'Default', content: 'You are a unique individual with your own personality, opinions, and way of speaking. Respond naturally as yourself.' }],
        activePromptId: 'p1',
        autoPostEnabled: false,
        autoPostInterval: 5,
        autoPostUstagram: true,
        autoPostRebbit: true,
        autoPostY: true,
        autonomousEnabled: false
    },
    sessions: {},
    memories: {},  // { charId: [ { id, text, timestamp, category } ] }
    monologues: {}, // { charId: [ { text, timestamp } ] }
    activeCharId: null,
    instagramPosts: [],
    redditPosts: [],
    xPosts: [],
    maxSessionMessages: 100,
    maxMemoriesPerChar: 50,  // Max memories stored per character
    lastReadTimestamps: {},  // { AppName: ts }
    chatReadTimestamps: {},  // { charId: ts }
    maxStateSize: 500000,    // serialized chars (~0.5MB) before archival

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
            // Ensure memories object exists (migration for existing state)
            if (!this.memories) this.memories = {};
            if (!this.lastReadTimestamps) this.lastReadTimestamps = {};
            if (!this.sessions) this.sessions = {};

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
                if (msg.type === 'image' && msg.text && msg.text.startsWith('data:image')) {
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
            const data = {
                characters: this.characters,
                userProfile: this.userProfile,
                settings: this.settings,
                sessions: this.sessions,
                memories: this.memories || {},
                activeCharId: this.activeCharId,
                instagramPosts: this.instagramPosts,
                redditPosts: this.redditPosts,
                xPosts: this.xPosts,
                lastReadTimestamps: this.lastReadTimestamps || {},
                chatReadTimestamps: this.chatReadTimestamps || {}
            };
            let serialized = JSON.stringify(data);

            // Archival Logic: If state is too large, archive old messages
            if (serialized.length > this.maxStateSize) {
                console.log("State size exceeded limit, archiving old messages...");
                this.performAutoArchival();
                // Re-serialize after archival
                data.sessions = this.sessions;
                serialized = JSON.stringify(data);
            }

            if (window.AndroidBridge && typeof window.AndroidBridge.saveToFile === 'function') {
                window.AndroidBridge.saveToFile('state.json', serialized);
                // Save full state to localStorage too as backup (if not too huge)
                if (serialized.length < 2000000) { // LocalStorage 2MB limit safely
                    localStorage.setItem('fancy_ai_state', serialized);
                }
            } else {
                localStorage.setItem('fancy_ai_state', serialized);
            }
        } catch(e) {
            if (e.name === 'QuotaExceededError' || e.message.includes('quota')) {
                console.error("CRITICAL: Storage Full. Emergency Pruning.");
                this.performAutoArchival(true); // Aggressive archival
                this.save();
            }
        }
    },

    /**
     * Moves old messages from sessions into separate archive files.
     * @param {boolean} aggressive If true, keeps only 10 messages instead of 50.
     */
    performAutoArchival: function(aggressive = false) {
        const keepCount = aggressive ? 10 : 50;
        for (let charId in this.sessions) {
            if (this.sessions[charId].length > keepCount) {
                const toArchive = this.sessions[charId].slice(0, -keepCount);
                const toKeep = this.sessions[charId].slice(-keepCount);

                this.archiveMessages(charId, toArchive);
                this.sessions[charId] = toKeep;
            }
        }
    },

    /**
     * Appends messages to a character's native archive file.
     */
    archiveMessages: function(charId, messages) {
        if (!window.AndroidBridge || typeof window.AndroidBridge.readFile !== 'function') return;

        const fileName = `archive_${charId}.json`;
        try {
            let archive = [];
            const existing = window.AndroidBridge.readFile(fileName);
            if (existing) {
                archive = JSON.parse(existing);
            }
            archive = archive.concat(messages);
            window.AndroidBridge.saveToFile(fileName, JSON.stringify(archive));
            console.log(`Archived ${messages.length} messages for ${charId}`);
        } catch(e) {
            console.error("Archival failed:", e);
        }
    },

    /**
     * Loads the archive for a character.
     */
    getArchive: function(charId) {
        if (!window.AndroidBridge || typeof window.AndroidBridge.readFile !== 'function') return [];
        const fileName = `archive_${charId}.json`;
        try {
            const data = window.AndroidBridge.readFile(fileName);
            return data ? JSON.parse(data) : [];
        } catch(e) {
            return [];
        }
    },

    hasArchive: function(charId) {
        if (!window.AndroidBridge || typeof window.AndroidBridge.readFile !== 'function') return false;
        const fileName = `archive_${charId}.json`;
        try {
            // listFiles doesn't exist for the root getFilesDir() easily via Bridge right now
            // But we can try reading it.
            const data = window.AndroidBridge.readFile(fileName);
            return !!data;
        } catch(e) {
            return false;
        }
    },

    // --- Memory System ---
    // Add a memory for a character
    addMemory: function(charId, text, category) {
        if (!this.memories) this.memories = {};
        if (!this.memories[charId]) this.memories[charId] = [];

        // Deduplicate: don't add if very similar memory already exists
        const existing = this.memories[charId];
        const normalized = text.toLowerCase().trim();
        for (const m of existing) {
            if (m.text.toLowerCase().trim() === normalized) return false;
            // Also skip if one is a substring of the other (avoid redundancy)
            if (normalized.includes(m.text.toLowerCase().trim()) || m.text.toLowerCase().trim().includes(normalized)) return false;
        }

        const memory = {
            id: 'mem_' + Date.now() + '_' + Math.random().toString(36).substr(2, 5),
            text: text.trim(),
            timestamp: Date.now(),
            category: category || 'general'  // 'preference', 'fact', 'event', 'relationship', 'general'
        };
        this.memories[charId].push(memory);

        // Enforce limit
        if (this.memories[charId].length > this.maxMemoriesPerChar) {
            this.memories[charId] = this.memories[charId].slice(-this.maxMemoriesPerChar);
        }

        this.save();
        return true;
    },

    // Get all memories for a character
    getMemories: function(charId) {
        if (!this.memories) this.memories = {};
        return this.memories[charId] || [];
    },

    // Delete a specific memory
    deleteMemory: function(charId, memoryId) {
        if (!this.memories || !this.memories[charId]) return;
        this.memories[charId] = this.memories[charId].filter(m => m.id !== memoryId);
        this.save();
    },

    // Clear all memories for a character
    clearMemories: function(charId) {
        if (!this.memories) this.memories = {};
        this.memories[charId] = [];
        this.save();
    },

    // Get memories formatted for injection into system prompt
    getMemoriesPrompt: function(charId) {
        const memories = this.getMemories(charId);
        if (memories.length === 0) return '';

        const categoryEmoji = {
            preference: '💜',
            fact: '📌',
            event: '📅',
            relationship: '❤️',
            general: '🧠'
        };

        const lines = memories.map(m => {
            const emoji = categoryEmoji[m.category] || '🧠';
            const age = this.formatMemoryAge(m.timestamp);
            return `${emoji} ${m.text} (${age})`;
        });

        return `[MEMORIES ABOUT ${this.userProfile?.name || 'User'}]\nYou remember these things about the person you're talking to. Reference them naturally when relevant, but don't force it:\n${lines.join('\n')}`;
    },

    formatMemoryAge: function(timestamp) {
        if (!timestamp) return 'unknown';
        const diff = Date.now() - timestamp;
        const mins = Math.floor(diff / 60000);
        if (mins < 1) return 'just now';
        if (mins < 60) return mins + 'm ago';
        const hrs = Math.floor(mins / 60);
        if (hrs < 24) return hrs + 'h ago';
        const days = Math.floor(hrs / 24);
        if (days < 30) return days + 'd ago';
        return Math.floor(days / 30) + 'mo ago';
    }
};
// Expose a promise so the OS boot sequence can await state initialization
window._stateReady = State.init();




