package mybatisrender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * ローカル Web サーバ。画面（静的ファイル）と API を提供する。
 *
 * <p>ブラウザから任意のサイト経由で叩かれないよう、次の対策をとる。
 * <ul>
 * <li>127.0.0.1 だけで待ち受ける</li>
 * <li>Host ヘッダが自分自身でなければ拒否する（DNS リバインディング対策）</li>
 * <li>Origin ヘッダが付いていて自分自身でなければ拒否する。CORS 用のヘッダは返さない</li>
 * <li>API は起動時に生成したトークン（X-Auth-Token ヘッダ）を必須にする</li>
 * </ul>
 */
public final class ToolServer {

  static final String TOKEN_HEADER = "X-Auth-Token";
  private static final int MAX_BODY_BYTES = 1024 * 1024;
  /** /api/changes で変更を待つ最大時間 */
  private static final long CHANGES_TIMEOUT_MILLIS = 25_000;
  private static final Map<String, String> CONTENT_TYPES = new HashMap<>();

  static {
    CONTENT_TYPES.put("html", "text/html; charset=utf-8");
    CONTENT_TYPES.put("js", "text/javascript; charset=utf-8");
    CONTENT_TYPES.put("css", "text/css; charset=utf-8");
    CONTENT_TYPES.put("svg", "image/svg+xml");
    CONTENT_TYPES.put("png", "image/png");
    CONTENT_TYPES.put("ico", "image/x-icon");
    CONTENT_TYPES.put("json", "application/json; charset=utf-8");
    CONTENT_TYPES.put("woff2", "font/woff2");
  }

  private final MapperWorkspace workspace;
  private final byte[] token;
  private final HttpServer server;
  private final ExecutorService executor;
  private final int port;
  private final Set<String> allowedHosts;
  private final Set<String> allowedOrigins;

