/**
 * gallery.js
 * Intelligent Gallery Module for Fancy AI OS.
 * Features: Automatic Categorization, Lazy Loading, and Batch Management.
 */
const GalleryApp = {
    container: null,
    images: [], // List of { id, timestamp, category, data? }
    categories: {}, // Name -> [image indices]
    currentCategory: null,
    selectedIds: new Set(),
    isSelectionMode: false,
    observer: null,

    init: async function(container, params) {
        this.container = container;
        this.injectStyles();
        this.selectedIds.clear();
        this.isSelectionMode = false;

        await this.loadAndCategorize();
        this.renderFolders();

        // Setup IntersectionObserver for lazy loading
        this.observer = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    this.loadImage(entry.target);
                }
            });
        }, { root: null, rootMargin: '200px' });
    },

    injectStyles: function() {
        const styleId = "gallery-app-style";
        if (document.getElementById(styleId)) return;
        const style = document.createElement('style');
        style.id = styleId;
        style.innerHTML = `
            .gallery-wrapper { display: flex; flex-direction: column; height: 100%; background: var(--bg-dark); }
            .gallery-header { padding: 12px 16px; background: rgba(20, 20, 22, 0.8); backdrop-filter: blur(10px); border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; position: sticky; top: 0; z-index: 100; }
            .gallery-title { font-size: 1rem; font-weight: 800; color: white; }
            .gallery-actions { display: flex; gap: 10px; }
            .gallery-btn { background: rgba(255,255,255,0.06); border: 1px solid var(--border); color: white; padding: 6px 12px; border-radius: 8px; font-size: 0.8rem; font-weight: 600; cursor: pointer; }
            .gallery-btn-danger { color: var(--danger); border-color: rgba(239, 68, 68, 0.3); }
            .gallery-btn-accent { background: var(--accent); border: none; }

            /* Folder View */
            .folder-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; padding: 16px; overflow-y: auto; }
            .folder-item { background: var(--bg-card); border: 1px solid var(--border); border-radius: 16px; padding: 12px; display: flex; flex-direction: column; gap: 8px; cursor: pointer; transition: transform 0.2s; }
            .folder-item:active { transform: scale(0.96); }
            .folder-preview { height: 120px; border-radius: 10px; background: var(--bg-input); overflow: hidden; display: grid; grid-template-columns: 1fr 1fr; grid-template-rows: 1fr 1fr; gap: 2px; }
            .folder-preview-img { width: 100%; height: 100%; object-fit: cover; background: #222; }
            .folder-info { display: flex; flex-direction: column; }
            .folder-name { font-size: 0.9rem; font-weight: 700; color: white; }
            .folder-count { font-size: 0.75rem; color: var(--text-muted); }

            /* Image Grid — Simple Flexbox Layout */
            .image-grid { display: flex; flex-wrap: wrap; gap: 2px; padding: 2px; overflow-y: auto; flex: 1; align-content: flex-start; }
            .gallery-item { width: calc(33.333% - 2px); aspect-ratio: 1/1; position: relative; background: var(--bg-input); overflow: hidden; cursor: pointer; }
            .gallery-img { position: absolute; top: 0; left: 0; width: 100%; height: 100%; object-fit: cover; opacity: 0; transition: opacity 0.3s; }
            .gallery-img.loaded { opacity: 1; }

            .selection-overlay { position: absolute; top: 0; left: 0; right: 0; bottom: 0; background: rgba(139, 92, 246, 0.3); display: none; align-items: center; justify-content: center; z-index: 5; pointer-events: none; }
            .gallery-item.selected .selection-overlay { display: flex; }
            .selection-check { width: 24px; height: 24px; background: var(--accent); border-radius: 50%; display: flex; align-items: center; justify-content: center; color: white; font-size: 14px; font-weight: 900; box-shadow: 0 2px 8px rgba(0,0,0,0.5); }

            .selection-toolbar { position: fixed; bottom: 0; left: 0; right: 0; background: #1a1a1e; border-top: 1px solid var(--border); padding: 12px 16px; padding-bottom: calc(12px + env(safe-area-inset-bottom)); display: flex; justify-content: space-between; align-items: center; z-index: 200; transform: translateY(100%); transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1); }
            .selection-toolbar.active { transform: translateY(0); }
        `;
        document.head.appendChild(style);
    },

    loadAndCategorize: async function() {
        const registry = await window.ImageDB.getRegistry();
        const mapping = this.buildUsageMapping();

        this.images = registry.map(item => {
            const cat = mapping[item.id] || "Generated";
            return {
                id: item.id,
                timestamp: item.timestamp,
                category: cat
            };
        });

        this.categories = {};
        this.images.forEach((img, index) => {
            if (!this.categories[img.category]) this.categories[img.category] = [];
            this.categories[img.category].push(index);
        });
    },

    buildUsageMapping: function() {
        const mapping = {}; // id -> category

        // Helpers to extract ID from db:ID string
        const getId = (ref) => (ref && typeof ref === 'string' && ref.startsWith('db:')) ? ref.replace('db:', '') : null;

        // 1. Avatars
        State.characters.forEach(c => {
            const id = getId(c.avatar);
            if (id) mapping[id] = "Avatars";
        });

        // 2. Sessions (Messenger)
        Object.keys(State.sessions).forEach(charId => {
            const char = State.characters.find(c => c.id === charId);
            const catName = char ? char.name : "Chat";
            const session = State.sessions[charId] || [];
            session.forEach(msg => {
                const id1 = getId(msg.image); if (id1) mapping[id1] = catName;
                const id2 = getId(msg.text); if (id2) mapping[id2] = catName;
                const id3 = getId(msg.source); if (id3) mapping[id3] = catName;
                const id4 = getId(msg.attachment); if (id4) mapping[id4] = catName;
                const id5 = getId(msg.denoisedImage); if (id5) mapping[id5] = catName;
            });
        });

        // 3. Social Apps
        (State.instagramPosts || []).forEach(p => {
            const id = getId(p.image); if (id) mapping[id] = "Ustagram";
        });
        (State.redditPosts || []).forEach(p => {
            const id = getId(p.image); if (id) mapping[id] = "Rebbit";
        });
        (State.xPosts || []).forEach(p => {
            const id = getId(p.image); if (id) mapping[id] = "Y";
        });

        return mapping;
    },

    renderFolders: function() {
        this.currentCategory = null;
        this.isSelectionMode = false;
        this.selectedIds.clear();
        this.container.innerHTML = `
            <div class="gallery-wrapper">
                <div class="gallery-header">
                    <div class="gallery-title">Gallery</div>
                    <div class="gallery-actions">
                        <button class="gallery-btn gallery-btn-danger" onclick="GalleryApp.deleteAllPrompt()">🗑️ Clear All</button>
                    </div>
                </div>
                <div class="folder-grid" id="galleryContent"></div>
            </div>
        `;

        const grid = document.getElementById('galleryContent');

        // Sort categories: Avatars first, then alpha, then Generated last
        const sortedCats = Object.keys(this.categories).sort((a, b) => {
            if (a === "Avatars") return -1;
            if (b === "Avatars") return 1;
            if (a === "Generated") return 1;
            if (b === "Generated") return -1;
            return a.localeCompare(b);
        });

        sortedCats.forEach(cat => {
            const indices = this.categories[cat];
            const folder = document.createElement('div');
            folder.className = 'folder-item';
            folder.onclick = () => this.renderFolder(cat);

            folder.innerHTML = `
                <div class="folder-preview" id="preview-${cat.replace(/\s/g, '')}">
                    ${this.renderPreviewImages(indices)}
                </div>
                <div class="folder-info">
                    <span class="folder-name">${cat}</span>
                    <span class="folder-count">${indices.length} items</span>
                </div>
            `;
            grid.appendChild(folder);
            this.loadPreviews(cat, indices);
        });
    },

    renderPreviewImages: function(indices) {
        // Return 4 placeholders
        return `<div class="folder-preview-img"></div>`.repeat(Math.min(4, indices.length)) +
               `<div class="folder-preview-img" style="background:transparent"></div>`.repeat(Math.max(0, 4 - indices.length));
    },

    loadPreviews: async function(cat, indices) {
        const previewDiv = document.getElementById(`preview-${cat.replace(/\s/g, '')}`);
        if (!previewDiv) return;
        const slots = previewDiv.querySelectorAll('.folder-preview-img');

        for (let i = 0; i < Math.min(4, indices.length); i++) {
            const imgData = this.images[indices[i]];
            const src = await window.ImageDB.get('db:' + imgData.id);
            if (src && slots[i]) {
                slots[i].style.backgroundImage = `url(${src})`;
                slots[i].style.backgroundSize = 'cover';
                slots[i].style.backgroundPosition = 'center';
            }
        }
    },

    renderFolder: function(cat) {
        this.currentCategory = cat;
        this.container.innerHTML = `
            <div class="gallery-wrapper">
                <div class="gallery-header">
                    <button class="gallery-btn" onclick="GalleryApp.renderFolders()">Back</button>
                    <div class="gallery-title">${cat}</div>
                    <div class="gallery-actions">
                        <button class="gallery-btn ${this.isSelectionMode ? 'gallery-btn-accent' : ''}" id="selectBtn" onclick="GalleryApp.toggleSelectionMode()">${this.isSelectionMode ? 'Cancel' : 'Select'}</button>
                    </div>
                </div>
                <div class="image-grid" id="galleryContent"></div>
                <div class="selection-toolbar" id="selectionToolbar">
                    <div style="color:white; font-size:0.9rem; font-weight:700;"><span id="selectCount">0</span> Selected</div>
                    <div style="display:flex; gap:10px;">
                        <button class="gallery-btn" onclick="GalleryApp.selectAll()">All</button>
                        <button class="gallery-btn gallery-btn-danger" onclick="GalleryApp.deleteSelected()">Delete</button>
                    </div>
                </div>
            </div>
        `;

        const grid = document.getElementById('galleryContent');
        const indices = this.categories[cat];

        // Render all cells upfront (cheap—no images loaded yet). IntersectionObserver
        // handles lazy-loading. This keeps grid layout stable during scroll.
        indices.forEach(idx => {
            const img = this.images[idx];
            const item = document.createElement('div');
            item.className = 'gallery-item' + (this.selectedIds.has(img.id) ? ' selected' : '');
            item.dataset.id = img.id;
            item.onclick = () => this.handleItemClick(img.id, item);
            item.innerHTML = `
                <img class="gallery-img" data-id="${img.id}">
                <div class="selection-overlay"><div class="selection-check">✓</div></div>
            `;
            grid.appendChild(item);
            this.observer.observe(item);
        });
    },

    handleItemClick: function(id, el) {
        if (this.isSelectionMode) {
            if (this.selectedIds.has(id)) {
                this.selectedIds.delete(id);
                el.classList.remove('selected');
            } else {
                this.selectedIds.add(id);
                el.classList.add('selected');
            }
            this.updateToolbar();
        } else {
            // Always open full-res in lightbox (thumbnail src is downsampled)
            const imgEl = el.querySelector('img');
            const fullSrc = imgEl && imgEl.dataset.fullsrc;
            if (fullSrc && typeof OS !== 'undefined') {
                OS.openLightbox(fullSrc);
            } else {
                (async () => {
                    const src = await window.ImageDB.get('db:' + id);
                    if (src && typeof OS !== 'undefined') OS.openLightbox(src);
                })();
            }
        }
    },

    toggleSelectionMode: function() {
        this.isSelectionMode = !this.isSelectionMode;
        if (!this.isSelectionMode) this.selectedIds.clear();
        this.renderFolder(this.currentCategory);
        this.updateToolbar();
    },

    updateToolbar: function() {
        const toolbar = document.getElementById('selectionToolbar');
        const countEl = document.getElementById('selectCount');
        if (!toolbar || !countEl) return;

        countEl.innerText = this.selectedIds.size;
        if (this.isSelectionMode && this.selectedIds.size > 0) {
            toolbar.classList.add('active');
        } else {
            toolbar.classList.remove('active');
        }
    },

    selectAll: function() {
        const indices = this.categories[this.currentCategory];
        indices.forEach(idx => this.selectedIds.add(this.images[idx].id));
        this.renderFolder(this.currentCategory);
        this.updateToolbar();
    },

    deleteSelected: function() {
        if (this.selectedIds.size === 0) return;
        OS.confirm(`Delete ${this.selectedIds.size} images?`, async () => {
            OS.toast(`Deleting ${this.selectedIds.size} items...`);
            for (const id of this.selectedIds) {
                await window.ImageDB.delete(id);
            }
            this.selectedIds.clear();
            this.isSelectionMode = false;
            await this.loadAndCategorize();
            if (this.categories[this.currentCategory]) {
                this.renderFolder(this.currentCategory);
            } else {
                this.renderFolders();
            }
        }, { confirmText: 'Delete', danger: true });
    },

    deleteAllPrompt: function() {
        OS.confirm("Wipe entire gallery?", async () => {
            OS.toast("Wiping gallery...", "warning");
            const registry = await window.ImageDB.getRegistry();
            for (const item of registry) {
                await window.ImageDB.delete(item.id);
            }
            OS.toast("Gallery wiped clean", "success");
            await this.init(this.container);
        }, { title: 'Wipe Gallery', confirmText: 'DELETE ALL', danger: true });
    },

    // Lazy Loading Logic
    loadImage: async function(container) {
        const imgEl = container.querySelector('img');
        // Use data-loaded flag (set synchronously) to prevent race conditions
        if (!imgEl || imgEl.dataset.loaded) return;
        imgEl.dataset.loaded = '1';

        const id = imgEl.dataset.id;
        const src = await window.ImageDB.get('db:' + id);
        if (!src) return;

        imgEl.dataset.fullsrc = src;  // preserve full-res URL for lightbox
        // Request a natively-downsampled thumbnail (media.fancy.ai URLs only).
        // Native decode via BitmapFactory.inSampleSize keeps bitmap memory tiny.
        if (src.startsWith('https://media.fancy.ai/')) {
            imgEl.src = src + (src.includes('?') ? '&' : '?') + 'thumb=1';
        } else {
            imgEl.src = src;
        }
        imgEl.classList.add('loaded');
        this.observer.unobserve(container);  // stop watching once loaded
    },

    cleanup: function() {
        if (this.observer) this.observer.disconnect();
    }
};
window.GalleryApp = GalleryApp;
