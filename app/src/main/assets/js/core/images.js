/**
 * images.js
 * ImageService: Centralized image generation, storage, and reference management
 * Eliminates the duplicated generate → save → reference pattern across 7 callers
 */

window.ImageService = {
    /**
     * Generate an image and save it atomically — returns a db: reference
     * Caller only needs to worry about the result, not the 3-step manual process
     */
    async generateAndSave(prompt, settings, onProgress, namespace = 'img') {
        try {
            // Step 1: Generate via ImagingApp
            if (!window.ImagingApp || !window.ImagingApp.generate) {
                throw new Error('ImagingApp not available');
            }

            const base64 = await window.ImagingApp.generate(prompt, settings, onProgress);
            if (!base64) {
                throw new Error('Image generation returned no result');
            }

            // Step 2: Save to ImageDB
            return await this.save(base64, namespace);
        } catch (e) {
            console.error('ImageService.generateAndSave failed:', e);
            throw e;
        }
    },

    /**
     * Save an existing base64 image to ImageDB — returns a db: reference
     * Namespace standardizes the ID prefix (avatar_, img_, src_, post_, etc.)
     */
    async save(base64, namespace = 'img') {
        try {
            if (!window.ImageDB) {
                throw new Error('ImageDB not available');
            }

            // Standard ID format: namespace_timestamp
            const dbId = `${namespace}_${Date.now()}`;
            const dbRef = await window.ImageDB.save(dbId, base64);

            return dbRef;
        } catch (e) {
            console.error('ImageService.save failed:', e);
            throw e;
        }
    },

    /**
     * Resolve a db: reference to a displayable src (base64 or data URL)
     * Used for inline image display where ElementID isn't available
     */
    async resolve(ref, forceBase64 = false) {
        try {
            if (!ref) return null;

            // Resolve db: references through ImageDB
            if (ref.startsWith('db:') && window.ImageDB) {
                return await window.ImageDB.get(ref, forceBase64);
            }

            // Return file: or raw base64 refs as-is
            return ref;
        } catch (e) {
            console.warn(`ImageService.resolve failed for ${ref}:`, e);
            return null;
        }
    },

    /**
     * Delete an image from ImageDB by its db: reference
     */
    async delete(ref) {
        try {
            if (!ref.startsWith('db:') || !window.ImageDB) {
                return; // Nothing to delete
            }

            await window.ImageDB.delete(ref);
        } catch (e) {
            console.warn(`ImageService.delete failed for ${ref}:`, e);
        }
    }
};
