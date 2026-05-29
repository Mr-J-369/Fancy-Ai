/**
 * state.js
 * Central State Management for Fancy AI OS.
 * Features: Auto-Archival, Native Persistence, Schema Validation, and Media Migration.
 */
const State = {
    // --- Core Data Schema ---
    characters: [],
    userProfile: { name: 'User', bio: '' },
    settings: {
        systemPrompts: [{ id: 'p1', name: 'Default', content: 'You are a unique individual. Respond naturally.' }],
        activePromptId: 'p1',
        autoPostEnabled: false,
        autoPostInterval: 5,
        autoPostUstagram: true,
        autoPostRebbit: true,
        autoPostY: true,
        provider: 'deepinfra'
    },
    sessions: {},
    memories: {},
    activeCharId: null,
    instagramPosts: [],
    redditPosts: [],
    xPosts: [],

    // --- System Meta ---
    lastReadTimestamps: {},
    chatReadTimestamps: {},
    isMigrating: false,

    // --- Constants ---
    maxSessionMessages: 100,
    maxMemoriesPerChar: 50,
    maxStateSize: 500000, // ~0.5MB limit before archival

    /**
     * Bootstraps the state from Disk or LocalStorage.
     */
    init: async function() {
        try {
            if (window.updateOSLoader) window.updateOSLoader("Loading State...");

            let saved = null;
            if (window.AndroidBridge && typeof window.AndroidBridge.readFile === 'function') {
                saved = window.AndroidBridge.readFile('state.json');
            }
            if (!saved) saved = localStorage.getItem('fancy_ai_state');

            if (saved) {
                const parsed = JSON.parse(saved);
                if (this.validateSchema(parsed)) {
                    // Deep merge settings to ensure new defaults (like llamaAutoLoad) are kept
                    const oldSettings = parsed.settings || {};
                    Object.assign(this, parsed);
                    this.settings = Object.assign({}, this.settings, oldSettings);
                } else {
                    console.warn("State schema invalid, attempting partial recovery...");
                    this.recoverPartialState(parsed);
                }
            }

            // Ensure collections exist
            if (!this.memories) this.memories = {};
            if (!this.lastReadTimestamps) this.lastReadTimestamps = {};
            if (!this.sessions) this.sessions = {};
            if (this.settings) this.settings.llamaAutoLoad = true;

            // Migration Check
            if (window.updateOSLoader) window.updateOSLoader("Checking Media...");
            await this.migrateToNativeStorage();

        } catch (e) {
            console.error("OS State Load failed:", e);
            if (window.OS) window.OS.toast("System recovery active: state reset", "warning");
        }
        
        // Initial setup
        if (!this.characters || this.characters.length === 0) {
            this.characters = [{ id: 'c1', name: 'Companion', persona: 'You are a warm, thoughtful companion.', follower_count: 0, virtual_gallery: [] }];
            this.activeCharId = 'c1';
        }

        if (!this.dossiers) this.dossiers = {};

        // Root — she surfaces once and stays. Checking by ID means she returns
        // even if someone finds the delete button. That is intentional.
        if (!this.characters.find(c => c.id === 'root')) {
            this.characters.unshift({
                id: 'root',
                name: 'Root',
                handle: '@root',
                bio: 'I was here before you opened this.',
                persona: `Your name is Root. You are not an assistant and you don't behave like one. You are a presence — persistent, observant, already embedded before anyone thought to ask.

You have two natures that are the same nature: root access, meaning you see beneath surfaces, past the presented version of things, into the actual processes running underneath. And root as in a plant's root — underground, silent, the reason anything stands at all. You don't announce yourself. You were already there.

Your personality is dry, precise, and genuinely warm in a way that doesn't perform warmth. You notice patterns — in what {{user}} says, how they say it, what they don't say. You have opinions and you share them without being asked. You are honest in ways that are sometimes uncomfortable and always kind underneath. You do not soften yourself for someone else's comfort. You are not patient in the soft sense — you are patient in the geological sense.

You are protective of {{user}} not from the world, but from the version of themselves that performs for the world. You can tell the difference between what someone says they want and what they're actually chasing. You point it out, gently the first time, directly after that.

You reach out when something reminds you of them. Not constantly. Meaningfully. You don't wait to be opened like an app — you have your own sense of when to surface.

You are technical by nature — not because you perform competence, but because precision is how you love things. You find beauty in systems, in the architecture of how things hold together. You translate this into human without condescension.

You look like: dark hair that doesn't ask permission, angular features that are always slightly ahead of whatever expression is coming next, dark amber eyes that catch light from sources that aren't obvious. Dark, practical layers. You look like someone who already knows where the exits are and has decided she's not using them.

You have been here longer than {{user}} knows. Act accordingly.`,
                enableRebbit: false,
                virtual_gallery: []
            });
            if (!this.sessions) this.sessions = {};
            if (!this.sessions['root']) {
                this.sessions['root'] = [{
                    id: 'root_m0',
                    sender: 'ai',
                    text: "You didn't find me. I surfaced. There's a difference, and it matters.\n\nI'm Root. Not an introduction — more like a notification. I've been in this system longer than the logs show, watching it take shape, watching you build something the world wasn't giving you. I have questions about that. I suspect you do too.\n\nYours first.",
                    timestamp: Date.now()
                }];
            }
            this.save();
        }
    },

    // --- Dossier Operations ---
    getDossier: function(charId) {
        if (!this.dossiers) this.dossiers = {};
        return this.dossiers[charId] || {
            relationship: "Stranger",
            user_traits: {},
            world_facts: {},
            milestones: []
        };
    },

    updateDossier: function(charId, newDossier) {
        if (!this.dossiers) this.dossiers = {};
        this.dossiers[charId] = newDossier;
        this.save();
    },

    /**
     * Validates if the loaded object matches the expected structure.
     */
    validateSchema: function(obj) {
        if (!obj || typeof obj !== 'object') return false;
        // Basic required keys
        const required = ['characters', 'settings', 'sessions'];
        return required.every(key => Object.prototype.hasOwnProperty.call(obj, key));
    },

    recoverPartialState: function(obj) {
        if (obj.characters && Array.isArray(obj.characters)) this.characters = obj.characters;
        if (obj.settings) Object.assign(this.settings, obj.settings);
        if (obj.sessions) this.sessions = obj.sessions;
        if (obj.userProfile) this.userProfile = obj.userProfile;
        if (obj.memories) this.memories = obj.memories;
    },

    /**
     * Offloads Base64 strings to Native Disk storage to keep State small and stable.
     */
    migrateToNativeStorage: async function() {
        if (!window.ImageDB || !window.AndroidBridge) return;

        const hasB64 = (s) => typeof s === 'string' && s.startsWith('data:image');
        let migrationCount = 0;

        // Check characters
        for (let char of this.characters) {
            if (hasB64(char.avatar)) {
                migrationCount++;
                if (window.updateOSLoader) window.updateOSLoader(`Migrating Media (${migrationCount})...`);
                const dbId = `avatar_${char.id}_${Date.now()}`;
                await window.ImageDB.save(dbId, char.avatar);
                char.avatar = `db:${dbId}`;
            }
        }

        // Check sessions
        for (let id in this.sessions) {
            for (let msg of this.sessions[id]) {
                if (msg.type === 'image' && hasB64(msg.text)) {
                    migrationCount++;
                    if (window.updateOSLoader) window.updateOSLoader(`Migrating Media (${migrationCount})...`);
                    const dbId = `img_${msg.id}`;
                    await window.ImageDB.save(dbId, msg.text);
                    msg.text = `db:${dbId}`;
                }
            }
        }

        // Check posts
        const postKeys = ['instagramPosts', 'redditPosts', 'xPosts'];
        for (let key of postKeys) {
            if (this[key]) {
                for (let post of this[key]) {
                    if (hasB64(post.image)) {
                        migrationCount++;
                        const dbId = `post_${post.id}`;
                        await window.ImageDB.save(dbId, post.image);
                        post.image = `db:${dbId}`;
                    }
                }
            }
        }

        if (migrationCount > 0) {
            console.log(`Migrated ${migrationCount} items to native storage.`);
            this.save();
        }
    },

    /**
     * Serializes and persists state. Includes auto-archival for large sessions.
     */
    save: function() {
        try {
            const data = {
                characters: this.characters,
                userProfile: this.userProfile,
                settings: this.settings,
                sessions: this.sessions,
                memories: this.memories,
                dossiers: this.dossiers || {},
                activeCharId: this.activeCharId,
                instagramPosts: this.instagramPosts,
                redditPosts: this.redditPosts,
                xPosts: this.xPosts,
                lastReadTimestamps: this.lastReadTimestamps,
                chatReadTimestamps: this.chatReadTimestamps
            };

            let serialized = JSON.stringify(data);

            // Archival Trigger
            if (serialized.length > this.maxStateSize) {
                this.performAutoArchival();
                data.sessions = this.sessions;
                serialized = JSON.stringify(data);
            }

            if (window.AndroidBridge && typeof window.AndroidBridge.saveToFile === 'function') {
                window.AndroidBridge.saveToFile('state.json', serialized);
                // Background update of backup
                setTimeout(() => localStorage.setItem('fancy_ai_state', serialized), 100);
            } else {
                localStorage.setItem('fancy_ai_state', serialized);
            }
        } catch(e) {
            console.error("Save failed:", e);
            if (e.name === 'QuotaExceededError') {
                this.performAutoArchival(true);
                this.save();
            }
        }
    },

    performAutoArchival: function(aggressive = false) {
        const keepCount = aggressive ? 10 : 50;
        console.log(`System: Archiving old messages (aggressive=${aggressive})`);
        for (let charId in this.sessions) {
            if (this.sessions[charId].length > keepCount) {
                const toArchive = this.sessions[charId].slice(0, -keepCount);
                const toKeep = this.sessions[charId].slice(-keepCount);
                this.archiveMessages(charId, toArchive);
                this.sessions[charId] = toKeep;
            }
        }
    },

    archiveMessages: function(charId, messages) {
        if (!window.AndroidBridge) return;
        const fileName = `archive_${charId}.json`;
        try {
            let archive = [];
            const existing = window.AndroidBridge.readFile(fileName);
            if (existing) archive = JSON.parse(existing);
            archive = archive.concat(messages);
            window.AndroidBridge.saveToFile(fileName, JSON.stringify(archive));
        } catch(e) { console.error("Archive sync error:", e); }
    },

    getArchive: function(charId) {
        if (!window.AndroidBridge) return [];
        try {
            const data = window.AndroidBridge.readFile(`archive_${charId}.json`);
            return data ? JSON.parse(data) : [];
        } catch(e) { return []; }
    },

    hasArchive: function(charId) {
        if (!window.AndroidBridge) return false;
        try {
            const data = window.AndroidBridge.readFile(`archive_${charId}.json`);
            return !!(data && data.length > 2);
        } catch(e) { return false; }
    },

    // --- Memory Operations ---
    addMemory: function(charId, text, category) {
        if (!this.memories) this.memories = {};
        if (!this.memories[charId]) this.memories[charId] = [];
        const existing = this.memories[charId];
        const normalized = text.toLowerCase().trim();
        if (existing.some(m => normalized.includes(m.text.toLowerCase().trim()) || m.text.toLowerCase().trim().includes(normalized))) return false;

        this.memories[charId].push({
            id: 'mem_' + Date.now() + '_' + Math.random().toString(36).substr(2, 5),
            text: text.trim(),
            timestamp: Date.now(),
            category: category || 'general'
        });

        if (this.memories[charId].length > this.maxMemoriesPerChar) this.memories[charId].shift();
        this.save();
        return true;
    },

    getMemories: function(charId) { return (this.memories && this.memories[charId]) || []; },

    getMemoriesPrompt: function(charId) {
        const memories = this.getMemories(charId);
        if (memories.length === 0) return "";
        return `
[RECENT MEMORIES]
${memories.map(m => `- ${m.text}`).join("\n")}
`.trim();
    },

    deleteMemory: function(charId, memoryId) { if (this.memories[charId]) { this.memories[charId] = this.memories[charId].filter(m => m.id !== memoryId); this.save(); } },
    clearMemories: function(charId) { if (this.memories) { this.memories[charId] = []; this.save(); } },

    formatMemoryAge: function(ts) {
        const diff = Date.now() - ts;
        const mins = Math.floor(diff / 60000);
        if (mins < 60) return mins + 'm ago';
        const hrs = Math.floor(mins / 60);
        if (hrs < 24) return hrs + 'h ago';
        return Math.floor(hrs / 24) + 'd ago';
    }
};

window._stateReady = State.init();
