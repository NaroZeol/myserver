(() => {
  const $ = (id) => document.getElementById(id);
  const draftKey = "thoughts-draft-v1";
  let editing = null,
    filter = "all",
    offset = 0,
    timer,
    sequence = 0,
    saving = false;
  const status = (message, error = false) => {
    $("status").textContent = message;
    $("status").className = error ? "error" : "";
  };
  const node = (tag, text, cls) => {
    const n = document.createElement(tag);
    n.textContent = text;
    if (cls) n.className = cls;
    return n;
  };
  const date = (value) => new Date(value).toLocaleString("zh-CN");
  async function api(path, method = "GET", body) {
    const response = await fetch(`/api${path}`, {
      method,
      credentials: "same-origin",
      headers: body ? { "Content-Type": "application/json" } : {},
      body: body ? JSON.stringify(body) : undefined,
      cache: "no-store",
    });
    const data = await response.json();
    if (!response.ok) {
      if (response.status === 401) showLogin();
      throw new Error(data.error || "请求失败");
    }
    return data;
  }
  function showLogin() {
    $("login-panel").hidden = false;
    $("notebook").hidden = true;
    $("logout").hidden = true;
    $("items").replaceChildren();
  }
  async function showNotebook() {
    $("login-panel").hidden = true;
    $("notebook").hidden = false;
    $("logout").hidden = false;
    await list(true);
    await publication();
  }
  function draft() {
    try {
      localStorage.setItem(
        draftKey,
        JSON.stringify({
          content: $("content").value,
          tags: $("tags").value,
          editing,
        }),
      );
      $("draft-state").textContent = "草稿已保存在当前设备";
      return true;
    } catch {
      $("draft-state").textContent = "设备存储不可用，请及时复制或保存内容";
      return false;
    }
  }
  function reset() {
    editing = null;
    $("editor").reset();
    $("editor-title").textContent = "记下这一刻。";
    $("cancel-edit").hidden = true;
    draft();
  }
  function loadEditor(item) {
    if (saving) return;
    editing = { id: item.id, version: item.version };
    $("content").value = item.content;
    $("tags").value = item.tags.join(", ");
    $("editor-title").textContent = "编辑想法";
    $("cancel-edit").hidden = false;
    draft();
    $("content").focus();
    window.scrollTo({ top: 0, behavior: "smooth" });
  }
  function actions(card, item) {
    const bar = node("div", "", "record-actions");
    const add = (label, fn) => {
      const button = node("button", label);
      button.type = "button";
      button.onclick = async () => {
        button.disabled = true;
        try {
          await fn();
        } catch (e) {
          status(e.message, true);
        } finally {
          button.disabled = false;
        }
      };
      bar.append(button);
    };
    if (item.deleted_at)
      add("恢复并发布", async () => {
        await api(`/thoughts/${item.id}`, "PATCH", {
          version: item.version,
          restore: true,
        });
        await list(true);
        status("已恢复，等待发布到 Gist");
        await publication(true);
      });
    else {
      add("编辑", () => {
        if (
          $("content").value.trim() &&
          !confirm("当前编辑框有草稿，替换为这条想法？")
        )
          return;
        loadEditor(item);
      });
      add("移到回收站", async () => {
        if (!confirm("移到回收站？之后仍可以恢复。")) return;
        await api(`/thoughts/${item.id}`, "DELETE", { version: item.version });
        await list(true);
        status("已移到回收站，正在更新 Gist");
        await publication(true);
      });
    }
    add("历史", async () => {
      const data = await api(`/thoughts/${item.id}/history`);
      $("history-items").replaceChildren();
      for (const row of data.items) {
        const el = node("article", "", "record");
        el.append(
          node(
            "div",
            `版本 ${row.item.version} · ${date(row.saved_at)}`,
            "record-meta",
          ),
          node("p", row.item.content, "record-content"),
        );
        $("history-items").append(el);
      }
      if (!data.items.length)
        $("history-items").append(node("p", "还没有编辑历史。", "empty"));
      $("history-dialog").showModal();
    });
    card.append(bar);
  }
  async function list(resetList) {
    const current = ++sequence;
    if (resetList) offset = 0;
    $("more").disabled = true;
    const params = new URLSearchParams({
      limit: 50,
      offset,
      q: $("search").value,
    });
    if (filter === "trash") params.set("trash", "1");
    try {
      const data = await api(`/thoughts?${params}`);
      if (current !== sequence) return;
      if (resetList) $("items").replaceChildren();
      if (!data.items.length && !offset)
        $("items").append(
          node(
            "p",
            filter === "trash"
              ? "回收站是空的。"
              : $("search").value
                ? "没有找到相关想法。"
                : "还没有想法，从左侧记下第一条。",
            "empty",
          ),
        );
      for (const item of data.items) {
        const card = node("article", "", "record");
        const meta = node("div", "", "record-meta");
        meta.append(
          node("time", date(item.created_at)),
          node("span", item.deleted_at ? "回收站" : "已保存", "badge"),
        );
        card.append(
          meta,
          node("p", item.content, "record-content"),
          node("div", item.tags.map((t) => `#${t}`).join("  "), "record-tags"),
        );
        actions(card, item);
        $("items").append(card);
      }
      offset = data.next_offset;
      $("more").hidden = !data.has_more;
    } catch (e) {
      status(
        navigator.onLine ? e.message : "当前离线，编辑框中的草稿仍可保存。",
        true,
      );
    } finally {
      if (current === sequence) $("more").disabled = false;
    }
  }
  async function publication(publish = false) {
    $("publish").disabled = true;
    try {
      const data = await api(
        publish ? "/publish" : "/publication",
        publish ? "POST" : "GET",
        publish ? {} : undefined,
      );
      $("publication-status").textContent = data.pending
        ? `已保存到服务器 · 等待发布到 Gist${data.last_error ? "：" + data.last_error : ""}`
        : "已发布到 Gist";
      $("publish").hidden = !data.pending;
    } catch (e) {
      $("publication-status").textContent =
        "无法确认发布状态，服务器会自动重试";
      $("publish").hidden = false;
    } finally {
      $("publish").disabled = false;
    }
  }
  $("publish").onclick = () => publication(true);
  $("login-form").onsubmit = async (event) => {
    event.preventDefault();
    const button = event.submitter;
    button.disabled = true;
    try {
      await api("/login", "POST", { password: $("password").value });
      $("password").value = "";
      status("已登录");
      await showNotebook();
    } catch (e) {
      status(e.message, true);
    } finally {
      button.disabled = false;
    }
  };
  $("logout").onclick = async () => {
    if (
      $("content").value.trim() &&
      !confirm("退出会清除这台设备上的草稿，确认已经保存？")
    )
      return;
    try {
      await api("/logout", "POST", {});
      reset();
      localStorage.removeItem(draftKey);
      showLogin();
      status("已退出");
    } catch (e) {
      status(e.message, true);
    }
  };
  $("editor").onsubmit = async (event) => {
    event.preventDefault();
    if (saving) return;
    saving = true;
    const button = $("editor").querySelector("[type=submit]");
    for (const control of $("editor").elements) control.disabled = true;
    draft();
    try {
      const body = {
        content: $("content").value,
        tags: $("tags")
          .value.split(/[,，]/)
          .map((t) => t.trim())
          .filter(Boolean),
      };
      if (editing) body.version = editing.version;
      else {
        body.id = crypto.randomUUID();
        editing = { id: body.id, pending: true };
        draft();
      }
      if (editing.pending) body.id = editing.id;
      await api(
        editing.pending ? "/thoughts" : `/thoughts/${editing.id}`,
        editing.pending ? "POST" : "PATCH",
        body,
      );
      reset();
      status("已保存到服务器，正在发布到 Gist…");
      await list(true);
      await publication(true);
    } catch (e) {
      status(`${e.message}。草稿已保留，可重试。`, true);
    } finally {
      saving = false;
      for (const control of $("editor").elements) control.disabled = false;
    }
  };
  // Reuse the same ID after an uncertain response, preventing duplicate notes.
  $("content").addEventListener("input", draft);
  $("tags").addEventListener("input", draft);
  $("content").addEventListener("keydown", (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
      $("editor").requestSubmit();
      event.preventDefault();
    }
  });
  $("cancel-edit").onclick = () => {
    if (confirm("清空当前编辑草稿？")) reset();
  };
  document.querySelectorAll("[data-filter]").forEach(
    (button) =>
      (button.onclick = () => {
        filter = button.dataset.filter;
        document
          .querySelectorAll("[data-filter]")
          .forEach((b) => b.setAttribute("aria-pressed", String(b === button)));
        list(true);
      }),
  );
  $("search").oninput = () => {
    clearTimeout(timer);
    timer = setTimeout(() => list(true), 250);
  };
  $("more").onclick = () => list(false);
  $("refresh").onclick = () => {
    list(true);
    publication();
  };
  $("close-history").onclick = () => $("history-dialog").close();
  try {
    const saved = JSON.parse(localStorage.getItem(draftKey));
    if (saved) {
      $("content").value = saved.content || "";
      $("tags").value = saved.tags || "";
      editing = saved.editing;
      if (editing) {
        $("editor-title").textContent = "继续编辑";
        $("cancel-edit").hidden = false;
      }
    }
  } catch {
    status("无法读取本机草稿", true);
  }
  api("/session")
    .then(showNotebook)
    .catch(() => showLogin());
})();
