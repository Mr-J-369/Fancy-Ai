/**
 * contacts.js
 * Contacts & Character Manager for Fancy AI OS
 * Clean list view with avatar, name, handle, and persona preview.
 */

const ContactsApp = {
    container: null,

    init: function(container) {
        this.container = container;
        this.injectStyles();
        this.renderList();
    },

    injectStyles: function() {
        const styleId = "contacts-app-style";
        if (document.getElementById(styleId)) return;
        const style = document.createElement('style');
        style.id = styleId;
        style.innerHTML = `
            /* ─── List View ─── */
            .contacts-wrap { height: 100%; display: flex; flex-direction: column; background: var(--md-surface); }
            .contacts-header {
                padding: 14px 16px;
                display: flex;
                justify-content: space-between;
                align-items: center;
                position: sticky;
                top: 0;
                z-index: 10;
                background: var(--md-surface-container-low);
                border-bottom: 1px solid var(--md-outline-variant);
                padding-top: calc(14px + env(safe-area-inset-top));
            }
            .contacts-title {
                font-size: 1.2rem;
                font-weight: 700;
                color: var(--md-on-surface);
            }
            .contacts-header-actions { display: flex; gap: 10px; align-items: center; }
            .btn-contacts-import {
                background: transparent;
                border: 1px solid var(--md-outline-variant);
                color: var(--md-on-surface-variant);
                padding: 8px 14px;
                border-radius: 20px;
                font-size: 0.75rem;
                font-weight: 600;
                cursor: pointer;
                transition: all 0.2s;
            }
            .btn-contacts-import:active {
                background: rgba(208,188,255,0.1);
                color: var(--md-primary);
            }
            .btn-contacts-new {
                background: var(--md-primary);
                border: none;
                color: var(--md-on-primary);
                padding: 8px 16px;
                border-radius: 20px;
                font-size: 0.75rem;
                font-weight: 600;
                cursor: pointer;
                box-shadow: 0 1px 3px rgba(0,0,0,0.3), 0 4px 8px rgba(0,0,0,0.2);
                transition: all 0.2s;
            }
            .btn-contacts-new:active {
                box-shadow: 0 6px 12px rgba(0,0,0,0.4);
                transform: scale(0.96);
            }

            .contacts-list { flex: 1; overflow-y: auto; padding: 8px 0; padding-bottom: 100px; }
            .char-item {
                display: flex;
                align-items: center;
                gap: 14px;
                padding: 14px 16px;
                cursor: pointer;
                transition: background 0.15s;
            }
            .char-item:active {
                background: rgba(208,188,255,0.08);
            }
            .char-item-avatar {
                width: 54px;
                height: 54px;
                border-radius: 50%;
                background: var(--md-surface-container-high);
                display: flex;
                align-items: center;
                justify-content: center;
                font-size: 1.4rem;
                font-weight: 700;
                color: var(--md-primary);
                overflow: hidden;
                flex-shrink: 0;
                box-shadow: 0 1px 3px rgba(0,0,0,0.3);
            }
            .char-item-avatar img {
                width: 100%;
                height: 100%;
                object-fit: cover;
            }
            .char-item-info { flex: 1; min-width: 0; }
            .char-item-name {
                font-weight: 600;
                color: var(--md-on-surface);
                font-size: 0.98rem;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
                margin-bottom: 4px;
            }
            .char-item-handle {
                font-size: 0.72rem;
                color: var(--md-primary);
                font-weight: 600;
                margin-bottom: 3px;
            }
            .char-item-preview {
                font-size: 0.75rem;
                color: var(--md-on-surface-variant);
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }
            .char-item-arrow {
                color: var(--md-outline-variant);
                font-size: 0.9rem;
                flex-shrink: 0;
                margin-left: 4px;
            }

            /* ─── Profile View ─── */
            .p-scroll {
                height: 100%;
                overflow-y: auto;
                background: var(--md-surface);
                padding-bottom: 100px;
            }

            .p-hero {
                position: relative;
                height: 180px;
                overflow: visible;
                display: flex;
                align-items: flex-end;
                justify-content: center;
                background: linear-gradient(135deg, rgba(79,55,139,0.3) 0%, rgba(51,46,81,0.25) 100%);
            }
            .p-hero-bg {
                position: absolute;
                inset: 0;
                filter: blur(20px) brightness(0.4);
                background-size: cover;
                background-position: center;
            }
            .p-hero-avatar-container {
                position: relative;
                display: flex;
                flex-direction: column;
                align-items: center;
                transform: translateY(48px);
            }
            .p-avatar-ring {
                width: 96px;
                height: 96px;
                border-radius: 50%;
                border: 3px solid var(--md-outline-variant);
                overflow: hidden;
                background: var(--md-surface-container-high);
                display: flex;
                align-items: center;
                justify-content: center;
                font-size: 2.4rem;
                font-weight: 700;
                color: var(--md-primary);
                box-shadow: 0 3px 6px rgba(0,0,0,0.4), 0 6px 12px rgba(0,0,0,0.3);
            }
            .p-avatar-ring img {
                width: 100%;
                height: 100%;
                object-fit: cover;
            }

            .p-body {
                padding: 56px 16px 16px;
                display: flex;
                flex-direction: column;
                gap: 16px;
            }
            .p-name-block {
                text-align: center;
                margin-bottom: 4px;
            }
            .p-name {
                font-size: 1.3rem;
                font-weight: 700;
                color: var(--md-on-surface);
                letter-spacing: 0.3px;
            }
            .p-handle {
                font-size: 0.8rem;
                color: var(--md-primary);
                font-weight: 600;
                margin-top: 4px;
                letter-spacing: 0.5px;
            }

            .btn-gen-avatar {
                background: var(--md-primary-container);
                color: var(--md-on-primary-container);
                border: none;
                padding: 10px 20px;
                border-radius: var(--shape-full);
                font-size: 0.8rem;
                font-weight: 600;
                cursor: pointer;
                align-self: center;
                box-shadow: 0 1px 3px rgba(0,0,0,0.3), 0 4px 8px rgba(0,0,0,0.2);
                transition: all 0.2s;
            }
            .btn-gen-avatar:active {
                box-shadow: 0 6px 12px rgba(0,0,0,0.4);
                transform: scale(0.96);
            }
            .btn-gen-avatar:disabled {
                opacity: 0.5;
                cursor: wait;
            }

            .p-section {
                background: var(--md-surface-container-low);
                border: 1px solid var(--md-outline-variant);
                border-radius: var(--shape-lg);
                overflow: hidden;
                box-shadow: 0 1px 3px rgba(0,0,0,0.2);
            }
            .p-section-row {
                padding: 16px;
                border-bottom: 1px solid var(--md-outline-variant);
                display: flex;
                flex-direction: column;
                gap: 8px;
            }
            .p-section-row:last-child {
                border-bottom: none;
            }
            .field-label {
                font-size: 0.65rem;
                color: var(--md-on-surface-variant);
                font-weight: 700;
                text-transform: uppercase;
                letter-spacing: 0.8px;
            }
            .field-input, .field-area {
                background: transparent;
                border: none;
                border-bottom: 2px solid var(--md-outline-variant);
                color: var(--md-on-surface);
                padding: 8px 0;
                outline: none;
                font-size: 0.92rem;
                font-family: inherit;
                width: 100%;
                transition: border-color 0.2s;
            }
            .field-input:focus, .field-area:focus {
                border-bottom-color: var(--md-primary);
            }
            .field-input::placeholder, .field-area::placeholder {
                color: var(--md-on-surface-variant);
                opacity: 0.7;
            }
            .field-area {
                resize: none;
                line-height: 1.6;
                min-height: 60px;
            }
            .toggle-row {
                display: flex;
                align-items: center;
                gap: 12px;
                padding: 14px 0;
            }
            .toggle-row input[type="checkbox"] {
                accent-color: var(--md-primary);
                cursor: pointer;
            }
            .toggle-row label {
                font-size: 0.88rem;
                color: var(--md-on-surface);
                font-weight: 500;
                cursor: pointer;
                flex: 1;
            }

            .p-action-btn-save {
                background: var(--md-primary);
                color: var(--md-on-primary);
                border: none;
                padding: 12px 24px;
                border-radius: var(--shape-lg);
                font-weight: 600;
                font-size: 0.85rem;
                cursor: pointer;
                transition: all 0.2s;
                box-shadow: 0 1px 3px rgba(0,0,0,0.3), 0 4px 8px rgba(0,0,0,0.2);
            }
            .p-action-btn-save:active {
                box-shadow: 0 6px 12px rgba(0,0,0,0.4);
                transform: scale(0.96);
            }

            .p-actions {
                display: flex;
                gap: 10px;
                margin-top: 8px;
            }
            .p-action-btn {
                border: 1px solid var(--md-outline-variant);
                background: transparent;
                color: var(--md-on-surface);
                padding: 12px 16px;
                border-radius: var(--shape-lg);
                font-weight: 600;
                font-size: 0.8rem;
                cursor: pointer;
                flex: 1;
                transition: all 0.2s;
            }
            .p-action-btn:active {
                background: rgba(208,188,255,0.1);
                border-color: var(--md-primary);
                color: var(--md-primary);
            }
            .btn-p-delete {
                flex: 0 0 auto !important;
                padding: 12px 14px !important;
                border-color: var(--md-error) !important;
                color: var(--md-error) !important;
            }
            .btn-p-delete:active {
                background: rgba(242,184,181,0.15) !important;
            }
        `;
        document.head.appendChild(style);
    },

    renderList: function() {
        this.container.innerHTML = `
            <div class="contacts-wrap">
                <div class="contacts-header">
                    <div class="contacts-title">Characters</div>
                    <div class="contacts-header-actions">
                        <label class="btn-contacts-import">
                            📥 Import
                            <input type="file" style="display:none" accept=".json,.png" onchange="ContactsApp.importCharacter(event)">
                        </label>
                        <button class="btn-contacts-new" onclick="ContactsApp.newCharacter()">+ New</button>
                    </div>
                </div>
                <div class="contacts-list" id="contactsList"></div>
            </div>
        `;

        const list = document.getElementById('contactsList');

        for (const char of State.characters) {
            const item = document.createElement('div');
            item.className = 'char-item';
            item.onclick = () => this.showProfile(char.id);

            const avId = `char-av-${char.id}`;
            const preview = char.persona ? char.persona.substring(0, 55) + (char.persona.length > 55 ? '…' : '') : 'No persona defined';

            item.innerHTML = `
                <div class="char-item-avatar" id="${avId}">${char.name[0]}</div>
                <div class="char-item-info">
                    <div class="char-item-name">${char.name}</div>
                    <div class="char-item-handle">${char.handle || '@ai'}</div>
                    <div class="char-item-preview">${preview}</div>
                </div>
                <div class="char-item-arrow">›</div>
            `;
            list.appendChild(item);

            if (char.avatar) {
                (async () => {
                    let src = char.avatar;
                    if (src.startsWith('db:') && window.ImageDB) src = await window.ImageDB.get(src);
                    const el = document.getElementById(avId);
                    if (el && src) el.innerHTML = `<img src="${src}">`;
                })();
            }
        }
    },

    showProfile: async function(charId, restoreFn) {
        const char = State.characters.find(c => c.id === charId);
        if (!char) return;

        if (window.OS) {
            const oldTitle = document.getElementById('os-app-title').innerText;
            document.getElementById('os-app-title').innerText = char.name;
            // When opened from the chat hub, restoreFn re-renders that chat on back.
            OS.pushView(restoreFn ? restoreFn : () => {
                document.getElementById('os-app-title').innerText = oldTitle;
                this.renderList();
            });
        }

        this.container.innerHTML = `
            <div class="p-scroll">
                <div class="p-hero">
                    <div class="p-hero-bg" id="pHeroBg"></div>
                    <div class="p-hero-avatar-container">
                        <div class="p-avatar-ring" id="pAvatarRing">${char.name[0]}</div>
                    </div>
                </div>

                <div class="p-body">
                    <div class="p-name-block">
                        <div class="p-name">${char.name}</div>
                        <div class="p-handle">${char.handle || '@ai'}</div>
                    </div>

                    <button class="btn-gen-avatar" id="btnGenAvatar" onclick="ContactsApp.generateAvatar('${char.id}')">✨ Generate Avatar</button>

                    <div class="p-section">
                        <div class="p-section-row">
                            <div class="field-label">Display Name</div>
                            <input type="text" id="editName" class="field-input" value="${char.name}" placeholder="Character name">
                        </div>
                        <div class="p-section-row">
                            <div class="field-label">Social Handle</div>
                            <input type="text" id="editHandle" class="field-input" value="${char.handle || ''}" placeholder="@handle">
                        </div>
                        <div class="p-section-row">
                            <div class="field-label">Short Bio</div>
                            <textarea id="editBio" class="field-area" rows="2" placeholder="What others see on social feeds...">${char.bio || ''}</textarea>
                        </div>
                        <div class="p-section-row">
                            <div class="field-label">Persona / AI Behavior</div>
                            <textarea id="editPersona" class="field-area" rows="5" placeholder="Describe personality, traits, speaking style...">${char.persona || ''}</textarea>
                        </div>
                        <div class="toggle-row">
                            <input type="checkbox" id="editEnableRebbit" ${char.enableRebbit !== false ? 'checked' : ''} style="width:17px; height:17px; accent-color:#ff4500; flex-shrink:0;">
                            <label for="editEnableRebbit">Allow Rebbit Posts</label>
                        </div>
                    </div>

                    <button class="p-action-btn-save" onclick="ContactsApp.save('${char.id}')">💾 Save Identity</button>

                    <div class="p-actions">
                        <button class="p-action-btn" onclick="OS.launch('MessengerApp', { charId: '${char.id}' })">💬 Chat</button>
                        <button class="p-action-btn" onclick="ContactsApp.showDossier('${char.id}')">📂 Dossier</button>
                        ${char.id !== 'root' ? `<button class="p-action-btn btn-p-delete" onclick="ContactsApp.delete('${char.id}')">🗑️</button>` : ''}
                    </div>
                </div>
            </div>
        `;

        if (char.avatar) {
            let src = char.avatar;
            if (src.startsWith('db:') && window.ImageDB) src = await window.ImageDB.get(src);
            if (src) {
                const ring = document.getElementById('pAvatarRing');
                const heroBg = document.getElementById('pHeroBg');
                if (ring) ring.innerHTML = `<img src="${src}">`;
                if (heroBg) heroBg.style.backgroundImage = `url(${src})`;
            }
        }
    },

    generateAvatar: async function(charId) {
        const char = State.characters.find(c => c.id === charId);
        if (!char) return;
        if (window.OS && OS.guardBusy("⏳ Please wait — a task is still running.")) return;

        const btn = document.getElementById('btnGenAvatar');
        const ring = document.getElementById('pAvatarRing');
        const heroBg = document.getElementById('pHeroBg');

        btn.disabled = true;
        btn.innerText = "⏳ Generating...";

        try {
            // Build a descriptive prompt from available character info
            const personaSnippet = (char.persona || '').substring(0, 120);
            const prompt = `Portrait photo of a person. ${personaSnippet ? 'Personality: ' + personaSnippet + '.' : ''} ${char.bio ? char.bio + '.' : ''} Cinematic lighting, detailed face, photorealistic, sharp focus, professional photography.`;

            if (!window.ImagingApp) throw new Error("Imaging module not loaded.");
            window.ImagingApp.attachedImage = null;

            const img = await window.ImagingApp.generate(prompt);
            if (!img) throw new Error("No image returned.");

            const dbId = `avatar_${charId}_${Date.now()}`;
            if (window.ImageDB) {
                await window.ImageDB.save(dbId, img);
                char.avatar = `db:${dbId}`;
            } else {
                char.avatar = img;
            }

            if (ring) ring.innerHTML = `<img src="${img}">`;
            if (heroBg) heroBg.style.backgroundImage = `url(${img})`;
            State.save();
            OS.toast("Avatar generated!", 'success');
        } catch (e) {
            OS.toast("Avatar failed: " + e.message, 'error');
        } finally {
            btn.disabled = false;
            btn.innerText = "✨ Generate Avatar";
        }
    },

    save: function(charId) {
        const char = State.characters.find(c => c.id === charId);
        if (!char) return;

        char.name = document.getElementById('editName').value.trim() || char.name;
        char.handle = document.getElementById('editHandle').value.trim();
        char.bio = document.getElementById('editBio').value;
        char.persona = document.getElementById('editPersona').value;
        char.enableRebbit = document.getElementById('editEnableRebbit').checked;

        State.save();
        OS.toast("Character updated!", 'success');
        OS.goBack();
    },

    importCharacter: function(event, onDone) {
        const file = event.target.files[0];
        if (!file) return;

        const reader = new FileReader();

        if (file.name.endsWith('.png')) {
            reader.onload = async (e) => {
                try {
                    const buffer = e.target.result;
                    const view = new DataView(buffer);

                    if (view.getUint32(0) !== 0x89504E47 || view.getUint32(4) !== 0x0D0A1A0A) {
                        throw new Error("Not a valid PNG file");
                    }

                    let offset = 8;
                    let charData = null;

                    while (offset < buffer.byteLength) {
                        const length = view.getUint32(offset);
                        const type = Array.from(new Uint8Array(buffer, offset + 4, 4)).map(b => String.fromCharCode(b)).join('');

                        if (type === 'chara' || type === 'tEXt') {
                            const data = new Uint8Array(buffer, offset + 8, length);
                            const text = new TextDecoder().decode(data);

                            if (type === 'chara') {
                                try { charData = JSON.parse(atob(text)); } catch { charData = JSON.parse(text); }
                            } else if (type === 'tEXt' && text.startsWith('chara\0')) {
                                try { charData = JSON.parse(atob(text.substring(6))); } catch { charData = JSON.parse(text.substring(6)); }
                            }
                        }

                        if (charData) break;
                        offset += length + 12;
                    }

                    if (!charData) throw new Error("No character metadata found in PNG");

                    const name = charData.name || charData.data?.name || "Imported Card";
                    const persona = charData.description || charData.data?.description || "";
                    const bio = charData.personality || charData.data?.personality || "";
                    const firstMsg = charData.first_mes || charData.data?.first_mes || "";

                    const avatarB64 = await new Promise(resolve => {
                        const imgReader = new FileReader();
                        imgReader.onload = (ev) => resolve(ev.target.result);
                        imgReader.readAsDataURL(file);
                    });

                    const id = 'c' + Date.now();
                    const newChar = {
                        id, name,
                        handle: '@' + name.toLowerCase().replace(/\s/g, ''),
                        bio, persona, enableRebbit: true, virtual_gallery: []
                    };

                    if (avatarB64) {
                        const dbId = `avatar_${id}`;
                        await window.ImageDB.save(dbId, avatarB64);
                        newChar.avatar = `db:${dbId}`;
                    }

                    State.characters.push(newChar);

                    if (firstMsg) {
                        if (!State.sessions) State.sessions = {};
                        State.sessions[id] = [{ id: 'm' + Date.now(), sender: 'ai', text: firstMsg, timestamp: Date.now() }];
                    }

                    State.save();
                    OS.toast(`Imported ${name}!`, 'success');
                    if (onDone) onDone(); else this.renderList();
                } catch (err) {
                    console.error("PNG import failed:", err);
                    OS.toast("Import failed: " + err.message, 'error');
                }
            };
            reader.readAsArrayBuffer(file);
        } else {
            reader.onload = async (e) => {
                try {
                    const data = JSON.parse(e.target.result);
                    const name = data.name || data.char_name || "Imported AI";
                    const persona = data.description || data.persona || data.char_persona || "";
                    const bio = data.personality || data.scenario || "";
                    const firstMsg = data.first_mes || data.first_message || "";
                    const avatar = data.avatar || "";

                    const id = 'c' + Date.now();
                    const newChar = {
                        id, name,
                        handle: '@' + name.toLowerCase().replace(/\s/g, ''),
                        bio, persona, enableRebbit: true, virtual_gallery: []
                    };

                    if (avatar && avatar.startsWith('data:image')) {
                        const dbId = `avatar_${id}`;
                        await window.ImageDB.save(dbId, avatar);
                        newChar.avatar = `db:${dbId}`;
                    }

                    State.characters.push(newChar);

                    if (firstMsg) {
                        if (!State.sessions) State.sessions = {};
                        State.sessions[id] = [{ id: 'm' + Date.now(), sender: 'ai', text: firstMsg, timestamp: Date.now() }];
                    }

                    State.save();
                    OS.toast(`Imported ${name}!`, 'success');
                    if (onDone) onDone(); else this.renderList();
                } catch (err) {
                    console.error("JSON import failed:", err);
                    OS.toast("Import failed: invalid JSON", 'error');
                }
            };
            reader.readAsText(file);
        }
    },

    newCharacter: function(onDone) {
        OS.prompt("Character Name:", "", (name) => {
            if (!name || !name.trim()) return;
            const id = 'c' + Date.now();
            State.characters.push({
                id,
                name: name.trim(),
                handle: '@' + name.trim().toLowerCase().replace(/\s/g, ''),
                bio: '',
                persona: '',
                enableRebbit: true,
                virtual_gallery: []
            });
            State.save();
            if (onDone) onDone(); else this.renderList();
        });
    },

    delete: function(charId) {
        if (charId === 'root') { OS.toast("Root doesn't leave.", 'warning'); return; }
        if (State.characters.length <= 1) { OS.toast("Must have at least one character.", 'warning'); return; }
        OS.confirm("Delete this character? Their avatar and session images will also be removed.", () => {
            const char = State.characters.find(c => c.id === charId);
            if (char) {
                if (char.avatar && char.avatar.startsWith('db:') && window.ImageDB) {
                    window.ImageDB.delete(char.avatar.replace('db:', ''));
                }
                const session = State.sessions[charId] || [];
                session.forEach(msg => {
                    if (msg.type === 'image' && msg.text && msg.text.startsWith('db:') && window.ImageDB) {
                        window.ImageDB.delete(msg.text.replace('db:', ''));
                    }
                    if (msg.type === 'img2img' && window.ImageDB) {
                        if (msg.source && msg.source.startsWith('db:')) window.ImageDB.delete(msg.source.replace('db:', ''));
                        if (msg.text && msg.text.startsWith('db:')) window.ImageDB.delete(msg.text.replace('db:', ''));
                    }
                });
                [State.instagramPosts, State.redditPosts, State.xPosts].forEach(posts => {
                    if (!posts) return;
                    for (let i = posts.length - 1; i >= 0; i--) {
                        if (posts[i].charId === charId) {
                            if (posts[i].image && posts[i].image.startsWith('db:') && window.ImageDB) {
                                window.ImageDB.delete(posts[i].image.replace('db:', ''));
                            }
                            posts.splice(i, 1);
                        }
                    }
                });
            }

            State.characters = State.characters.filter(c => c.id !== charId);
            delete State.sessions[charId];
            if (State.dossiers) delete State.dossiers[charId];
            State.save();
            // The chat/profile we came from now points at a deleted character.
            // Unwind every pushed view back to the contact list in one step
            // (the list's restore runs last, so that's what stays on screen).
            if (OS.navStack && OS.navStack.length > 0 && window.MessengerApp && OS.activeApp === 'MessengerApp') {
                history.go(-OS.navStack.length);
            } else {
                OS.goBack();
            }
        });
    },

    showDossier: function(charId) {
        const char = State.characters.find(c => c.id === charId);
        const dossier = State.getDossier(charId);

        const existing = document.getElementById('dossierOverlay');
        if (existing) existing.remove();

        const overlay = document.createElement('div');
        overlay.id = 'dossierOverlay';
        overlay.style.cssText = 'position:fixed; top:0; left:0; right:0; bottom:0; z-index:99998; display:flex; align-items:flex-start; justify-content:center; padding-top:60px; background:rgba(0,0,0,0.6); backdrop-filter:blur(10px);';
        overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };

        const sheet = document.createElement('div');
        sheet.style.cssText = 'background:var(--md-surface-container-high); border-radius:var(--shape-xl); padding:20px; width:90%; max-width:420px; box-shadow:0 3px 6px rgba(0,0,0,0.4), 0 6px 12px rgba(0,0,0,0.3); border:1px solid var(--md-outline-variant); max-height:80vh; overflow-y:auto;';
        sheet.onclick = (e) => e.stopPropagation();

        const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
        const renderSection = (title, data) => {
            let section = `<div style="margin-bottom:16px;"><div style="color:var(--md-on-surface-variant); font-size:0.65rem; font-weight:700; text-transform:uppercase; letter-spacing:0.8px; margin-bottom:8px;">${title}</div>`;
            if (typeof data === 'object' && !Array.isArray(data)) {
                for (const key in data) {
                    section += `<div style="display:flex; gap:10px; margin-bottom:6px; font-size:0.8rem;"><span style="color:var(--md-primary); flex-shrink:0;">${esc(key.replace(/_/g, ' '))}:</span><span style="color:var(--md-on-surface);">${esc(data[key])}</span></div>`;
                }
            } else if (Array.isArray(data)) {
                data.forEach(item => { section += `<div style="color:var(--md-on-surface); font-size:0.8rem; margin-bottom:4px;">• ${esc(item)}</div>`; });
            } else {
                section += `<div style="color:var(--md-on-surface); font-size:0.8rem;">${esc(data)}</div>`;
            }
            return section + '</div>';
        };

        let html = `<div style="color:var(--md-primary); font-size:1.05rem; font-weight:700; margin-bottom:18px; padding-bottom:12px; border-bottom:1px solid var(--md-outline-variant);">📂 ${char.name.toUpperCase()}'S DOSSIER</div>`;
        html += renderSection("Relationship Status", dossier.relationship || "Stranger");
        if (dossier.user_traits && Object.keys(dossier.user_traits).length) html += renderSection("User Knowledge", dossier.user_traits);
        if (dossier.world_facts && Object.keys(dossier.world_facts).length) html += renderSection("World Intelligence", dossier.world_facts);
        if (dossier.milestones && dossier.milestones.length) html += renderSection("Timeline Milestones", dossier.milestones);
        html += `<button style="width:100%; margin-top:12px; padding:12px; background:transparent; border:1px solid var(--md-outline-variant); color:var(--md-on-surface); border-radius:var(--shape-lg); font-weight:600; cursor:pointer; font-size:0.8rem;" onclick="document.getElementById('dossierOverlay').remove()">CLOSE</button>`;

        sheet.innerHTML = html;
        overlay.appendChild(sheet);
        document.body.appendChild(overlay);
    }
};

window.ContactsApp = ContactsApp;
