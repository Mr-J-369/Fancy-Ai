/**
 * contacts.js
 * Comprehensive Contacts & Character Manager for Fancy AI OS
 * Modern Grid Layout with Avatar Generation and Persistent Native Storage.
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
            .contacts-wrap {
                height: 100%;
                display: flex;
                flex-direction: column;
                background: #0a0a0b;
                overflow-y: auto;
                padding-bottom: 100px;
            }
            .contacts-header {
                padding: 16px 20px;
                display: flex;
                justify-content: space-between;
                align-items: center;
                background: rgba(10,10,11,0.9);
                backdrop-filter: blur(10px);
                position: sticky; top: 0; z-index: 10;
            }
            .contacts-header h2 {
                margin: 0; font-size: 1.2rem; font-weight: 800; color: white;
            }
            .contacts-add-btn {
                background: var(--accent); color: white; border: none;
                padding: 8px 16px; border-radius: 20px; font-weight: 700; font-size: 0.8rem;
                box-shadow: 0 4px 12px rgba(139,92,246,0.3);
            }

            /* GRID LAYOUT */
            .contacts-grid {
                display: grid;
                grid-template-columns: repeat(2, 1fr);
                gap: 12px;
                padding: 12px;
            }
            .char-card {
                background: var(--bg-card);
                border-radius: 24px;
                overflow: hidden;
                border: 1px solid var(--border);
                aspect-ratio: 1/1.35;
                position: relative;
                display: flex;
                flex-direction: column;
                cursor: pointer;
                transition: transform 0.2s;
            }
            .char-card:active { transform: scale(0.96); }
            
            .char-card-img {
                width: 100%;
                flex: 1;
                background: linear-gradient(135deg, #1e1b4b, #312e81);
                display: flex;
                align-items: center;
                justify-content: center;
                font-size: 3.5rem;
                font-weight: 800;
                color: rgba(255,255,255,0.1);
                position: relative;
                overflow: hidden;
            }
            .char-card-img img { width: 100%; height: 100%; object-fit: cover; }
            
            .char-card-overlay {
                position: absolute; bottom: 0; left: 0; right: 0;
                background: linear-gradient(transparent, rgba(0,0,0,0.95) 60%);
                padding: 20px 12px 10px;
                display: flex;
                flex-direction: column; gap: 1px;
                min-height: 0;
            }
            .char-card-name { font-weight: 800; color: white; font-size: 0.9rem; text-shadow: 0 2px 4px rgba(0,0,0,0.5); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
            .char-card-handle { font-size: 0.7rem; color: var(--accent); font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

            /* PROFILE VIEW */
            .p-view-header {
                padding: 12px 16px;
                display: flex;
                align-items: center;
                justify-content: space-between;
                border-bottom: 1px solid var(--border);
                background: rgba(10,10,11,0.5);
            }
            .p-view-back { background: none; border: none; color: var(--accent); font-size: 1.5rem; font-weight: bold; cursor: pointer; padding: 0 10px; }
            .p-view-title { font-weight: 800; font-size: 1rem; color: white; flex: 1; text-align: center; margin-right: 40px; }

            .p-content { padding: 20px; display: flex; flex-direction: column; gap: 20px; overflow-y: auto; height: 100%; }
            
            .p-avatar-box {
                width: 140px; height: 140px; border-radius: 40px;
                background: var(--bg-card); border: 2px solid var(--border);
                margin: 0 auto; overflow: hidden; display: flex;
                align-items: center; justify-content: center; font-size: 4rem;
                position: relative; box-shadow: 0 10px 30px rgba(0,0,0,0.5);
            }
            .p-avatar-box img { width: 100%; height: 100%; object-fit: cover; }
            
            .btn-gen-avatar {
                margin: -10px auto 0;
                background: linear-gradient(135deg, #8b5cf6, #6366f1);
                color: white; border: none;
                padding: 10px 20px; border-radius: 15px;
                font-size: 0.8rem; font-weight: 800; cursor: pointer;
                box-shadow: 0 4px 15px rgba(139,92,246,0.4);
            }
            .btn-gen-avatar:disabled { opacity: 0.6; cursor: wait; }

            .field-group { display: flex; flex-direction: column; gap: 6px; }
            .field-group label { font-size: 0.7rem; color: var(--text-muted); font-weight: 800; text-transform: uppercase; letter-spacing: 1px; }
            .field-input, .field-area {
                background: var(--bg-input); border: 1px solid var(--border);
                color: white; padding: 14px; border-radius: 16px; outline: none;
                font-size: 0.95rem; font-family: inherit;
            }
            .field-area { resize: none; line-height: 1.5; }
            
            .btn-save {
                background: var(--accent); color: white; border: none;
                padding: 16px; border-radius: 20px; font-weight: 800; font-size: 1rem;
                margin-top: 10px; box-shadow: 0 4px 12px rgba(139,92,246,0.3);
            }
            .btn-chat {
                background: rgba(255,255,255,0.05); color: white; border: 1px solid var(--border);
                padding: 14px; border-radius: 20px; font-weight: 700; flex: 1;
            }
            .btn-delete {
                background: rgba(239,68,68,0.1); border: 1px solid rgba(239,68,68,0.2);
                color: #ef4444; padding: 14px; border-radius: 20px; font-weight: 700; flex: 1;
            }
        `;
        document.head.appendChild(style);
    },

    renderList: function() {
        this.container.innerHTML = `
            <div class="contacts-wrap">
                <div class="contacts-header">
                    <h2>Characters</h2>
                    <button class="contacts-add-btn" onclick="ContactsApp.newCharacter()">+ New AI</button>
                </div>
                <div class="contacts-grid" id="contactsGrid"></div>
            </div>
        `;
        const grid = document.getElementById('contactsGrid');
        
        // Use for...of for cleaner async handling in loop
        for (const char of State.characters) {
            const card = document.createElement('div');
            card.className = 'char-card';
            card.onclick = () => this.showProfile(char.id);
            
            const cardImgId = `card-img-${char.id}`;
            card.innerHTML = `
                <div class="char-card-img" id="${cardImgId}">
                    ${char.name[0]}
                </div>
                <div class="char-card-overlay">
                    <div class="char-card-name">${char.name}</div>
                    <div class="char-card-handle">${char.handle || '@ai'}</div>
                </div>
            `;
            grid.appendChild(card);
            
            // Async resolve avatar from Native Storage
            if (char.avatar) {
                (async () => {
                    let src = char.avatar;
                    if (src.startsWith('db:') && window.ImageDB) {
                        src = await window.ImageDB.get(src);
                    }
                    const imgEl = document.getElementById(cardImgId);
                    if (imgEl && src) imgEl.innerHTML = `<img src="${src}">`;
                })();
            }
        }
    },

    showProfile: async function(charId) {
        const char = State.characters.find(c => c.id === charId);
        if (!char) return;

        OS.pushView(() => this.renderList());

        this.container.innerHTML = `
            <div class="p-view-header">
                <button class="p-view-back" onclick="OS.goBack()">‹</button>
                <div class="p-view-title">Profile Settings</div>
            </div>
            <div class="p-content">
                <div class="p-avatar-box" id="pAvatarBox">
                    ${char.name[0]}
                </div>
                <button class="btn-gen-avatar" id="btnGenAvatar" onclick="ContactsApp.generateAvatar('${char.id}')">✨ Generate Profile Picture</button>
                
                <div class="field-group">
                    <label>Display Name</label>
                    <input type="text" id="editName" class="field-input" value="${char.name}">
                </div>
                <div class="field-group">
                    <label>Social Handle</label>
                    <input type="text" id="editHandle" class="field-input" value="${char.handle || ''}">
                </div>
                <div class="field-group">
                    <label>Short Bio (Public info)</label>
                    <textarea id="editBio" class="field-area" rows="2" placeholder="What others see on social feeds...">${char.bio || ''}</textarea>
                </div>
                <div class="field-group">
                    <label>Persona (AI Identity & Behavior)</label>
                    <textarea id="editPersona" class="field-area" rows="5" placeholder="System directives for chat behavior...">${char.persona || ''}</textarea>
                </div>

                <div style="display:flex; align-items:center; gap:12px; padding:8px 0;">
                    <label style="position:relative; display:inline-flex; align-items:center; gap:10px; cursor:pointer;">
                        <input type="checkbox" id="editEnableRebbit" ${char.enableRebbit !== false ? 'checked' : ''} style="width:18px;height:18px;accent-color:#ff4500;">
                        <span style="font-size:0.85rem; color:var(--text-muted); font-weight:600;">Allow Rebbit Posts</span>
                    </label>
                </div>

                <button class="btn-save" onclick="ContactsApp.save('${char.id}')">💾 Save Identity</button>
                
                <div style="display:flex; gap:12px; margin-top:10px;">
                    <button class="btn-chat" onclick="OS.launch('MessengerApp', { charId: '${char.id}' })">💬 Message</button>
                    <button class="btn-delete" onclick="ContactsApp.delete('${char.id}')">🗑️ Delete</button>
                </div>
            </div>
        `;

        // Async resolve avatar from Native Storage
        if (char.avatar) {
            let src = char.avatar;
            if (src.startsWith('db:') && window.ImageDB) {
                src = await window.ImageDB.get(src);
            }
            const box = document.getElementById('pAvatarBox');
            if (box && src) box.innerHTML = `<img src="${src}">`;
        }
    },

    generateAvatar: async function(charId) {
        const char = State.characters.find(c => c.id === charId);
        if (!char) return;

        const btn = document.getElementById('btnGenAvatar');
        const box = document.getElementById('pAvatarBox');
        
        btn.disabled = true;
        btn.innerText = "⏳ Dreaming Profile...";
        
        try {
            const prompt = `Professional profile photo of ${char.name}. Identity: ${char.persona}. Bio: ${char.bio || 'beautiful person'}. Cinematic lighting, detailed facial features, realistic photography, sharp focus.`;
            
            if (window.ImagingApp) {
                const img = await window.ImagingApp.generate(prompt);
                if (img) {
                    if (window.ImageDB) {
                        const dbId = `avatar_${char.id}`;
                        await window.ImageDB.save(dbId, img);
                        char.avatar = `db:${dbId}`;
                    } else {
                        char.avatar = img;
                    }
                    box.innerHTML = `<img src="${img}">`;
                    State.save();
                    // Don't call renderList here, it will overwrite the profile view if we are still in it.
                    // Just update the State.
                }
            } else {
                alert("Imaging module not initialized.");
            }
        } catch (e) {
            alert("Avatar generation failed: " + e.message);
        } finally {
            btn.disabled = false;
            btn.innerText = "✨ Generate Profile Picture";
        }
    },

    save: function(charId) {
        const char = State.characters.find(c => c.id === charId);
        if (!char) return;

        char.name = document.getElementById('editName').value;
        char.handle = document.getElementById('editHandle').value;
        char.bio = document.getElementById('editBio').value;
        char.persona = document.getElementById('editPersona').value;
        char.enableRebbit = document.getElementById('editEnableRebbit').checked;

        State.save();
        alert("Character updated!");
        OS.goBack();
    },

    newCharacter: function() {
        const name = prompt("Character Name:");
        if (name) {
            const id = 'c' + Date.now();
            State.characters.push({
                id: id,
                name: name,
                handle: '@' + name.toLowerCase().replace(/\s/g, ''),
                bio: '',
                persona: '',
                enableRebbit: true,
                virtual_gallery: []
            });
            State.save();
            this.renderList();
        }
    },

    delete: function(charId) {
        if (State.characters.length <= 1) return alert("Must have at least one character.");
        if (!confirm("Delete this character? Their avatar, session images, and social feed posts will also be removed.")) return;

        // Clean up associated images
        const char = State.characters.find(c => c.id === charId);
        if (char) {
            // Delete avatar from storage
            if (char.avatar && char.avatar.startsWith('db:') && window.ImageDB) {
                window.ImageDB.delete(char.avatar.replace('db:', ''));
            }
            // Delete session images
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
            // Delete social feed posts images
            [State.instagramPosts, State.redditPosts, State.xPosts].forEach(posts => {
                if (posts) {
                    for (let i = posts.length - 1; i >= 0; i--) {
                        if (posts[i].charId === charId) {
                            if (posts[i].image && posts[i].image.startsWith('db:') && window.ImageDB) {
                                window.ImageDB.delete(posts[i].image.replace('db:', ''));
                            }
                            posts.splice(i, 1);
                        }
                    }
                }
            });
        }

        State.characters = State.characters.filter(c => c.id !== charId);
        delete State.sessions[charId];
        State.save();
        OS.goBack();
    }
};

window.ContactsApp = ContactsApp;
