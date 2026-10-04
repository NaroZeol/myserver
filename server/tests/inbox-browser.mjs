import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { mkdtemp, rm, mkdir, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repository = resolve(here, "../..");
const { chromium } = await import(
  process.env.PLAYWRIGHT_MODULE || "playwright"
);
const directory = await mkdtemp(resolve(tmpdir(), "myserver-inbox-browser-"));
const child = spawn(
  "python3",
  [
    "-u",
    "-c",
    `
import sys
from pathlib import Path
from database import initialize
from modules.inbox.web import InboxServer
import json
database=Path(sys.argv[1])/'myserver.sqlite'
initialize(database)
server=InboxServer(database,0,3600,1800)
print(json.dumps(dict(url='http://127.0.0.1:'+str(server.server_address[1]),token=server.token)),flush=True)
server.serve_forever()
`,
    directory,
  ],
  {
    env: { ...process.env, PYTHONPATH: resolve(repository, "server") },
    stdio: ["ignore", "pipe", "pipe"],
  },
);
let browser;
const screenshots =
  process.env.INBOX_SCREENSHOTS || resolve(directory, "screenshots");
await mkdir(screenshots, { recursive: true });
try {
  const ready = await new Promise((resolve, reject) => {
    let output = "";
    child.stdout.on("data", (chunk) => {
      output += chunk;
      if (output.includes("\n")) resolve(JSON.parse(output.split("\n")[0]));
    });
    child.once("exit", (code) => reject(new Error("Fixture exited " + code)));
    child.stderr.on("data", (chunk) => process.stderr.write(chunk));
  });
  browser = await chromium.launch({ headless: true, args: ["--no-sandbox"] });
  const page = await browser.newPage({
    viewport: { width: 1280, height: 900 },
    acceptDownloads: true,
  });
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto(ready.url);
  assert.equal(
    (await page.request.get(ready.url + "/api/inbox")).status(),
    401,
  );
  await page.locator("#token").fill("invalid-fixture-token");
  await page.locator("#login-form button").click();
  await page.waitForFunction(() =>
    document.querySelector("#message").textContent.includes("不正确"),
  );
  await page.locator("#token").fill(ready.token);
  await page.locator("#login-form button").click();
  await page.locator("#workspace").waitFor({ state: "visible" });
  const checks = await page.evaluate(async () => {
    const bytes = new Uint8Array(FINGERPRINT_CHUNK + 127);
    bytes.fill(7);
    const same = new File([bytes], "same.bin", { lastModified: 12345 });
    const original = await fileFingerprint(same);
    const copy = await fileFingerprint(
      new File([bytes], "other-name.bin", { lastModified: 98765 }),
    );
    bytes[bytes.length - 1] = 9;
    const wrong = new File([bytes], "same.bin", { lastModified: 12345 });
    const changed = await fileFingerprint(wrong);
    const controller = new AbortController();
    let canceled = false;
    try {
      await fileFingerprint(same, controller.signal, () => controller.abort());
    } catch (error) {
      canceled = error.name === "AbortError";
    }
    return { original, copy, changed, canceled };
  });
  assert.equal(
    checks.original,
    checks.copy,
    "content identity must not depend on incidental filename/mtime",
  );
  assert.notEqual(
    checks.original,
    checks.changed,
    "changes after the first 4 MiB must change fingerprint",
  );
  assert.equal(
    checks.canceled,
    true,
    "hashing must stop when explicitly paused",
  );
  const staged = await page.evaluate(async () => {
    const bytes = new Uint8Array(FINGERPRINT_CHUNK + 127);
    bytes.fill(7);
    const file = new File([bytes], "same.bin", { lastModified: 12345 });
    const manifest = {
      id: crypto.randomUUID(),
      text: "resume fixture",
      note: "",
      source: "browser",
      files: [
        {
          id: crypto.randomUUID(),
          name: file.name,
          size: file.size,
          mime: "application/octet-stream",
        },
      ],
    };
    await api("/inbox/uploads", "POST", manifest);
    const prefix = 131072;
    const response = await fetch(
      "/api/transfer?" +
        new URLSearchParams({
          item: manifest.id,
          file: manifest.files[0].id,
          offset: 0,
        }),
      {
        method: "PUT",
        headers: {
          "X-CSRF-Token": csrf,
          "Content-Type": "application/octet-stream",
        },
        body: file.slice(0, prefix),
      },
    );
    if (!response.ok) throw new Error("Cannot prepare partial upload");
    sessionStorage.setItem(
      "myserver-inbox-transfers",
      JSON.stringify([
        {
          manifest,
          originals: [
            {
              name: file.name,
              size: file.size,
              modified: file.lastModified,
              fingerprint: await fileFingerprint(file),
            },
          ],
        },
      ]),
    );
    return { id: manifest.id, prefix };
  });
  await page.reload();
  await page.locator("#workspace").waitFor({ state: "visible" });
  // Replace only the OS picker; exercise resume() and its actual onchange handler.
  await page.evaluate(() => {
    const create = document.createElement.bind(document);
    document.createElement = (tag, ...args) => {
      const element = create(tag, ...args);
      if (tag === "input") {
        window.fixturePicker = element;
        element.click = () => {};
      }
      return element;
    };
    window.selectFixture = async (changed) => {
      resume(tasks[0]);
      const input = window.fixturePicker;
      const bytes = new Uint8Array(FINGERPRINT_CHUNK + 127);
      bytes.fill(7);
      if (changed) bytes[bytes.length - 1] = 9;
      const file = new File([bytes], "same.bin", {
        lastModified: 12345,
        type: "application/octet-stream",
      });
      const transfer = new DataTransfer();
      transfer.items.add(file);
      input.files = transfer.files;
      await input.onchange();
    };
  });
  await page.evaluate(() => window.selectFixture(true));
  const rejected = await page.evaluate(async () => ({
    state: tasks[0].status,
    error: tasks[0].error,
    progress: await api("/inbox/uploads/" + tasks[0].manifest.id),
  }));
  assert.equal(rejected.state, "paused");
  assert.match(rejected.error, /内容.*不同/);
  assert.equal(
    rejected.progress.files[0].offset,
    staged.prefix,
    "wrong bytes must never be appended despite matching name, size and mtime",
  );
  await page.evaluate(() => window.selectFixture(false));
  await page.waitForFunction(() => tasks.length === 0, { timeout: 30000 });
  const completed = await page.evaluate(async (id) => {
    const item = (await api("/inbox/items/" + id)).item;
    const data = await (
      await fetch("/api/download/" + item.id + "/" + item.files[0].id)
    ).arrayBuffer();
    return {
      length: data.byteLength,
      last: new Uint8Array(data)[data.byteLength - 1],
      text: item.text,
    };
  }, staged.id);
  assert.equal(completed.length, 4 * 1024 * 1024 + 127);
  assert.equal(completed.last, 7);
  assert.equal(completed.text, "resume fixture");
  let checkedBeforeCreate = false;
  await page.route("**/api/inbox/uploads", async (route) => {
    if (route.request().method() === "POST")
      checkedBeforeCreate = await page.evaluate(() =>
        JSON.parse(sessionStorage.getItem("myserver-inbox-transfers")).every(
          (task) =>
            task.originals.every((file) =>
              /^[0-9a-f]{64}$/.test(file.fingerprint),
            ),
        ),
      );
    await route.continue();
  });
  await page.evaluate(() => {
    selected = [new File(["fresh content"], "fresh.txt")];
    $("send").click();
  });
  await page.waitForFunction(() => tasks.length === 0, { timeout: 30000 });
  assert.equal(
    checkedBeforeCreate,
    true,
    "fingerprints must be persisted before creating a server upload",
  );
  await page
    .locator("#text")
    .fill(
      "桌面验证已完成，接下来在手机检查。\n<script>window.fixtureInjected=true</script>",
    );
  await page.locator("#files").setInputFiles([
    {
      name: "测试材料.bin",
      mimeType: "application/octet-stream",
      buffer: Buffer.from("private fixture bytes"),
    },
    {
      name: "empty.bin",
      mimeType: "application/octet-stream",
      buffer: Buffer.alloc(0),
    },
    {
      name: "<img src=x onerror=alert(1)>.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("safe text"),
    },
  ]);
  await page.locator("#send").click();
  await page.waitForFunction(
    () =>
      tasks.length === 0 &&
      document.querySelectorAll("article.item").length === 3,
  );
  assert.equal(await page.locator("#items script").count(), 0);
  assert.equal(await page.evaluate(() => window.fixtureInjected), undefined);
  const card = page
    .locator("article.item")
    .filter({ hasText: "桌面验证已完成" });
  assert.equal(await card.locator(".file-tile").count(), 3);
  await card.locator(".file-tile").first().click();
  await page.locator("#viewer").waitFor({ state: "visible" });
  const downloadPromise = page.waitForEvent("download");
  await page.locator("#viewer-download").click();
  const download = await downloadPromise;
  assert.equal(download.suggestedFilename(), "测试材料.bin");
  assert.equal(
    (await readFile(await download.path())).toString(),
    "private fixture bytes",
  );
  await page.locator("#viewer-close").click();
  await card.locator(".file-tile").nth(2).click();
  await page.waitForFunction(
    () =>
      document.querySelector("#viewer-content pre")?.textContent ===
      "safe text",
  );
  assert.equal(await page.locator("#viewer script,#viewer iframe").count(), 0);
  await page.locator("#viewer-close").click();
  await card.locator(".item-more summary").click();
  await card.getByRole("button", { name: "编辑", exact: true }).click();
  await page.locator("#edit-title").fill("本周测试材料");
  await page.locator("#edit-note").fill("保留这组文件，稍后在手机继续。");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await page.waitForFunction(
    () =>
      document.querySelector("article.item h2")?.textContent === "本周测试材料",
  );
  await page.locator("#selection-toggle").click();
  await page.locator("#select-loaded").check();
  assert.equal(await page.locator(".item-checkbox:checked").count(), 3);
  assert.equal(
    await page.locator("#selection-count").textContent(),
    "已选 3 条",
  );
  const bundlePromise = page.waitForEvent("download");
  await page.locator("#download-selected").click();
  const bundle = await bundlePromise;
  assert.equal(bundle.suggestedFilename(), "myserver-inbox.zip");
  execFileSync("python3", [
    "-c",
    `
import sys, zipfile
from pathlib import PurePosixPath
with zipfile.ZipFile(sys.argv[1]) as archive:
    assert archive.testzip() is None
    names = archive.namelist()
    assert len(names) == len(set(names)) == 8, names
    assert all(not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts for name in names)
    content = {name: archive.read(name) for name in names}
    assert b'private fixture bytes' in content.values()
    assert b'fresh content' in content.values()
    assert b'safe text' in content.values()
    assert b'' in content.values()
    assert bytes([7]) * (4 * 1024 * 1024 + 127) in content.values()
    assert any(name.endswith('/备注.txt') and '保留这组文件'.encode() in value for name, value in content.items())
`,
    await bundle.path(),
  ]);
  await page.locator("#search").fill("onerror");
  await page.waitForFunction(
    () => document.querySelectorAll("article.item").length === 1,
  );
  assert.equal(await page.locator(".item-checkbox:checked").count(), 0);
  assert.equal(await page.locator("#download-selected").isDisabled(), true);
  await page.locator("#search").fill("nothing-matches-fixture");
  await page.waitForFunction(() =>
    document.querySelector("#items").textContent.includes("没有匹配"),
  );
  await page.locator("#search").fill("");
  await page.waitForFunction(
    () => document.querySelectorAll("article.item").length === 3,
  );
  await page.locator("#selection-toggle").click();
  await page
    .locator("#files")
    .setInputFiles([
      {
        name: "草稿配图.png",
        mimeType: "image/png",
        buffer: Buffer.from(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=",
          "base64",
        ),
      },
    ]);
  await page.locator("#send").click();
  await page.waitForFunction(
    () =>
      tasks.length === 0 &&
      document.querySelectorAll("article.item").length === 4,
  );
  await page.locator("#type-filter").selectOption("image");
  await page.waitForFunction(
    () =>
      document.querySelectorAll("article.item").length === 1 &&
      document.querySelector(".image-tile img")?.naturalWidth > 0,
  );
  await page.locator(".image-tile").click();
  await page.waitForFunction(
    () => document.querySelector("#viewer img")?.naturalWidth > 0,
  );
  await page.locator("#viewer-close").click();
  await page.locator("#filter-toggle").click();
  await page.locator("#source-filter").selectOption("server");
  await page.waitForFunction(
    () => document.querySelectorAll("article.item").length === 0,
  );
  await page.locator("#source-filter").selectOption("computer");
  await page.waitForFunction(
    () => document.querySelectorAll("article.item").length === 1,
  );
  await page.locator("#reset-filters").click();
  await page.waitForFunction(
    () => document.querySelectorAll("article.item").length === 4,
  );
  await page.locator("#sort-filter").selectOption("size");
  await page.waitForFunction(
    () => document.querySelector("article.item h2")?.textContent === "same.bin",
  );
  await page.locator("#sort-filter").selectOption("newest");
  await page.waitForFunction(
    () =>
      document.querySelector("article.item h2")?.textContent === "草稿配图.png",
  );
  await page.locator("#filter-toggle").click();
  await page.screenshot({
    path: resolve(screenshots, "inbox-desktop.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    "Mobile layout overflows",
  );
  await page.screenshot({
    path: resolve(screenshots, "inbox-mobile.png"),
    fullPage: true,
  });
  page.once("dialog", (dialog) => dialog.accept());
  await card.locator(".item-more summary").click();
  await card.getByRole("button", { name: "删除", exact: true }).click();
  await page.waitForFunction(
    () => document.querySelectorAll("article.item").length === 3,
  );
  await page.locator("#selection-toggle").click();
  await page.locator("#select-loaded").check();
  page.once("dialog", (dialog) => dialog.accept());
  await page.locator("#delete-selected").click();
  await page.waitForFunction(
    () => document.querySelectorAll("article.item").length === 0,
  );
  await page.locator("#logout").click();
  await page.locator("#login").waitFor({ state: "visible" });
  assert.equal(
    (await page.request.get(ready.url + "/api/inbox")).status(),
    401,
  );
  assert.deepEqual(errors, []);
  console.log(
    "PASS: browser token login, multi-file upload and resume integrity, image/text previews, type/source/sort filters, ZIP downloads, edit/search/batch delete, logout and responsive layout",
  );
} finally {
  if (browser) await browser.close();
  child.kill("SIGTERM");
  await new Promise((resolve) =>
    child.exitCode !== null ? resolve() : child.once("exit", resolve),
  );
  await rm(directory, { recursive: true, force: true });
}
