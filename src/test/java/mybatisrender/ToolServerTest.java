package mybatisrender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class ToolServerTest {

  private static final String TOKEN = "test-token";
  private static MapperWorkspace workspace;
  private static ToolServer server;
  private static String host;

  @BeforeAll
  static void start() throws Exception {
    Path res = Paths.get("src/test/resources");
    workspace = MapperWorkspace.ofPaths(Arrays.asList(res.resolve("mappers"), res.resolve("params")));
    server = ToolServer.start(workspace, ConnectionStore.inMemory(), 0, TOKEN);
    host = "127.0.0.1:" + server.getPort();
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  // ---- セキュリティ ----

  @Test
  void トークンがなければ401() throws Exception {
    assertEquals(401, request("GET", "/api/statements", headers("Host", host), null).status);
    assertEquals(401, request("GET", "/api/statements", headers("Host", host, "X-Auth-Token", "wrong"), null).status);
  }

  @Test
  void Hostヘッダが自分自身でなければ403() throws Exception {
    // DNS リバインディングでは、攻撃者のドメイン名のまま 127.0.0.1 に届く
    Response r = request("GET", "/api/statements", headers("Host", "evil.example:" + server.getPort(), "X-Auth-Token", TOKEN), null);
    assertEquals(403, r.status);
    assertEquals(403, request("GET", "/", headers("Host", "evil.example"), null).status);
  }

  @Test
  void Originが他サイトなら403でCORSヘッダも返さない() throws Exception {
    Response r = request("GET", "/api/statements",
        headers("Host", host, "X-Auth-Token", TOKEN, "Origin", "http://evil.example"), null);
    assertEquals(403, r.status);

    Response ok = request("GET", "/api/statements",
        headers("Host", "localhost:" + server.getPort(), "X-Auth-Token", TOKEN, "Origin", "http://localhost:" + server.getPort()), null);
    assertEquals(200, ok.status);
    assertFalse(ok.headers.toLowerCase().contains("access-control-"), ok.headers);
  }

  @Test
  void 公開URLを指定するとリバースプロキシ経由のHostとOriginも許可する() throws Exception {
    ToolServer proxied = ToolServer.start(workspace, ConnectionStore.inMemory(), 0, TOKEN,
        Arrays.asList(java.net.URI.create("https://ide.example.com/proxy/7171/"), java.net.URI.create("http://10.0.0.1:8080/proxy/7171/")));
    try {
      ToolServer original = server;
      server = proxied;
      try {
        assertEquals(200, request("GET", "/api/statements",
            headers("Host", "ide.example.com", "X-Auth-Token", TOKEN, "Origin", "https://ide.example.com"), null).status);
        assertEquals(200, request("GET", "/api/statements",
            headers("Host", "10.0.0.1:8080", "X-Auth-Token", TOKEN, "Origin", "http://10.0.0.1:8080"), null).status);
        // 別のホストや、スキームの違う Origin は拒否する
        assertEquals(403, request("GET", "/api/statements",
            headers("Host", "other.example.com", "X-Auth-Token", TOKEN), null).status);
        assertEquals(403, request("GET", "/api/statements",
            headers("Host", "ide.example.com", "X-Auth-Token", TOKEN, "Origin", "http://ide.example.com"), null).status);
        // トークンは引き続き必須
        assertEquals(401, request("GET", "/api/statements", headers("Host", "ide.example.com"), null).status);
      } finally {
        server = original;
      }
    } finally {
      proxied.stop();
    }
  }

  // ---- API ----

  @Test
  void statement一覧() throws Exception {
    JsonObject body = api("GET", "/api/statements", null, 200);
    JsonArray ids = body.getAsJsonArray("statements");
    assertTrue(ids.toString().contains("\"sample.UserMapper.search\""), ids.toString());
    assertTrue(ids.toString().contains("\"sample.ParamMapper.hints\""), ids.toString());
    assertEquals(0, body.getAsJsonArray("loadErrors").size());
  }

  @Test
  void パラメータの木() throws Exception {
    JsonObject body = api("GET", "/api/parameters?id=sample.UserMapper.bulkInsert", null, 200);
    JsonObject users = body.getAsJsonObject("tree").getAsJsonArray("properties").get(0).getAsJsonObject();
    assertEquals("users", users.get("name").getAsString());
    assertEquals("[\"ITERATE\"]", users.get("usages").toString());
    JsonArray elementProps = users.getAsJsonObject("element").getAsJsonArray("properties");
    assertEquals("id", elementProps.get(0).getAsJsonObject().get("name").getAsString());

    api("GET", "/api/parameters?id=no.such", null, 404);
  }

  @Test
  void レンダリング() throws Exception {
    // 数値は 10 が 10.0 にならず、小数は指数表記にならないこと
    JsonObject body = api("POST", "/api/render",
        "{\"statementId\":\"sample.UserMapper.findStatic\",\"parameters\":{\"id\":10,\"name\":\"O'Reilly\"}}", 200);
    assertTrue(body.get("literalSql").getAsString().contains("WHERE id = 10\n"), body.toString());
    assertTrue(body.get("literalSql").getAsString().contains("name = 'O''Reilly'"), body.toString());
    JsonObject b1 = body.getAsJsonArray("bindings").get(0).getAsJsonObject();
    assertEquals("{\"index\":1,\"property\":\"id\",\"value\":10,\"literal\":\"10\"}", b1.toString());

    JsonObject dec = api("POST", "/api/render",
        "{\"statementId\":\"sample.UserMapper.findStatic\",\"parameters\":{\"id\":1.50E+2,\"name\":null}}", 200);
    assertTrue(dec.get("literalSql").getAsString().contains("WHERE id = 150\n"), dec.toString());
    assertTrue(dec.get("literalSql").getAsString().contains("name = NULL"), dec.toString());
  }

  @Test
  void レンダリングのエラー() throws Exception {
    // OGNL の評価エラーは 422
    JsonObject e = api("POST", "/api/render", "{\"statementId\":\"sample.ParamMapper.broken\",\"parameters\":{}}", 422);
    assertTrue(e.get("error").getAsString().contains("a =="), e.toString());

    api("POST", "/api/render", "{\"statementId\":\"no.such\"}", 404);
    api("POST", "/api/render", "{\"statementId\":", 400);
    api("POST", "/api/render", "{}", 400);
    Response wrongType = request("POST", "/api/render",
        headers("Host", host, "X-Auth-Token", TOKEN, "Content-Type", "text/plain"), "{}");
    assertEquals(415, wrongType.status);
    api("GET", "/api/render", null, 405);
  }

  @Test
  void 接続先の保存と一覧ではパスワードを返さない() throws Exception {
    JsonObject saved = api("POST", "/api/connections/save",
        "{\"name\":\"開発\",\"host\":\"127.0.0.1\",\"port\":1,\"database\":\"app\",\"user\":\"u\",\"password\":\"secret\"}", 200);
    String id = saved.get("id").getAsString();
    assertTrue(saved.get("hasPassword").getAsBoolean());
    String list = api("GET", "/api/connections", null, 200).toString();
    assertTrue(list.contains(id), list);
    assertFalse(list.contains("secret"), list);

    api("POST", "/api/connections/save", "{\"name\":\"x\",\"host\":\"h\",\"port\":\"abc\",\"database\":\"d\"}", 400);
    // ポート 1 には接続できない。エラーは 422 で返す
    JsonObject failed = api("POST", "/api/execute",
        "{\"statementId\":\"sample.UserMapper.findStatic\",\"connectionId\":\"" + id + "\",\"parameters\":{}}", 422);
    assertTrue(failed.get("error").getAsString().startsWith("接続できません"), failed.toString());
    api("POST", "/api/execute", "{\"statementId\":\"no.such\",\"connectionId\":\"" + id + "\"}", 404);
    api("POST", "/api/execute", "{\"statementId\":\"sample.UserMapper.findStatic\",\"connectionId\":\"no-such\"}", 404);

    api("POST", "/api/connections/delete", "{\"id\":\"" + id + "\"}", 200);
    api("POST", "/api/connections/delete", "{\"id\":\"" + id + "\"}", 404);
  }

  @Test
  void 変更待ちは世代番号が進むまで応答を保留する() throws Exception {
    long current = api("GET", "/api/statements", null, 200).get("generation").getAsLong();

    // 古い世代番号を渡すとすぐ応答する
    assertEquals(current, api("GET", "/api/changes?since=" + (current - 1), null, 200).get("generation").getAsLong());

    // 現在の世代番号を渡すと、読み込み直されるまで待つ
    java.util.concurrent.CompletableFuture<JsonObject> pending = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        return api("GET", "/api/changes?since=" + current, null, 200);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    Thread.sleep(300);
    assertFalse(pending.isDone());
    workspace.reload();
    assertEquals(current + 1, pending.get(5, java.util.concurrent.TimeUnit.SECONDS).get("generation").getAsLong());

    api("GET", "/api/changes?since=abc", null, 400);
  }

  // ---- HTTP（Host ヘッダを自由に設定するため、ソケットで直接やりとりする） ----

  private static JsonObject api(String method, String path, String body, int expectedStatus) throws Exception {
    Map<String, String> h = headers("Host", host, "X-Auth-Token", TOKEN);
    if (body != null) {
      h.put("Content-Type", "application/json");
    }
    Response r = request(method, path, h, body);
    assertEquals(expectedStatus, r.status, r.body);
    return Json.GSON.fromJson(r.body, JsonObject.class);
  }

  private static Map<String, String> headers(String... kv) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  private static final class Response {
    final int status;
    final String headers;
    final String body;

    Response(int status, String headers, String body) {
      this.status = status;
      this.headers = headers;
      this.body = body;
    }
  }

  private static Response request(String method, String path, Map<String, String> headers, String body) throws Exception {
    byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    StringBuilder req = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
    for (Map.Entry<String, String> e : headers.entrySet()) {
      req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
    }
    req.append("Content-Length: ").append(payload.length).append("\r\nConnection: close\r\n\r\n");

    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getPort())) {
      OutputStream out = socket.getOutputStream();
      out.write(req.toString().getBytes(StandardCharsets.UTF_8));
      out.write(payload);
      out.flush();
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) > 0) {
        buf.write(b, 0, n);
      }
      String raw = new String(buf.toByteArray(), StandardCharsets.UTF_8);
      int sep = raw.indexOf("\r\n\r\n");
      String head = raw.substring(0, sep);
      int status = Integer.parseInt(head.split(" ")[1]);
      return new Response(status, head, raw.substring(sep + 4));
    }
  }
}
