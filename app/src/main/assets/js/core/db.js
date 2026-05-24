/**
 * db.js
 * The ONLY authority for media storage.
 * Bypasses WebView memory limits by using Android Native Storage via Bridge.
 * Uses a flat file registry on Android instead of IndexedDB for maximum reliability.
 */
window.ImageDB = {
    registry: {}, // id -> { data: storageRef, timestamp: Date.now() }
    initialized: false,

    init: async function() {
        if (this.initialized) return;
        let loaded = false;

        // Try Android native file first
        if (window.AndroidBridge && typeof window.AndroidBridge.readFile === 'function') {
            const data = window.AndroidBridge.readFile('media_registry.json');
            if (data) {
                try {
                    this.registry = JSON.parse(data);
                    loaded = true;
                } catch(e) {
                    console.error("Media registry parse failed", e); 
                }
            }
        }

        // Fallback: try to restore from localStorage backup
        if (!loaded) {
            try {
                const backup = localStorage.getItem('fancy_ai_media_registry');
                if (backup) {
                    this.registry = JSON.parse(backup);
                    loaded = true;
                    // Re-persist to Android file so we don't lose it again
                    if (window.AndroidBridge && typeof window.AndroidBridge.saveToFile === 'function') {
                        window.AndroidBridge.saveToFile('media_registry.json', backup);
                    }
                }
            } catch(e) {
                console.error("Media registry localStorage fallback failed", e);
                this.registry = {};
            }
        }

        this.initialized = true;
    },

    /**
     * Saves image bytes to Android Disk. Returns a "db:ID" reference.
     */
    save: async function(id, base64) {
        if (!base64 || !base64.startsWith('data:image')) return base64;
        await this.init();
        
        let storageRef = base64;
        if (window.AndroidBridge && typeof window.AndroidBridge.saveImageToDisk === 'function') {
            const fileName = window.AndroidBridge.saveImageToDisk(base64);
            if (fileName) storageRef = "file:" + fileName;
        }

        this.registry[id] = { data: storageRef, timestamp: Date.now() };
        this._persist();
        return "db:" + id; 
    },

    /**
     * Resolves ANY reference (db:ID, file:filename, or raw Data URL) 
     * back into a viewable Base64 string.
     */
    get: async function(ref) {
        if (!ref || typeof ref !== 'string') return ref;
        if (ref.startsWith('data:image')) return ref;

        await this.init();
        let target = ref;

        // Resolve db: prefix
        if (ref.startsWith('db:')) {
            const id = ref.replace('db:', '');
            const record = this.registry[id];
            if (record) target = record.data;
            else return null;
        }

        // Resolve file: from Android Disk
        if (target && target.startsWith('file:') && window.AndroidBridge) {
            return window.AndroidBridge.loadImageFromDisk(target.replace('file:', ''));
        }

        return (target && target.startsWith('data:image')) ? target : null;
    },

    getAll: async function() {
        await this.init();
        const results = [];
        // Sort by timestamp descending
        const sortedIds = Object.keys(this.registry).sort((a, b) => {
            return (this.registry[b].timestamp || 0) - (this.registry[a].timestamp || 0);
        });

        for (let id of sortedIds) {
            const b64 = await this.get('db:' + id);
            if (b64) results.push({ id: id, data: b64 });
        }
        return results;
    },

    delete: async function(id) {
        await this.init();
        if (this.registry[id]) {
            delete this.registry[id];
            this._persist();
        }
    },

    _persist: function() {
        const json = JSON.stringify(this.registry);
        // Always save to localStorage as a backup
        try {
            localStorage.setItem('fancy_ai_media_registry', json);
        } catch(e) {
            console.error("Media registry localStorage backup failed", e);
        }
        if (window.AndroidBridge && typeof window.AndroidBridge.saveToFile === 'function') {
            window.AndroidBridge.saveToFile('media_registry.json', json);
        }
    }
};
ImageDB.init();
