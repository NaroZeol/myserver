package app.thoughts.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

final class Sync {

  static String run(Store store, Account account, Api api) throws Exception {
    if (!account.isVerified()) return "已保存在手机 · 连接设备后同步";
    if (
      !account.can("thoughts")
    ) return "此设备未获想法同步权限，本机记录已保留";
    int conflicts = 0;
    for (Store.Entry entry : store.entries()) {
      if (entry.pending == null) continue;
      if (entry.error != null) {
        conflicts++;
        continue;
      }
      String id = entry.note.getString("id"),
        path = "/thoughts/" + id,
        method = "PATCH";
      JSONObject body = new JSONObject(entry.note.toString());
      body.remove("visibility");
      if ("create".equals(entry.pending)) {
        path = "/thoughts";
        method = "POST";
      }
      if ("delete".equals(entry.pending)) {
        method = "DELETE";
        body = new JSONObject().put("version", entry.note.getInt("version"));
      }
      if ("restore".equals(entry.pending)) body = new JSONObject()
        .put("version", entry.note.getInt("version"))
        .put("restore", true);
      try {
        store.acknowledge(entry, api.request(path, method, body));
      } catch (Api.Failure e) {
        if (e.code == 409 || e.code == 400) {
          store.conflict(entry, e.getMessage());
          conflicts++;
        } else throw e;
      }
    }
    int offset = 0;
    JSONArray all = new JSONArray();
    while (true) {
      JSONObject page = api.request(
        "/thoughts?all=1&limit=200&offset=" + offset,
        "GET",
        null
      );
      JSONArray items = page.getJSONArray("items");
      for (int i = 0; i < items.length(); i++) all.put(items.getJSONObject(i));
      if (!page.getBoolean("has_more")) break;
      offset = page.getInt("next_offset");
    }
    store.merge(all);
    int pending = 0;
    for (Store.Entry entry : store.entries())
      if (entry.pending != null) pending++;
    JSONObject publication = api.request("/publish", "POST", new JSONObject());
    if (conflicts > 0) return conflicts + " 条记录需要处理冲突，本地内容已保留";
    if (pending > 0) return pending + " 条待同步，点同步继续";
    if (publication.getBoolean("pending")) return (
      "已保存到服务器 · 待发布到 Gist：" +
      publication.optString("last_error", "稍后自动重试")
    );
    return (
      "已发布到 Gist · " + java.time.LocalTime.now().withSecond(0).withNano(0)
    );
  }
}
