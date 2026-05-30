/**
 * characters.js
 * CharacterService: Single source of truth for character lookups and avatar management
 */

window.CharacterService = {
    /**
     * Get a character by ID — replaces State.characters.find(c => c.id === id) pattern
     */
    get(id) {
        if (!window.State || !State.characters) return null;
        return State.characters.find(c => c.id === id) || null;
    },

    /**
     * Get all characters (shorthand for State.characters)
     */
    getAll() {
        return (window.State && State.characters) ? State.characters : [];
    },

    /**
     * Get session messages for a character — always returns an array (never undefined)
     */
    getSession(charId) {
        if (!window.State || !State.sessions) return [];
        return State.sessions[charId] || [];
    },

    /**
     * Resolve avatar reference (db:, file:, or raw base64) and inject into DOM element as <img>
     * Returns the displayable src string (base64 for raw/file, or data URL for db: refs)
     */
    async resolveAvatar(ref, elementId) {
        if (!ref) return null;

        const el = document.getElementById(elementId);
        if (!el) return null;

        let src = ref;

        // Resolve db: refs through ImageDB
        if (ref.startsWith('db:') && window.ImageDB) {
            try {
                src = await window.ImageDB.get(ref);
            } catch (e) {
                console.warn(`Failed to resolve avatar ${ref}:`, e);
                return null;
            }
        }

        // Inject the image into the element
        if (src && el) {
            try {
                el.innerHTML = `<img src="${src}" alt="avatar" style="width:100%; height:100%; object-fit:cover;">`;
            } catch (e) {
                console.warn('Failed to set avatar image:', e);
            }
        }

        return src;
    },

    /**
     * Set a character's avatar: save base64 to ImageDB, update char.avatar, persist state
     * Centralizes all avatar writes — the ONLY place char.avatar should be written
     */
    async setAvatar(charId, base64) {
        const char = this.get(charId);
        if (!char) {
            console.warn(`Character ${charId} not found`);
            return false;
        }

        try {
            // Save base64 to ImageDB, get back a db: reference
            const dbId = `avatar_${charId}_${Date.now()}`;
            const dbRef = await window.ImageDB.save(dbId, base64);

            // Update character avatar field
            char.avatar = dbRef;

            // Persist to disk
            if (window.State && window.State.save) {
                State.save();
            }

            return true;
        } catch (e) {
            console.error('Failed to set avatar:', e);
            return false;
        }
    },

    /**
     * Delete a character's avatar: remove from ImageDB and clear the field
     */
    async deleteAvatar(charId) {
        const char = this.get(charId);
        if (!char || !char.avatar) return true;

        try {
            // Delete from ImageDB if it's a db: reference
            if (char.avatar.startsWith('db:') && window.ImageDB) {
                await window.ImageDB.delete(char.avatar);
            }

            // Clear the field
            char.avatar = null;

            // Persist
            if (window.State && window.State.save) {
                State.save();
            }

            return true;
        } catch (e) {
            console.error('Failed to delete avatar:', e);
            return false;
        }
    }
};
