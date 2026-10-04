// Maintainer interaction checks. Requires Playwright + Chromium; no production server.
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const {chromium} = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../assets/terminal/', import.meta.url));
const browser = await chromium.launch({headless: true});
try {
  for (const legacy of [false, true]) {
    const page = await browser.newPage({viewport: {width: 390, height: 660}, hasTouch: true});
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.route('https://terminal.invalid/**', route => {
      const name = new URL(route.request().url()).pathname.slice(1);
      return route.fulfill({path: root + name, contentType: name.endsWith('.js') ? 'application/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html'});
    });
    await page.addInitScript(legacy => {
      window.events = {input: [], acks: [], ready: false, modifiers: [0, 0], selection: false, font: 14};
      window.Phone = {
        input: v => events.input.push(atob(v)), resize() {}, ack: id => events.acks.push(id), ready: () => events.ready = true,
        modifiersChanged: (c, a) => events.modifiers = [c, a], selectionChanged: active => events.selection = active,
        fontStep: step => { events.font += step; TerminalUI.font(events.font); }
      };
      if (legacy) {
        delete window.WeakRef; delete window.FinalizationRegistry;
        delete Element.prototype.replaceChildren; delete DocumentFragment.prototype.replaceChildren;
        delete Promise.allSettled; delete String.prototype.replaceAll;
        delete MediaQueryList.prototype.addEventListener; delete MediaQueryList.prototype.removeEventListener;
      }
    }, legacy);
    await page.goto('https://terminal.invalid/index.html');
    await page.waitForFunction(() => events.ready);
    await page.waitForTimeout(100);
    assert.equal(await page.evaluate(() => {
      const r = document.querySelector('.xterm-screen').getBoundingClientRect();
      return r.bottom <= innerHeight - 8 && r.right <= innerWidth - 8;
    }), true, 'Entire terminal grid must fit inside its padding, including the last row and column');
    let serial = 0;
    const write = async text => {
      const id = ++serial;
      await page.evaluate(({text, id}) => TerminalUI.write(text, id), {text: Buffer.from(text).toString('base64'), id});
      await page.waitForFunction(id => events.acks.includes(id), id);
    };
    const inputs = async fn => {
      await page.evaluate(() => { events.input = []; TerminalUI.resetModifiers(); });
      await page.evaluate(fn);
      return page.evaluate(() => events.input);
    };
    assert.deepEqual(await inputs(() => { TerminalUI.modifier('CTRL', false); TerminalUI.key('c'); TerminalUI.key('c'); }), ['\x03', 'c']);
    assert.deepEqual(await inputs(() => { TerminalUI.modifier('CTRL', true); TerminalUI.key('a'); TerminalUI.key('e'); TerminalUI.modifier('CTRL', false); TerminalUI.key('c'); }), ['\x01', '\x05', 'c']);
    assert.deepEqual(await inputs(() => { TerminalUI.modifier('ALT', false); TerminalUI.key('b'); TerminalUI.key('f'); }), ['\x1bb', 'f']);
    assert.deepEqual(await inputs(() => { TerminalUI.modifier('CTRL', false); TerminalUI.modifier('ALT', false); TerminalUI.special('LEFT'); TerminalUI.special('UP'); }), ['\x1b[1;7D', '\x1b[A']);
    assert.deepEqual(await inputs(() => { TerminalUI.volumeControl(true); TerminalUI.key(' '); TerminalUI.key('?'); TerminalUI.volumeControl(false); TerminalUI.key('z'); }), ['\x00', '\x7f', 'z']);
    const functionCodes = ['\x1bOP', '\x1bOQ', '\x1bOR', '\x1bOS', '\x1b[15~', '\x1b[17~', '\x1b[18~', '\x1b[19~', '\x1b[20~', '\x1b[21~', '\x1b[23~', '\x1b[24~'];
    assert.deepEqual(await inputs(() => { for (let i = 1; i <= 12; i++) TerminalUI.special('F' + i); }), functionCodes);
    assert.deepEqual(await inputs(() => { TerminalUI.special('INSERT'); TerminalUI.special('DELETE'); TerminalUI.special('BACKTAB'); }), ['\x1b[2~', '\x1b[3~', '\x1b[Z']);
    assert.deepEqual(await inputs(() => {
      TerminalUI.modifier('CTRL', false); TerminalUI.special('F1'); TerminalUI.special('F1');
      TerminalUI.modifier('ALT', false); TerminalUI.special('F12');
      TerminalUI.modifier('CTRL', false); TerminalUI.modifier('ALT', false); TerminalUI.special('DELETE');
      TerminalUI.modifier('CTRL', false); TerminalUI.special('BACKTAB');
    }), ['\x1b[1;5P', '\x1bOP', '\x1b[24;3~', '\x1b[3;7~', '\x1b[1;6Z']);
    assert.deepEqual(await inputs(() => {
      TerminalUI.special('SHIFT+F1'); TerminalUI.special('SHIFT+UP'); TerminalUI.special('SHIFT+DELETE');
      TerminalUI.modifier('CTRL', false); TerminalUI.special('SHIFT+F1');
      TerminalUI.modifier('ALT', false); TerminalUI.special('SHIFT+UP');
      TerminalUI.modifier('CTRL', false); TerminalUI.modifier('ALT', false); TerminalUI.special('SHIFT+DELETE');
      TerminalUI.special('SHIFT+TAB'); TerminalUI.special('SHIFT+ENTER');
      TerminalUI.modifier('CTRL', false); TerminalUI.special('SHIFT+TAB');
    }), ['\x1b[1;2P', '\x1b[1;2A', '\x1b[3;2~', '\x1b[1;6P', '\x1b[1;4A', '\x1b[3;8~', '\x1b[Z', '\r', '\x1b[1;6Z'], 'Native Shift must combine with Fn/navigation/editing keys and Ctrl/Alt');
    // Check virtual F keys against the pinned renderer's physical keyboard encoding.
    await inputs(() => TerminalUI.focus());
    for (let i = 1; i <= 12; i++) await page.keyboard.press('F' + i);
    assert.deepEqual(await page.evaluate(() => events.input), functionCodes);
    assert.deepEqual(await inputs(() => {
      for (const key of ['/', ' ', '[', '\\', ']', '^', '_']) {
        TerminalUI.modifier('CTRL', false); TerminalUI.key(key);
      }
      TerminalUI.key('/');
    }), ['\x1f', '\x00', '\x1b', '\x1c', '\x1d', '\x1e', '\x1f', '/']);
    await inputs(() => TerminalUI.focus());
    await page.keyboard.press('Control+/');
    assert.deepEqual(await page.evaluate(() => events.input), ['\x1f'], 'Physical Ctrl+/ must match the virtual chord');
    assert.deepEqual(await inputs(() => { TerminalUI.modifier('CTRL', false); TerminalUI.modifier('ALT', false); TerminalUI.special('/'); TerminalUI.special('/'); }), ['\x1b\x1f', '/']);
    assert.deepEqual(await inputs(() => { TerminalUI.modifier('CTRL', false); TerminalUI.special('BACKSPACE'); }), ['\x08']);
    assert.deepEqual(await inputs(() => { TerminalUI.modifier('CTRL', false); TerminalUI.key('ß'); }), ['\xc3\x9f']);
    await inputs(() => { TerminalUI.focus(); TerminalUI.modifier('CTRL', true); });
    await page.keyboard.press('F5');
    await page.keyboard.press('Alt+ArrowLeft');
    await page.keyboard.press('Delete');
    assert.deepEqual(await page.evaluate(() => events.input), ['\x1b[15;5~', '\x1b[1;7D', '\x1b[3;5~'], 'Sticky modifiers must combine with physical special keys');
    await inputs(() => { TerminalUI.focus(); TerminalUI.modifier('CTRL', true); });
    await page.keyboard.press('Control+8');
    await page.keyboard.press('Backspace');
    assert.deepEqual(await page.evaluate(() => events.input), ['\x7f', '\x08'], 'An already encoded physical Ctrl chord must not be interpreted as Backspace again');
    await inputs(() => TerminalUI.focus());
    await page.keyboard.press('Tab');
    assert.equal(await page.evaluate(() => document.activeElement === terminal.textarea), true, 'Tab must keep focus in the terminal');
    await page.keyboard.press('Shift+Tab');
    assert.equal(await page.evaluate(() => document.activeElement === terminal.textarea), true, 'Backtab must keep focus in the terminal');
    await page.keyboard.press('Control+Shift+Tab');
    await page.keyboard.press('Alt+Shift+Tab');
    assert.deepEqual(await page.evaluate(() => events.input), ['\t', '\x1b[Z', '\x1b[1;6Z', '\x1b[1;4Z'], 'Tab and Backtab must preserve physical Ctrl/Alt while leaving plain Shift+Tab canonical');
    await write('\x1b[?1h');
    assert.deepEqual(await inputs(() => { TerminalUI.special('UP'); TerminalUI.special('HOME'); TerminalUI.special('PGDN'); }), ['\x1bOA', '\x1bOH', '\x1b[6~']);
    await write('\x1b[?1l\x1b[?2004h');
    assert.deepEqual(await inputs(() => { TerminalUI.modifier('CTRL', true); TerminalUI.paste('abc\ndef'); }), ['\x1b[200~abc\rdef\x1b[201~']);
    await page.evaluate(() => { events.input = []; TerminalUI.resetModifiers(); TerminalUI.modifier('CTRL', false); TerminalUI.modifier('ALT', false); });
    await write('\x1b[6n');
    assert.match((await page.evaluate(() => events.input))[0], /^\x1b\[\d+;\d+R$/);
    assert.deepEqual(await page.evaluate(() => events.modifiers), [1, 1], 'Protocol reply must not consume modifiers');
    await page.evaluate(() => { events.input = []; TerminalUI.resetModifiers(); TerminalUI.focus(); TerminalUI.modifier('CTRL', false); });
    await page.keyboard.type('c');
    await page.keyboard.type('pwd');
    await page.keyboard.press('Enter');
    assert.equal(await page.evaluate(() => events.input.join('')), '\x03pwd\r');
    await write('\x1b[2J\x1b[Hhello 世界\r\nsecond line\r\n');
    const cdp = await page.context().newCDPSession(page);
    const touch = (type, points) => cdp.send('Input.dispatchTouchEvent', {type, touchPoints: points.map(([x, y], id) => ({x, y, id}))});
    const swipe = async (dy, x = 120) => {
      await touch('touchStart', [[x, 180]]);
      for (let step = 1; step <= 5; step++) await touch('touchMove', [[x, 180 + dy * step / 5]]);
      await touch('touchEnd', []);
    };
    await write('\x1b[?1049h\x1b[?1000h\x1b[?1006h');
    await inputs(() => {});
    await swipe(100);
    let reports = await page.evaluate(() => events.input);
    assert.ok(reports.length > 0 && reports.every(v => /^\x1b\[<64;\d+;\d+M$/.test(v)), 'Touch swipe must reach a mouse-enabled TUI as wheel-up reports');
    await inputs(() => {});
    await swipe(-100);
    reports = await page.evaluate(() => events.input);
    assert.ok(reports.length > 0 && reports.every(v => /^\x1b\[<65;\d+;\d+M$/.test(v)), 'Reverse swipe must emit wheel-down, without phantom clicks');
    // Coordinates remain anchored to the original pane throughout the gesture.
    assert.equal(new Set(reports.map(v => v.slice(6))).size, 1);
    await write('\x1b[?1006l');
    await inputs(() => {});
    await swipe(80);
    reports = await page.evaluate(() => events.input);
    assert.ok(reports.length > 0 && reports.every(v => /^\x1b\[M`[\s\S]{2}$/.test(v)), 'Legacy mouse encoding must remain delegated to xterm');
    // A gesture cannot resume after briefly leaving the application it started in.
    await inputs(() => {});
    await touch('touchStart', [[120, 180]]);
    await write('\x1b[?1000l\x1b[?1049l');
    await touch('touchMove', [[120, 220]]);
    await write('\x1b[?1049h\x1b[?1000h');
    await touch('touchMove', [[120, 280]]);
    await touch('touchEnd', []);
    assert.deepEqual(await page.evaluate(() => events.input), [], 'A mode change must invalidate the whole gesture, even if the old mode returns');
    await touch('touchStart', [[120, 180]]);
    await write('\x1b[?1049l\x1b[?1049h');
    await touch('touchMove', [[120, 280]]);
    await touch('touchEnd', []);
    assert.deepEqual(await page.evaluate(() => events.input), [], 'A buffer transition must cancel the gesture even before its first movement');
    // Locking or backgrounding during pinch cannot leave the next swipe stuck in pinch mode.
    await touch('touchStart', [[100, 180], [200, 180]]);
    await page.evaluate(() => window.dispatchEvent(new Event('blur')));
    await touch('touchMove', [[70, 180], [230, 180]]);
    await touch('touchCancel', []);
    assert.deepEqual(await page.evaluate(() => events.input), [], 'Cancelled and blurred gestures must not send clicks or scroll reports');
    await inputs(() => {});
    await swipe(80);
    assert.ok(await page.evaluate(() => events.input.length > 0), 'A fresh swipe must work after a cancelled pinch');
    await write('\x1b[?1000l\x1b[?1h');
    await inputs(() => {});
    await swipe(100);
    assert.match(await page.evaluate(() => events.input.join('')), /^(\x1bOA)+$/, 'Alternate-screen swipe must send application cursor keys without mouse mode');
    await write('\x1b[?1l\x1b[?1049l');
    const point = await page.evaluate(() => { const r = document.querySelector('.xterm-screen').getBoundingClientRect(); return [r.left + 20, r.top + 8]; });
    await write('\x1b[?1000h\x1b[?1006h');
    await inputs(() => {});
    await touch('touchStart', [point]); await page.waitForTimeout(650); await touch('touchEnd', []);
    assert.deepEqual(await page.evaluate(() => events.input), [], 'Long-press selection must not send remote clicks');
    await write('\x1b[?1000l\x1b[?1006l');
    assert.equal(await page.evaluate(() => TerminalUI.selection()), 'hello');
    assert.equal(await page.evaluate(() => events.selection), true);
    const handle = await page.locator('.terminal-handle').nth(1).boundingBox();
    await touch('touchStart', [[handle.x + 16, handle.y + 12]]);
    await touch('touchMove', [[point[0] + 67, point[1] + 14]]);
    await touch('touchEnd', []);
    assert.match(await page.evaluate(() => TerminalUI.selection()), /hello 世界/);
    // Android's IME can finish resizing after a long press has selected text.
    // Exercise real fit/layout events, including a multiline, wide-cell range.
    await page.evaluate(() => terminal.select(0, terminal.buffer.active.viewportY, terminal.cols + 6));
    const selectedBeforeResize = await page.evaluate(() => TerminalUI.selection());
    assert.equal(selectedBeforeResize, 'hello 世界\nsecond');
    const fullRows = await page.evaluate(() => terminal.rows);
    await page.setViewportSize({width: 390, height: 340});
    await page.waitForFunction(rows => terminal.rows < rows, fullRows);
    assert.equal(await page.evaluate(() => TerminalUI.selection()), selectedBeforeResize, 'IME height shrink must preserve exactly the selected text');
    assert.equal(await page.evaluate(() => events.selection), true, 'Selection toolbar must remain active after IME shrink');
    await page.setViewportSize({width: 390, height: 660});
    await page.waitForFunction(rows => terminal.rows === rows, fullRows);
    assert.equal(await page.evaluate(() => TerminalUI.selection()), selectedBeforeResize, 'IME height expansion must preserve exactly the selected text');
    await page.evaluate(() => TerminalUI.clearSelection());
    assert.equal(await page.evaluate(() => events.selection), false);
    await page.setViewportSize({width: 390, height: 340});
    await page.waitForFunction(rows => terminal.rows < rows, fullRows);
    assert.equal(await page.evaluate(() => TerminalUI.selection()), '', 'A resize must not resurrect an explicitly cleared selection');
    await page.setViewportSize({width: 390, height: 660});
    await page.waitForFunction(rows => terminal.rows === rows, fullRows);
    await page.evaluate(() => terminal.select(0, terminal.buffer.active.viewportY, terminal.cols + 6));
    const fullCols = await page.evaluate(() => terminal.cols);
    await page.setViewportSize({width: 280, height: 660});
    await page.waitForFunction(cols => terminal.cols < cols, fullCols);
    assert.equal(await page.evaluate(() => TerminalUI.selection()), '', 'Column reflow must discard the old coordinates rather than copy a different range');
    await page.setViewportSize({width: 390, height: 660});
    await page.waitForFunction(cols => terminal.cols === cols, fullCols);
    assert.equal(await page.evaluate(() => TerminalUI.selection()), '', 'Re-expanding columns must not resurrect the old selection');
    await page.evaluate(() => window.dispatchEvent(new Event('blur')));
    await touch('touchStart', [[100, 180], [200, 180]]);
    await touch('touchMove', [[70, 180], [230, 180]]);
    await touch('touchEnd', []);
    assert.ok(await page.evaluate(() => events.font > 14), 'Pinch changes font size');
    await write(Array.from({length: 150}, (_, i) => `history ${i}\r\n`).join(''));
    await inputs(() => {});
    await swipe(100);
    assert.equal(await page.evaluate(() => terminal.buffer.active.viewportY < terminal.buffer.active.baseY), true, 'Normal shell gestures must scroll local history');
    assert.deepEqual(await page.evaluate(() => events.input), [], 'History browsing must not type arrows into the shell');
    await page.evaluate(() => terminal.scrollToTop());
    await page.waitForTimeout(100);
    assert.equal(await page.locator('.terminal-bottom').isVisible(), true);
    await page.setViewportSize({width: 390, height: 480});
    await page.waitForTimeout(150);
    assert.equal(await page.evaluate(() => terminal.buffer.active.viewportY), 0, 'Resize preserves history reading');
    await page.locator('.terminal-bottom').click();
    assert.equal(await page.evaluate(() => terminal.buffer.active.viewportY === terminal.buffer.active.baseY), true);
    await page.setViewportSize({width: 390, height: 340});
    await page.waitForTimeout(150);
    assert.equal(await page.evaluate(() => terminal.buffer.active.viewportY === terminal.buffer.active.baseY), true, 'Prompt follows IME shrink');
    // At the scrollback limit, shrinking can trim lines and make an old range
    // point at different text. The equality guard must reject that restoration.
    await page.evaluate(() => { terminal.options.scrollback = 1; });
    const rowsBeforeTrim = await page.evaluate(() => terminal.rows);
    await write(Array.from({length: rowsBeforeTrim + 5}, (_, i) => `trim fixture ${i}\r\n`).join(''));
    await page.evaluate(() => terminal.select(0, 0, terminal.cols));
    const beforeTrim = await page.evaluate(() => TerminalUI.selection());
    assert.match(beforeTrim, /^trim fixture \d+$/);
    await page.setViewportSize({width: 390, height: 240});
    await page.waitForFunction(rows => terminal.rows < rows, rowsBeforeTrim);
    assert.notEqual(await page.evaluate(() => terminal.buffer.active.getLine(0).translateToString(true)), beforeTrim, 'Fixture must actually trim the previously selected cells');
    assert.equal(await page.evaluate(() => TerminalUI.selection()), '', 'Changed buffer coordinates must not restore a different text selection');
    assert.deepEqual(errors, []);
    await page.close();
  }
  console.log('PASS: terminal modifiers/Fn keys, Ctrl+/, touch mouse protocols and alternate screen, protocol replies, bracketed paste, selection/handles and IME resize, pinch and history (normal + legacy DOM)');
} finally { await browser.close(); }
