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
    const point = await page.evaluate(() => { const r = document.querySelector('.xterm-screen').getBoundingClientRect(); return [r.left + 20, r.top + 8]; });
    await touch('touchStart', [point]); await page.waitForTimeout(650); await touch('touchEnd', []);
    assert.equal(await page.evaluate(() => TerminalUI.selection()), 'hello');
    assert.equal(await page.evaluate(() => events.selection), true);
    const handle = await page.locator('.terminal-handle').nth(1).boundingBox();
    await touch('touchStart', [[handle.x + 16, handle.y + 12]]);
    await touch('touchMove', [[point[0] + 67, point[1] + 14]]);
    await touch('touchEnd', []);
    assert.match(await page.evaluate(() => TerminalUI.selection()), /hello 世界/);
    await page.evaluate(() => TerminalUI.clearSelection());
    assert.equal(await page.evaluate(() => events.selection), false);
    await touch('touchStart', [[100, 180], [200, 180]]);
    await touch('touchMove', [[70, 180], [230, 180]]);
    await touch('touchEnd', []);
    assert.ok(await page.evaluate(() => events.font > 14), 'Pinch changes font size');
    await write(Array.from({length: 150}, (_, i) => `history ${i}\r\n`).join(''));
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
    assert.deepEqual(errors, []);
    await page.close();
  }
  console.log('PASS: terminal modifiers, protocol replies, application cursor mode, bracketed paste, long-press selection/handles, pinch and scroll preservation (normal + legacy DOM)');
} finally { await browser.close(); }
