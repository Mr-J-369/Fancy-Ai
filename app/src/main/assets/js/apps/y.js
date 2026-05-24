/**
 * y.js
 * Y (Twitter-style) feed — bots post text-only statuses and interact.
 */
const YApp = {
    container: null,

    init: function(container) {
        this.container = container;
        this.render();
        this.loadPosts();
    },

    render: function() {
        const styleId = "y-app-style";
        if (!document.getElementById(styleId)) {
            const style = document.createElement('style');
            style.id = styleId;
            style.innerHTML = `
                .y-wrap { padding: 0; overflow-y: auto; height: 100%; background: #0a0a0b; display: flex; flex-direction: column; padding-bottom: 100px; }
                .y-header { background: rgba(29,161,242,0.08); padding: 12px 16px; border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 8px; }
                .y-header h2 { margin: 0; font-size: 1.1rem; font-weight: 800; color: #1da1f2; }
                .y-controls { padding: 10px 16px; display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; background: rgba(255,255,255,0.02); border-bottom: 1px solid var(--border); position: sticky; top: 0; z-index: 10; }
                .y-btn-gen { padding: 10px 20px; font-size: 0.8rem; background: #1da1f2; color: white; border: none; border-radius: 20px; font-weight: 700; cursor: pointer; box-shadow: 0 4px 12px rgba(29,161,242,0.3); transition: all 0.2s; }
                .y-btn-gen:disabled { opacity: 0.5; cursor: not-allowed; filter: grayscale(0.5); }
                .y-btn-clear { padding: 10px 20px; font-size: 0.8rem; background: rgba(239,68,68,0.15); color: #ef4444; border: 1px solid rgba(239,68,68,0.3); border-radius: 20px; font-weight: 700; cursor: pointer; }
                .y-tweet { padding: 14px 16px; border-bottom: 1px solid var(--border); animation: postFadeIn 0.3s ease; }
                .y-tweet-header { display: flex; align-items: flex-start; gap: 10px; }
                .y-tweet-avatar { width: 40px; height: 40px; border-radius: 50%; background: #1da1f2; color: white; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 0.95rem; flex-shrink: 0; overflow: hidden; }
                .y-tweet-avatar img { width: 100%; height: 100%; object-fit: cover; }
                .y-tweet-body { flex: 1; min-width: 0; }
                .y-tweet-name { font-weight: 700; color: white; font-size: 0.88rem; }
                .y-tweet-handle { font-size: 0.75rem; color: var(--text-muted); margin-left: 4px; }
                .y-tweet-text { font-size: 0.92rem; color: #e7e9ea; line-height: 1.45; margin-top: 2px; white-space: pre-wrap; }
                .y-tweet-time { font-size: 0.72rem; color: var(--text-muted); margin-top: 4px; }
                .y-tweet-reply { margin-left: 50px; margin-top: 10px; padding: 10px 14px; background: rgba(255,255,255,0.02); border-radius: 12px; border-left: 2px solid #1da1f2; }
                @keyframes postFadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
            `;
            document.head.appendChild(style);
        }
        this.container.innerHTML = `
            <div class="y-wrap">
                <div class="y-header"><h2>Y</h2></div>
                <div class="y-controls">
                    <button class="y-btn-gen" id="yBtnGen" onclick="YApp.generatePost()">🐦 New Post</button>
                    <button class="y-btn-clear" onclick="YApp.clearAll()">🗑️ Clear</button>
                </div>
                <div id="yPosts"></div>
            </div>
        `;
    },

    loadPosts: async function() {
        const el = document.getElementById('yPosts');
        if (!el) return;
        const posts = State.xPosts || [];
        if (posts.length === 0) {
            el.innerHTML = `<div style="padding:80px 30px; text-align:center; color:var(--text-muted); font-style:italic; font-size:0.9rem;">No posts yet.<br>Tap 🐦 New Post to start!</div>`;
            return;
        }

        el.innerHTML = "";
        const reversedPosts = [...posts].reverse();
        
        for (const p of reversedPosts) {
            const tweetDiv = document.createElement('div');
            tweetDiv.className = 'y-tweet';
            const avId = `y-av-${p.id}`;
            
            tweetDiv.innerHTML = `
                <div class="y-tweet-header">
                    <div class="y-tweet-avatar" id="${avId}">${(p.charName||'B')[0]}</div>
                    <div class="y-tweet-body">
                        <div><span class="y-tweet-name">${p.charName||'Bot'}</span><span class="y-tweet-handle">@${(p.charName||'bot').toLowerCase().replace(/\s/g,'')}</span></div>
                        <div class="y-tweet-text">${OS.formatMarkdown(p.text)}</div>
                        <div class="y-tweet-time">${this.formatTime(p.timestamp)}</div>
                    </div>
                </div>
                <div id="replies-${p.id}"></div>
            `;
            el.appendChild(tweetDiv);

            // Resolve Avatar
            const char = State.characters.find(c => c.id === p.charId);
            if (char && char.avatar) {
                this.resolveAvatar(char.avatar, avId);
            }

            // Render Replies
            const repliesContainer = document.getElementById(`replies-${p.id}`);
            if (p.replies && p.replies.length > 0) {
                for (const r of p.replies) {
                    const rDiv = document.createElement('div');
                    rDiv.className = 'y-tweet-reply';
                    const rAvId = `y-av-reply-${p.id}-${Math.random().toString(36).substr(2, 9)}`;
                    rDiv.innerHTML = `
                        <div class="y-tweet-header">
                            <div class="y-tweet-avatar" id="${rAvId}" style="width:28px;height:28px;font-size:0.7rem;">${(r.charName||'B')[0]}</div>
                            <div class="y-tweet-body">
                                <div><span class="y-tweet-name">${r.charName||'Bot'}</span><span class="y-tweet-handle">@${(r.charName||'bot').toLowerCase().replace(/\s/g,'')}</span></div>
                                <div class="y-tweet-text">${OS.formatMarkdown(r.text)}</div>
                            </div>
                        </div>
                    `;
                    repliesContainer.appendChild(rDiv);
                    
                    const rChar = State.characters.find(c => c.id === r.charId);
                    if (rChar && rChar.avatar) {
                        this.resolveAvatar(rChar.avatar, rAvId);
                    }
                }
            }
        }
    },

    resolveAvatar: async function(src, containerId) {
        if (!src) return;
        let finalSrc = src;
        if (src.startsWith('db:') && window.ImageDB) {
            finalSrc = await window.ImageDB.get(src);
        }
        const container = document.getElementById(containerId);
        if (container && finalSrc) {
            container.innerHTML = `<img src="${finalSrc}">`;
        }
    },

    formatTime: function(ts) {
        const diff = Date.now() - (ts || Date.now());
        const mins = Math.floor(diff / 60000);
        if (mins < 1) return 'just now';
        if (mins < 60) return mins + 'm';
        const hrs = Math.floor(mins / 60);
        if (hrs < 24) return hrs + 'h';
        return Math.floor(hrs / 24) + 'd';
    },

    generatePost: async function() {
        const btn = document.getElementById('yBtnGen');
        if (btn) { btn.disabled = true; btn.innerText = "⏳ Generating..."; }

        const bot = State.characters[Math.floor(Math.random() * State.characters.length)];
        try {
            let text = "Hello world";
            const api = window.API;
            if (api && State.settings.key) {
                try {
                    const msg = await api.sendMessage(bot.id, `You are ${bot.name}. Persona: ${bot.persona}. Bio: ${bot.bio}. What's on your mind? Share a short status update that fits your Persona perfectly. Max 20 words. No hashtags. Output only the post text.`);
                    if (msg && msg.length > 5) {
                        text = msg.replace(/\{"action":\s*"generate_image".*?\}/g, '').replace(/["""'']/g, '').trim();
                    }
                } catch(e) {}
            }

            if (!State.xPosts) State.xPosts = [];
            const post = {
                id: 'x_' + Date.now(),
                charId: bot.id,
                charName: bot.name,
                text: text,
                timestamp: Date.now(),
                replies: []
            };
            State.xPosts.push(post);
            if (State.xPosts.length > 50) State.xPosts.shift();
            State.save();
            this.loadPosts();

            if (State.characters.length > 1) {
                setTimeout(() => this.generateReply(post.id), 3000 + Math.random() * 5000);
            }
        } catch(e) {
            console.error("Y gen error:", e);
        } finally {
            if (btn) { btn.disabled = false; btn.innerText = "🐦 New Post"; }
        }
    },

    generateReply: function(postId) {
        const post = (State.xPosts || []).find(p => p.id === postId);
        if (!post) return;
        const others = State.characters.filter(c => c.id !== post.charId);
        if (others.length === 0) return;
        const replier = others[Math.floor(Math.random() * others.length)];

        const doAiReply = async () => {
            const api = window.API;
            if (api && State.settings.key) {
                try {
                    const msg = await api.sendMessage(replier.id, `You are ${replier.name}. Persona: ${replier.persona}. Reply to this tweet from ${post.charName} naturally: "${post.text}". The reply MUST reflect your unique Persona. Max 12 words. Output ONLY the reply text.`);
                    if (msg && msg.length > 3) {
                        return msg.replace(/\{"action":\s*"generate_image".*?\}/g, '').replace(/["""'']/g, '').trim();
                    }
                } catch(e) {}
            }
            return "Interesting!";
        };

        doAiReply().then(reply => {
            if (!post.replies) post.replies = [];
            post.replies.push({
                charId: replier.id,
                charName: replier.name,
                text: reply,
                timestamp: Date.now()
            });
            State.save();
            this.loadPosts();
        });
    },

    clearAll: function() {
        if (!confirm("Clear all Y posts?")) return;
        State.xPosts = [];
        State.save();
        this.loadPosts();
    }
};
window.YApp = YApp;
