/**
 * gallery.js
 * Standalone Media Gallery Module for Fancy AI OS
 * Uses IndexedDB (ImageDB) for persistent high-capacity storage.
 * Features multi-select, select all, and bulk delete.
 */

const GalleryApp = {
    container: null,
    selectedIds: new Set(),
    selectMode: false,
    // Touch gesture tracking parameters
    lightboxScale: 1,
    startX: 0,
    startY: 0,
    translateX: 0,
    translateY: 0,
    lastDist: 0,
    lastTap: 0,
    isClosingLightbox: false,

    /**
     * Entry point called by OS.launch
     */
    init: function(container) {
        this.container = container;
        this.selectedIds = new Set();
        this.selectMode = false;
        this.render();
        this.loadGalleryGrid();

        // Register popstate listener in the capture phase (true) to intercept before the parent shell can act
        window.removeEventListener('popstate', GalleryApp.handlePopState, true);
        window.addEventListener('popstate', GalleryApp.handlePopState, true);
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
            `;
            document.head.appendChild(style);
        }

        this.container.innerHTML = `
            <div class="gallery-wrap">
                <div class="gallery-header">
                    <div class="gallery-header-left">
                        <span style="font-weight: 700; font-size: 0.95rem; color: white;">Gallery</span>
                        <span id="galleryGridItemCount" class="gallery-counter">Images: 0</span>
                    </div>
                    <div style="display:flex; gap:6px; align-items:center;">
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
                <div id="mediaGridSlot" class="grid-container"></div>
            </div>
        `;
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
        const gridSlot = document.getElementById('mediaGridSlot');
        if (!gridSlot) return;
        const items = gridSlot.querySelectorAll('.grid-item');
        items.forEach(item => {
            const id = item.dataset.imgId;
            if (id) this.selectedIds.add(id);
            item.classList.add('selected');
        });
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
        if (!confirm(`Delete ${count} image${count > 1 ? 's' : ''}?`)) return;
        try {
            for (const id of this.selectedIds) {
                if (window.ImageDB) {
                    await window.ImageDB.delete(id);
                }
            }
            this.selectedIds.clear();
            this.updateBulkCount();
            this.updateDeleteBtn();
            this.loadGalleryGrid();
        } catch (err) {
            alert("Delete failed: " + err.message);
        }
    },

    loadGalleryGrid: async function() {
        const gridSlot = document.getElementById('mediaGridSlot');
        const countBadge = document.getElementById('galleryGridItemCount');
        if (!gridSlot) return;

        gridSlot.innerHTML = `<div style="grid-column: span 3; text-align: center; padding: 40px; color: var(--accent);">Loading Gallery...</div>`;

        try {
            // Wait for DB if it's still initializing
            if (window.ImageDB && typeof window.ImageDB.getAll === 'function') {
                const images = await window.ImageDB.getAll();
                
                if (!images || images.length === 0) {
                    countBadge.innerText = "Images: 0";
                    gridSlot.innerHTML = `<div style="grid-column: span 3; padding: 60px 20px; text-align: center; color: var(--text-muted); font-size: 0.88rem; font-style: italic;">No images saved yet.</div>`;
                    return;
                }

                countBadge.innerText = `Images: ${images.length}`;
                gridSlot.innerHTML = '';

                // Reverse to show newest first
                [...images].reverse().forEach(img => {
                    const item = document.createElement('div');
                    item.className = 'grid-item' + (this.selectedIds.has(img.id) ? ' selected' : '');
                    item.dataset.imgId = img.id;

                    if (this.selectMode) {
                        item.classList.add('selectable');
                        item.innerHTML = `
                            <div class="check-overlay">${this.selectedIds.has(img.id) ? '✓' : ''}</div>
                            <img src="${img.data}" alt="Gallery item">
                        `;
                        item.onclick = () => this.toggleSelect(img.id);
                    } else {
                        item.innerHTML = `
                            <button class="delete-thumbnail-btn" onclick="event.stopPropagation(); GalleryApp.purgeStoredImage('${img.id}')">✕</button>
                            <img src="${img.data}" alt="Gallery item">
                        `;
                        item.onclick = () => {
                            if (window.ImagingApp) {
                                window.ImagingApp.openLocalLightbox(img.data);
                            }
                        };
                    }
                    gridSlot.appendChild(item);
                });
            } else {
                throw new Error("ImageDB not initialized");
            }
        } catch(e) {
            console.error("Gallery Load Error:", e);
            gridSlot.innerHTML = `<div style="grid-column: span 3; text-align: center; color: var(--danger); padding:20px;">Storage Error: ${e.message}</div>`;
        }
    },

    purgeStoredImage: async function(imgId) {
        if (!confirm("Delete this image?")) return;
        try {
            if (window.ImageDB) {
                await window.ImageDB.delete(imgId);
                this.loadGalleryGrid();
            }
        } catch (err) {
            alert("Delete failed: " + err.message);
        }
    },

    handlePopState: function(e) {
        // Placeholder to prevent app exit if lightbox is open (handled by ImagingApp global lightbox)
    }
};

window.GalleryApp = GalleryApp;