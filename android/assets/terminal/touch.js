'use strict';
// Use public buffer/selection APIs so touch behavior does not depend on xterm internals.
function installTerminalTouch(terminal) {
  const screen = document.querySelector('.xterm-screen');
  const root = document.getElementById('terminal');
  const handles = [document.createElement('div'), document.createElement('div')];
  let selecting = false, timer = 0, start = null, pinching = false, distance = 0, held = false, dragging = false, discarded = false;
  function rect() { return screen.getBoundingClientRect(); }
  function cell(x, y) {
    const box = rect();
    let col = Math.max(0, Math.min(terminal.cols - 1, Math.floor((x - box.left) / box.width * terminal.cols)));
    const row = terminal.buffer.active.viewportY + Math.max(0, Math.min(terminal.rows - 1, Math.floor((y - box.top) / box.height * terminal.rows)));
    const line = terminal.buffer.active.getLine(row);
    if (line && col > 0 && line.getCell(col).getWidth() === 0) col--;
    return { col, row };
  }
  function selectAt(x, y) {
    const point = cell(x, y), line = terminal.buffer.active.getLine(point.row);
    if (!line) return;
    let left = point.col, right = point.col;
    const word = c => /[^\s()[\]{}<>"'`]/.test(line.getCell(c).getChars() || ' ');
    if (word(left)) {
      while (left > 0 && (word(left - 1) || line.getCell(left - 1).getWidth() === 0)) left--;
      while (right + 1 < terminal.cols && (word(right + 1) || line.getCell(right + 1).getWidth() === 0)) right++;
    }
    terminal.select(left, point.row, right - left + Math.max(1, line.getCell(right).getWidth()));
  }
  function update() {
    const position = terminal.getSelectionPosition();
    const active = !!position;
    if (selecting !== active) { selecting = active; Phone.selectionChanged(active); }
    const box = rect();
    handles.forEach((handle, i) => {
      if (!position) { handle.style.display = 'none'; return; }
      const point = i === 0 ? position.start : position.end;
      const row = point.y - terminal.buffer.active.viewportY;
      handle.style.display = row < 0 || row >= terminal.rows ? 'none' : 'block';
      handle.style.left = Math.max(0, Math.min(window.innerWidth - 32, box.left + point.x / terminal.cols * box.width - 16)) + 'px';
      handle.style.top = Math.min(window.innerHeight - 32, box.top + (row + 1) / terminal.rows * box.height - 4) + 'px';
    });
  }
  handles.forEach((handle, i) => {
    handle.className = 'terminal-handle';
    handle.setAttribute('aria-hidden', 'true');
    document.body.appendChild(handle);
    let anchor;
    handle.addEventListener('touchstart', event => {
      event.preventDefault(); event.stopPropagation();
      const position = terminal.getSelectionPosition();
      if (position) anchor = i === 0 ? position.end : position.start;
    }, { passive: false });
    handle.addEventListener('touchmove', event => {
      event.preventDefault(); event.stopPropagation();
      if (!anchor || event.touches.length !== 1) return;
      const touch = event.touches[0], box = rect();
      if (touch.clientY < box.top + 16) terminal.scrollLines(-1);
      if (touch.clientY > box.bottom - 16) terminal.scrollLines(1);
      const point = cell(touch.clientX, touch.clientY - 12);
      const a = anchor.y * terminal.cols + anchor.x;
      const b = point.row * terminal.cols + point.col + (i === 1 ? Math.max(1, terminal.buffer.active.getLine(point.row).getCell(point.col).getWidth()) : 0);
      const first = Math.min(a, b), last = Math.max(a, b);
      terminal.select(first % terminal.cols, Math.floor(first / terminal.cols), Math.max(1, last - first));
    }, { passive: false });
    const release = event => { event.preventDefault(); event.stopPropagation(); anchor = null; };
    handle.addEventListener('touchend', release, { passive: false });
    handle.addEventListener('touchcancel', release, { passive: false });
  });
  function span(touches) { return Math.hypot(touches[0].clientX - touches[1].clientX, touches[0].clientY - touches[1].clientY); }
  function cancel() { clearTimeout(timer); timer = 0; }
  function mode() { return terminal.modes.mouseTrackingMode + '/' + terminal.buffer.active.type; }
  function scroll(rows) {
    if (!rows) return;
    if (terminal.modes.mouseTrackingMode === 'none' && terminal.buffer.active.type === 'normal') {
      terminal.scrollLines(rows);
      return;
    }
    // Reuse xterm's negotiated mouse encoding and application-cursor handling.
    // Anchor wheel coordinates to the initial touch so a swipe stays in its tmux pane.
    const box = rect();
    const x = Math.max(box.left + 1, Math.min(box.right - 1, start.x));
    const y = Math.max(box.top + 1, Math.min(box.bottom - 1, start.y));
    for (let i = 0; i < Math.min(Math.abs(rows), terminal.rows); i++) {
      screen.dispatchEvent(new WheelEvent('wheel', {
        bubbles: true, cancelable: true, deltaMode: 1,
        deltaY: rows < 0 ? -1 : 1, clientX: x, clientY: y
      }));
    }
  }
  root.addEventListener('touchstart', event => {
    cancel();
    if (event.touches.length === 2) {
      if (!start && !pinching) discarded = false;
      pinching = true; held = false; distance = span(event.touches);
      terminal.clearSelection(); event.preventDefault(); event.stopImmediatePropagation(); return;
    }
    if (event.touches.length !== 1) {
      discarded = true; event.preventDefault(); event.stopImmediatePropagation(); return;
    }
    discarded = false; pinching = false;
    const touch = event.touches[0];
    start = { x: touch.clientX, y: touch.clientY, lastY: touch.clientY, remainder: 0, mode: mode() };
    held = false; dragging = false;
    if (selecting) terminal.clearSelection();
    timer = setTimeout(() => { held = true; selectAt(start.x, start.y); }, 500);
  }, { passive: false, capture: true });
  root.addEventListener('touchmove', event => {
    if (discarded) { event.preventDefault(); event.stopImmediatePropagation(); return; }
    if (pinching) {
      cancel(); event.preventDefault(); event.stopImmediatePropagation();
      if (event.touches.length === 2) {
        const next = span(event.touches);
        if (distance > 0 && Math.abs(next / distance - 1) > 0.1) {
          Phone.fontStep(next > distance ? 1 : -1); distance = next;
        }
      }
      return;
    }
    if (held) { event.preventDefault(); event.stopImmediatePropagation(); return; }
    if (!start || event.touches.length !== 1) return;
    const touch = event.touches[0];
    if (!dragging && Math.hypot(touch.clientX - start.x, touch.clientY - start.y) <= 8) return;
    dragging = true;
    cancel(); event.preventDefault(); event.stopImmediatePropagation();
    // A screen/mouse-mode transition ends this gesture, rather than sending input to a new app.
    if (start.mode !== mode()) { discarded = true; return; }
    const height = rect().height / terminal.rows;
    if (height <= 0) return;
    const delta = start.remainder + start.lastY - touch.clientY;
    const rows = Math.trunc(delta / height);
    start.lastY = touch.clientY;
    start.remainder = delta - rows * height;
    scroll(rows);
  }, { passive: false, capture: true });
  function end(event) {
    cancel();
    if (held || pinching || dragging || discarded) { event.preventDefault(); event.stopImmediatePropagation(); }
    if (!event.touches.length) { pinching = false; held = false; dragging = false; discarded = false; start = null; }
  }
  root.addEventListener('touchend', end, { passive: false, capture: true });
  root.addEventListener('touchcancel', event => { interrupt(); end(event); }, { passive: false, capture: true });
  root.addEventListener('contextmenu', event => event.preventDefault());
  terminal.onSelectionChange(update);
  terminal.onScroll(update);
  terminal.onRender(update);
  function interrupt() {
    cancel(); start = null; pinching = false; held = false; dragging = false; discarded = true;
  }
  terminal.buffer.onBufferChange(() => { if (start) { cancel(); discarded = true; } });
  window.addEventListener('resize', () => { interrupt(); update(); });
  window.addEventListener('blur', interrupt);
  const bottom = document.createElement('button');
  bottom.className = 'terminal-bottom'; bottom.textContent = '回到底部 ↓';
  bottom.addEventListener('click', () => { terminal.clearSelection(); terminal.scrollToBottom(); });
  document.body.appendChild(bottom);
  const bottomState = () => { bottom.hidden = terminal.buffer.active.viewportY >= terminal.buffer.active.baseY; };
  terminal.onScroll(bottomState); terminal.onRender(bottomState); bottomState();
  // Native accessibility/menu action offers selection without a precision long press.
  TerminalUI.selectVisible = () => {
    terminal.select(0, terminal.buffer.active.viewportY, terminal.rows * terminal.cols);
  };
}
