'use strict';
(() => {
  const terminal = new Terminal({
    cursorBlink: true, fontFamily: 'monospace', fontSize: 14,
    scrollback: 2000, screenReaderMode: true, allowProposedApi: false,
    theme: { background: '#1c1d20', foreground: '#e8e6df', cursor: '#d8af8c', selectionBackground: '#57534e' },
    linkHandler: { activate() {} }
  });
  const fit = new FitAddon.FitAddon();
  terminal.loadAddon(fit);
  terminal.open(document.getElementById('terminal'));
  terminal.parser.registerOscHandler(52, () => true);
  const modifiers = { CTRL: 0, ALT: 0 }; // off / next key / locked
  let volumeControl = false, keyboardEvent = false, pasting = false;
  function state() { Phone.modifiersChanged(modifiers.CTRL || (volumeControl ? 2 : 0), modifiers.ALT); }
  function consume() {
    if (modifiers.CTRL === 1) modifiers.CTRL = 0;
    if (modifiers.ALT === 1) modifiers.ALT = 0;
    state();
  }
  function raw(text) {
    const bytes = new TextEncoder().encode(text);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    Phone.input(btoa(binary));
  }
  function modified(text) {
    const control = modifiers.CTRL !== 0 || volumeControl;
    const alt = modifiers.ALT !== 0;
    if (control && text.length === 1) {
      const code = text.toUpperCase().charCodeAt(0);
      if (code >= 64 && code <= 95) text = String.fromCharCode(code - 64);
      else if (text === ' ' || text === '2') text = '\x00';
      else if (text === '?' || text === '8') text = '\x7f';
      else if (text >= '3' && text <= '7') text = String.fromCharCode(Number(text) + 24);
    }
    if (alt) text = '\x1b' + text;
    consume();
    return text;
  }
  function named(name) {
    const suffix = { UP: 'A', DOWN: 'B', RIGHT: 'C', LEFT: 'D', HOME: 'H', END: 'F' }[name];
    const parameter = 1 + (modifiers.ALT ? 2 : 0) + (modifiers.CTRL || volumeControl ? 4 : 0);
    let value;
    if (suffix) value = parameter > 1 ? '\x1b[1;' + parameter + suffix :
      (terminal.modes.applicationCursorKeysMode ? '\x1bO' : '\x1b[') + suffix;
    else if (name === 'PGUP' || name === 'PGDN') value = '\x1b[' + (name === 'PGUP' ? '5' : '6') + (parameter > 1 ? ';' + parameter : '') + '~';
    else return modified({ ESC: '\x1b', TAB: '\t', ENTER: '\r', BACKSPACE: '\x7f' }[name] || name);
    consume();
    return value;
  }
  terminal.onKey(() => { keyboardEvent = true; });
  terminal.onData(text => {
    // Terminal status replies also use onData. Never consume modifiers or alter protocol replies.
    const userInput = keyboardEvent || text.charAt(0) !== '\x1b';
    keyboardEvent = false;
    if (pasting || !userInput) { raw(text); return; }
    const cursor = /^\x1b(?:\[|O)([ABCDHF])$/.exec(text);
    raw(cursor ? named({ A: 'UP', B: 'DOWN', C: 'RIGHT', D: 'LEFT', H: 'HOME', F: 'END' }[cursor[1]]) : modified(text));
  });
  terminal.onBinary(data => Phone.input(btoa(data)));
  terminal.onResize(size => Phone.resize(size.cols, size.rows));
  function resize() {
    if (document.body.clientHeight <= 0) return;
    const position = terminal.buffer.active.viewportY;
    const following = position >= terminal.buffer.active.baseY;
    fit.fit();
    requestAnimationFrame(() => {
      if (following) terminal.scrollToBottom(); else terminal.scrollToLine(position);
    });
  }
  window.addEventListener('resize', resize);
  if (typeof ResizeObserver !== 'undefined') new ResizeObserver(resize).observe(document.body);
  window.TerminalUI = {
    write(encoded, id) {
      const binary = atob(encoded);
      const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
      terminal.write(bytes, () => Phone.ack(id));
    },
    newSession() { terminal.reset(); TerminalUI.resetModifiers(); },
    key(text) { terminal.clearSelection(); raw(modified(text)); terminal.scrollToBottom(); terminal.focus(); },
    special(name) { terminal.clearSelection(); raw(named(name)); terminal.scrollToBottom(); terminal.focus(); },
    paste(text) {
      terminal.clearSelection();
      pasting = true;
      try { terminal.paste(text); } finally { pasting = false; }
      terminal.scrollToBottom(); terminal.focus();
    },
    modifier(name, lock) {
      if (!(name in modifiers)) return;
      modifiers[name] = lock ? (modifiers[name] === 2 ? 0 : 2) : (modifiers[name] ? 0 : 1);
      state();
    },
    control(enabled) { modifiers.CTRL = enabled ? 1 : 0; state(); },
    volumeControl(enabled) { volumeControl = enabled; state(); },
    resetModifiers() { modifiers.CTRL = modifiers.ALT = 0; volumeControl = false; state(); },
    focus() { terminal.scrollToBottom(); terminal.focus(); },
    blur() { terminal.blur(); },
    selection() { return terminal.getSelection(); },
    selectAll() { terminal.selectAll(); },
    clearSelection() { terminal.clearSelection(); },
    clear() { terminal.clear(); },
    font(size) { terminal.clearSelection(); terminal.options.fontSize = size; resize(); }
  };
  window.terminal = terminal;
  installTerminalTouch(terminal);
  resize();
  Phone.ready();
})();
