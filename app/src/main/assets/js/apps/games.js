/**
 * games.js
 * Interactive Games Hub for Fancy AI OS
 * Features AI-driven character interactions and integrated stealth image generation.
 */
const GamesApp = {
    container: null,
    activeGame: null,
    gameCharId: null,
    pHP: 100,
    aHP: 100,
    hackCode: [],
    hackGuess: [],
    gameTurn: 'player', // tracks 'player' or 'ai' for turn-based games
    todPhase: 'choice',  // tracks 'choice' or 'input' phase specifically for Truth or Dare

    /**
     * Entry point called by OS.launch
     */
    init: function(container) {
        this.container = container;
        this.injectStyles();
        this.renderHub();
    },

    injectStyles: function() {
        const styleId = "games-global-styles";
        if (document.getElementById(styleId)) return;
        const style = document.createElement('style');
        style.id = styleId;
        style.innerHTML = `
            .games-hub { padding: 20px; overflow-y: auto; height: 100%; background: #0a0a0b; display: flex; flex-direction: column; gap: 16px; padding-bottom: 120px; }
            .game-card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 16px; padding: 18px; display: flex; align-items: center; gap: 16px; cursor: pointer; transition: 0.2s; border-left: 2px solid transparent; }
            .game-card:active { transform: scale(0.97); }
            .game-card:hover { border-left-color: var(--accent); background: #1a1a1e; }
            .game-icon { width: 54px; height: 54px; background: var(--bg-input); border-radius: 12px; display: flex; align-items: center; justify-content: center; font-size: 1.8rem; }
            .game-title { font-weight: 700; font-size: 1.05rem; color: white; margin-bottom: 2px; }
            .game-desc { font-size: 0.82rem; color: var(--text-muted); line-height: 1.2; }

            .game-play-area { padding: 20px; display: flex; flex-direction: column; gap: 16px; height: 100%; overflow-y: auto; padding-bottom: 120px; }
            .ai-bubble { background: var(--bg-card); border: 1px solid var(--border); padding: 16px; border-radius: 14px; color: white; font-size: 0.95rem; line-height: 1.5; border-left: 4px solid var(--accent); position: relative; box-shadow: 0 4px 15px rgba(0,0,0,0.2); }
            .btn-group { display: flex; gap: 10px; width: 100%; flex-wrap: wrap; }
            .game-input-row { display: flex; gap: 10px; width: 100%; }

            .game-media-frame { width: 100%; border-radius: 12px; border: 1px solid var(--border); overflow: hidden; background: #111113; margin-top: 10px; box-shadow: 0 4px 12px rgba(0,0,0,0.3); }
            .game-media-frame img { width: 100%; display: block; object-fit: contain; cursor: zoom-in; }

            .stats-row { display: flex; justify-content: space-between; gap: 10px; margin-bottom: 5px; }
            .stat-pill { flex: 1; background: var(--bg-input); padding: 10px; border-radius: 10px; border: 1px solid var(--border); text-align: center; }
            .stat-val { font-size: 1.2rem; font-weight: 800; }
            .stat-label { font-size: 0.65rem; color: var(--text-muted); text-transform: uppercase; font-weight: 600; }

            .terminal-card { background: #0d0d11; padding: 16px; border-radius: 12px; border: 1px solid #1f2937; font-family: monospace; }
            .grid-code-input { display: flex; justify-content: center; gap: 8px; margin: 15px 0; }
            .code-digit-btn { width: 44px; height: 44px; background: #111; border: 1px solid #333; display: flex; align-items: center; justify-content: center; font-size: 1.2rem; color: white; border-radius: 8px; }
            .log-stream { height: 100px; overflow-y: auto; font-size: 0.75rem; color: #888; border-top: 1px solid #222; padding-top: 10px; margin-top: 10px; }
            .keypad-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-top: 10px; }
        `;
        document.head.appendChild(style);
    },

    renderHub: function() {
        this.container.innerHTML = `
            <div class="games-hub">
                <div style="margin-bottom: 5px;">
                    <h2 style="font-size: 1.6rem; font-weight: 800; color: var(--accent); letter-spacing: 1px;">GAMING HUB</h2>
                    <p style="color: var(--text-muted); font-size: 0.88rem;">Interact and play with your AI companions</p>
                </div>
                <div class="game-card" onclick="GamesApp.launchGame('Adventure')">
                    <div class="game-icon">🗺️</div>
                    <div class="game-info">
                        <div class="game-title">World Adventure</div>
                        <div class="game-desc">A Choose Your Own Adventure story. Bots can visualize scenes for you!</div>
                    </div>
                </div>
                <div class="game-card" onclick="GamesApp.launchGame('DiceDuel')">
                    <div class="game-icon">⚔️</div>
                    <div class="game-info">
                        <div class="game-title">Dice Duel RPG</div>
                        <div class="game-desc">Narrative combat. AI visualizes the action.</div>
                    </div>
                </div>
                <div class="game-card" onclick="GamesApp.launchGame('TruthOrDare')">
                    <div class="game-icon">🎭</div>
                    <div class="game-info">
                        <div class="game-title">Truth or Dare</div>
                        <div class="game-desc">Classic game. Bots will "show" dares via generation!</div>
                    </div>
                </div>
                <div class="game-card" onclick="GamesApp.launchGame('TacticalCommand')">
                    <div class="game-icon">♟️</div>
                    <div class="game-info">
                        <div class="game-title">Tactical Command</div>
                        <div class="game-desc">Simulated narrative Chess. Outmaneuver the AI.</div>
                    </div>
                </div>
                <div class="game-card" onclick="GamesApp.launchGame('TwoTruths')">
                    <div class="game-icon">🤥</div>
                    <div class="game-info">
                        <div class="game-title">Two Truths & A Lie</div>
                        <div class="game-desc">Identify the falsehood in the character's story.</div>
                    </div>
                </div>
                <div class="game-card" onclick="GamesApp.launchGame('Oracle')">
                    <div class="game-icon">🔮</div>
                    <div class="game-info">
                        <div class="game-title">The Oracle</div>
                        <div class="game-desc">Fortune reading with visual tarot cards.</div>
                    </div>
                </div>
                <div class="game-card" onclick="GamesApp.launchGame('WouldYouRather')">
                    <div class="game-icon">🤔</div>
                    <div class="game-info">
                        <div class="game-title">Would You Rather</div>
                        <div class="game-desc">Deep and funny choices presented by your AI.</div>
                    </div>
                </div>
                <div class="game-card" onclick="GamesApp.launchHackingGame()">
                    <div class="game-icon">🛡️</div>
                    <div class="game-info">
                        <div class="game-title">Security Bypass</div>
                        <div class="game-desc">Logic-breaker terminal encryption game.</div>
                    </div>
                </div>
            </div>
        `;
    },

    renderBackButton: function() {
        return `<button class="btn" style="width:auto; padding: 8px 16px; margin-bottom: 10px; background:var(--bg-input); font-weight:700;" onclick="GamesApp.renderHub()">← EXIT TO HUB</button>`;
    },

    launchGame: function(gameKey) {
        const chars = State.characters || [];
        const titles = {
            'TruthOrDare': '🎭 Truth or Dare',
            'TwoTruths': '🤥 Two Truths & A Lie',
            'Adventure': '🗺️ AI Adventure',
            'DiceDuel': '⚔️ Dice Duel RPG',
            'RiddleMaster': '🧩 Riddle Master',
            'TacticalCommand': '♟️ Tactical Command',
            'Oracle': '🔮 The Oracle',
            'WouldYouRather': '🤔 Would You Rather'
        };

        this.container.innerHTML = `
            <div class="game-play-area">
                ${this.renderBackButton()}
                <h3 style="color:var(--accent); text-transform:uppercase; letter-spacing:1px;">${titles[gameKey]}</h3>

                <div id="game-setup" style="background: var(--bg-card); padding: 22px; border-radius: 18px; border: 1px solid var(--border);">
                    <p style="font-size: 0.9rem; margin-bottom: 15px; color:var(--text-muted);">Choose an AI identity to engage:</p>
                    <select id="game-char-select" class="form-control" style="margin-bottom: 22px; height:48px;">
                        ${chars.map(c => `<option value="${c.id}">${c.name}</option>`).join('')}
                    </select>
                    <button class="btn btn-primary" style="height:48px;" onclick="GamesApp.startGame('${gameKey}')">INITIALIZE SESSION</button>
                </div>

                <div id="game-arena" style="display:none; flex-direction: column; gap: 16px;">
                    <div id="game-stats-area"></div>
                    <div id="game-ai-bubble" class="ai-bubble">Connecting...</div>
                    <div id="game-image-slot"></div>
                    <div id="game-controls"></div>
                </div>
            </div>
        `;
    },

    startGame: async function(gameKey) {
        this.gameCharId = document.getElementById('game-char-select').value;
        this.activeGame = gameKey;
        document.getElementById('game-setup').style.display = 'none';
        document.getElementById('game-arena').style.display = 'flex';

        const bubble = document.getElementById('game-ai-bubble');
        const controls = document.getElementById('game-controls');
        const stats = document.getElementById('game-stats-area');
        bubble.classList.add('loading-pulse');

        this.gameTurn = 'player';
        this.todPhase = 'choice';
        let prompt = "";

        if (gameKey === 'DiceDuel') {
            this.pHP = 100; this.aHP = 100;
            stats.innerHTML = `
                <div class="stats-row">
                    <div class="stat-pill"><div class="stat-label">You</div><div id="p-hp" class="stat-val" style="color:#10b981;">100</div></div>
                    <div class="stat-pill"><div class="stat-label">Opponent</div><div id="a-hp" class="stat-val" style="color:#f43f5e;">100</div></div>
                </div>
            `;
            prompt = "We are entering a Dice Duel (RPG battle). Describe our encounter briefly (1-2 sentences) and prepare for combat! Keep it short. You can visualize the scene using your tools.";
            controls.innerHTML = `<button class="btn btn-primary" id="dice-roll-btn" onclick="GamesApp.diceTurn()">ROLL ATTACK DICE</button>`;
        }
        else if (gameKey === 'TacticalCommand') {
            prompt = "We are playing a narrative game of Tactical Command (Chess-like). Set the scene briefly (1-2 sentences). You are the opposing commander. Describe your opening move concisely. You can visualize the board if needed.";
            this.setStandardInput("Your strategy (e.g. Move Knight to E4)...");
        }
        else if (gameKey === 'Oracle') {
            prompt = "I come to you for a reading. Read my fortune or pull a tarot card. Keep it short and mystical (1-2 sentences). You can visualize the card for me.";
            this.setStandardInput("Ask about your future...");
        }
        else if (gameKey === 'TruthOrDare') {
            prompt = "We are playing Truth or Dare. Present yourself in character and ask me to choose: TRUTH or DARE! Keep it short and welcoming.";
            controls.innerHTML = `
                <div class="btn-group">
                    <button class="btn" style="flex:1; background:#10b981; color:white;" onclick="GamesApp.sendGameAction('I choose TRUTH')">TRUTH</button>
                    <button class="btn" style="flex:1; background:#f43f5e; color:white;" onclick="GamesApp.sendGameAction('I choose DARE')">DARE</button>
                </div>
            `;
        } else if (gameKey === 'TwoTruths') {
            prompt = "We are playing 'Two Truths and a Lie'. Tell me three short facts about yourself, one must be a lie. Format it clearly as option numbers: 1. [Fact 1] 2. [Fact 2] 3. [Fact 3]. Keep each fact to one sentence. Don't reveal the lie yet.";
            this.setStandardInput("Guess which one is the lie...");
        } else if (gameKey === 'Adventure') {
            prompt = "Start a Choose Your Own Adventure story. Set the scene briefly (1-2 sentences) and offer my first choices. Format the choices clearly using option labels like: 1. [Choice One] or 2. [Choice Two]. Keep it short and punchy. Visualizing the environment is encouraged.";
            this.setStandardInput("What do you do?");
        } else if (gameKey === 'WouldYouRather') {
            prompt = "Let's play Would You Rather. Give me two difficult options to choose from. Format it clearly as options like: 1. [Option One] or 2. [Option Two]. Keep it short and brief.";
            this.setStandardInput("Make your choice...");
        }

        try {
            const responseText = await API.sendMessage(this.gameCharId, prompt, (text) => {
                let display = text.split('{')[0].split('flux prompt:')[0].trim();
                bubble.innerText = display;
                bubble.classList.remove('loading-pulse');
            }, false, 'game');
            await this.parseToolCalls(responseText);

            if (gameKey !== 'TruthOrDare' && gameKey !== 'DiceDuel') {
                this.detectAndRenderOptions(responseText);
            }
        } catch (e) { bubble.innerText = "Error: " + e.message; }
    },

    setStandardInput: function(placeholder = "Type your response...") {
        const controls = document.getElementById('game-controls');
        if (!controls) return;
        controls.innerHTML = `
            <div class="game-input-row">
                <input type="text" id="game-user-input" class="form-control" placeholder="${placeholder}" style="height:48px;">
                <button class="btn btn-primary" style="width:54px; height:48px;" onclick="GamesApp.sendGameAction()">➔</button>
            </div>
        `;
        const input = document.getElementById('game-user-input');
        if(input) input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') this.sendGameAction();
        });
    },

    sendGameAction: async function(overrideText = null) {
        const input = document.getElementById('game-user-input');
        const text = overrideText || (input ? input.value.trim() : "");
        if (!text && !overrideText) return;
        if (input) input.value = '';

        const bubble = document.getElementById('game-ai-bubble');
        const imgSlot = document.getElementById('game-image-slot');
        if(imgSlot) imgSlot.innerHTML = '';
        bubble.innerText = "...";
        bubble.classList.add('loading-pulse');

        let actionText = text;

        if (this.activeGame === 'TruthOrDare') {
            const lowerText = text.toLowerCase();
            if (this.todPhase === 'choice') {
                if (lowerText.includes('truth')) {
                    actionText = "I choose TRUTH. Ask me a direct, revealing, or embarrassing truth question. Keep it concise (1 sentence).";
                    this.todPhase = 'input';
                } else {
                    actionText = "I choose DARE. Give me a fun, bold, or slightly provocative dare challenge to execute! CRITICAL: You MUST end your response with 'flux prompt:' followed by a detailed visual description depicting the dare or the scene of the dare challenge action dynamically.";
                    this.todPhase = 'input';
                }
            } else {
                actionText = `[GAME ACTION: We are playing Truth or Dare. The user responds to your challenge with: "${text}". Briefly react to their execution/answer in character, and then explicitly ask them to choose "TRUTH or DARE" again for the next round!]`;
                this.todPhase = 'choice';
            }
        } else if (this.activeGame === 'Adventure') {
            actionText = `[GAME ACTION: We are playing a Choose Your Own Adventure story game. The user chooses the action: "${text}". Process this choice, advance the narrative scene dynamically (2-3 sentences), and present 2-4 new numbered or bulleted choice options for the next step. You can use 'flux prompt:' at the end if you visualize the scene.]`;
        } else if (this.activeGame === 'TacticalCommand') {
            actionText = `[GAME ACTION: We are playing a tactical command/narrative chess game. The user makes the move: "${text}". Respond as the opposing commander, execute your counter-move concisely (1-2 sentences), and outline the new choices or state of the board clearly.]`;
        } else if (this.activeGame === 'TwoTruths') {
            actionText = `[GAME ACTION: We are playing 'Two Truths and a Lie'. The user guesses: "${text}". Reveal if they are correct or incorrect, briefly explain the context behind the lie, and ask if they want to play another round.]`;
        } else if (this.activeGame === 'WouldYouRather') {
            actionText = `[GAME ACTION: We are playing 'Would You Rather'. The user picks: "${text}". React to their choice briefly in character (1 sentence), then present a brand-new 'Would You Rather' dilemma with two clearly numbered choices.]`;
        } else if (this.activeGame === 'Oracle') {
            actionText = `[GAME ACTION: The user asks the Oracle: "${text}". Continue the mystical fortune reading or tarot interpretation based on their input. Keep it short (2 sentences) and thematic.]`;
        }

        try {
            const responseText = await API.sendMessage(this.gameCharId, actionText, (resp) => {
                let display = resp.split('{')[0].split('flux prompt:')[0].trim();
                bubble.innerText = display;
                bubble.classList.remove('loading-pulse');
            }, false, 'game');

            await this.parseToolCalls(responseText);

            if (this.activeGame === 'TruthOrDare') {
                if (this.todPhase === 'choice') {
                    const controls = document.getElementById('game-controls');
                    if (controls) {
                        controls.innerHTML = `
                            <div class="btn-group">
                                <button class="btn" style="flex:1; background:#10b981; color:white;" onclick="GamesApp.sendGameAction('I choose TRUTH')">TRUTH</button>
                                <button class="btn" style="flex:1; background:#f43f5e; color:white;" onclick="GamesApp.sendGameAction('I choose DARE')">DARE</button>
                            </div>
                        `;
                    }
                } else {
                    this.setStandardInput("Complete the challenge or reply here...");
                }
            } else if (this.activeGame !== 'DiceDuel') {
                this.detectAndRenderOptions(responseText);
            }
        } catch (e) { bubble.innerText = "Error: " + e.message; }
    },

    detectAndRenderOptions: function(text) {
        let cleanText = text.split('{')[0].split('flux prompt:')[0].trim();
        const lines = cleanText.split('\n');
        const options = [];

        lines.forEach(line => {
            let trimmed = line.trim();
            const match = trimmed.match(/^([1-4]\.|\*|^-|[A-D]\)|Option\s+[A-D]:|\u2022)\s*(.+)$/i);
            if (match && match[2].trim().length > 1) {
                options.push(match[2].trim());
            }
        });

        if (options.length >= 2) {
            const controls = document.getElementById('game-controls');
            if (controls) {
                let html = `<div class="btn-group" style="flex-direction:column; gap:8px; width:100%;">`;
                options.forEach((opt) => {
                    html += `<button class="btn" style="background:var(--bg-card); border:1px solid var(--border); text-align:left; padding:12px 16px; color:white; width:100%; border-radius:12px; transition:0.2s;" onmouseover="this.style.background='#1a1a1e'; this.style.borderColor='var(--accent)';" onmouseout="this.style.background='var(--bg-card)'; this.style.borderColor='var(--border)';" onclick="GamesApp.sendGameAction('${opt.replace(/'/g, "\\'")}')">${opt}</button>`;
                });
                html += `</div>
                <div class="game-input-row" style="margin-top:12px;">
                    <input type="text" id="game-user-input" class="form-control" placeholder="Or type a custom action..." style="height:48px;">
                    <button class="btn btn-primary" style="width:54px; height:48px;" onclick="GamesApp.sendGameAction()">➔</button>
                </div>`;
                controls.innerHTML = html;

                const input = document.getElementById('game-user-input');
                if(input) input.addEventListener('keydown', (e) => {
                    if (e.key === 'Enter') this.sendGameAction();
                });
                return;
            }
        }
        this.setStandardInput();
    },

    parseToolCalls: async function(text) {
        if (text.toLowerCase().includes("flux prompt:")) {
            const parts = text.split(/flux prompt:/i);
            const prompt = parts[1].trim();
            const bubble = document.getElementById('game-ai-bubble');
            if (bubble) bubble.innerText = parts[0].trim();
            await this.triggerGameImageGeneration(prompt);
            return true;
        }

        const jsonMatch = text.match(/(\{[\s\S]*?\})/);
        if (!jsonMatch) return false;

        try {
            const rawJson = jsonMatch[1];
            const data = JSON.parse(rawJson);

            if (data.action === "generate_image" || data.tool === "generate_image") {
                const bubble = document.getElementById('game-ai-bubble');
                if (bubble) {
                    let cleanText = text.replace(/```(json)?\s*/g, '').replace(rawJson, '').replace(/```/g, '').trim();
                    bubble.innerText = cleanText;
                }
                const prompt = data.action_input || data.input || data.prompt;
                await this.triggerGameImageGeneration(prompt);
                return true;
            }
        } catch (e) {
            console.warn("JSON Parse failed in Game tool check:", e);
        }
        return false;
    },

    triggerGameImageGeneration: async function(prompt) {
        const imgSlot = document.getElementById('game-image-slot');
        if (!imgSlot) return;

        imgSlot.innerHTML = `<div class="ai-bubble loading-pulse" style="border-left-color:#fbbf24; font-style:italic;">AI is visualizing the scene...</div>`;

        try {
            const imageB64 = await window.ImagingApp.generate(prompt);
            const imageId = `game_gen_${Date.now()}`;
            let displaySrc = imageB64;
            if (window.ImageDB) {
                const dbRef = await window.ImageDB.save(imageId, imageB64);
                displaySrc = await window.ImageDB.get(dbRef);
            }

            imgSlot.innerHTML = `
                <div class="game-media-frame">
                    <img src="${displaySrc}" onclick="if(window.ImagingApp) ImagingApp.openLocalLightbox(this.src)" alt="Game generation">
                </div>
            `;

            if (State.settings.enableBotSocial && window.SocialApp) {
                window.SocialApp.injectPost(this.gameCharId, `Freshly generated in our game session: ${prompt}`, imageB64);
            }
        } catch (e) {
            imgSlot.innerHTML = `<div class="ai-bubble" style="color:var(--danger)">⚠️ Visualization Failed: ${e.message}</div>`;
        } finally {
            if (this.activeGame === 'DiceDuel') {
                const rollBtn = document.getElementById('dice-roll-btn');
                if (rollBtn) {
                    rollBtn.disabled = false;
                    rollBtn.innerText = "ROLL ATTACK DICE";
                }
            }
        }
    },

    diceTurn: async function() {
        const bubble = document.getElementById('game-ai-bubble');
        const imgSlot = document.getElementById('game-image-slot');
        const rollBtn = document.getElementById('dice-roll-btn');

        if(imgSlot) imgSlot.innerHTML = '';
        if (rollBtn) {
            rollBtn.disabled = true;
            rollBtn.innerText = "⏳ VISUALIZING ROUND...";
        }

        const pDmg = Math.floor(Math.random() * 15) + 5;
        const aDmg = Math.floor(Math.random() * 15) + 5;
        this.pHP -= aDmg; this.aHP -= pDmg;

        const pHPDisplay = document.getElementById('p-hp');
        const aHPDisplay = document.getElementById('a-hp');
        if (pHPDisplay) pHPDisplay.innerText = Math.max(0, this.pHP);
        if (aHPDisplay) aHPDisplay.innerText = Math.max(0, this.aHP);

        bubble.innerText = "...";
        bubble.classList.add('loading-pulse');

        const prompt = `RPG DUEL UPDATE: Me: ${pDmg} dmg. You: ${aDmg} dmg. My HP: ${this.pHP}, Your HP: ${this.aHP}. Narrate this round briefly (1-2 sentences). Keep it short and punchy. Feel free to visualize the combat.`;

        try {
            const response = await API.sendMessage(this.gameCharId, prompt, (resp) => {
                let display = resp.split('{')[0].split('flux prompt:')[0].trim();
                bubble.innerText = display;
                bubble.classList.remove('loading-pulse');
            }, false, 'game');

            const imageTriggered = await this.parseToolCalls(response);

            if (!imageTriggered && this.pHP > 0 && this.aHP > 0) {
                if (rollBtn) {
                    rollBtn.disabled = false;
                    rollBtn.innerText = "ROLL ATTACK DICE";
                }
            }

            if (this.pHP <= 0 || this.aHP <= 0) {
                const controls = document.getElementById('game-controls');
                if (controls) controls.innerHTML = `<button class="btn btn-primary" onclick="GamesApp.launchGame('DiceDuel')">REMATCH</button>`;
            }
        } catch (e) {
            bubble.innerText = "Error: " + e.message;
            if (rollBtn) {
                rollBtn.disabled = false;
                rollBtn.innerText = "ROLL ATTACK DICE";
            }
        }
    },

    launchHackingGame: function() {
        this.container.innerHTML = `
            <div class="game-play-area" style="font-family:monospace;">
                ${this.renderBackButton()}
                <div class="terminal-card">
                    <div style="color:#10b981; font-size:0.7rem;">CORE_BYPASS_v1.0</div>
                    <div id="hack-msg" style="color:#34d399; margin:10px 0;">Crack the 4-digit code (1-6).</div>
                    <div class="grid-code-input">
                        ${[0,1,2,3].map(i => `<div id="h-${i}" class="code-digit-btn">-</div>`).join('')}
                    </div>
                    <div id="hack-log" class="log-stream"></div>
                </div>
                <div class="keypad-grid">
                    ${[1,2,3,4,5,6].map(n => `<button class="btn" style="padding:15px;" onclick="GamesApp.hackKey(${n})">${n}</button>`).join('')}
                    <button class="btn" style="color:var(--danger)" onclick="GamesApp.hackGuess=[]; GamesApp.updateHackDisplay()">✕</button>
                    <button class="btn" style="grid-column:span 2; color:#10b981" onclick="GamesApp.hackSubmit()">SUBMIT KEY</button>
                </div>
            </div>
        `;
        this.hackCode = Array.from({length:4}, () => Math.floor(Math.random()*6)+1);
        this.hackGuess = [];
        this.updateHackDisplay();
    },

    hackKey: function(n) {
        if(n && this.hackGuess.length < 4) this.hackGuess.push(n);
        this.updateHackDisplay();
    },

    updateHackDisplay: function() {
        for(let i=0; i<4; i++) {
            const el = document.getElementById(`h-${i}`);
            if(el) el.innerText = this.hackGuess[i] || '-';
        }
    },

    hackSubmit: function() {
        if(this.hackGuess.length < 4) return;
        let p=0, v=0; const s=[...this.hackCode], g=[...this.hackGuess];
        for(let i=0;i<4;i++) if(g[i]===s[i]){ p++; s[i]=g[i]=null; }
        for(let i=0;i<4;i++) if(g[i]!==null){ let idx=s.indexOf(g[i]); if(idx>-1){ v++; s[idx]=null; } }
        const log = document.getElementById('hack-log');
        if(log) {
            log.innerHTML += `<div>➔ [${this.hackGuess.join('')}] L:${p} N:${v}</div>`;
            log.scrollTop = log.scrollHeight;
        }
        if(p===4) {
            const msg = document.getElementById('hack-msg');
            if(msg) msg.innerText = "🔓 ACCESS GRANTED!";
        }
        this.hackGuess=[]; this.updateHackDisplay();
    }
};

window.GamesApp = GamesApp;