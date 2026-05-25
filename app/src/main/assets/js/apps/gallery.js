/**
 * gallery.js
 * Standalone Media Gallery Module for Fancy AI OS
 * Uses IndexedDB (ImageDB) for persistent high-capacity storage.
 * Features multi-select, select all, bulk delete, and lazy loading.
 */

const GalleryApp = {
    container: null,
    selectedIds: new Set(),
    selectMode: false,
    currentFolder: null, // null means "Album View", otherwise a string like "All", "Ustagram", etc.
    // Touch gesture tracking parameters
    lightboxScale: 1,
    startX: 0,
    startY: 0,
    translateX: 0,
    translateY: 0,
    lastDist: 0,
    lastTap: 0,
    isClosingLightbox: false,

    // Lazy loading state
    _observer: null,          // IntersectionObserver for lazy loading
    _loadedImages: new Map(), // imgId -> dataURL cache (only for visible items)
    _registryCache: null,     // cached registry entries from ImageDB
    _maxLoaded: 60,           // max images to keep in memory at once

    /**
     * Entry point called by OS.launch
     */
    init: function(container) {
        this.container = container;
        this.selectedIds = new Set();
        this.selectMode = false;
        this.currentFolder = null;
        this._loadedImages.clear();
        this._registryCache = null;

        this.render();
        this.loadAlbumView();
    },

    render: function() {
        const styleId = "gallery-app-style";
        if (!document.getElementById(styleId)) {
            const style = document.createElement('style');
            style.id = styleId;
            style.innerHTML = `
                .gallery-wrap {
                    padding: 12px;
                    overflow-y: auto;
                    height: 100%;
                    background: #0a0a0b;
                    display: flex;
                    flex-direction: column;
                    gap: 12px;
                    padding-bottom: 100px;
                }
                .gallery-header {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    padding: 4px 6px;
                    flex-wrap: wrap;
                    gap: 8px;
                }
                .gallery-header-left {
                    display: flex;
                    align-items: center;
                    gap: 10px;
                }
                .gallery-counter {
                    font-size: 0.78rem;
                    color: var(--text-muted);
                    background: var(--bg-card);
                    padding: 4px 10px;
                    border-radius: 10px;
                    border: 1px solid var(--border);
                    font-weight: 600;
                }
                .gallery-select-btn {
                    background: rgba(139,92,246,0.15);
                    color: var(--accent);
                    border: 1px solid rgba(139,92,246,0.3);
                    padding: 6px 12px;
                    border-radius: 10px;
                    font-size: 0.72rem;
                    font-weight: 700;
                    cursor: pointer;
                    transition: all 0.15s;
                }
                .gallery-select-btn:active { transform: scale(0.94); }
                .gallery-select-btn.active {
                    background: var(--accent);
                    color: white;
                    border-color: var(--accent);
                }
                .gallery-delete-selected-btn {
                    background: rgba(239,68,68,0.15);
                    color: var(--danger);
                    border: 1px solid rgba(239,68,68,0.3);
                    padding: 6px 12px;
                    border-radius: 10px;
                    font-size: 0.72rem;
                    font-weight: 700;
                    cursor: pointer;
                    transition: all 0.15s;
                    display: none;
                }
                .gallery-delete-selected-btn:active { transform: scale(0.94); }
                .gallery-delete-selected-btn.show { display: inline-block; }
                .grid-container {
                    display: grid;
                    grid-template-columns: repeat(3, 1fr);
                    gap: 8px;
                    width: 100%;
                }
                /* Album Grid */
                .album-grid {
                    display: grid;
                    grid-template-columns: 1fr 1fr;
                    gap: 16px;
                    padding: 8px;
                }
                .album-card {
                    background: var(--bg-card);
                    border-radius: 16px;
                    overflow: hidden;
                    border: 1px solid var(--border);
                    cursor: pointer;
                    display: flex;
                    flex-direction: column;
                    transition: transform 0.2s;
                }
                .album-card:active { transform: scale(0.96); }
                .album-preview {
                    width: 100%;
                    aspect-ratio: 1/1;
                    background: #1a1a1e;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 2rem;
                    position: relative;
                }
                .album-preview img {
                    width: 100%; height: 100%; object-fit: cover;
                }
                .album-info {
                    padding: 10px 12px;
                }
                .album-name {
                    font-size: 0.85rem;
                    font-weight: 700;
                    color: white;
                    margin-bottom: 2px;
                }
                .album-count {
                    font-size: 0.7rem;
                    color: var(--text-muted);
                }
                .grid-item {
                    position: relative;
                    aspect-ratio: 1 / 1;
                    background: var(--bg-card);
                    border: 2px solid transparent;
                    border-radius: 10px;
                    overflow: hidden;
                    transition: border-color 0.15s, opacity 0.15s;
                }
                .grid-item.selected {
                    border-color: var(--accent);
                    box-shadow: 0 0 12px rgba(139,92,246,0.3);
                }
                .grid-item img {
                    width: 100%;
                    height: 100%;
                    object-fit: cover;
                    display: block;
                    cursor: pointer;
                    transition: opacity 0.2s ease;
                }
                .grid-item img.lazy-placeholder {
                    opacity: 0;
                }
                .grid-item img.lazy-loaded {
                    opacity: 1;
                }
                .grid-item.selectable img { cursor: default; }
                .delete-thumbnail-btn {
                    position: absolute;
                    top: 4px;
                    right: 4px;
                    width: 24px;
                    height: 24px;
                    border-radius: 50%;
                    background: rgba(0, 0, 0, 0.7);
                    color: white;
                    border: none;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 14px;
                    z-index: 10;
                    cursor: pointer;
                }
                .check-overlay {
                    position: absolute;
                    top: 4px;
                    left: 4px;
                    width: 24px;
                    height: 24px;
                    border-radius: 50%;
                    background: rgba(0,0,0,0.6);
                    border: 2px solid rgba(255,255,255,0.3);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    z-index: 10;
                    font-size: 12px;
                    color: transparent;
                    transition: all 0.15s;
                    pointer-events: none;
                }
                .grid-item.selected .check-overlay {
                    background: var(--accent);
                    border-color: var(--accent);
                    color: white;
                }
                .bulk-bar {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    padding: 8px 6px;
                    border-radius: 12px;
                    background: rgba(139,92,246,0.08);
                    border: 1px solid rgba(139,92,246,0.15);
                    gap: 8px;
                }
                .bulk-count {
                    font-size: 0.82rem;
                    color: var(--text-main);
                    font-weight: 600;
                }
                .lazy-shimmer {
                    width: 100%;
                    height: 100%;
                    background: linear-gradient(110deg, var(--bg-card) 30%, rgba(255,255,255,0.04) 50%, var(--bg-card) 70%);
                    background-size: 200% 100%;
                    animation: shimmer 1.5s ease-in-out infinite;
                }
                @keyframes shimmer {
                    0% { background-position: 200% 0; }
                    100% { background-position: -200% 0; }
                }
            `;
            document.head.appendChild(style);
        }

        this.container.innerHTML = `
            <div class="gallery-wrap">
                <div class="gallery-header">
                    <div class="gallery-header-left">
                        <span id="galleryGridItemCount" class="gallery-counter">0</span>
                    </div>
                    <div id="galleryActions" style="display:none; gap:6px; align-items:center;">
                        <button id="gallerySelectBtn" class="gallery-select-btn" onclick="GalleryApp.toggleSelectMode()">Select</button>
                        <button id="galleryDeleteSelectedBtn" class="gallery-delete-selected-btn" onclick="GalleryApp.bulkDelete()">🗑️ Delete</button>
                    </div>
                </div>
                <div id="galleryBulkBar" class="bulk-bar" style="display:none;">
                    <span id="galleryBulkCount" class="bulk-count">0 selected</span>
                    <div style="display:flex; gap:6px;">
                        <button class="gallery-select-btn" onclick="GalleryApp.selectAll()">Select All</button>
                        <button class="gallery-select-btn" onclick="GalleryApp.deselectAll()">Clear</button>
                    </div>
                </div>
                <div id="mediaGridSlot"></div>
            </div>
        `;
    },

    goBackToAlbums: function() {
        // Just trigger the OS back logic — it knows how to handle the navStack
        if (window.OS && typeof OS.goBack === 'function') {
            OS.goBack();
        } else {
            // Fallback
            this.currentFolder = null;
            this.selectMode = false;
            this.selectedIds.clear();
            this.loadAlbumView();
        }
    },

    loadAlbumView: async function() {
        const slot = document.getElementById('mediaGridSlot');
        const countBadge = document.getElementById('galleryGridItemCount');
        const actions = document.getElementById('galleryActions');
        const bulkBar = document.getElementById('galleryBulkBar');

        if (!slot) return;

        // Use OS title
        if (document.getElementById('os-app-title')) {
            document.getElementById('os-app-title').innerText = "Gallery";
        }

        if (actions) actions.style.display = 'none';
        if (bulkBar) bulkBar.style.display = 'none';

        slot.innerHTML = `<div style="text-align: center; padding: 40px; color: var(--accent);">Organizing...</div>`;

        try {
            if (!window.ImageDB) throw new Error("Storage not ready");
            const entries = await window.ImageDB.getRegistry();

            const categories = {
                'All': { name: 'All Photos', emoji: '🖼️', ids: [], preview: null },
                'Ustagram': { name: 'Ustagram', emoji: '📸', ids: [], preview: null },
                'Rebbit': { name: 'Rebbit', emoji: '🔞', ids: [], preview: null },
                'Messenger': { name: 'Messenger', emoji: '💬', ids: [], preview: null },
                'Avatars': { name: 'Avatars', emoji: '👤', ids: [], preview: null }
            };

            // Per-character albums
            (State.characters || []).forEach(c => {
                categories[`char_${c.id}`] = { name: c.name, emoji: '👤', ids: [], preview: null, isChar: true };
            });

            // Categorize
            for (const entry of entries) {
                const id = entry.id;
                categories['All'].ids.push(id);
                if (!categories['All'].preview) categories['All'].preview = id;

                if (id.startsWith('ig_')) {
                    categories['Ustagram'].ids.push(id);
                    if (!categories['Ustagram'].preview) categories['Ustagram'].preview = id;
                } else if (id.startsWith('rb_')) {
                    categories['Rebbit'].ids.push(id);
                    if (!categories['Rebbit'].preview) categories['Rebbit'].preview = id;
                } else if (id.startsWith('avatar_')) {
                    categories['Avatars'].ids.push(id);
                    if (!categories['Avatars'].preview) categories['Avatars'].preview = id;
                    // Also find which character this avatar belongs to
                    const charId = id.replace('avatar_', '');
                    if (categories[`char_${charId}`]) {
                        categories[`char_${charId}`].ids.push(id);
                        if (!categories[`char_${charId}`].preview) categories[`char_${charId}`].preview = id;
                    }
                } else if (id.startsWith('img_') || id.startsWith('res_') || id.startsWith('src_')) {
                    categories['Messenger'].ids.push(id);
                    if (!categories['Messenger'].preview) categories['Messenger'].preview = id;

                    // Try to map Messenger images to characters
                    // img_TIMESTAMP or img_charId_TIMESTAMP?
                    // Actually, let's look at State.sessions if we really want accurate character mapping
                }
            }

            // More accurate character mapping by scanning sessions
            for (const charId in State.sessions) {
                if (categories[`char_${charId}`]) {
                    const session = State.sessions[charId] || [];
                    for (const msg of session) {
                        if (msg.type === 'image' || msg.type === 'img2img') {
                            const refs = [];
                            if (msg.text && msg.text.startsWith('db:')) refs.push(msg.text.replace('db:', ''));
                            if (msg.source && msg.source.startsWith('db:')) refs.push(msg.source.replace('db:', ''));

                            for (const ref of refs) {
                                if (!categories[`char_${charId}`].ids.includes(ref)) {
                                    categories[`char_${charId}`].ids.push(ref);
                                    if (!categories[`char_${charId}`].preview) categories[`char_${charId}`].preview = ref;
                                }
                            }
                        }
                    }
                }
            }

            countBadge.innerText = entries.length;

            let html = '<div class="album-grid">';
            for (const key in categories) {
                const cat = categories[key];
                if (cat.ids.length === 0 && key !== 'All') continue;

                html += `
                    <div class="album-card" onclick="GalleryApp.openFolder('${key}', '${cat.name}')">
                        <div class="album-preview" id="preview-${key}">
                            ${cat.emoji}
                        </div>
                        <div class="album-info">
                            <div class="album-name">${cat.name}</div>
                            <div class="album-count">${cat.ids.length} images</div>
                        </div>
                    </div>
                `;
            }
            html += '</div>';
            slot.innerHTML = html;

            // Load previews lazily
            for (const key in categories) {
                const cat = categories[key];
                if (cat.preview) {
                    const previewEl = document.getElementById(`preview-${key}`);
                    if (previewEl) {
                        window.ImageDB.get('db:' + cat.preview).then(src => {
                            if (src) previewEl.innerHTML = `<img src="${src}">`;
                        });
                    }
                }
            }

        } catch (e) {
            slot.innerHTML = `<div style="padding:20px; color:var(--danger);">${e.message}</div>`;
        }
    },

    openFolder: function(folderId, folderName) {
        this.currentFolder = folderId;
        if (window.OS && typeof OS.pushView === 'function') {
            const oldTitle = document.getElementById('os-app-title').innerText;
            document.getElementById('os-app-title').innerText = folderName;
            OS.pushView(() => {
                document.getElementById('os-app-title').innerText = oldTitle;
                this.currentFolder = null;
                this.selectMode = false;
                this.selectedIds.clear();
                this.loadAlbumView();
            });
        }

        // Ensure actions are fresh immediately
        const actions = document.getElementById('galleryActions');
        if (actions) actions.style.display = 'flex';

        this.loadGalleryGrid();
    },

    toggleSelectMode: function() {
        this.selectMode = !this.selectMode;
        const btn = document.getElementById('gallerySelectBtn');
        const bulkBar = document.getElementById('galleryBulkBar');
        if (this.selectMode) {
            btn.classList.add('active');
            btn.innerText = 'Cancel';
            bulkBar.style.display = 'flex';
        } else {
            btn.classList.remove('active');
            btn.innerText = 'Select';
            bulkBar.style.display = 'none';
            this.deselectAll();
        }
        this.loadGalleryGrid();
    },

    selectAll: function() {
        if (!this._registryCache) return;
        for (const entry of this._registryCache) {
            this.selectedIds.add(entry.id);
        }
        const gridSlot = document.getElementById('mediaGridSlot');
        if (gridSlot) {
            gridSlot.querySelectorAll('.grid-item').forEach(item => item.classList.add('selected'));
        }
        this.updateBulkCount();
        this.updateDeleteBtn();
    },

    deselectAll: function() {
        this.selectedIds.clear();
        const gridSlot = document.getElementById('mediaGridSlot');
        if (gridSlot) {
            gridSlot.querySelectorAll('.grid-item').forEach(item => item.classList.remove('selected'));
        }
        this.updateBulkCount();
        this.updateDeleteBtn();
    },

    toggleSelect: function(imgId) {
        if (this.selectedIds.has(imgId)) {
            this.selectedIds.delete(imgId);
        } else {
            this.selectedIds.add(imgId);
        }
        this.updateBulkCount();
        this.updateDeleteBtn();
        // Update visual state
        const item = document.querySelector(`.grid-item[data-img-id="${imgId}"]`);
        if (item) item.classList.toggle('selected');
    },

    updateBulkCount: function() {
        const el = document.getElementById('galleryBulkCount');
        if (el) el.innerText = `${this.selectedIds.size} selected`;
    },

    updateDeleteBtn: function() {
        const btn = document.getElementById('galleryDeleteSelectedBtn');
        if (btn) {
            btn.classList.toggle('show', this.selectedIds.size > 0);
        }
    },

    bulkDelete: async function() {
        if (this.selectedIds.size === 0) return;
        const count = this.selectedIds.size;
        OS.confirm(`Delete ${count} image${count > 1 ? 's' : ''}?`, async () => {
            try {
                for (const id of this.selectedIds) {
                    if (window.ImageDB) {
                        await window.ImageDB.delete(id);
                    }
                    this._loadedImages.delete(id);
                }
                this.selectedIds.clear();
                this.updateBulkCount();
                this.updateDeleteBtn();
                this._registryCache = null; // invalidate cache
                GalleryApp.loadGalleryGrid();
                // Trigger orphan cleanup after bulk delete
                if (window.ImageDB && window.ImageDB.purgeOrphanedFiles) window.ImageDB.purgeOrphanedFiles();
            } catch (err) {
                OS.toast("Delete failed: " + err.message, 'error');
            }
        }, { title: 'Delete Images', confirmText: 'Delete', danger: true });
    },

    /**
     * Lazy load gallery grid — only loads image data for visible items.
     * Uses IntersectionObserver to load images as they scroll into view.
     */
    loadGalleryGrid: async function() {
        const gridSlot = document.getElementById('mediaGridSlot');
        const countBadge = document.getElementById('galleryGridItemCount');
        if (!gridSlot) return;

        gridSlot.className = "grid-container"; // Ensure grid class is applied

        // Disconnect any previous observer
        if (this._observer) {
            this._observer.disconnect();
            this._observer = null;
        }

        gridSlot.innerHTML = `<div style="grid-column: span 3; text-align: center; padding: 40px; color: var(--accent);">Opening Album...</div>`;

        try {
            if (window.ImageDB && typeof window.ImageDB.getRegistry === 'function') {
                // Use lightweight registry
                let entries = await window.ImageDB.getRegistry();

                // Filter by current folder
                if (this.currentFolder && this.currentFolder !== 'All') {
                    if (this.currentFolder === 'Ustagram') {
                        entries = entries.filter(e => e.id.startsWith('ig_'));
                    } else if (this.currentFolder === 'Rebbit') {
                        entries = entries.filter(e => e.id.startsWith('rb_'));
                    } else if (this.currentFolder === 'Avatars') {
                        entries = entries.filter(e => e.id.startsWith('avatar_'));
                    } else if (this.currentFolder === 'Messenger') {
                        entries = entries.filter(e => e.id.startsWith('img_') || e.id.startsWith('res_') || e.id.startsWith('src_'));
                    } else if (this.currentFolder.startsWith('char_')) {
                        const charId = this.currentFolder.replace('char_', '');
                        const charIds = new Set();

                        // Add avatar
                        charIds.add(`avatar_${charId}`);

                        // Add from session
                        const session = State.sessions[charId] || [];
                        for (const msg of session) {
                            if (msg.text && msg.text.startsWith('db:')) charIds.add(msg.text.replace('db:', ''));
                            if (msg.source && msg.source.startsWith('db:')) charIds.add(msg.source.replace('db:', ''));
                        }

                        // Add from posts
                        (State.instagramPosts || []).filter(p => p.charId === charId).forEach(p => charIds.add(p.image.replace('db:', '')));
                        (State.redditPosts || []).filter(p => p.charId === charId).forEach(p => charIds.add(p.image.replace('db:', '')));

                        entries = entries.filter(e => charIds.has(e.id));
                    }
                }

                this._registryCache = entries;

                if (!entries || entries.length === 0) {
                    countBadge.innerText = "Images: 0";
                    gridSlot.innerHTML = `
                        <div style="grid-column: span 3; padding: 60px 20px; text-align: center; color: var(--text-muted); font-size: 0.88rem; font-style: italic;">
                            No images saved yet.
                            <br><br>
                            <button class="btn" onclick="GalleryApp.triggerRebuild()" style="width:auto; padding:8px 16px; font-size:0.75rem;">🔧 Recover Missing Images</button>
                        </div>
                    `;
                    return;
                }

                countBadge.innerText = `Images: ${entries.length}`;
                gridSlot.innerHTML = '';

                // Create placeholder grid items for ALL entries (lightweight — no image data)
                for (const entry of entries) {
                    const item = document.createElement('div');
                    item.className = 'grid-item' + (this.selectedIds.has(entry.id) ? ' selected' : '');
                    item.dataset.imgId = entry.id;

                    // Start with shimmer placeholder — image loads lazily
                    if (this.selectMode) {
                        item.classList.add('selectable');
                        item.innerHTML = `
                            <div class="check-overlay">${this.selectedIds.has(entry.id) ? '✓' : ''}</div>
                            <div class="lazy-shimmer" data-lazy-img="${entry.id}"></div>
                        `;
                        item.onclick = () => this.toggleSelect(entry.id);
                    } else {
                        item.innerHTML = `
                            <button class="delete-thumbnail-btn" onclick="event.stopPropagation(); GalleryApp.purgeStoredImage('${entry.id}')">✕</button>
                            <div class="lazy-shimmer" data-lazy-img="${entry.id}"></div>
                        `;
                        item.onclick = () => {
                            // Load full image for lightbox on click
                            this._openLightbox(entry.id);
                        };
                    }
                    gridSlot.appendChild(item);
                }

                // Set up IntersectionObserver for lazy loading
                this._setupLazyObserver(gridSlot);

            } else {
                throw new Error("ImageDB not initialized");
            }
        } catch(e) {
            console.error("Gallery Load Error:", e);
            gridSlot.innerHTML = `<div style="grid-column: span 3; text-align: center; color: var(--danger); padding:20px;">Storage Error: ${e.message}</div>`;
        }
    },

    /**
     * Sets up IntersectionObserver to lazy-load images as they scroll into view.
     * Only loads the actual image data when a placeholder becomes visible.
     */
    _setupLazyObserver: function(gridSlot) {
        if (this._observer) {
            this._observer.disconnect();
        }

        const rootMargin = '200px'; // Start loading 200px before item enters viewport

        this._observer = new IntersectionObserver((entries) => {
            for (const entry of entries) {
                if (entry.isIntersecting) {
                    const placeholder = entry.target.querySelector('[data-lazy-img]');
                    if (placeholder && placeholder.tagName !== 'IMG') {
                        const imgId = placeholder.dataset.lazyImg;
                        this._loadImageIntoSlot(imgId, placeholder);
                    }
                }
            }
        }, {
            root: gridSlot.closest('.gallery-wrap') || null,
            rootMargin: rootMargin,
            threshold: 0.01
        });

        // Observe all grid items
        gridSlot.querySelectorAll('.grid-item').forEach(item => {
            this._observer.observe(item);
        });
    },

    /**
     * Loads an image from ImageDB into a placeholder slot.
     * Uses the in-memory cache to avoid redundant disk reads.
     */
    _loadImageIntoSlot: async function(imgId, placeholder) {
        // Check in-memory cache first
        let dataUrl = this._loadedImages.get(imgId);

        if (!dataUrl) {
            // Load from ImageDB
            try {
                dataUrl = await window.ImageDB.get('db:' + imgId);
                if (dataUrl) {
                    this._loadedImages.set(imgId, dataUrl);
                    // Evict old entries if cache is too large
                    this._evictIfNeeded();
                }
            } catch(e) {
                console.error("Lazy load failed for", imgId, e);
                return;
            }
        }

        if (!dataUrl || !placeholder.parentNode) return;

        // Replace shimmer placeholder with actual image
        const img = document.createElement('img');
        img.src = dataUrl;
        img.alt = 'Gallery item';
        img.className = 'lazy-placeholder';
        img.onload = () => {
            img.classList.remove('lazy-placeholder');
            img.classList.add('lazy-loaded');
        };
        placeholder.replaceWith(img);
    },

    /**
     * Evicts oldest cached images when the in-memory cache exceeds _maxLoaded.
     * Only evicts images that are NOT currently visible in the viewport.
     */
    _evictIfNeeded: function() {
        if (this._loadedImages.size <= this._maxLoaded) return;

        // Find visible image IDs (currently in the DOM as <img> elements)
        const visibleIds = new Set();
        const gridSlot = document.getElementById('mediaGridSlot');
        if (gridSlot) {
            gridSlot.querySelectorAll('.grid-item').forEach(item => {
                visibleIds.add(item.dataset.imgId);
            });
        }

        // Evict oldest entries that are not currently visible
        // Use insertion order of Map (oldest first)
        let evicted = 0;
        const targetEvict = this._loadedImages.size - this._maxLoaded;
        for (const [id, _] of this._loadedImages) {
            if (evicted >= targetEvict) break;
            if (!visibleIds.has(id)) {
                this._loadedImages.delete(id);
                evicted++;
            }
        }
    },

    /**
     * Opens the lightbox for an image, loading it from cache or disk.
     */
    _openLightbox: async function(imgId) {
        let dataUrl = this._loadedImages.get(imgId);
        if (!dataUrl) {
            try {
                dataUrl = await window.ImageDB.get('db:' + imgId);
                if (dataUrl) this._loadedImages.set(imgId, dataUrl);
            } catch(e) {
                console.error("Lightbox load failed for", imgId, e);
                return;
            }
        }
        if (dataUrl && window.ImagingApp) {
            window.ImagingApp.openLocalLightbox(dataUrl, 'db:' + imgId);
        }
    },

    purgeStoredImage: async function(imgId) {
        OS.confirm("Delete this image?", async () => {
            try {
                if (window.ImageDB) {
                    await window.ImageDB.delete(imgId);
                    this._loadedImages.delete(imgId);
                    this._registryCache = null; // invalidate cache
                    GalleryApp.loadGalleryGrid();
                }
            } catch (err) {
                OS.toast("Delete failed: " + err.message, 'error');
            }
        }, { title: 'Delete Image', confirmText: 'Delete', danger: true });
    },

    triggerRebuild: async function() {
        if (!window.ImageDB || !window.ImageDB.rebuildRegistryFromDisk) return;
        const count = await window.ImageDB.rebuildRegistryFromDisk();
        if (count > 0) {
            OS.toast(`Restored ${count} missing images!`, 'success');
            this.loadAlbumView();
        } else {
            OS.toast("No missing images found.", 'info');
        }
    },

    cleanup: function() {
        // Disconnect observer and clear cache when leaving gallery
        if (this._observer) {
            this._observer.disconnect();
            this._observer = null;
        }
        this._loadedImages.clear();
        this._registryCache = null;
    }
};

window.GalleryApp = GalleryApp;