"use strict";
const $ = (id) => document.getElementById(id);
let csrf = "",
  selected = [],
  items = [],
  offset = 0,
  hasMore = false,
  working = false,
  editId = "";
let tasks = [],
  refreshVersion = 0;
let pickedItems = new Set();
try {
  tasks = JSON.parse(
    sessionStorage.getItem("myserver-inbox-transfers") || "[]",
  ).map((t) => ({ ...t, status: "paused", files: null }));
} catch (_) {}
const bytes = (n) =>
  n < 1024
    ? n + " B"
    : n < 1048576
      ? (n / 1024).toFixed(1) + " KB"
      : n < 1073741824
        ? (n / 1048576).toFixed(1) + " MB"
        : (n / 1073741824).toFixed(2) + " GB";
let messageTimer;
function message(text = "") {
  clearTimeout(messageTimer);
  $("message").textContent = text;
  if (text)
    messageTimer = setTimeout(() => {
      $("message").textContent = "";
    }, 8000);
}
function saveTasks() {
  try {
    sessionStorage.setItem(
      "myserver-inbox-transfers",
      JSON.stringify(
        tasks.map(({ manifest, originals }) => ({ manifest, originals })),
      ),
    );
    return true;
  } catch (_) {
    message("浏览器无法保存传输状态，请检查浏览器存储空间。");
    return false;
  }
}
async function api(path, method = "GET", body, signal) {
  const headers = { "X-CSRF-Token": csrf };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch("/api" + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });
  const result = await response.json();
  if (!response.ok) {
    if (response.status === 401) showLogin();
    const error = new Error(result.error || "操作失败");
    error.status = response.status;
    throw error;
  }
  return result;
}
function node(tag, text, cls) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (cls) element.className = cls;
  return element;
}
function button(text, action, cls = "quiet") {
  const b = node("button", text, cls);
  b.type = "button";
  b.onclick = () => Promise.resolve(action()).catch((e) => message(e.message));
  return b;
}
function showLogin() {
  $("login").hidden = false;
  $("workspace").hidden = true;
  $("logout").hidden = true;
  csrf = "";
  pickedItems.clear();
}
async function enter(value) {
  csrf = value;
  $("token").value = "";
  $("login").hidden = true;
  $("workspace").hidden = false;
  $("logout").hidden = false;
  renderTasks();
  await refresh();
}
$("login-form").onsubmit = async (event) => {
  event.preventDefault();
  try {
    const result = await api("/login", "POST", {
      token: $("token").value.trim(),
    });
    await enter(result.csrf);
    message();
  } catch (e) {
    message(e.message);
  }
};
$("logout").onclick = async () => {
  try {
    for (const task of tasks) if (task.controller) task.controller.abort();
    await api("/logout", "POST", {});
    showLogin();
  } catch (e) {
    message(e.message);
  }
};
function pick(files) {
  const next = Array.from(files);
  if (next.length + selected.length > 32) {
    message("每次最多选择 32 个文件。");
    return;
  }
  selected.push(...next);
  renderSelected();
  message();
}
function renderSelected() {
  $("selected").replaceChildren();
  selected.forEach((file, index) => {
    const chip = node("span", file.name + " · " + bytes(file.size), "chip");
    chip.append(
      button("×", () => {
        selected.splice(index, 1);
        renderSelected();
      }),
    );
    $("selected").append(chip);
  });
}
$("files").onchange = () => {
  pick($("files").files);
  $("files").value = "";
};
const zone = $("dropzone");
for (const event of ["dragenter", "dragover"])
  zone.addEventListener(event, (e) => {
    e.preventDefault();
    zone.classList.add("drag");
  });
for (const event of ["dragleave", "drop"])
  zone.addEventListener(event, (e) => {
    e.preventDefault();
    zone.classList.remove("drag");
  });
