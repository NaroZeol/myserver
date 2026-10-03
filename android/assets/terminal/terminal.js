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
  let volumeControl = false, keyboardEvent = null, pasting = false;
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
  function modified(text, physicalAlt = false, physicalControl = false) {
    const control = modifiers.CTRL !== 0 || volumeControl;
    const alt = modifiers.ALT !== 0 || physicalAlt;
    if (control && !physicalControl && text.length === 1) {
      const point = text.charCodeAt(0);
      const code = point >= 97 && point <= 122 ? point - 32 : point;
      if (code >= 64 && code <= 95) text = String.fromCharCode(code - 64);
      else if (text === ' ' || text === '2') text = '\x00';
      else if (text === '\x7f') text = '\x08';
      else if (text === '/') text = '\x1f';
      else if (text === '?' || text === '8') text = '\x7f';
      else if (text >= '3' && text <= '7') text = String.fromCharCode(Number(text) + 24);
    }
    if (alt) text = '\x1b' + text;
    consume();
    return text;
  }
  const tildeKeys = { INSERT: 2, DELETE: 3, PGUP: 5, PGDN: 6,
    F5: 15, F6: 17, F7: 18, F8: 19, F9: 20, F10: 21, F11: 23, F12: 24 };
  function named(name, physical = 0) {
    if (name.startsWith('SHIFT+')) {
      name = name.slice(6);
      physical |= 1;
      if (name === 'TAB') name = 'BACKTAB';
    }
    const suffix = { UP: 'A', DOWN: 'B', RIGHT: 'C', LEFT: 'D', HOME: 'H', END: 'F' }[name];
    const bits = physical | (modifiers.ALT ? 2 : 0) | (modifiers.CTRL || volumeControl ? 4 : 0);
    const parameter = 1 + bits;
    let value;
    if (suffix) value = parameter > 1 ? '\x1b[1;' + parameter + suffix :
      (terminal.modes.applicationCursorKeysMode ? '\x1bO' : '\x1b[') + suffix;
    else if (name === 'BACKTAB') value = (bits & ~1) !== 0 ? '\x1b[1;' + ((bits | 1) + 1) + 'Z' : '\x1b[Z';
    else if (/^F[1-4]$/.test(name)) {
      const final = String.fromCharCode(79 + Number(name.slice(1)));
      value = parameter > 1 ? '\x1b[1;' + parameter + final : '\x1bO' + final;
    } else if (Object.prototype.hasOwnProperty.call(tildeKeys, name)) {
      value = '\x1b[' + tildeKeys[name] + (parameter > 1 ? ';' + parameter : '') + '~';
    }
    else return modified({ ESC: '\x1b', TAB: '\t', ENTER: '\r', BACKSPACE: '\x7f' }[name] || name);
    consume();
    return value;
  }
  // Browsers/xterm do not consistently map the Ctrl+/ alias of Ctrl+_.
  terminal.attachCustomKeyEventHandler(event => {
    if (event.type !== 'keydown' || !event.ctrlKey || event.key !== '/' || event.metaKey) return true;
    event.preventDefault();
    raw((event.altKey || modifiers.ALT ? '\x1b' : '') + '\x1f');
    consume(); keyboardEvent = null;
    return false;
  });
  terminal.onKey(event => {
    keyboardEvent = event.domEvent;
    // Tab is an application key here; its browser default would move focus away.
    if (keyboardEvent.key === 'Tab') keyboardEvent.preventDefault();
  });
  terminal.onData(text => {
    // Terminal status replies also use onData. Never consume modifiers or alter protocol replies.
    const physicalControl = !!(keyboardEvent && keyboardEvent.ctrlKey);
    const physicalBits = keyboardEvent ? (keyboardEvent.shiftKey ? 1 : 0) |
      (keyboardEvent.altKey ? 2 : 0) | (physicalControl ? 4 : 0) : 0;
    const userInput = keyboardEvent || text.charAt(0) !== '\x1b';
    keyboardEvent = null;
    if (pasting || !userInput) { raw(text); return; }
    const ss3 = /^\x1bO([ABCDHFPQRS])$/.exec(text);
    const csi = /^\x1b\[(?:(\d+)(?:;(\d+))?)?([ABCDHFPQRSZ~])$/.exec(text);
    const finals = { A: 'UP', B: 'DOWN', C: 'RIGHT', D: 'LEFT', H: 'HOME', F: 'END',
      P: 'F1', Q: 'F2', R: 'F3', S: 'F4', Z: 'BACKTAB' };
    let name, bits = 0;
    if (ss3) name = finals[ss3[1]];
    if (csi) {
      bits = csi[2] ? Number(csi[2]) - 1 : 0;
      name = csi[3] === '~' ? Object.keys(tildeKeys).find(key => tildeKeys[key] === Number(csi[1])) : finals[csi[3]];
    }
    raw(name ? named(name, bits | physicalBits) : text.length === 2 && text[0] === '\x1b'
      ? modified(text.slice(1), true, physicalControl) : modified(text, false, physicalControl));
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
    inputMode(internal) { terminal.textarea.inputMode = internal ? "none" : "text"; },
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
