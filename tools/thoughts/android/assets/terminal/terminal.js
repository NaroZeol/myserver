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
  // Remote output must never read or replace the phone's clipboard.
  terminal.parser.registerOscHandler(52, () => true);
  let control = false;
  function send(text) {
    if (control && text.length === 1) {
      const code = text.toUpperCase().charCodeAt(0);
      if (code >= 64 && code <= 95) text = String.fromCharCode(code - 64);
    }
    if (control) { control = false; Phone.controlReleased(); }
    const bytes = new TextEncoder().encode(text);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    Phone.input(btoa(binary));
  }
  terminal.onData(send);
  terminal.onBinary(data => Phone.input(btoa(data)));
  terminal.onResize(size => Phone.resize(size.cols, size.rows));
  function resize() { if (document.body.clientHeight > 0) fit.fit(); }
  window.addEventListener('resize', resize);
  if (typeof ResizeObserver !== 'undefined') new ResizeObserver(resize).observe(document.body);
  window.TerminalUI = {
    write(encoded, id) {
      const binary = atob(encoded);
      const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
      terminal.write(bytes, () => Phone.ack(id));
    },
    key(text) { send(text); terminal.focus(); },
    paste(text) { terminal.paste(text); terminal.focus(); },
    control(enabled) { control = enabled; terminal.focus(); },
    focus() { terminal.focus(); },
    selection() { return terminal.getSelection(); },
    clear() { terminal.clear(); },
    font(size) { terminal.options.fontSize = size; resize(); }
  };
  // Available to instrumentation for real rendering/input assertions, without adding native privileges.
  window.terminal = terminal;
  resize();
  Phone.ready();
})();
