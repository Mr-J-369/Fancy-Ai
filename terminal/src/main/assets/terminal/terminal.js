'use strict';
const terminal = new Terminal({
  cursorBlink: true, scrollback: 5000, fontFamily: 'monospace', fontSize: 12,
  allowProposedApi: false, allowTransparency: true, screenReaderMode: false,
  theme: { background: '#0b0e11', foreground: '#ffffff', cursor: '#e3b366' }
});
const fit = new FitAddon.FitAddon();
terminal.loadAddon(fit);
const container = document.getElementById('terminal');
terminal.open(container);

// Configure helper textarea to disable autocorrect/predictive text duplication on mobile virtual keyboards
const setupTextarea = () => {
  const ta = container.querySelector('textarea');
  if (ta) {
    ta.setAttribute('autocapitalize', 'off');
    ta.setAttribute('autocomplete', 'off');
    ta.setAttribute('autocorrect', 'off');
    ta.setAttribute('spellcheck', 'false');
    ta.setAttribute('inputmode', 'text');
  }
};
setupTextarea();

let touchY = null;
let touchX = null;
let swiped = false;
let baseFontSize = 12;
let fontScale = 1;
let pinch = null;
const distance = touches => Math.hypot(
  touches[0].clientX - touches[1].clientX,
  touches[0].clientY - touches[1].clientY
);
container.addEventListener('touchstart', event => {
  if (event.touches.length >= 2) {
    pinch = { distance: distance(event.touches), scale: fontScale };
    touchY = null;
    touchX = null;
    swiped = true;
    return;
  }
  swiped = false;
  if (event.touches.length === 1 && !event.target.closest('.scrollbar')) {
    touchY = event.touches[0].clientY;
    touchX = event.touches[0].clientX;
  } else {
    touchY = null;
    touchX = null;
  }
}, { passive: true });
container.addEventListener('touchmove', event => {
  if (pinch && event.touches.length >= 2) {
    if (event.cancelable) event.preventDefault();
    if (pinch.distance > 0) {
      fontScale = Math.max(0.5, Math.min(2.5, pinch.scale * distance(event.touches) / pinch.distance));
      terminal.options.fontSize = Math.round(baseFontSize * fontScale * 10) / 10;
      fit.fit();
    }
    return;
  }
  if (event.touches.length !== 1) { touchY = null; touchX = null; return; }
  if (touchY === null || touchX === null) return;
  const y = event.touches[0].clientY;
  const delta = touchY - y;
  if (!swiped && Math.abs(delta) < 8) return;
  swiped = true;
  if (event.cancelable) event.preventDefault();
  const screenEl = container.querySelector('.xterm-screen');
  const lineHeight = screenEl ? screenEl.getBoundingClientRect().height / terminal.rows : 16;
  if (lineHeight <= 0) return;
  const lines = Math.trunc(delta / lineHeight);
  if (lines !== 0) {
    if (terminal.buffer.active.type === 'alternate') {
      // Send standard SGR mouse wheel reporting (button 64 = wheel up, button 65 = wheel down)
      // This scrolls conversation/code viewports in OpenCode, vim, less, tmux without typing into the input prompt.
      const charWidth = screenEl ? screenEl.getBoundingClientRect().width / terminal.cols : 8;
      const col = Math.max(1, Math.min(terminal.cols, Math.floor(touchX / charWidth) + 1));
      const row = Math.max(1, Math.min(terminal.rows, Math.floor(y / lineHeight) + 1));
      const btn = lines < 0 ? 64 : 65; // 64 = wheel up, 65 = wheel down
      const count = Math.min(Math.abs(lines), 4);
      for (let i = 0; i < count; i++) {
        FancyTerminal.input(`\x1b[<${btn};${col};${row}M`);
      }
    } else {
      terminal.scrollLines(lines);
    }
    touchY -= lines * lineHeight;
  }
}, { passive: false });
const endTouch = event => {
  touchY = null;
  touchX = null;
  if (pinch && event.touches.length < 2) {
    pinch = null;
    FancyTerminal.fontScale(fontScale);
  }
};
container.addEventListener('touchend', endTouch);
container.addEventListener('touchcancel', endTouch);
const focusTerminal = () => {
  terminal.focus();
  setupTextarea();
  const textarea = container.querySelector('textarea');
  if (textarea) textarea.focus();
  FancyTerminal.showKeyboard();
};
container.addEventListener('click', event => {
  if (swiped) { event.preventDefault(); event.stopImmediatePropagation(); return; }
  focusTerminal();
}, { capture: true });
window.focusTerminalInput = focusTerminal;
terminal.onData(data => FancyTerminal.input(data));
terminal.onResize(size => FancyTerminal.resize(size.cols, size.rows));
new ResizeObserver(() => fit.fit()).observe(container);
window.receiveTerminal = data => terminal.write(Uint8Array.from(atob(data), c => c.charCodeAt(0)));
window.pasteTerminal = text => terminal.paste(text);
window.selectedTerminalText = () => terminal.getSelection();
window.configureTerminal = config => {
  document.documentElement.lang = config.language;
  document.title = config.label;
  Terminal.strings.promptLabel = config.label;
  Terminal.strings.tooMuchOutput = config.outputLimit;
  terminal.options.theme = { background: config.background, foreground: config.foreground, cursor: config.cursor };
  document.body.style.setProperty('--terminal-background', config.background);
  baseFontSize = config.fontSize;
  if (!pinch) fontScale = config.fontScale;
  terminal.options.fontSize = Math.round(baseFontSize * fontScale * 10) / 10;
  setupTextarea();
  document.querySelector('textarea')?.setAttribute('aria-label', config.label);
  fit.fit();
};
fit.fit();
FancyTerminal.ready();