zone.addEventListener("drop", (e) => pick(e.dataTransfer.files));
zone.addEventListener("paste", (e) => {
  const files = Array.from(e.clipboardData.items)
    .filter((x) => x.kind === "file")
    .map((x) => x.getAsFile())
    .filter(Boolean);
  if (files.length) {
    e.preventDefault();
    pick(files);
  }
});
$("send").onclick = () => {
  const text = $("text").value;
  if (!text.trim() && !selected.length) {
    message("先添加文件或输入文字。");
    return;
  }
  const files = selected.slice();
  const manifest = {
    id: crypto.randomUUID(),
    text,
    note: "",
    source: "browser",
    files: files.map((f) => ({
      id: crypto.randomUUID(),
      name: f.name,
      size: f.size,
      mime: f.type || "application/octet-stream",
    })),
  };
  tasks.push({
    manifest,
    files,
    originals: files.map((f) => ({
      name: f.name,
      size: f.size,
      modified: f.lastModified,
    })),
    status: "queued",
    done: 0,
  });
  selected = [];
  $("text").value = "";
  renderSelected();
  saveTasks();
  renderTasks();
  pump();
};
const FINGERPRINT_CHUNK = 4 * 1024 * 1024;
function aborted(signal) {
  if (signal?.aborted) throw new DOMException("Paused", "AbortError");
}
// A domain-separated hash chain uses bounded memory regardless of file size.
// Unlike name/size/mtime, it binds a resumed upload to every byte of its original file.
async function fileFingerprint(file, signal, progress = () => {}) {
  aborted(signal);
  let chain = new Uint8Array(
    await crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(
        "myserver-inbox-file-v1\n" + file.size + "\n" + FINGERPRINT_CHUNK,
      ),
    ),
  );
  for (let at = 0; at < file.size; at += FINGERPRINT_CHUNK) {
    aborted(signal);
    const end = Math.min(file.size, at + FINGERPRINT_CHUNK);
    const block = await file.slice(at, end).arrayBuffer();
    aborted(signal);
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", block));
    aborted(signal);
    const pair = new Uint8Array(64);
    pair.set(chain);
    pair.set(digest, 32);
    chain = new Uint8Array(await crypto.subtle.digest("SHA-256", pair));
    aborted(signal);
    progress(end);
  }
  aborted(signal);
  return Array.from(chain, (b) => b.toString(16).padStart(2, "0")).join("");
}
async function prepareFiles(task, files, signal) {
  task.phase = "checking";
  task.hashDone = 0;
  renderTasks();
  let before = 0;
  const result = [];
  for (const file of files) {
    const preceding = before;
    const fingerprint = await fileFingerprint(file, signal, (bytes) => {
      task.hashDone = preceding + bytes;
      renderTasks();
    });
    result.push({ file, fingerprint });
    before += file.size;
  }
  return result;
}
async function ensureFingerprints(task, signal) {
  if (task.verified) return;
  const hashes = await prepareFiles(task, task.files || [], signal);
  aborted(signal);
  for (let i = 0; i < hashes.length; i++) {
    const original = task.originals[i];
    if (original.fingerprint && original.fingerprint !== hashes[i].fingerprint)
      throw new Error("文件内容已改变，已停止续传。请作为新收件发送。");
    original.fingerprint = hashes[i].fingerprint;
  }
  // No server upload is created until all original content fingerprints are durable.
  if (!saveTasks()) throw new Error("无法保存文件校验记录，上传尚未开始。");
  task.verified = true;
}
function renderTasks() {
  $("transfers").replaceChildren();
  for (const task of tasks) {
    const line = node("div", undefined, "transfer");
    const top = node("div", undefined, "transfer-top");
    const title = node(
      "div",
      (task.manifest.files[0]?.name || task.manifest.text.trim().slice(0, 40)) +
        (task.manifest.files.length > 1
          ? " 等 " + task.manifest.files.length + " 个文件"
          : ""),
      "transfer-title",
    );
    const actions = node("div");
    if (task.status === "running" || task.status === "verifying")
      actions.append(
        button("暂停", () => {
          task.status = "paused";
          task.controller?.abort();
          renderTasks();
        }),
      );
    else if (task.controller) {
      const waiting = button("正在暂停", () => {});
      waiting.disabled = true;
      actions.append(waiting);
    } else
      actions.append(
        button(
          task.files || !task.originals.length ? "继续" : "重新选择文件",
          () => resume(task),
        ),
      );
    actions.append(
      button("取消", async () => {
        task.status = "canceling";
        task.controller?.abort();
        try {
          await api("/inbox/uploads/" + task.manifest.id, "DELETE");
          tasks = tasks.filter((t) => t !== task);
          saveTasks();
          renderTasks();
        } catch (e) {
          task.status = "paused";
          task.error = "取消未完成：" + e.message;
          renderTasks();
        }
      }),
    );
    top.append(title, actions);
    line.append(top);
    const checking =
      task.phase === "checking" &&
      (task.status === "running" || task.status === "verifying");
    const label = checking
      ? "正在校验文件"
      : task.status === "running"
        ? "正在传输"
        : task.status === "queued"
          ? "等待传输"
          : task.status === "canceling"
            ? "正在取消"
            : task.error || "已暂停，可继续传输";
    line.append(node("small", label));
    const bar = node("progress");
    bar.max = Math.max(
      1,
      task.manifest.files.reduce((sum, f) => sum + f.size, 0),
    );
    bar.value = checking ? task.hashDone || 0 : task.done || 0;
    line.append(bar);
    $("transfers").append(line);
  }
}
function resume(task) {
  if (task.files || !task.originals.length) {
    task.status = "queued";
    task.error = "";
    renderTasks();
    pump();
    return;
  }
  const legacy = task.originals.some((f) => !f.fingerprint);
  if (
    legacy &&
    !confirm(
      "这次传输没有保存内容校验记录。重新选择文件后将从头上传，避免续传时拼接错误内容。",
    )
  )
    return;
  const input = document.createElement("input");
  input.type = "file";
  input.multiple = true;
  input.onchange = async () => {
    const candidates = Array.from(input.files);
    if (candidates.length !== task.originals.length) {
      message("请选择这次传输的全部原文件，不要包含额外文件。");
      return;
    }
    const names = candidates.map((f) => ({ name: f.name, size: f.size }));
    for (const original of task.originals) {
      const index = names.findIndex(
        (f) => f.name === original.name && f.size === original.size,
      );
      if (index < 0) {
        message("请选择上传时的原文件，名称与大小需相同。");
        return;
      }
      names.splice(index, 1);
    }
    task.status = "verifying";
    task.controller = new AbortController();
    task.error = "";
    const signal = task.controller.signal;
    try {
      const hashed = await prepareFiles(task, candidates, signal),
        matched = [];
      for (const original of task.originals) {
        const index = hashed.findIndex(
          ({ file, fingerprint }) =>
            file.name === original.name &&
            file.size === original.size &&
            (legacy || fingerprint === original.fingerprint),
        );
        if (index < 0)
          throw new Error("所选文件内容与原文件不同，已停止续传。");
        matched.push(hashed.splice(index, 1)[0]);
      }
      aborted(signal);
      if (legacy) {
        try {
          await api(
            "/inbox/uploads/" + task.manifest.id,
            "DELETE",
            undefined,
            signal,
          );
        } catch (e) {
          if (e.status === 409) {
            tasks = tasks.filter((t) => t !== task);
            saveTasks();
            renderTasks();
            await refresh();
            message("这条收件已在服务器完成，请从列表查看。");
            return;
          }
          throw e;
        }
        task.manifest.id = crypto.randomUUID();
        for (const file of task.manifest.files) file.id = crypto.randomUUID();
        task.done = 0;
      }
      task.files = matched.map((value) => value.file);
      task.originals = matched.map(({ file, fingerprint }) => ({
        name: file.name,
        size: file.size,
        modified: file.lastModified,
        fingerprint,
      }));
      if (!saveTasks()) throw new Error("无法保存文件校验记录，上传尚未开始。");
      task.verified = true;
      task.status = "queued";
      task.error = "";
      task.phase = "";
      renderTasks();
      if (legacy) message("已重新校验文件，将从头上传。");
    } catch (e) {
      if (task.status !== "canceling") {
        task.status = "paused";
        task.error = e.name === "AbortError" ? "已暂停，可继续校验" : e.message;
      }
      renderTasks();
    } finally {
      task.controller = null;
      renderTasks();
    }
    pump();
  };
  input.click();
}
async function pump() {
  if (working || !csrf) return;
  working = true;
  try {
    let task;
    while ((task = tasks.find((t) => t.status === "queued"))) {
      task.status = "running";
      task.controller = new AbortController();
      task.error = "";
      renderTasks();
      const signal = task.controller.signal;
      try {
        await ensureFingerprints(task, signal);
        aborted(signal);
        task.phase = "uploading";
        renderTasks();
        let progress = await api(
          "/inbox/uploads",
          "POST",
          task.manifest,
          signal,
        );
        task.done = progress.files.reduce((sum, f) => sum + f.offset, 0);
        if (progress.state !== "ready") {
          for (let index = 0; index < task.manifest.files.length; index++) {
            const file = task.manifest.files[index],
              local = task.files[index];
            let at = progress.files.find((f) => f.id === file.id).offset;
            while (at < file.size) {
              if (task.status !== "running")
                throw new DOMException("Paused", "AbortError");
              const end = Math.min(at + 4 * 1024 * 1024, file.size);
              const response = await fetch(
                "/api/transfer?" +
                  new URLSearchParams({
                    item: task.manifest.id,
                    file: file.id,
                    offset: at,
                  }),
                {
                  method: "PUT",
                  headers: {
                    "X-CSRF-Token": csrf,
                    "Content-Type": "application/octet-stream",
                  },
                  body: local.slice(at, end),
                  signal,
                },
              );
              const result = await response.json();
              if (!response.ok) throw new Error(result.error || "文件传输失败");
              if (result.offset !== end)
                throw new Error("服务器上传进度不一致，请重试");
              task.done += end - at;
              at = end;
              renderTasks();
            }
          }
          await api(
            "/inbox/uploads/" + task.manifest.id + "/commit",
            "POST",
            {},
            signal,
          );
        }
        tasks = tasks.filter((t) => t !== task);
        saveTasks();
        renderTasks();
        await refresh();
        message("已存入收件箱。");
      } catch (e) {
        if (task.status !== "canceling") {
          task.status = "paused";
          task.error =
            e.name === "AbortError" ? "已暂停，可继续传输" : e.message;
        }
        renderTasks();
      } finally {
        task.controller = null;
        renderTasks();
      }
    }
  } finally {
    working = false;
  }
}
async function refresh(append = false) {
  const version = ++refreshVersion;
  const start = append ? offset : 0;
  const result = await api(
    "/inbox?" +
      new URLSearchParams({ q: $("search").value, offset: start, limit: 50 }),
  );
  if (version !== refreshVersion) return;
  items = append ? items.concat(result.items) : result.items;
  const loaded = new Set(items.map((item) => item.id));
  pickedItems = new Set([...pickedItems].filter((id) => loaded.has(id)));
  offset = result.next_offset;
  hasMore = result.has_more;
  $("storage").textContent =
    bytes(result.storage.used_bytes) +
    " / " +
    bytes(result.storage.quota_bytes);
  $("more").hidden = !hasMore;
  renderItems();
}
function selectionEntries(selection = pickedItems) {
  return items.filter((item) => selection.has(item.id));
}
function withinSelectionLimit(selection) {
  const chosen = selectionEntries(selection);
  if (
    chosen.length > 32 ||
    chosen.reduce((total, item) => total + item.files.length, 0) > 64
  ) {
    message("每次最多下载 32 条收件、64 个附件，请减少选择。");
    return false;
  }
  return true;
}
function renderSelection() {
  const chosen = selectionEntries();
  const count = chosen.length;
  $("selection-count").textContent = count
    ? "已选 " + count + " 条收件"
    : "未选择收件";
  $("download-selected").disabled = count === 0;
  $("select-loaded").disabled = items.length === 0;
  $("select-loaded").checked = items.length > 0 && count === items.length;
  $("select-loaded").indeterminate = count > 0 && count < items.length;
}
$("select-loaded").onchange = () => {
  const proposed = $("select-loaded").checked
    ? new Set(items.map((item) => item.id))
    : new Set();
  if (withinSelectionLimit(proposed)) pickedItems = proposed;
  renderItems();
};
$("download-selected").onclick = async () => {
  try {
    const chosen = selectionEntries();
    if (!chosen.length || !withinSelectionLimit(pickedItems)) return;
    await api("/session");
    const link = document.createElement("a");
    link.href =
      "/api/bundle?" +
      new URLSearchParams({ items: chosen.map((item) => item.id).join(",") });
    link.click();
  } catch (error) {
    message(error.message);
  }
};
function renderItems() {
  renderSelection();
  $("items").replaceChildren();
  if (!items.length) {
    $("items").append(
      node(
        "div",
        $("search").value
          ? "没有找到匹配的收件。"
          : "还没有收件，先发送一个文件或一段文字。",
        "empty",
      ),
    );
    return;
  }
  for (const item of items) {
    const card = node("article", undefined, "item"),
      top = node("div", undefined, "item-top");
    const heading = node("div", undefined, "item-title");
    const checkbox = node("input");
    checkbox.type = "checkbox";
    checkbox.className = "item-checkbox";
    checkbox.setAttribute("aria-label", "选择收件：" + item.title);
    checkbox.checked = pickedItems.has(item.id);
    checkbox.onchange = () => {
      if (checkbox.checked) {
        const proposed = new Set([...pickedItems, item.id]);
        if (!withinSelectionLimit(proposed)) {
          checkbox.checked = false;
          return;
        }
        pickedItems = proposed;
      } else pickedItems.delete(item.id);
      renderSelection();
    };
    heading.append(checkbox, node("h2", item.title));
    top.append(heading);
    top.append(
      node(
        "span",
        new Date(item.created_at).toLocaleString([], {
          month: "short",
          day: "numeric",
          hour: "2-digit",
          minute: "2-digit",
        }) +
          " · " +
          ({ browser: "电脑", server: "服务器", Android: "手机" }[
            item.source
          ] || item.source),
        "item-time",
      ),
    );
    card.append(top);
    if (item.text) card.append(node("div", item.text, "item-text"));
    if (item.note) card.append(node("p", item.note, "item-note"));
    for (const file of item.files) {
      const link = node("a", undefined, "file-link");
      link.href = "/api/download/" + item.id + "/" + file.id;
      link.append(
        node("span", "↓ " + file.name),
        node("span", bytes(file.size)),
      );
      card.append(link);
    }
    const actions = node("div", undefined, "item-actions");
    if (item.text)
      actions.append(
        button("复制文字", async () => {
          await navigator.clipboard.writeText(item.text);
          message("文字已复制。");
        }),
      );
    actions.append(
      button("编辑", () => {
        editId = item.id;
        $("edit-title").value = item.title;
        $("edit-note").value = item.note;
        $("edit").showModal();
      }),
    );
    actions.append(
      button("删除", async () => {
        if (
          !confirm(
            "删除“" + item.title + "”？这会从服务器移除该收件和全部附件。",
          )
        )
          return;
        await api("/inbox/items/" + item.id, "DELETE");
        await refresh();
      }),
    );
    card.append(actions);
    $("items").append(card);
  }
}
$("edit-cancel").onclick = () => $("edit").close();
$("edit-form").onsubmit = async (event) => {
  event.preventDefault();
  try {
    await api("/inbox/items/" + editId, "PATCH", {
      title: $("edit-title").value,
      note: $("edit-note").value,
    });
    $("edit").close();
    await refresh();
  } catch (e) {
    message(e.message);
  }
};
$("refresh").onclick = () => refresh().catch((e) => message(e.message));
$("more").onclick = () => refresh(true).catch((e) => message(e.message));
let searchTimer;
$("search").oninput = () => {
  pickedItems.clear();
  renderItems();
  clearTimeout(searchTimer);
  searchTimer = setTimeout(
    () => refresh().catch((e) => message(e.message)),
    250,
  );
};
api("/session")
  .then((value) => enter(value.csrf))
  .catch(() => showLogin());
