'use strict';
const terminal = new Terminal({
  cursorBlink: true, scrollback: 5000, fontFamily: 'monospace', fontSize: 12,
  allowProposedApi: false, allowTransparency: true, screenReaderMode: true,
  theme: { background: '#0b0e11', foreground: '#ffffff', cursor: '#e3b366' }
});
const fit = new FitAddon.FitAddon();
terminal.loadAddon(fit);
const container = document.getElementById('terminal');
terminal.open(container);
let touchY = null;
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
    swiped = true;
    return;
  }
  swiped = false;
  touchY = event.touches.length === 1 && !event.target.closest('.scrollbar')
    ? event.touches[0].clientY : null;
}, { passive: true });
container.addEventListener('touchmove', event => {
  if (pinch && event.touches.length >= 2) {
    event.preventDefault();
    if (pinch.distance > 0) {
      fontScale = Math.max(0.5, Math.min(2.5, pinch.scale * distance(event.touches) / pinch.distance));
      terminal.options.fontSize = Math.round(baseFontSize * fontScale * 10) / 10;
      fit.fit();
    }
    return;
  }
  if (event.touches.length !== 1) { touchY = null; return; }
  if (touchY === null || terminal.buffer.active.type !== 'normal') return;
  const y = event.touches[0].clientY;
  const delta = touchY - y;
  if (!swiped && Math.abs(delta) < 8) return;
  swiped = true;
  event.preventDefault();
  const lineHeight = container.querySelector('.xterm-screen').getBoundingClientRect().height / terminal.rows;
  if (lineHeight <= 0) return;
  const lines = Math.trunc(delta / lineHeight);
  if (lines !== 0) {
    terminal.scrollLines(lines);
    touchY -= lines * lineHeight;
  }
}, { passive: false });
const endTouch = event => {
  touchY = null;
  if (pinch && event.touches.length < 2) {
    pinch = null;
    FancyTerminal.fontScale(fontScale);
  }
};
container.addEventListener('touchend', endTouch);
container.addEventListener('touchcancel', endTouch);
container.addEventListener('click', event => {
  if (swiped) { event.preventDefault(); event.stopImmediatePropagation(); return; }
  terminal.focus();
  FancyTerminal.showKeyboard();
}, { capture: true });
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
  document.querySelector('textarea')?.setAttribute('aria-label', config.label);
  fit.fit();
};
fit.fit();
FancyTerminal.ready();
