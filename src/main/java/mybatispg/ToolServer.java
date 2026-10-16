package mybatispg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
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

import com.google.gson.JsonElement;
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

  /** 結果セットから読む行数の上限（画面からの指定もこれを超えられない） */
  private static final int MAX_ROWS_LIMIT = 10_000;

  private final MapperWorkspace workspace;
  private final ConnectionStore connections;
  private final GlobalStore globals;
  private volatile EditorLauncher editor = EditorLauncher.disabled();
  private final byte[] token;
  private final HttpServer server;
  private final ExecutorService executor;
  private final int port;
  private final Set<String> allowedHosts;
  private final Set<String> allowedOrigins;

  private ToolServer(MapperWorkspace workspace, ConnectionStore connections, GlobalStore globals, int port,
      String token, List<URI> publicUrls) throws IOException {
    this.workspace = workspace;
    this.connections = connections;
    this.globals = globals;
    this.token = token.getBytes(StandardCharsets.UTF_8);
    this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    this.port = server.getAddress().getPort();
    this.allowedHosts = new HashSet<>(Arrays.asList("127.0.0.1:" + this.port, "localhost:" + this.port));
    this.allowedOrigins = new HashSet<>(Arrays.asList("http://127.0.0.1:" + this.port, "http://localhost:" + this.port));
    // リバースプロキシ（code-server の /proxy/{port}/ など）経由では、外部のホスト名と Origin のまま届く
    for (URI url : publicUrls) {
      String hostPort = url.getHost().toLowerCase() + (url.getPort() < 0 ? "" : ":" + url.getPort());
      allowedHosts.add(hostPort);
      allowedOrigins.add(url.getScheme().toLowerCase() + "://" + hostPort);
    }
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
  public static ToolServer start(MapperWorkspace workspace, ConnectionStore connections, GlobalStore globals,
      int port, String token) throws IOException {
    return start(workspace, connections, globals, port, token, java.util.Collections.<URI>emptyList());
  }

  /** @param publicUrls リバースプロキシ経由で公開する URL。そのホストと Origin からのアクセスも許可する */
  public static ToolServer start(MapperWorkspace workspace, ConnectionStore connections, GlobalStore globals,
      int port, String token, List<URI> publicUrls) throws IOException {
    ToolServer s = new ToolServer(workspace, connections, globals, port, token, publicUrls);
    s.server.start();
    return s;
  }

  /** ファイルを開くエディタ。設定しなければ開けない */
  public void setEditor(EditorLauncher editor) {
    this.editor = editor;
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
      case "/api/template":
        if (requireMethod(ex, method, "GET")) {
          template(ex);
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
      case "/api/connections":
        if (requireMethod(ex, method, "GET")) {
          listConnections(ex);
        }
        break;
      case "/api/connections/save":
        if (requireMethod(ex, method, "POST")) {
          saveConnection(ex);
        }
        break;
      case "/api/connections/delete":
        if (requireMethod(ex, method, "POST")) {
          deleteConnection(ex);
        }
        break;
      case "/api/connections/test":
        if (requireMethod(ex, method, "POST")) {
          testConnection(ex);
        }
        break;
      case "/api/parameter-names":
        if (requireMethod(ex, method, "GET")) {
          parameterNames(ex);
        }
        break;
      case "/api/globals":
        if (requireMethod(ex, method, "GET")) {
          sendJson(ex, 200, globals.snapshot());
        }
        break;
      case "/api/globals/create":
      case "/api/globals/rename":
      case "/api/globals/delete":
      case "/api/globals/select":
      case "/api/globals/values":
        if (requireMethod(ex, method, "POST")) {
          updateGlobals(ex, path.substring("/api/globals/".length()));
        }
        break;
      case "/api/editor":
        if (requireMethod(ex, method, "GET")) {
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("available", editor.isAvailable());
          body.put("command", editor.describe());
          sendJson(ex, 200, body);
        }
        break;
      case "/api/open":
        if (requireMethod(ex, method, "POST")) {
          openInEditor(ex);
        }
        break;
      case "/api/explain":
        if (requireMethod(ex, method, "POST")) {
          explain(ex);
        }
        break;
      case "/api/execute":
        if (requireMethod(ex, method, "POST")) {
          execute(ex);
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

  /** statement の一覧（定義したファイル付き）と、読み込みエラーの一覧 */
  private void statements(HttpExchange ex) throws IOException {
    long generation = workspace.generation();
    SqlRenderer renderer = workspace.renderer();
    List<Map<String, Object>> statements = new ArrayList<>();
    for (String id : renderer.statementIds()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", id);
      m.put("file", displayPath(renderer.statementSource(id)));
      statements.add(m);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("generation", generation);
    body.put("statements", statements);
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

  /** statement の元の記述（mapper XML に書かれたままの要素） */
  private void template(HttpExchange ex) throws IOException {
    String id = query(ex).get("id");
    SqlRenderer renderer = workspace.renderer();
    if (id == null || !renderer.hasStatement(id)) {
      sendJson(ex, 404, error("statement がありません: " + id));
      return;
    }
    String resource = renderer.statementSource(id);
    StatementSource.Snippet snippet;
    try {
      snippet = StatementSource.find(Paths.get(resource), id.substring(id.lastIndexOf('.') + 1));
    } catch (IOException | RuntimeException e) {
      sendJson(ex, 422, error("ファイルを読み込めません: " + resource + "\n" + Errors.describe(e)));
      return;
    }
    if (snippet == null) {
      // 読み込んだ後にファイルが変わった場合など
      sendJson(ex, 404, error("ファイルに statement が見つかりません: " + resource));
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("file", displayPath(resource));
    body.put("line", snippet.line);
    body.put("xml", snippet.xml);
    sendJson(ex, 200, body);
  }

  /** リクエスト: {"statementId": "...", "parameters": 任意の JSON} */
  private void render(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String statementId = string(req, "statementId");
    if (statementId == null) {
      sendJson(ex, 400, error("statementId がありません"));
      return;
    }
    Object parameters = req.has("parameters") ? Json.GSON.fromJson(req.get("parameters"), Object.class) : null;

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

  // ---- 接続先 ----

  private void listConnections(HttpExchange ex) throws IOException {
    List<Map<String, Object>> list = new ArrayList<>();
    for (ConnectionStore.Profile p : connections.list()) {
      list.add(toJson(p));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("connections", list);
    sendJson(ex, 200, body);
  }

  /**
   * リクエスト: {"id"?, "name", "host", "port", "database", "user", "params", "password"?}。
   * id がなければ追加する。password がなければ今のパスワードのまま、空文字ならパスワードを消す。
   */
  private void saveConnection(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    ConnectionStore.Profile p = new ConnectionStore.Profile();
    p.id = string(req, "id");
    p.name = string(req, "name");
    p.host = string(req, "host");
    p.database = string(req, "database");
    p.user = string(req, "user");
    p.params = string(req, "params");
    String password = string(req, "password");
    try {
      if (req.has("port")) {
        p.port = req.get("port").getAsInt();
      }
      sendJson(ex, 200, toJson(connections.save(p, password)));
    } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
      sendJson(ex, 400, error("ポートは整数で入力してください"));
    } catch (IllegalArgumentException e) {
      sendJson(ex, 400, error(e.getMessage()));
    }
  }

  private void deleteConnection(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    if (!connections.delete(string(req, "id"))) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    sendJson(ex, 200, new LinkedHashMap<String, Object>());
  }

  /** 接続できるかを確かめ、DB の版を返す */
  private void testConnection(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String id = string(req, "id");
    if (id == null || connections.get(id) == null) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    try (java.sql.Connection c = connections.open(id)) {
      java.sql.DatabaseMetaData meta = c.getMetaData();
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("server", meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
      sendJson(ex, 200, body);
    } catch (java.sql.SQLException | RuntimeException e) {
      sendJson(ex, 422, error("接続できません\n" + Errors.describe(e)));
    }
  }

  private Map<String, Object> toJson(ConnectionStore.Profile p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", p.id);
    m.put("name", p.name);
    m.put("host", p.host);
    m.put("port", p.port);
    m.put("database", p.database);
    m.put("user", p.user);
    m.put("params", p.params);
    m.put("hasPassword", connections.hasPassword(p.id));
    return m;
  }

  // ---- エディタ ----

  /**
   * mapper XML をエディタで開く。開けるのは読み込んでいる mapper XML だけ。
   * リクエスト: {"statementId"} なら statement の開始行、{"file", "line"?} ならそのファイル
   * （file は statement 一覧のパスか、読み込みエラーの source）。
   */
  private void openInEditor(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    SqlRenderer renderer = workspace.renderer();
    Path file;
    int line = 1;
    String statementId = string(req, "statementId");
    if (statementId != null) {
      if (!renderer.hasStatement(statementId)) {
        sendJson(ex, 404, error("statement がありません: " + statementId));
        return;
      }
      file = Paths.get(renderer.statementSource(statementId));
      StatementSource.Snippet s = StatementSource.find(file, statementId.substring(statementId.lastIndexOf('.') + 1));
      if (s != null) {
        line = s.line;
      }
    } else {
      file = mapperFile(string(req, "file"));
      if (file == null) {
        sendJson(ex, 404, error("読み込んでいる mapper XML ではありません: " + string(req, "file")));
        return;
      }
      try {
        line = req.has("line") && !req.get("line").isJsonNull() ? req.get("line").getAsInt() : 1;
      } catch (RuntimeException e) {
        sendJson(ex, 400, error("line は整数で指定してください"));
        return;
      }
    }
    try {
      editor.open(file.toAbsolutePath().normalize(), line);
    } catch (IOException e) {
      sendJson(ex, 422, error(e.getMessage()));
      return;
    }
    sendJson(ex, 200, new LinkedHashMap<String, Object>());
  }

  /** 画面のパス（相対パスか絶対パス）から、読み込んでいる mapper XML を探す */
  private Path mapperFile(String shown) {
    if (shown == null) {
      return null;
    }
    for (Path p : workspace.mapperFiles()) {
      if (displayPath(p.toString()).equals(shown) || p.toString().equals(shown)
          || p.toAbsolutePath().normalize().toString().equals(shown)) {
        return p;
      }
    }
    return null;
  }

  // ---- 共通パラメータ ----

  /** 全 statement で使われている項目（共通パラメータの候補） */
  private void parameterNames(HttpExchange ex) throws IOException {
    List<Map<String, Object>> names = new ArrayList<>();
    for (ParameterNames.Entry e : ParameterNames.collect(workspace.renderer())) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("path", e.path);
      m.put("count", e.count);
      m.put("usages", e.usages);
      m.put("typeHints", e.typeHints);
      m.put("object", e.object);
      m.put("list", e.list);
      names.add(m);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("names", names);
    sendJson(ex, 200, body);
  }

  /**
   * 共通パラメータを変える。応答は変えた後の全体（GET /api/globals と同じ形）。
   * <ul>
   * <li>create: {"name", "copyFrom"?}</li>
   * <li>rename: {"id", "name"}</li>
   * <li>delete: {"id"}</li>
   * <li>select: {"id"}（null で「なし」）</li>
   * <li>values: {"id", "values": オブジェクト}</li>
   * </ul>
   */
  private void updateGlobals(HttpExchange ex, String action) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String id = string(req, "id");
    try {
      switch (action) {
        case "create":
          globals.create(string(req, "name"), string(req, "copyFrom"));
          break;
        case "rename":
          globals.rename(id, string(req, "name"));
          break;
        case "delete":
          globals.delete(id);
          break;
        case "select":
          globals.select(id);
          break;
        default:
          JsonElement values = req.get("values");
          if (values == null || !values.isJsonObject()) {
            sendJson(ex, 400, error("values はオブジェクトで指定してください"));
            return;
          }
          globals.saveValues(id, values.getAsJsonObject());
          break;
      }
    } catch (IllegalArgumentException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }
    sendJson(ex, 200, globals.snapshot());
  }

  // ---- 実行 ----

  /**
   * リクエスト: {"statementId", "parameters", "connectionId", "commit"?, "maxRows"?}。
   * commit が true でなければロールバックする。
   */
  private void execute(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String statementId = string(req, "statementId");
    String connectionId = string(req, "connectionId");
    if (statementId == null || connectionId == null) {
      sendJson(ex, 400, error("statementId と connectionId を指定してください"));
      return;
    }
    QueryExecutor.Options options = new QueryExecutor.Options();
    try {
      options.commit = req.has("commit") && req.get("commit").getAsBoolean();
      if (req.has("maxRows")) {
        options.maxRows = Math.max(1, Math.min(MAX_ROWS_LIMIT, req.get("maxRows").getAsInt()));
      }
    } catch (RuntimeException e) {
      sendJson(ex, 400, error("commit は真偽値、maxRows は整数で指定してください"));
      return;
    }
    Object parameters = req.has("parameters") ? Json.GSON.fromJson(req.get("parameters"), Object.class) : null;

    SqlRenderer renderer = workspace.renderer();
    if (!renderer.hasStatement(statementId)) {
      sendJson(ex, 404, error("statement がありません: " + statementId));
      return;
    }
    if (connections.get(connectionId) == null) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    QueryExecutor.Result r;
    try {
      r = QueryExecutor.execute(renderer, statementId, parameters, () -> connections.open(connectionId), options);
    } catch (QueryExecutor.ExecutionException | RuntimeException e) {
      // SQL のエラー、接続できない、OGNL の評価エラーなど
      sendJson(ex, 422, error(e instanceof QueryExecutor.ExecutionException ? e.getMessage() : Errors.describe(e)));
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sql", r.sql);
    body.put("resultSets", r.resultSets);
    body.put("updateCounts", r.updateCounts);
    body.put("warnings", r.warnings);
    body.put("committed", r.committed);
    body.put("elapsedMillis", r.elapsedMillis);
    sendJson(ex, 200, body);
  }

  /** 基準ディレクトリからの相対パスを / 区切りで返す。基準の外なら絶対パスのまま */
  private String displayPath(String resource) {
    Path base = workspace.baseDir();
    Path file;
    try {
      file = Paths.get(resource).toAbsolutePath().normalize();
    } catch (RuntimeException e) {
      return resource;
    }
    Path shown = base != null && file.startsWith(base) && !file.equals(base) ? base.relativize(file) : file;
    return shown.toString().replace('\\', '/');
  }

  /**
   * リクエスト: {"statementId", "parameters", "connectionId", "analyze"?, "buffers"?, "readOnly"?, "timeoutSeconds"?}。
   * 応答の plan は EXPLAIN (FORMAT JSON) の結果の文字列（数値の精度を保つため、解析せずに渡す）。
   */
  private void explain(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String statementId = string(req, "statementId");
    String connectionId = string(req, "connectionId");
    if (statementId == null || connectionId == null) {
      sendJson(ex, 400, error("statementId と connectionId を指定してください"));
      return;
    }
    QueryExecutor.ExplainOptions options = new QueryExecutor.ExplainOptions();
    try {
      if (req.has("analyze")) {
        options.analyze = req.get("analyze").getAsBoolean();
      }
      if (req.has("buffers")) {
        options.buffers = req.get("buffers").getAsBoolean();
      }
      if (req.has("readOnly")) {
        options.readOnly = req.get("readOnly").getAsBoolean();
      }
      if (req.has("timeoutSeconds")) {
        options.timeoutSeconds = Math.max(1, Math.min(3600, req.get("timeoutSeconds").getAsInt()));
      }
    } catch (RuntimeException e) {
      sendJson(ex, 400, error("analyze・buffers・readOnly は真偽値、timeoutSeconds は整数で指定してください"));
      return;
    }
    Object parameters = req.has("parameters") ? Json.GSON.fromJson(req.get("parameters"), Object.class) : null;
    SqlRenderer renderer = workspace.renderer();
    if (!renderer.hasStatement(statementId)) {
      sendJson(ex, 404, error("statement がありません: " + statementId));
      return;
    }
    if (connections.get(connectionId) == null) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    QueryExecutor.ExplainResult r;
    try {
      r = QueryExecutor.explain(renderer, statementId, parameters, () -> connections.open(connectionId), options);
    } catch (QueryExecutor.ExecutionException | RuntimeException e) {
      sendJson(ex, 422, error(e instanceof QueryExecutor.ExecutionException ? e.getMessage() : Errors.describe(e)));
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sql", r.sql);
    body.put("plan", r.plan);
    body.put("warnings", r.warnings);
    body.put("elapsedMillis", r.elapsedMillis);
    body.put("analyze", options.analyze);
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
    if ("html".equals(ext)) {
      // 画面が読み込めるのは自サーバの資源だけにする
      ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; "
          + "img-src 'self' data:; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
    }
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

  /** JSON のリクエストを読む。読めなければエラーを返して null */
  private static JsonObject readJson(HttpExchange ex) throws IOException {
    String contentType = ex.getRequestHeaders().getFirst("Content-Type");
    if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
      sendJson(ex, 415, error("Content-Type は application/json にしてください"));
      return null;
    }
    byte[] raw = readBody(ex);
    if (raw == null) {
      sendJson(ex, 413, error("リクエストが大きすぎます"));
      return null;
    }
    try {
      JsonObject req = Json.GSON.fromJson(new String(raw, StandardCharsets.UTF_8), JsonObject.class);
      if (req == null) {
        sendJson(ex, 400, error("リクエストが空です"));
      }
      return req;
    } catch (JsonParseException e) {
      sendJson(ex, 400, error("JSON を解析できません: " + e.getMessage()));
      return null;
    }
  }

  /** 文字列の項目。なければ（null も含む）null */
  private static String string(JsonObject obj, String key) {
    JsonElement e = obj.get(key);
    return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
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
