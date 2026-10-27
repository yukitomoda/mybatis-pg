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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
  /** EXPLAIN ANALYZE を繰り返す回数の上限 */
  private static final int MAX_REPEAT = 20;

  private final MapperWorkspace workspace;
  private final ConnectionStore connections;
  private final GlobalStore globals;
  /** 下書きの保存先。起動した後に useDrafts で決める */
  private volatile DraftStore draftStore = DraftStore.inMemory();
  /** 接続先のパスワードの暗号化した保存先。起動した後に useVault で決める */
  private volatile PasswordVault vault = PasswordVault.inMemory();
  /** プレイグラウンドの保存先。起動した後に usePlaygrounds で決める */
  private volatile PlaygroundStore playgrounds = PlaygroundStore.at(null);
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
    // 起動した後に公開 URL を足すことがあるので、スレッドから安全に読み書きできる集合にする
    this.allowedHosts = ConcurrentHashMap.newKeySet();
    this.allowedHosts.addAll(Arrays.asList("127.0.0.1:" + this.port, "localhost:" + this.port));
    this.allowedOrigins = ConcurrentHashMap.newKeySet();
    this.allowedOrigins.addAll(Arrays.asList("http://127.0.0.1:" + this.port, "http://localhost:" + this.port));
    for (URI url : publicUrls) {
      addPublicUrl(url);
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

  /** 下書きの保存先を決める */
  public void useDrafts(DraftStore store) {
    this.draftStore = store;
  }

  /** 接続先のパスワードの暗号化した保存先を決める */
  public void useVault(PasswordVault v) {
    this.vault = v;
  }

  /** プレイグラウンドの保存先を決める */
  public void usePlaygrounds(PlaygroundStore store) {
    this.playgrounds = store;
  }

  /** リレーション（試作）の保存先（~/.mybatis-pg/relations）。null なら保存しない */
  private Path relationsDir;
  /** 接続先ごとの、リレーションの解析の結果 */
  private final Map<String, Relations.Store> relationStores = new java.util.concurrent.ConcurrentHashMap<>();
  /** 接続先ごとの、解析中の進み具合 */
  private final Map<String, Relations.Progress> relationRuns = new java.util.concurrent.ConcurrentHashMap<>();
  /** 接続先ごとの、最後に終えた（一時停止を含む）解析の進み具合（かかった時間を見せる） */
  private final Map<String, Relations.Progress> relationLast = new java.util.concurrent.ConcurrentHashMap<>();

  public void useRelations(Path dir) {
    this.relationsDir = dir;
  }

  /** 手で設定したリレーション（プロジェクトごと。接続先をまたいで使う） */
  private ManualRelations manualRelations;

  private synchronized ManualRelations manualRelations() throws IOException {
    if (manualRelations == null) {
      manualRelations = ManualRelations.open(relationsDir, workspace.projectKey());
    }
    return manualRelations;
  }

  private Relations.Store relationStore(String connectionId) throws IOException {
    Relations.Store s = relationStores.get(connectionId);
    if (s == null) {
      s = Relations.Store.open(relationsDir, workspace.projectKey(), connectionId);
      relationStores.put(connectionId, s);
    }
    return s;
  }

  /**
   * リバースプロキシ（code-server の /proxy/{port}/ など）経由で公開する URL を足す。
   * そのホスト名と Origin からのアクセスも許可する（プロキシ経由では外部のホスト名と Origin のまま届く）。
   */
  public void addPublicUrl(URI url) {
    String hostPort = url.getHost().toLowerCase() + (url.getPort() < 0 ? "" : ":" + url.getPort());
    allowedHosts.add(hostPort);
    allowedOrigins.add(url.getScheme().toLowerCase() + "://" + hostPort);
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
        // 下書きを渡すときは POST {"id", "draft"}
        if ("POST".equals(method)) {
          JsonObject req = readJson(ex);
          if (req != null) {
            parameters(ex, string(req, "id"), req);
          }
        } else if (requireMethod(ex, method, "GET")) {
          parameters(ex, query(ex).get("id"), null);
        }
        break;
      case "/api/file":
        if (requireMethod(ex, method, "GET")) {
          file(ex);
        }
        break;
      case "/api/drafts":
        if (requireMethod(ex, method, "GET")) {
          listDrafts(ex);
        }
        break;
      case "/api/drafts/save":
        if (requireMethod(ex, method, "POST")) {
          saveDraft(ex);
        }
        break;
      case "/api/drafts/discard":
        if (requireMethod(ex, method, "POST")) {
          discardDrafts(ex);
        }
        break;
      case "/api/file/save":
        if (requireMethod(ex, method, "POST")) {
          saveFile(ex);
        }
        break;
      case "/api/vault":
        if (requireMethod(ex, method, "GET")) {
          sendJson(ex, 200, vaultStatus());
        }
        break;
      case "/api/vault/setup":
      case "/api/vault/unlock":
      case "/api/vault/lock":
      case "/api/vault/change":
      case "/api/vault/reset":
        if (requireMethod(ex, method, "POST")) {
          updateVault(ex, path.substring("/api/vault/".length()));
        }
        break;
      case "/api/playground/create":
      case "/api/playground/rename":
      case "/api/playground/delete":
        if (requireMethod(ex, method, "POST")) {
          updatePlayground(ex, path.substring("/api/playground/".length()));
        }
        break;
      case "/api/draft/check":
        if (requireMethod(ex, method, "POST")) {
          checkDraft(ex);
        }
        break;
      case "/api/parameters-all":
        if (requireMethod(ex, method, "GET")) {
          allParameters(ex);
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
      case "/api/globals/default":
      case "/api/globals/reorder":
      case "/api/globals/statement":
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
      case "/api/part":
        if (requireMethod(ex, method, "POST")) {
          runPart(ex);
        }
        break;
      case "/api/relations":
        if (requireMethod(ex, method, "POST")) {
          relations(ex);
        }
        break;
      case "/api/relations/manual":
        if (requireMethod(ex, method, "POST")) {
          changeManualRelation(ex);
        }
        break;
      case "/api/db-columns":
        if (requireMethod(ex, method, "POST")) {
          dbColumns(ex);
        }
        break;
      case "/api/relations/stop":
        if (requireMethod(ex, method, "POST")) {
          stopRelations(ex);
        }
        break;
      case "/api/relations/analyze":
        if (requireMethod(ex, method, "POST")) {
          analyzeRelations(ex);
        }
        break;
      case "/api/param-sources":
        if (requireMethod(ex, method, "POST")) {
          paramSources(ex);
        }
        break;
      case "/api/column-rows":
        if (requireMethod(ex, method, "POST")) {
          columnRows(ex);
        }
        break;
      case "/api/column-values":
        if (requireMethod(ex, method, "POST")) {
          columnValues(ex);
        }
        break;
      case "/api/explain":
        if (requireMethod(ex, method, "POST")) {
          explain(ex);
        }
        break;
      case "/api/tables":
        if (requireMethod(ex, method, "POST")) {
          tables(ex);
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
  /** statement と、それを組み立てる renderer（下書きのあるファイルなら下書きで読み込んだもの） */
  private static final class StatementRef {
    final SqlRenderer renderer;
    final String id;
    final String file;
    /** 下書きで足した（保存されているファイルにない） */
    final boolean added;
    final boolean playground;

    StatementRef(SqlRenderer renderer, String id, String file, boolean added, boolean playground) {
      this.renderer = renderer;
      this.id = id;
      this.file = file;
      this.added = added;
      this.playground = playground;
    }
  }

  /**
   * プレイグラウンドを 1 つずつ読み込んだもの: 画面のパス → 読み込んだもの。
   * プロジェクトの mapper XML の sql 断片を include できる（下書きと同じ仕組みで読み込む）。
   */
  private Map<String, SqlRenderer> playgroundRenderers() throws IOException {
    Map<String, SqlRenderer> out = new LinkedHashMap<>();
    for (String name : playgrounds.names()) {
      String shown = PlaygroundStore.PREFIX + name;
      Path file = playgrounds.file(shown);
      if (file != null) {
        out.put(shown, draftRenderer(file, MapperText.read(file)));
      }
    }
    return out;
  }

  /**
   * すべての statement。下書きのあるファイルは、下書きの中の statement にする（足した・消した・名前を変えたものを反映する）。
   * ただし下書きにエラーがある間（入力の途中など）は、保存されているファイルの statement のままにする。
   */
  private List<StatementRef> allStatements() throws IOException {
    SqlRenderer base = workspace.renderer();
    Map<String, SqlRenderer> drafted = new LinkedHashMap<>();
    for (DraftStore.Draft d : draftStore.list()) {
      Path file = mapperFile(d.file);
      if (file != null) {
        SqlRenderer r = draftRenderer(file, d.content);
        if (r.getLoadErrors().isEmpty()) {
          drafted.put(d.file, r);
        }
      }
    }
    List<StatementRef> out = new ArrayList<>();
    for (String id : base.statementIds()) {
      String file = displayPath(base.statementSource(id));
      if (!drafted.containsKey(file)) {
        out.add(new StatementRef(base, id, file, false, false));
      }
    }
    for (Map.Entry<String, SqlRenderer> e : drafted.entrySet()) {
      for (String id : e.getValue().statementIds()) {
        out.add(new StatementRef(e.getValue(), id, e.getKey(), !base.hasStatement(id), false));
      }
    }
    for (Map.Entry<String, SqlRenderer> e : playgroundRenderers().entrySet()) {
      for (String id : e.getValue().statementIds()) {
        // プロジェクトと同じ id は、プロジェクトのものを使う
        if (!base.hasStatement(id)) {
          out.add(new StatementRef(e.getValue(), id, e.getKey(), false, true));
        }
      }
    }
    return out;
  }

  private void statements(HttpExchange ex) throws IOException {
    long generation = workspace.generation();
    SqlRenderer renderer = workspace.renderer();
    List<Map<String, Object>> statements = new ArrayList<>();
    for (StatementRef s : allStatements()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", s.id);
      m.put("file", s.file);
      m.put("type", s.renderer.statementType(s.id));
      if (s.added) {
        m.put("added", true);
      }
      if (s.playground) {
        m.put("playground", true);
      }
      statements.add(m);
    }
    // statement がない・読み込めないプレイグラウンドも一覧に出す
    List<Map<String, Object>> playgroundList = new ArrayList<>();
    for (Map.Entry<String, SqlRenderer> e : playgroundRenderers().entrySet()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("file", e.getKey());
      m.put("name", e.getKey().substring(PlaygroundStore.PREFIX.length()));
      m.put("errors", e.getValue().getLoadErrors());
      playgroundList.add(m);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("generation", generation);
    body.put("statements", statements);
    body.put("playgrounds", playgroundList);
    // JAR から読み込んだときなど、ファイルに保存できない
    body.put("readOnly", workspace.isReadOnly());
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
  private void parameters(HttpExchange ex, String id, JsonObject req) throws IOException {
    SqlRenderer renderer = rendererFor(ex, req, id);
    if (renderer == null) {
      return;
    }
    ParameterExtractor.Result r = renderer.parameters(id);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tree", toJson(r.root));
    body.put("warnings", r.warnings);
    sendJson(ex, 200, body);
  }

  /**
   * すべての statement のパラメータの木: {"trees": {id: 木}, "errors": {id: 理由}}（下書きのあるファイルは下書きの内容で）。
   * 一括チェックで、値が足りない statement を選ぶ前に見分けるのに使う。
   */
  private void allParameters(HttpExchange ex) throws IOException {
    Map<String, Object> trees = new LinkedHashMap<>();
    Map<String, Object> errors = new LinkedHashMap<>();
    // 下書きのあるファイルは、下書きの内容で調べる（statement 一覧と同じ）
    for (StatementRef s : allStatements()) {
      try {
        trees.put(s.id, toJson(s.renderer.parameters(s.id).root));
      } catch (RuntimeException e) {
        errors.put(s.id, Errors.describe(e));
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trees", trees);
    body.put("errors", errors);
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
    Object parameters;
    try {
      parameters = req.has("parameters") ? TypedValues.decode(Json.GSON.fromJson(req.get("parameters"), Object.class)) : null;
    } catch (IllegalArgumentException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }

    SqlRenderer renderer = rendererFor(ex, req, statementId);
    if (renderer == null) {
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
      // 日時は画面で読める形にする（Gson の既定の形は読みにくい）
      m.put("value", b.value instanceof java.sql.Timestamp ? SqlLiteral.timestamp((java.sql.Timestamp) b.value)
          : b.value instanceof java.util.Date ? b.value.toString()
          : b.value instanceof org.postgresql.util.PGobject ? ((org.postgresql.util.PGobject) b.value).getValue() : b.value);
      m.put("literal", SqlLiteral.of(b.value));
      bindings.add(m);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("placeholderSql", r.placeholderSql);
    body.put("literalSql", r.literalSql);
    body.put("numberedSql", r.numberedSql);
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

  // ---- パスワードの保存（マスターパスワードで暗号化） ----

  /** {"exists": マスターパスワードを設定済みか, "unlocked": 開いているか, "minLength"} */
  private Map<String, Object> vaultStatus() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("exists", vault.exists());
    m.put("unlocked", vault.isUnlocked());
    m.put("minLength", PasswordVault.MIN_LENGTH);
    return m;
  }

  /**
   * <ul>
   * <li>setup: {"password"}（マスターパスワードを設定して開く）</li>
   * <li>unlock: {"password"}（開いて、保存しているパスワードを接続先に入れる）</li>
   * <li>lock: {}（閉じて、保存しているパスワードを接続先から外す）</li>
   * <li>change: {"current", "password"}</li>
   * <li>reset: {}（マスターパスワードを忘れたとき。保存しているパスワードごと消す）</li>
   * </ul>
   * 応答は vaultStatus と同じ形。
   */
  private void updateVault(HttpExchange ex, String action) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String password = string(req, "password");
    try {
      switch (action) {
        case "setup":
          vault.setup(chars(password));
          break;
        case "unlock":
          for (Map.Entry<String, String> e : vault.unlock(chars(password)).entrySet()) {
            if (connections.get(e.getKey()) != null) {
              connections.setPassword(e.getKey(), e.getValue());
            }
          }
          break;
        case "lock":
          vault.lock();
          for (String id : vault.savedIds()) {
            connections.setPassword(id, null);
          }
          break;
        case "change":
          vault.change(chars(string(req, "current")), chars(password));
          break;
        default:
          vault.reset();
          break;
      }
    } catch (IllegalArgumentException | IllegalStateException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }
    sendJson(ex, 200, vaultStatus());
  }

  private static char[] chars(String s) {
    return s == null ? new char[0] : s.toCharArray();
  }

  /**
   * リクエスト: {"id"?, "name", "host", "port", "database", "user", "params", "password"?, "remember"?}。
   * id がなければ追加する。password がなければ今のパスワードのまま、空文字ならパスワードを消す。
   * remember が true ならパスワードを暗号化して保存し（マスターパスワードで開いている必要がある）、false なら保存しているものを消す。
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
      Boolean remember = req.has("remember") && !req.get("remember").isJsonNull() ? req.get("remember").getAsBoolean() : null;
      if (Boolean.TRUE.equals(remember) && !vault.isUnlocked()) {
        sendJson(ex, 400, error("パスワードを保存するには、マスターパスワードを入れてください"));
        return;
      }
      ConnectionStore.Profile saved = connections.save(p, password);
      if (password != null && password.isEmpty() || Boolean.FALSE.equals(remember)) {
        vault.remove(saved.id);
      } else if (Boolean.TRUE.equals(remember) && password != null) {
        vault.put(saved.id, password);
      } else if (Boolean.TRUE.equals(remember) && !vault.has(saved.id) && connections.hasPassword(saved.id)) {
        // 入力済みのパスワードを保存する（パスワード欄を空のまま「保存する」にしたとき）
        vault.put(saved.id, connections.password(saved.id));
      }
      sendJson(ex, 200, toJson(saved));
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
    try {
      if (!connections.delete(string(req, "id"))) {
        sendJson(ex, 404, error("接続先がありません"));
        return;
      }
    } catch (IllegalArgumentException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }
    vault.remove(string(req, "id"));
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
    m.put("passwordSaved", vault.has(p.id));
    // プロジェクトの設定（application.yml など）から読んだ接続先: そのファイルと、使えない理由
    m.put("source", p.source);
    m.put("error", p.error);
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

  /** 画面のパス（相対パスか絶対パス、プレイグラウンドなら playground:名前）から、読み込んでいる mapper XML を探す */
  private Path mapperFile(String shown) {
    if (shown == null) {
      return null;
    }
    if (shown.startsWith(PlaygroundStore.PREFIX)) {
      return playgrounds.file(shown);
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
   * <li>default: {"id", "on"}（すべての statement に既定で入れるか）</li>
   * <li>reorder: {"ids": [...]}（既定の順番。すべての id を並べる）</li>
   * <li>statement: {"statement", "added": [...], "removed": [...], "order": [...] または null}（statement ごとの設定）</li>
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
        case "default":
          globals.setDefault(id, req.has("on") && req.get("on").getAsBoolean());
          break;
        case "reorder":
          globals.reorder(strings(req, "ids"));
          break;
        case "statement":
          globals.setStatement(string(req, "statement"), strings(req, "added"), strings(req, "removed"),
              req.has("order") && !req.get("order").isJsonNull() ? strings(req, "order") : null);
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
    Object parameters;
    try {
      parameters = req.has("parameters") ? TypedValues.decode(Json.GSON.fromJson(req.get("parameters"), Object.class)) : null;
    } catch (IllegalArgumentException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }

    SqlRenderer renderer = rendererFor(ex, req, statementId);
    if (renderer == null) {
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

  // ---- 下書き（保存していない mapper XML の編集内容） ----

  /** 下書きで読み込んだもの。同じ内容で何度も読み込まないよう、最近のものを覚えておく */
  private final Map<String, SqlRenderer> draftRenderers = new LinkedHashMap<String, SqlRenderer>(16, 0.75f, true) {
    private static final long serialVersionUID = 1L;

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, SqlRenderer> eldest) {
      return size() > 32;
    }
  };

  private SqlRenderer draftRenderer(Path file, String content) {
    SqlRenderer base = workspace.renderer();
    // 元のファイルや他のファイルが読み込み直されたら、作り直す
    String key = System.identityHashCode(base) + "\u0000" + file + "\u0000" + content;
    synchronized (draftRenderers) {
      SqlRenderer r = draftRenderers.get(key);
      if (r == null) {
        r = base.withDraft(file, content);
        draftRenderers.put(key, r);
      }
      return r;
    }
  }

  /**
   * statement を組み立てる renderer。リクエストに draft {"file": 画面のパス, "content"} があれば、そのファイルを下書きの内容で読み込んだものを使う。
   * 使えなければ応答を送って null を返す。
   */
  private SqlRenderer rendererFor(HttpExchange ex, JsonObject req, String statementId) throws IOException {
    JsonElement d = req == null ? null : req.get("draft");
    if (d == null || d.isJsonNull()) {
      SqlRenderer renderer = workspace.renderer();
      if (statementId != null && !renderer.hasStatement(statementId)) {
        // プレイグラウンドの statement
        for (SqlRenderer r : playgroundRenderers().values()) {
          if (r.hasStatement(statementId)) {
            return r;
          }
        }
      }
      if (statementId == null || !renderer.hasStatement(statementId)) {
        sendJson(ex, 404, error("statement がありません: " + statementId));
        return null;
      }
      return renderer;
    }
    if (!d.isJsonObject()) {
      sendJson(ex, 400, error("draft の形が正しくありません"));
      return null;
    }
    Path file = mapperFile(string(d.getAsJsonObject(), "file"));
    String content = string(d.getAsJsonObject(), "content");
    if (file == null || content == null) {
      sendJson(ex, 400, error("下書きのファイルが、読み込んでいる mapper XML ではありません"));
      return null;
    }
    SqlRenderer renderer = draftRenderer(file, content);
    if (statementId == null || !renderer.hasStatement(statementId)) {
      StringBuilder message = new StringBuilder("編集中の XML に statement がありません: " + statementId);
      for (LoadError e : renderer.getLoadErrors()) {
        message.append("\n").append(e.line == null ? "" : e.line + " 行目: ").append(e.message);
      }
      sendJson(ex, 422, error(message.toString()));
      return null;
    }
    return renderer;
  }

  /**
   * 保存してある下書き: {"drafts": [{"file", "base", "content", "updatedAt", "loaded", "changed"}]}（更新の新しい順）。
   * loaded はそのファイルを読み込んでいるか（消したり名前を変えたりしていないか）、changed は編集を始めた後にファイルが変わったか。
   * 中身が今のファイルと同じになった下書き（外のエディタで同じ変更を保存したなど）は、ここで消す。
   */
  private void listDrafts(HttpExchange ex) throws IOException {
    List<Map<String, Object>> out = new ArrayList<>();
    for (DraftStore.Draft d : draftStore.list()) {
      Path file = mapperFile(d.file);
      String current = null;
      if (file != null) {
        try {
          current = MapperText.read(file);
        } catch (IOException e) {
          // 読めなければ、変わったかどうかは分からないものとする
        }
      }
      if (current != null && sameText(d.content, current)) {
        draftStore.delete(d.file);
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("file", d.file);
      m.put("base", d.base);
      m.put("content", d.content);
      m.put("updatedAt", d.updatedAt);
      m.put("loaded", file != null);
      m.put("changed", current != null && !sameText(current, d.base));
      out.add(m);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("drafts", out);
    sendJson(ex, 200, body);
  }

  /** 下書きを保存する: {"file", "base", "content"}。content が base と同じなら消す */
  private void saveDraft(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String file = string(req, "file");
    String base = string(req, "base");
    String content = string(req, "content");
    if (mapperFile(file) == null || base == null || content == null) {
      sendJson(ex, 400, error("下書きのファイルが、読み込んでいる mapper XML ではありません"));
      return;
    }
    draftStore.save(file, base, content);
    sendJson(ex, 200, new LinkedHashMap<String, Object>());
  }

  /** 下書きを消す: {"files": [...]}（読み込んでいないファイルの下書きも消せる） */
  private void discardDrafts(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    List<String> files;
    try {
      files = strings(req, "files");
    } catch (IllegalArgumentException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }
    for (String f : files) {
      if (f != null) {
        draftStore.delete(f);
      }
    }
    sendJson(ex, 200, new LinkedHashMap<String, Object>());
  }

  /**
   * 編集した内容をファイルに保存する: {"file", "base", "content", "force"?}。保存したら、そのファイルの下書きを消す。
   * 編集を始めた後にファイルが変わっていたら（今の内容が base と違えば）、force がない限り保存せず 409 を返す。
   */
  private void saveFile(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String shown = string(req, "file");
    Path file = mapperFile(shown);
    String base = string(req, "base");
    String content = string(req, "content");
    if (file == null || base == null || content == null) {
      sendJson(ex, 400, error("保存するファイルが、読み込んでいる mapper XML ではありません"));
      return;
    }
    if (workspace.isReadOnly() && !shown.startsWith(PlaygroundStore.PREFIX)) {
      sendJson(ex, 403, error("JAR から読み込んだ mapper XML は、ファイルに保存できません"));
      return;
    }
    boolean force = req.has("force") && req.get("force").getAsBoolean();

    try {
      // エディタは改行を LF にそろえるので、改行の違いだけなら変わっていないものとする
      String current = MapperText.read(file);
      if (!force && !sameText(current, base)) {
        Map<String, Object> body = error("編集を始めた後に、ファイルが変更されています");
        body.put("conflict", true);
        sendJson(ex, 409, body);
        return;
      }
      MapperText.write(file, content);
    } catch (IOException e) {
      sendJson(ex, 422, error("ファイルに保存できません: " + Errors.describe(e)));
      return;
    }
    draftStore.delete(shown);
    sendJson(ex, 200, new LinkedHashMap<String, Object>());
  }

  /** 改行の違い（CRLF と LF）を除いて同じか。エディタは改行を LF にそろえるため */
  private static boolean sameText(String a, String b) {
    return a.replace("\r\n", "\n").equals(b.replace("\r\n", "\n"));
  }

  /**
   * プレイグラウンドを作る・名前を変える・消す。
   * <ul>
   * <li>create: {"name"}</li>
   * <li>rename: {"name", "newName"}</li>
   * <li>delete: {"name"}</li>
   * </ul>
   */
  private void updatePlayground(HttpExchange ex, String action) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    try {
      switch (action) {
        case "create":
          playgrounds.create(string(req, "name"));
          break;
        case "rename":
          playgrounds.rename(string(req, "name"), string(req, "newName"));
          break;
        default:
          playgrounds.delete(string(req, "name"));
          break;
      }
    } catch (IllegalArgumentException | IllegalStateException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }
    sendJson(ex, 200, new LinkedHashMap<String, Object>());
  }

  /** mapper XML の中身: {"file", "content"}。読み込んでいる mapper XML だけを返す */
  private void file(HttpExchange ex) throws IOException {
    Path file = mapperFile(query(ex).get("path"));
    if (file == null) {
      sendJson(ex, 404, error("読み込んでいる mapper XML ではありません"));
      return;
    }
    String content;
    try {
      content = MapperText.read(file);
    } catch (IOException e) {
      sendJson(ex, 422, error("ファイルを読み込めません: " + Errors.describe(e)));
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("file", displayPath(file.toString()));
    body.put("content", content);
    sendJson(ex, 200, body);
  }

  /** 下書きを読み込んでみる: {"file", "content"} → {"statements": [id...], "errors": [LoadError...]} */
  private void checkDraft(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    Path file = mapperFile(string(req, "file"));
    String content = string(req, "content");
    if (file == null || content == null) {
      sendJson(ex, 400, error("下書きのファイルが、読み込んでいる mapper XML ではありません"));
      return;
    }
    SqlRenderer renderer = draftRenderer(file, content);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("statements", renderer.statementIds());
    body.put("errors", renderer.getLoadErrors());
    sendJson(ex, 200, body);
  }

  /**
   * statement の SQL の一部（サブクエリ・CTE など）を、本番と同じバインド変数で、読み取り専用で実行する。
   * リクエスト: {"statementId", "parameters", "draft"?, "connectionId", "sql": 番号付き（$1, $2, ...。render の numberedSql と同じ番号）, "maxRows"?, "timeoutSeconds"?}。
   * 応答: {"columns", "columnTypes", "rows", "truncated", "total", "elapsedMillis"}。
   */
  private void runPart(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String statementId = string(req, "statementId");
    String connectionId = string(req, "connectionId");
    String sql = string(req, "sql");
    if (sql == null || sql.trim().isEmpty()) {
      sendJson(ex, 400, error("sql がありません"));
      return;
    }
    Object parameters;
    try {
      parameters = req.has("parameters") ? TypedValues.decode(Json.GSON.fromJson(req.get("parameters"), Object.class)) : null;
    } catch (IllegalArgumentException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }
    SqlRenderer renderer = rendererFor(ex, req, statementId);
    if (renderer == null) {
      return;
    }
    if (connectionId == null || connections.get(connectionId) == null) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    int maxRows = req.has("maxRows") ? Math.max(1, Math.min(10000, req.get("maxRows").getAsInt())) : 200;
    int timeout = req.has("timeoutSeconds") ? Math.max(1, Math.min(600, req.get("timeoutSeconds").getAsInt())) : 30;
    SqlRenderer.Bound part;
    try {
      part = renderer.bind(statementId, parameters).part(sql);
    } catch (RuntimeException e) {
      sendJson(ex, 422, error(Errors.describe(e)));
      return;
    }
    QueryExecutor.PartResult r;
    try {
      r = QueryExecutor.queryPart(part.sql(), part::setParameters, () -> connections.open(connectionId), maxRows, timeout);
    } catch (QueryExecutor.ExecutionException e) {
      sendJson(ex, 422, error(e.getMessage()));
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("columns", r.table.columns);
    body.put("columnTypes", r.table.columnTypes);
    body.put("rows", r.table.rows);
    body.put("truncated", r.table.truncated);
    body.put("total", r.total);
    body.put("elapsedMillis", r.elapsedMillis);
    sendJson(ex, 200, body);
  }

  /**
   * パラメータがどの表のどの列と比べられているかを推論する（試作）: {"statementId", "parameters", "draft"?, "connectionId"}。
   * 応答: {"sources": [{"index", "property", "columns": [{"schema", "table", "column", "condition"}]}]}（推論できたものだけ）。
   */
  private void paramSources(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String statementId = string(req, "statementId");
    String connectionId = string(req, "connectionId");
    Object parameters;
    try {
      parameters = req.has("parameters") ? TypedValues.decode(Json.GSON.fromJson(req.get("parameters"), Object.class)) : null;
    } catch (IllegalArgumentException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }
    SqlRenderer renderer = rendererFor(ex, req, statementId);
    if (renderer == null) {
      return;
    }
    if (connectionId == null || connections.get(connectionId) == null) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    // 今の値で組み立てた SQL と、未設定の項目に仮の値を入れて if をすべて通した SQL の両方で推論し、項目ごとにまとめる
    // （今の値では if で条件がなくなっている項目や、${} が空で SQL が壊れているときも推論できるように）
    ParamNode schema = renderer.parameters(statementId).root;
    String script = renderer.scriptText(statementId);
    // ${schema}.表 のスキーマは、表を持つスキーマを DB に聞く（そういう形があるときだけ接続する）
    Object probe = ParamSources.probe(schema, parameters, path -> ParamSources.rawGuess(script, path, tables -> {
      try (java.sql.Connection c = connections.open(connectionId)) {
        return ParamSources.schemaOf(c, tables);
      } catch (java.sql.SQLException | RuntimeException e) {
        return null;
      }
    }));
    // 値の入っていない ${} の項目。推し量れないもの（表の名前など）や、推し量った値で SQL が壊れたときは、値を入れるよう知らせる
    List<String> rawMissing = ParamSources.rawPaths(schema, parameters);
    boolean probeOk = false;
    Map<String, List<ParamSources.Source>> byProperty = new LinkedHashMap<>();
    Map<String, Integer> indexOf = new LinkedHashMap<>();
    String firstError = null;
    boolean anyOk = false;
    // 先に両方を組み立てる（DB は使わない）。仮の値で新しい項目が出ないなら、仮の値の推論は省く（DB への負担を減らす）
    SqlRenderer.Result[] renders = new SqlRenderer.Result[2];
    Object[] all = {parameters, probe};
    for (int i = 0; i < 2; i++) {
      try {
        renders[i] = renderer.render(statementId, all[i]);
      } catch (RuntimeException e) {
        String m = (i == 1 ? "仮の値で if をすべて通した SQL: " : "今の値の SQL: ") + Errors.describe(e);
        firstError = firstError == null ? m : firstError + "\n" + m;
      }
    }
    if (renders[0] != null && renders[1] != null && properties(renders[0]).containsAll(properties(renders[1]))) {
      renders[1] = null;
      probeOk = true;
    }
    java.sql.Connection shared = null;
    try {
      for (int ri = 0; ri < 2; ri++) {
        SqlRenderer.Result rendered = renders[ri];
        Object params = all[ri];
        if (rendered == null) {
          continue;
        }
        try {
          if (shared == null && !rendered.bindings.isEmpty()) {
            shared = ParamSources.open(() -> connections.open(connectionId));
          }
          List<String> fallbackTypes = new ArrayList<>();
          List<String> actualValues = new ArrayList<>();
          for (SqlRenderer.Binding b : rendered.bindings) {
            fallbackTypes.add(QueryExecutor.pgType(b.value, true));
            actualValues.add(b.value == null ? null : b.value instanceof org.postgresql.util.PGobject
                ? ((org.postgresql.util.PGobject) b.value).getValue() : b.value.toString());
          }
          Map<Integer, List<ParamSources.Source>> inferred = ParamSources.infer(rendered.numberedSql, rendered.bindings.size(),
              fallbackTypes, actualValues, shared);
          anyOk = true;
          probeOk |= params == probe;
          for (Map.Entry<Integer, List<ParamSources.Source>> e : inferred.entrySet()) {
            String property = rendered.bindings.get(e.getKey() - 1).property;
            List<ParamSources.Source> list = byProperty.computeIfAbsent(property, k -> new ArrayList<>());
            indexOf.putIfAbsent(property, e.getKey());
            for (ParamSources.Source src : e.getValue()) {
              if (list.stream().noneMatch(x -> x.schema.equals(src.schema) && x.table.equals(src.table) && x.column.equals(src.column))) {
                list.add(src);
              }
            }
          }
        } catch (QueryExecutor.ExecutionException | RuntimeException e) {
          String m = (params == probe ? "仮の値で if をすべて通した SQL: " : "今の値の SQL: ") + Errors.describe(e);
          firstError = firstError == null ? m : firstError + "\n" + m;
        }
      }
    } finally {
      if (shared != null) {
        try {
          shared.close();
        } catch (java.sql.SQLException ignored) {
          // 閉じられなくても結果は変わらない
        }
      }
    }
    // 仮の値の SQL で推論できなかったら、値の入っていない ${} の項目のうち、推し量れなかったもの（なければすべて）に値を入れるよう知らせる
    List<String> needs = new ArrayList<>();
    if (!probeOk) {
      for (String p : rawMissing) {
        if (ParamSources.rawGuess(script, p) == null) {
          needs.add(p);
        }
      }
      if (needs.isEmpty()) {
        needs.addAll(rawMissing);
      }
    }
    // 比べている列を推論できなかったパラメータ（列挙型など）も、同じ名前のパラメータから候補を出せるように入れておく
    for (SqlRenderer.Result rendered : renders) {
      if (rendered == null) {
        continue;
      }
      for (int k = 0; k < rendered.bindings.size(); k++) {
        String property = rendered.bindings.get(k).property;
        if (!property.startsWith("__frch_") && !byProperty.containsKey(property)) {
          byProperty.put(property, new ArrayList<>());
          indexOf.putIfAbsent(property, k + 1);
        }
      }
    }
    // 値の候補を出す列を、リレーションの源流から並べる
    Relations.Graph graph = relationGraph(connectionId);
    Map<String, List<ParamSources.Source>> ordered = Relations.candidates(byProperty, graph);
    List<Map<String, Object>> sources = new ArrayList<>();
    for (Map.Entry<String, List<ParamSources.Source>> e : ordered.entrySet()) {
      if (e.getValue().isEmpty()) {
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("index", indexOf.get(e.getKey()));
      m.put("property", e.getKey());
      List<Map<String, Object>> cols = new ArrayList<>();
      for (ParamSources.Source src : e.getValue()) {
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("schema", src.schema);
        col.put("table", src.table);
        col.put("column", src.column);
        col.put("condition", src.condition);
        col.put("direct", src.direct);
        col.put("role", src.role);
        List<String> via = new ArrayList<>();
        for (String v : src.via) {
          String[] p = Relations.split(v);
          via.add(p[1] + "." + p[2]);
        }
        col.put("via", via);
        col.put("count", src.count);
        col.put("manual", src.manual);
        cols.add(col);
      }
      m.put("columns", cols);
      sources.add(m);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sources", sources);
    body.put("needs", needs);
    // リレーションを解析してあるか（していなければ、比べている列だけを出す）
    body.put("relations", relationsAnalyzed(connectionId));
    // どちらでも推論できなかったときの理由（比較のない INSERT などは、候補なしとしてエラーにしない）
    body.put("error", anyOk ? null : firstError);
    sendJson(ex, 200, body);
  }

  /**
   * 候補の値ごとの行と、入力中の値に当たる行（試作）: {"connectionId", "schema", "table", "column", "values": [...], "current"?}。
   * 応答: {"columns", "rows"（値ごとに 1 行）, "currentRows", "currentError"}
   */
  private void columnRows(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String connectionId = string(req, "connectionId");
    if (connectionId == null || connections.get(connectionId) == null) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    List<String> values;
    try {
      values = strings(req, "values");
    } catch (IllegalArgumentException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }
    try {
      sendJson(ex, 200, ParamSources.rows(string(req, "schema"), string(req, "table"), string(req, "column"),
          values.subList(0, Math.min(values.size(), 30)), string(req, "current"), () -> connections.open(connectionId)));
    } catch (QueryExecutor.ExecutionException e) {
      sendJson(ex, 422, error(e.getMessage()));
    }
  }

  /** 列の値の候補（試作）: {"connectionId", "schema", "table", "column"} → {"type", "estimatedRows", "method", "values": [{"value", "count"}]} */
  /** リレーションの解析を始める（終わるまで待たない。進み具合は /api/relations で見る） */
  private void analyzeRelations(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String connectionId = string(req, "connectionId");
    if (connectionId == null || connections.get(connectionId) == null) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    // すべての statement に入れる値（画面で選んだ共通パラメータ。スキーマを決める ${schema} など）
    Object parameters;
    try {
      parameters = req.has("parameters") ? TypedValues.decode(Json.GSON.fromJson(req.get("parameters"), Object.class)) : null;
    } catch (IllegalArgumentException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }
    Relations.Progress progress = new Relations.Progress();
    if (relationRuns.putIfAbsent(connectionId, progress) != null) {
      sendJson(ex, 409, error("解析中です"));
      return;
    }
    Relations.Store store = relationStore(connectionId);
    SqlRenderer renderer = workspace.renderer();
    List<String> ids = new ArrayList<>(renderer.statementIds());
    Thread t = new Thread(() -> {
      try (java.sql.Connection c = connections.open(connectionId)) {
        synchronized (store) {
          Relations.analyze(renderer, ids, parameters, c, store, progress);
        }
      } catch (Exception e) {
        progress.current = "エラー: " + Errors.describe(e);
      } finally {
        relationLast.put(connectionId, progress);
        relationRuns.remove(connectionId);
      }
    }, "relations-" + connectionId);
    t.setDaemon(true);
    t.start();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("started", true);
    sendJson(ex, 200, body);
  }

  /** リレーションを解析してあるか */
  private boolean relationsAnalyzed(String connectionId) throws IOException {
    if (relationsDir == null && !relationStores.containsKey(connectionId)) {
      return false;
    }
    Relations.Store store = relationStore(connectionId);
    synchronized (store) {
      return !store.statements.isEmpty();
    }
  }

  /**
   * 手で設定したリレーションを変える。{"action": "add" | "remove", "child", "parent"} か {"action": "disable" | "enable", "a", "b"}。
   * 列は schema.table.column
   */
  private void changeManualRelation(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    ManualRelations manual = manualRelations();
    String action = string(req, "action");
    try {
      if ("add".equals(action)) {
        manual.add(string(req, "child"), string(req, "parent"));
      } else if ("remove".equals(action)) {
        manual.remove(string(req, "child"), string(req, "parent"));
      } else if ("disable".equals(action)) {
        manual.disable(string(req, "a"), string(req, "b"));
      } else if ("enable".equals(action)) {
        manual.enable(string(req, "a"), string(req, "b"));
      } else {
        sendJson(ex, 400, error("action は add・remove・disable・enable のどれかにしてください"));
        return;
      }
    } catch (IllegalArgumentException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }
    DbLog.event("relations", "手で設定したリレーションを変えた: " + action + " " + req);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", true);
    sendJson(ex, 200, body);
  }

  /**
   * DB の表と列（リレーションを手で追加するときに選ぶ）。{"connectionId"} なら表（schema.table）の一覧、
   * {"connectionId", "table": "schema.table"} ならその表の列の一覧
   */
  private void dbColumns(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String connectionId = string(req, "connectionId");
    if (connectionId == null || connections.get(connectionId) == null) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    String table = string(req, "table");
    List<String> out = new ArrayList<>();
    try (java.sql.Connection c = connections.open(connectionId)) {
      if (table == null) {
        try (java.sql.Statement s = c.createStatement(); java.sql.ResultSet rs = s.executeQuery(
            "SELECT n.nspname || '.' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f') AND n.nspname NOT IN ('pg_catalog', 'information_schema')"
                + " AND n.nspname NOT LIKE 'pg\\_%' ORDER BY 1")) {
          while (rs.next()) {
            out.add(rs.getString(1));
          }
        }
      } else {
        int dot = table.indexOf('.');
        try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT a.attname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid"
            + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ?::name AND c.relname = ?::name AND a.attnum > 0"
            + " AND NOT a.attisdropped ORDER BY a.attnum")) {
          ps.setString(1, dot < 0 ? "public" : table.substring(0, dot));
          ps.setString(2, dot < 0 ? table : table.substring(dot + 1));
          try (java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              out.add(rs.getString(1));
            }
          }
        }
      }
    } catch (java.sql.SQLException | RuntimeException e) {
      sendJson(ex, 422, error(Errors.describe(e)));
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(table == null ? "tables" : "columns", out);
    sendJson(ex, 200, body);
  }

  /** リレーションの解析を一時停止する（そこまでの結果は保存する。次の「解析する」で続きから） */
  private void stopRelations(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    Relations.Progress p = relationRuns.get(string(req, "connectionId"));
    if (p != null) {
      p.stop = true;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("stopping", p != null);
    sendJson(ex, 200, body);
  }

  /** リレーションの解析の結果（つながりの一覧）と、解析中なら進み具合 */
  private void relations(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String connectionId = string(req, "connectionId");
    if (connectionId == null || connections.get(connectionId) == null) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    Relations.Store store = relationStore(connectionId);
    Map<String, Object> body = new LinkedHashMap<>();
    Relations.Progress p = relationRuns.get(connectionId);
    if (p != null) {
      Map<String, Object> pm = new LinkedHashMap<>();
      pm.put("done", p.done);
      pm.put("total", p.total);
      pm.put("current", p.current);
      pm.put("stopping", p.stop);
      pm.putAll(timing(p));
      body.put("progress", pm);
    }
    // 解析中は、書きかけの結果を読まない
    if (p != null) {
      sendJson(ex, 200, body);
      return;
    }
    synchronized (store) {
      body.put("updatedAt", store.updatedAt == 0 ? null : store.updatedAt);
      body.put("statements", store.statements.size());
      body.put("complete", store.complete);
      Relations.Progress last = relationLast.get(connectionId);
      if (last != null) {
        body.put("lastRun", timing(last));
      }
      // まだ解析していない statement の数（一時停止した後や、statement が増えたとき）
      int pending = 0;
      for (String id : workspace.renderer().statementIds()) {
        if (!store.statements.containsKey(id)) {
          pending++;
        }
      }
      body.put("pending", pending);
      List<Map<String, Object>> errors = new ArrayList<>();
      for (Map.Entry<String, Relations.Entry> e : store.statements.entrySet()) {
        if (e.getValue().error != null) {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("id", e.getKey());
          m.put("error", e.getValue().error);
          errors.add(m);
        }
      }
      body.put("errors", errors);
      // 解析で推し量ったものに、手で設定したもの（追加・無効）を重ねる
      ManualRelations manual = manualRelations();
      List<Relations.Link> all = Relations.links(store, manual);
      Relations.Graph graph = new Relations.Graph(store, all, manual);
      List<Map<String, Object>> links = new ArrayList<>();
      for (Relations.Link l : all) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", l.a);
        m.put("b", l.b);
        m.put("kind", l.kind);
        m.put("statements", new ArrayList<>(l.statements));
        m.put("manual", l.manual);
        m.put("disabled", l.disabled);
        links.add(m);
      }
      body.put("links", links);
      // 一意でない列ごとの源流（兄弟をたどって見つけたものも）
      java.util.Set<String> cols = new java.util.TreeSet<>();
      for (Relations.Link l : all) {
        cols.add(l.a);
        cols.add(l.b);
      }
      List<Map<String, Object>> origins = new ArrayList<>();
      for (String col : cols) {
        List<Relations.Origin> os = graph.origins(col);
        if (os.isEmpty()) {
          continue;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Relations.Origin o : os) {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("column", o.column);
          m.put("path", o.path);
          m.put("count", o.count);
          m.put("manual", o.manual);
          list.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("column", col);
        m.put("origins", list);
        origins.add(m);
      }
      body.put("origins", origins);
      body.put("unique", new ArrayList<>(store.keys.entrySet().stream().filter(e -> Relations.unique(store, e.getKey()))
          .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toList())));
    }
    sendJson(ex, 200, body);
  }

  /**
   * 解析にかかった時間。1 件あたりの時間（解析した件の平均。DB との往復を含む）、そのうち計画を作った時間、
   * 経過時間、残りの推定時間（解析した件の、経過時間での平均 × 残りの件数。解析し直さない件も残りに数えるので、多めに出る）
   */
  private static Map<String, Object> timing(Relations.Progress p) {
    Map<String, Object> m = new LinkedHashMap<>();
    long elapsed = System.currentTimeMillis() - p.startedAt;
    m.put("analyzed", p.analyzed);
    m.put("elapsedMillis", elapsed);
    if (p.analyzed > 0) {
      m.put("perStatementMillis", Math.round(p.millis / p.analyzed));
      m.put("planPerStatementMillis", Math.round(p.planMillis / p.analyzed));
    }
    // 数件では平均がぶれるので、3 件から出す
    if (p.analyzed >= 3 && p.total > p.done) {
      // 続きからのときは、解析済みの件を最初にまとめて飛ばすので、解析した件の数で割る
      m.put("remainingMillis", Math.round((double) elapsed / p.analyzed * (p.total - p.done)));
    }
    return m;
  }

  /**
   * 列と列のリレーション。今は、解析の結果（SQL の結合の条件から推し量ったもの）だけ。解析していなければ null。
   * （後で、手で設定したリレーションもここで足す）
   */
  private Relations.Graph relationGraph(String connectionId) throws IOException {
    if (relationsDir == null && !relationStores.containsKey(connectionId)) {
      return null;
    }
    Relations.Store store = relationStore(connectionId);
    ManualRelations manual = manualRelations();
    synchronized (store) {
      if (store.statements.isEmpty() && manual.isEmpty()) {
        return null;
      }
      // 保存が変わっていなければ、前にまとめたものを使う（statement が多いと、まとめるのに時間がかかる）
      Object[] cached = relationGraphs.get(connectionId);
      if (cached != null && (Long) cached[0] == store.updatedAt && (Integer) cached[1] == store.statements.size()
          && (Long) cached[3] == manual.version) {
        return (Relations.Graph) cached[2];
      }
      Relations.Graph g = new Relations.Graph(store, Relations.links(store, manual), manual);
      relationGraphs.put(connectionId, new Object[] {store.updatedAt, store.statements.size(), g, manual.version});
      return g;
    }
  }

  /** 接続先ごとの、まとめたリレーション（[保存した時刻, statement の数, Graph]） */
  private final Map<String, Object[]> relationGraphs = new java.util.concurrent.ConcurrentHashMap<>();



  /** SQL に出ている項目（バインド変数の元の式） */
  private static java.util.Set<String> properties(SqlRenderer.Result r) {
    java.util.Set<String> out = new java.util.HashSet<>();
    for (SqlRenderer.Binding b : r.bindings) {
      out.add(b.property);
    }
    return out;
  }

  private void columnValues(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String connectionId = string(req, "connectionId");
    if (connectionId == null || connections.get(connectionId) == null) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    try {
      boolean scan = req.has("scan") && req.get("scan").getAsBoolean();
      sendJson(ex, 200, ParamSources.values(string(req, "schema"), string(req, "table"), string(req, "column"), scan,
          () -> connections.open(connectionId)));
    } catch (QueryExecutor.ExecutionException e) {
      sendJson(ex, 422, error(e.getMessage()));
    }
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
    int repeat = 1;
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
      if (req.has("sixthExecution")) {
        options.sixthExecution = req.get("sixthExecution").getAsBoolean();
      }
      if (req.has("repeat")) {
        repeat = Math.max(1, Math.min(MAX_REPEAT, req.get("repeat").getAsInt()));
      }
    } catch (RuntimeException e) {
      sendJson(ex, 400, error("analyze・buffers・readOnly は真偽値、timeoutSeconds は整数で指定してください"));
      return;
    }
    Object parameters;
    try {
      parameters = req.has("parameters") ? TypedValues.decode(Json.GSON.fromJson(req.get("parameters"), Object.class)) : null;
    } catch (IllegalArgumentException e) {
      sendJson(ex, 400, error(e.getMessage()));
      return;
    }
    SqlRenderer renderer = rendererFor(ex, req, statementId);
    if (renderer == null) {
      return;
    }
    ConnectionStore.Profile profile = connections.get(connectionId);
    if (profile == null) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    options.stringUnspecified = profile.params != null && profile.params.matches("(?i).*\\bstringtype=unspecified\\b.*");
    if (!options.analyze) {
      repeat = 1;
    }
    // 繰り返すときは毎回別のトランザクションで実行し（毎回ロールバック）、実行時間が中央値の回の計画を返す
    List<QueryExecutor.ExplainResult> results = new ArrayList<>();
    List<Double> runs = new ArrayList<>();
    try {
      for (int i = 0; i < repeat; i++) {
        QueryExecutor.ExplainResult one =
            QueryExecutor.explain(renderer, statementId, parameters, () -> connections.open(connectionId), options);
        results.add(one);
        runs.add(executionTime(one.plan));
      }
    } catch (QueryExecutor.ExecutionException | RuntimeException e) {
      sendJson(ex, 422, error(e instanceof QueryExecutor.ExecutionException ? e.getMessage() : Errors.describe(e)));
      return;
    }
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < results.size(); i++) {
      order.add(i);
    }
    order.sort((a, b) -> Double.compare(runs.get(a), runs.get(b)));
    QueryExecutor.ExplainResult r = results.get(order.get((order.size() - 1) / 2));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sql", r.sql);
    body.put("plan", r.plan);
    if (repeat > 1) {
      body.put("runs", runs);
    }
    body.put("warnings", r.warnings);
    body.put("elapsedMillis", r.elapsedMillis);
    body.put("analyze", options.analyze);
    body.put("generic", r.generic);
    sendJson(ex, 200, body);
  }

  /** 表の情報。リクエスト: {"connectionId", "tables": ["users", "public.orders", ...]} */
  private void tables(HttpExchange ex) throws IOException {
    JsonObject req = readJson(ex);
    if (req == null) {
      return;
    }
    String connectionId = string(req, "connectionId");
    if (connectionId == null || connections.get(connectionId) == null) {
      sendJson(ex, 404, error("接続先がありません"));
      return;
    }
    List<String> names = new ArrayList<>();
    JsonElement list = req.get("tables");
    if (list == null || !list.isJsonArray()) {
      sendJson(ex, 400, error("tables は表の名前の配列で指定してください"));
      return;
    }
    for (JsonElement e : list.getAsJsonArray()) {
      if (e.isJsonPrimitive() && !e.getAsString().trim().isEmpty() && names.size() < 50) {
        names.add(e.getAsString().trim());
      }
    }
    try {
      sendJson(ex, 200, TableInfo.read(() -> connections.open(connectionId), names));
    } catch (QueryExecutor.ExecutionException e) {
      sendJson(ex, 422, error(e.getMessage()));
    }
  }

  /** EXPLAIN (FORMAT JSON) の結果の Execution Time。なければ 0 */
  private static double executionTime(String plan) {
    try {
      JsonElement top = com.google.gson.JsonParser.parseString(plan);
      JsonElement first = top.isJsonArray() ? top.getAsJsonArray().get(0) : top;
      JsonElement t = first.getAsJsonObject().get("Execution Time");
      return t == null ? 0 : t.getAsDouble();
    } catch (RuntimeException e) {
      return 0;
    }
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

  /** 文字列の配列の項目。なければ例外 */
  private static List<String> strings(JsonObject obj, String key) {
    JsonElement e = obj.get(key);
    if (e == null || !e.isJsonArray()) {
      throw new IllegalArgumentException(key + " は文字列の配列で指定してください");
    }
    List<String> out = new ArrayList<>();
    for (JsonElement item : e.getAsJsonArray()) {
      out.add(item.isJsonPrimitive() ? item.getAsString() : null);
    }
    return out;
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
