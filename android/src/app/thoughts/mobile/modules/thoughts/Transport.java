package app.thoughts.mobile.modules.thoughts;

import app.thoughts.mobile.core.Feature;
import org.json.JSONObject;

/** Feature modules depend on this request contract, not SSH library internals. */
interface Transport {
  JSONObject request(String path, String method, JSONObject body)
    throws Exception;
}