  private ToolServer(MapperWorkspace workspace, int port, String token) throws IOException {
    this.workspace = workspace;
    this.token = token.getBytes(StandardCharsets.UTF_8);
    this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    this.port = server.getAddress().getPort();
    this.allowedHosts = new HashSet<>(Arrays.asList("127.0.0.1:" + this.port, "localhost:" + this.port));
    this.allowedOrigins = new HashSet<>(Arrays.asList("http://127.0.0.1:" + this.port, "http://localhost:" + this.port));
    // /api/changes が応答を保留する間もスレッドを占有するため、必要に応じて増やせるプールにする
    this.executor = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "tool-server");
      t.setDaemon(true);
      return t;
    });
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  /** @param port 0 なら空いているポートを使う */
  public static ToolServer start(MapperWorkspace workspace, int port, String token) throws IOException {
    ToolServer s = new ToolServer(workspace, port, token);
    s.server.start();
    return s;
  }

  public int getPort() {
    return port;
  }

  public void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  // ---- 共通処理 ----

  private void handle(HttpExchange ex) {
    try {
      Headers h = ex.getRequestHeaders();
      String host = h.getFirst("Host");
      if (host == null || !allowedHosts.contains(host.toLowerCase())) {
        sendText(ex, 403, "Host ヘッダが不正です");
        return;
      }
      String origin = h.getFirst("Origin");
      if (origin != null && !allowedOrigins.contains(origin.toLowerCase())) {
        sendText(ex, 403, "Origin が不正です");
        return;
      }
      String path = ex.getRequestURI().getPath();
      if (path.startsWith("/api/")) {
        api(ex, path);
      } else {
        staticFile(ex, path);
      }
    } catch (Exception e) {
      try {
        sendJson(ex, 500, error(Errors.describe(e)));
      } catch (IOException ignored) {
        // 応答を返せない場合は何もしない
      }
    } finally {
      ex.close();
    }
  }

  private boolean authorized(HttpExchange ex) {
    String given = ex.getRequestHeaders().getFirst(TOKEN_HEADER);
    // 応答時間からトークンを推測されないよう、一定時間で比較する
    return given != null && MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8));
  }

  // ---- API ----

  private void api(HttpExchange ex, String path) throws IOException {
    if (!authorized(ex)) {
      sendJson(ex, 401, error("トークンがありません、または一致しません"));
      return;
    }
    String method = ex.getRequestMethod();
    switch (path) {
      case "/api/statements":
        if (requireMethod(ex, method, "GET")) {
          statements(ex);
        }
        break;
      case "/api/parameters":
        if (requireMethod(ex, method, "GET")) {
          parameters(ex);
        }
        break;
      case "/api/render":
        if (requireMethod(ex, method, "POST")) {
          render(ex);
        }
        break;
      case "/api/changes":
        if (requireMethod(ex, method, "GET")) {
          changes(ex);
        }
        break;
      default:
        sendJson(ex, 404, error("API がありません: " + path));
        break;
    }
  }

  private boolean requireMethod(HttpExchange ex, String actual, String expected) throws IOException {
    if (expected.equals(actual)) {
      return true;
    }
    ex.getResponseHeaders().set("Allow", expected);
    sendJson(ex, 405, error(expected + " のみ使えます"));
    return false;
  }

  /** statement の一覧と、読み込みエラーの一覧 */
  private void statements(HttpExchange ex) throws IOException {
    long generation = workspace.generation();
    SqlRenderer renderer = workspace.renderer();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("generation", generation);
    body.put("statements", renderer.statementIds());
    body.put("loadErrors", renderer.getLoadErrors());
    sendJson(ex, 200, body);
  }

  /**
   * mapper の読み込み直しを待つ（ロングポーリング）。
   * 世代番号が since より進んでいればすぐ、そうでなければ変わるかタイムアウトするまで待って、現在の世代番号を返す。
   */
  private void changes(HttpExchange ex) throws IOException {
    long since;
    try {
      since = Long.parseLong(query(ex).getOrDefault("since", "-1"));
    } catch (NumberFormatException e) {
      sendJson(ex, 400, error("since は整数で指定してください"));
      return;
    }
    long generation;
    try {
      generation = workspace.awaitChange(since, CHANGES_TIMEOUT_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      generation = workspace.generation();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("generation", generation);
    sendJson(ex, 200, body);
  }

  /** statement が参照するパラメータの木 */
  private void parameters(HttpExchange ex) throws IOException {
    String id = query(ex).get("id");
    SqlRenderer renderer = workspace.renderer();
    if (id == null || !renderer.hasStatement(id)) {
      sendJson(ex, 404, error("statement がありません: " + id));
      return;
    }
    ParameterExtractor.Result r = renderer.parameters(id);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tree", toJson(r.root));
    body.put("warnings", r.warnings);
    sendJson(ex, 200, body);
  }

  /** リクエスト: {"statementId": "...", "parameters": 任意の JSON} */
  private void render(HttpExchange ex) throws IOException {
    String contentType = ex.getRequestHeaders().getFirst("Content-Type");
    if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
      sendJson(ex, 415, error("Content-Type は application/json にしてください"));
      return;
    }
    byte[] raw = readBody(ex);
    if (raw == null) {
      sendJson(ex, 413, error("リクエストが大きすぎます"));
      return;
    }
    String statementId;
    Object parameters;
    try {
      JsonObject req = Json.GSON.fromJson(new String(raw, StandardCharsets.UTF_8), JsonObject.class);
      if (req == null || !req.has("statementId") || !req.get("statementId").isJsonPrimitive()) {
        sendJson(ex, 400, error("statementId がありません"));
        return;
      }
      statementId = req.get("statementId").getAsString();
      parameters = req.has("parameters") ? Json.GSON.fromJson(req.get("parameters"), Object.class) : null;
    } catch (JsonParseException e) {
      sendJson(ex, 400, error("JSON を解析できません: " + e.getMessage()));
      return;
    }

    SqlRenderer renderer = workspace.renderer();
    if (!renderer.hasStatement(statementId)) {
      sendJson(ex, 404, error("statement がありません: " + statementId));
      return;
    }
    SqlRenderer.Result r;
    try {
      r = renderer.render(statementId, parameters);
    } catch (RuntimeException e) {
      // OGNL の評価エラーなど、入力や XML の内容によるエラー
      sendJson(ex, 422, error(Errors.describe(e)));
      return;
    }
    List<Map<String, Object>> bindings = new ArrayList<>();
    for (SqlRenderer.Binding b : r.bindings) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("index", b.index);
      m.put("property", b.property);
      m.put("value", b.value);
      m.put("literal", SqlLiteral.of(b.value));
      bindings.add(m);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("placeholderSql", r.placeholderSql);
    body.put("literalSql", r.literalSql);
    body.put("bindings", bindings);
    sendJson(ex, 200, body);
  }

  private static Map<String, Object> toJson(ParamNode node) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", node.getName());
    m.put("usages", node.getUsages());
    m.put("typeHints", node.getTypeHints());
    List<Map<String, Object>> props = new ArrayList<>();
    for (ParamNode child : node.getProperties().values()) {
      props.add(toJson(child));
    }
    m.put("properties", props);
    m.put("element", node.getElement() == null ? null : toJson(node.getElement()));
    return m;
  }

  // ---- 静的ファイル ----

  /** クラスパスの web/ 配下を返す（フロントのビルド結果を置く） */
  private void staticFile(HttpExchange ex, String path) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) {
      ex.getResponseHeaders().set("Allow", "GET");
      sendText(ex, 405, "GET のみ使えます");
      return;
    }
    if (path.equals("/")) {
      path = "/index.html";
    }
    if (path.contains("..") || path.contains("\\")) {
      sendText(ex, 404, "Not Found");
      return;
    }
    byte[] bytes;
    try (InputStream in = ToolServer.class.getClassLoader().getResourceAsStream("web" + path)) {
      if (in == null) {
        sendText(ex, 404, "Not Found");
        return;
      }
      bytes = readAll(in, Integer.MAX_VALUE);
    }
    String ext = path.substring(path.lastIndexOf('.') + 1);
    String type = CONTENT_TYPES.get(ext);
    send(ex, 200, type == null ? "application/octet-stream" : type, bytes);
  }

  // ---- 入出力 ----

  private static Map<String, Object> error(String message) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("error", message);
    return m;
  }

  private static Map<String, String> query(HttpExchange ex) throws UnsupportedEncodingException {
    Map<String, String> params = new HashMap<>();
    String raw = ex.getRequestURI().getRawQuery();
    if (raw == null) {
      return params;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      params.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
    }
    return params;
  }

  /** 上限を超えたら null */
  private static byte[] readBody(HttpExchange ex) throws IOException {
    try (InputStream in = ex.getRequestBody()) {
      return readAll(in, MAX_BODY_BYTES);
    }
  }

  private static byte[] readAll(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) {
      if (out.size() + n > limit) {
        return null;
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    send(ex, status, "application/json; charset=utf-8", Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
  }

  private static void sendText(HttpExchange ex, int status, String text) throws IOException {
    send(ex, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
  }

  private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
    Headers h = ex.getResponseHeaders();
    h.set("Content-Type", contentType);
    h.set("X-Content-Type-Options", "nosniff");
    h.set("X-Frame-Options", "DENY");
    // URL のトークンが外部に漏れないようにする
    h.set("Referrer-Policy", "no-referrer");
    ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(body);
    }
  }
}
