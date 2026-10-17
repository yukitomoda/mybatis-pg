package mybatispg;

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
import com.google.gson.JsonParser;

class ToolServerTest {

  private static final String TOKEN = "test-token";
  private static MapperWorkspace workspace;
  private static ToolServer server;
  private static String host;

  @BeforeAll
  static void start() throws Exception {
    Path res = Paths.get("src/test/resources");
    workspace = MapperWorkspace.ofPaths(Arrays.asList(res.resolve("mappers"), res.resolve("params")));
    server = ToolServer.start(workspace, ConnectionStore.inMemory(), GlobalStore.inMemory(), 0, TOKEN);
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
    ToolServer proxied = ToolServer.start(workspace, ConnectionStore.inMemory(), GlobalStore.inMemory(), 0, TOKEN,
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
    String statements = body.getAsJsonArray("statements").toString();
    // ファイルは mapper の共通の親（src/test/resources）からの相対パス
    assertTrue(statements.contains("{\"id\":\"sample.UserMapper.search\",\"file\":\"mappers/UserMapper.xml\",\"type\":\"SELECT\"}"), statements);
    assertTrue(statements.contains("{\"id\":\"sample.ParamMapper.hints\",\"file\":\"params/ParamMapper.xml\",\"type\":\"SELECT\"}"), statements);
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

    JsonObject all = api("GET", "/api/parameters-all", null, 200);
    assertEquals(body.get("tree"), all.getAsJsonObject("trees").get("sample.UserMapper.bulkInsert"));
    assertEquals(0, all.getAsJsonObject("errors").size());
  }

  @Test
  void テンプレート() throws Exception {
    JsonObject body = api("GET", "/api/template?id=sample.UserMapper.bulkInsert", null, 200);
    assertEquals("mappers/UserMapper.xml", body.get("file").getAsString());
    assertTrue(body.get("xml").getAsString().startsWith("<insert id=\"bulkInsert\">\n  INSERT INTO users"), body.toString());
    assertTrue(body.get("line").getAsInt() > 1, body.toString());
    api("GET", "/api/template?id=no.such", null, 404);
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
  void 下書き() throws Exception {
    JsonObject file = api("GET", "/api/file?path=mappers/UserMapper.xml", null, 200);
    assertEquals("mappers/UserMapper.xml", file.get("file").getAsString());
    String content = file.get("content").getAsString();
    assertTrue(content.contains("namespace=\"sample.UserMapper\""));
    // 読み込んでいない mapper XML 以外は返さない
    api("GET", "/api/file?path=../pom.xml", null, 404);

    // 下書きの内容で組み立てる。別ファイルの sql 断片の include もそのまま使える
    String draft = content.replace("<select id=\"search\" resultType=\"map\">",
        "<select id=\"search\" resultType=\"map\">\n    /* 編集中 */")
        .replace("</mapper>", "<select id=\"added\">SELECT <include refid=\"sample.Common.userColumns\"/> FROM t WHERE x = #{x}</select>\n</mapper>");
    String draftJson = "{\"file\":\"mappers/UserMapper.xml\",\"content\":" + new com.google.gson.Gson().toJson(draft) + "}";
    JsonObject rendered = api("POST", "/api/render",
        "{\"statementId\":\"sample.UserMapper.search\",\"parameters\":{\"cond\":{\"name\":\"a\"}},\"draft\":" + draftJson + "}", 200);
    assertTrue(rendered.get("literalSql").getAsString().contains("/* 編集中 */"), rendered.toString());
    JsonObject added = api("POST", "/api/render", "{\"statementId\":\"sample.UserMapper.added\",\"parameters\":{\"x\":1},\"draft\":" + draftJson + "}", 200);
    assertTrue(added.get("literalSql").getAsString().contains("id, name, status  FROM t WHERE x = 1"), added.toString());
    JsonObject params = api("POST", "/api/parameters", "{\"id\":\"sample.UserMapper.added\",\"draft\":" + draftJson + "}", 200);
    assertTrue(params.get("tree").toString().contains("\"name\":\"x\""), params.toString());
    // 保存したファイルの内容は変わらない
    api("POST", "/api/render", "{\"statementId\":\"sample.UserMapper.added\",\"parameters\":{}}", 404);

    JsonObject check = api("POST", "/api/draft/check", draftJson, 200);
    assertTrue(check.get("statements").toString().contains("sample.UserMapper.added"), check.toString());
    assertEquals(0, check.getAsJsonArray("errors").size());

    // 構文エラーは行番号つきで返し、statement は使えない
    String brokenJson = "{\"file\":\"mappers/UserMapper.xml\",\"content\":" + new com.google.gson.Gson().toJson(content.replace("</mapper>", "<select id=\"x\">")) + "}";
    JsonObject broken = api("POST", "/api/draft/check", brokenJson, 200);
    JsonObject error = broken.getAsJsonArray("errors").get(0).getAsJsonObject();
    assertTrue(error.has("line"), broken.toString());
    JsonObject e = api("POST", "/api/render", "{\"statementId\":\"sample.UserMapper.search\",\"parameters\":{},\"draft\":" + brokenJson + "}", 422);
    assertTrue(e.get("error").getAsString().contains("行目"), e.toString());
    api("POST", "/api/draft/check", "{\"file\":\"../pom.xml\",\"content\":\"\"}", 400);
  }

  @Test
  void 下書きの保存() throws Exception {
    String content = api("GET", "/api/file?path=mappers/UserMapper.xml", null, 200).get("content").getAsString();
    com.google.gson.Gson gson = new com.google.gson.Gson();
    String edited = content.replace("FROM users", "FROM users /* x */");
    api("POST", "/api/drafts/save", "{\"file\":\"mappers/UserMapper.xml\",\"base\":" + gson.toJson(content) + ",\"content\":" + gson.toJson(edited) + "}", 200);
    JsonObject d = api("GET", "/api/drafts", null, 200).getAsJsonArray("drafts").get(0).getAsJsonObject();
    assertEquals("mappers/UserMapper.xml", d.get("file").getAsString());
    assertEquals(edited, d.get("content").getAsString());
    assertTrue(d.get("loaded").getAsBoolean());
    assertFalse(d.get("changed").getAsBoolean());

    // 編集を始めたときと今のファイルが違えば changed
    api("POST", "/api/drafts/save", "{\"file\":\"mappers/UserMapper.xml\",\"base\":\"old\",\"content\":" + gson.toJson(edited) + "}", 200);
    assertTrue(api("GET", "/api/drafts", null, 200).getAsJsonArray("drafts").get(0).getAsJsonObject().get("changed").getAsBoolean());
    // 中身が今のファイルと同じになった下書きは、一覧を取るときに消す
    api("POST", "/api/drafts/save", "{\"file\":\"mappers/UserMapper.xml\",\"base\":\"old\",\"content\":" + gson.toJson(content) + "}", 200);
    assertEquals(0, api("GET", "/api/drafts", null, 200).getAsJsonArray("drafts").size());

    // 下書きのあるファイルは、statement 一覧と全 statement の木に下書きの内容を使う。足したものには added を付け、消したものは出さない
    String withAdded = content.replace("<select id=\"search\"", "<select id=\"added\">SELECT #{y}</select>\n  <select id=\"search\"")
        .replaceAll("(?s)<select id=\"findStatic\".*?</select>", "");
    api("POST", "/api/drafts/save", "{\"file\":\"mappers/UserMapper.xml\",\"base\":" + gson.toJson(content) + ",\"content\":" + gson.toJson(withAdded) + "}", 200);
    String list = api("GET", "/api/statements", null, 200).getAsJsonArray("statements").toString();
    assertTrue(list.contains("{\"id\":\"sample.UserMapper.added\",\"file\":\"mappers/UserMapper.xml\",\"type\":\"SELECT\",\"added\":true}"), list);
    assertTrue(list.contains("{\"id\":\"sample.UserMapper.search\",\"file\":\"mappers/UserMapper.xml\",\"type\":\"SELECT\"}"), list);
    assertFalse(list.contains("sample.UserMapper.findStatic"), list);
    assertTrue(api("GET", "/api/parameters-all", null, 200).getAsJsonObject("trees").get("sample.UserMapper.added").toString().contains("\"y\""));
    // 下書きにエラーがある間は、保存されているファイルの statement のまま
    api("POST", "/api/drafts/save", "{\"file\":\"mappers/UserMapper.xml\",\"base\":" + gson.toJson(content) + ",\"content\":" + gson.toJson(withAdded + "<broken") + "}", 200);
    list = api("GET", "/api/statements", null, 200).getAsJsonArray("statements").toString();
    assertTrue(list.contains("sample.UserMapper.findStatic") && !list.contains("sample.UserMapper.added"), list);

    api("POST", "/api/drafts/save", "{\"file\":\"mappers/UserMapper.xml\",\"base\":" + gson.toJson(content) + ",\"content\":\"x\"}", 200);
    api("POST", "/api/drafts/discard", "{\"files\":[\"mappers/UserMapper.xml\",\"gone.xml\"]}", 200);
    assertEquals(0, api("GET", "/api/drafts", null, 200).getAsJsonArray("drafts").size());
    api("POST", "/api/drafts/save", "{\"file\":\"../pom.xml\",\"base\":\"\",\"content\":\"x\"}", 400);
    api("POST", "/api/drafts/discard", "{\"files\":\"x\"}", 400);
  }

  @Test
  void ファイルに保存する(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
    Path mapper = dir.resolve("SaveMapper.xml");
    String original = "<?xml version=\"1.0\" encoding=\"Windows-31J\"?>\r\n"
        + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\r\n"
        + "<mapper namespace=\"save.M\">\r\n  <select id=\"find\">SELECT 1 /* 元 */</select>\r\n</mapper>\r\n";
    java.nio.charset.Charset sjis = java.nio.charset.Charset.forName("Windows-31J");
    java.nio.file.Files.write(mapper, original.getBytes(sjis));
    ToolServer s = ToolServer.start(MapperWorkspace.ofPaths(Arrays.asList(dir)), ConnectionStore.inMemory(), GlobalStore.inMemory(), 0, TOKEN);
    try {
      String saveHost = "127.0.0.1:" + s.getPort();
      java.util.function.BiFunction<String, String, Response> post = (path, body) -> {
        try {
          return request(s.getPort(), "POST", path, headers("Host", saveHost, "X-Auth-Token", TOKEN, "Content-Type", "application/json"), body);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      };
      com.google.gson.Gson gson = new com.google.gson.Gson();
      // 画面には文字として渡す（文字化けしない）。エディタは改行を LF にそろえる
      Response file = request(s.getPort(), "GET", "/api/file?path=SaveMapper.xml", headers("Host", saveHost, "X-Auth-Token", TOKEN), null);
      String base = JsonParser.parseString(file.body).getAsJsonObject().get("content").getAsString();
      assertEquals(original, base);
      String edited = base.replace("\r\n", "\n").replace("元", "変更");
      post.apply("/api/drafts/save", "{\"file\":\"SaveMapper.xml\",\"base\":" + gson.toJson(base) + ",\"content\":" + gson.toJson(edited) + "}");

      // 下書きの組み立ても、宣言の文字コードで読む
      Response rendered = post.apply("/api/render", "{\"statementId\":\"save.M.find\",\"parameters\":{},\"draft\":{\"file\":\"SaveMapper.xml\",\"content\":" + gson.toJson(edited) + "}}");
      assertTrue(rendered.body.contains("/* 変更 */"), rendered.body);

      // 保存すると、宣言の文字コードと元の改行で書き、下書きを消す
      assertEquals(200, post.apply("/api/file/save", "{\"file\":\"SaveMapper.xml\",\"base\":" + gson.toJson(base) + ",\"content\":" + gson.toJson(edited) + "}").status);
      assertEquals(original.replace("元", "変更"), new String(java.nio.file.Files.readAllBytes(mapper), sjis));
      Response drafts = request(s.getPort(), "GET", "/api/drafts", headers("Host", saveHost, "X-Auth-Token", TOKEN), null);
      assertEquals(0, JsonParser.parseString(drafts.body).getAsJsonObject().getAsJsonArray("drafts").size());

      // 編集を始めた後にファイルが変わっていたら、force がなければ保存しない
      Response conflict = post.apply("/api/file/save", "{\"file\":\"SaveMapper.xml\",\"base\":" + gson.toJson(base) + ",\"content\":\"x\"}");
      assertEquals(409, conflict.status);
      assertTrue(conflict.body.contains("\"conflict\":true"), conflict.body);
      assertEquals(original.replace("元", "変更"), new String(java.nio.file.Files.readAllBytes(mapper), sjis));
      String forced = edited.replace("変更", "上書き");
      assertEquals(200, post.apply("/api/file/save", "{\"file\":\"SaveMapper.xml\",\"base\":" + gson.toJson(base) + ",\"content\":" + gson.toJson(forced) + ",\"force\":true}").status);
      assertEquals(original.replace("元", "上書き"), new String(java.nio.file.Files.readAllBytes(mapper), sjis));

      // 読み込んでいない mapper XML 以外には書かない
      assertEquals(400, post.apply("/api/file/save", "{\"file\":\"../x.xml\",\"base\":\"\",\"content\":\"x\"}").status);
    } finally {
      s.stop();
    }
  }

  @Test
  void プレイグラウンド(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
    server.usePlaygrounds(PlaygroundStore.at(dir));
    try {
      api("POST", "/api/playground/create", "{\"name\":\"売上の調査\"}", 200);
      api("POST", "/api/playground/create", "{\"name\":\"売上の調査\"}", 400);
      api("POST", "/api/playground/create", "{\"name\":\"../x\"}", 400);
      JsonObject list = api("GET", "/api/statements", null, 200);
      assertTrue(list.getAsJsonArray("statements").toString().contains(
          "{\"id\":\"playground.売上の調査.example\",\"file\":\"playground:売上の調査\",\"type\":\"SELECT\",\"playground\":true}"), list.toString());
      assertEquals("[{\"file\":\"playground:売上の調査\",\"name\":\"売上の調査\",\"errors\":[]}]", list.getAsJsonArray("playgrounds").toString());

      // 中身はファイルの API で読み書きする。プロジェクトの sql 断片を include できる
      String content = api("GET", "/api/file?path=" + java.net.URLEncoder.encode("playground:売上の調査", "UTF-8"), null, 200).get("content").getAsString();
      String edited = content.replace("SELECT 1", "SELECT <include refid=\"sample.Common.userColumns\"/> FROM users WHERE id = #{id}");
      com.google.gson.Gson gson = new com.google.gson.Gson();
      api("POST", "/api/file/save", "{\"file\":\"playground:売上の調査\",\"base\":" + gson.toJson(content) + ",\"content\":" + gson.toJson(edited) + ",\"force\":true}", 200);
      JsonObject rendered = api("POST", "/api/render", "{\"statementId\":\"playground.売上の調査.example\",\"parameters\":{\"id\":7}}", 200);
      assertTrue(rendered.get("literalSql").getAsString().contains("id, name, status  FROM users WHERE id = 7"), rendered.toString());
      assertTrue(api("GET", "/api/parameters?id=" + java.net.URLEncoder.encode("playground.売上の調査.example", "UTF-8"), null, 200).get("tree").toString().contains("\"name\":\"id\""));
      assertTrue(api("GET", "/api/parameters-all", null, 200).getAsJsonObject("trees").has("playground.売上の調査.example"));

      // 読み込めないときもプレイグラウンドは一覧に出し、エラーを付ける
      api("POST", "/api/file/save", "{\"file\":\"playground:売上の調査\",\"base\":\"\",\"content\":\"<mapper\",\"force\":true}", 200);
      list = api("GET", "/api/statements", null, 200);
      assertTrue(list.getAsJsonArray("playgrounds").get(0).getAsJsonObject().getAsJsonArray("errors").size() > 0, list.toString());
      assertFalse(list.getAsJsonArray("statements").toString().contains("playground."), list.toString());

      api("POST", "/api/playground/rename", "{\"name\":\"売上の調査\",\"newName\":\"検証\"}", 200);
      assertEquals("playground:検証", api("GET", "/api/statements", null, 200).getAsJsonArray("playgrounds").get(0).getAsJsonObject().get("file").getAsString());
      api("POST", "/api/playground/delete", "{\"name\":\"検証\"}", 200);
      assertEquals(0, api("GET", "/api/statements", null, 200).getAsJsonArray("playgrounds").size());
      // プレイグラウンドのパスでも、ないファイルは返さない
      api("GET", "/api/file?path=" + java.net.URLEncoder.encode("playground:検証", "UTF-8"), null, 404);
    } finally {
      server.usePlaygrounds(PlaygroundStore.at(null));
    }
  }

  @Test
  void パスワードの保存() throws Exception {
    server.useVault(PasswordVault.inMemory());
    try {
      // マスターパスワードを設定していなければ、保存できない
      api("POST", "/api/connections/save",
          "{\"name\":\"v\",\"host\":\"h\",\"port\":5432,\"database\":\"d\",\"user\":\"u\",\"password\":\"pw\",\"remember\":true}", 400);
      assertEquals("{\"exists\":false,\"unlocked\":false,\"minLength\":8}", api("GET", "/api/vault", null, 200).toString());
      api("POST", "/api/vault/setup", "{\"password\":\"short\"}", 400);
      api("POST", "/api/vault/setup", "{\"password\":\"correct horse\"}", 200);
      JsonObject saved = api("POST", "/api/connections/save",
          "{\"name\":\"vault\",\"host\":\"h\",\"port\":5432,\"database\":\"d\",\"user\":\"u\",\"password\":\"pw\",\"remember\":true}", 200);
      String id = saved.get("id").getAsString();
      assertTrue(saved.get("passwordSaved").getAsBoolean());
      assertTrue(saved.get("hasPassword").getAsBoolean());

      // 閉じると、保存しているパスワードは接続先から外れる。開くと戻る
      api("POST", "/api/vault/lock", "{}", 200);
      JsonObject locked = connection(id);
      assertFalse(locked.get("hasPassword").getAsBoolean());
      assertTrue(locked.get("passwordSaved").getAsBoolean());
      api("POST", "/api/vault/unlock", "{\"password\":\"wrong password\"}", 400);
      assertEquals("{\"exists\":true,\"unlocked\":true,\"minLength\":8}", api("POST", "/api/vault/unlock", "{\"password\":\"correct horse\"}", 200).toString());
      assertTrue(connection(id).get("hasPassword").getAsBoolean());

      // 保存しないにすると消す。接続先を消しても消す
      api("POST", "/api/connections/save", "{\"id\":\"" + id + "\",\"name\":\"vault\",\"host\":\"h\",\"port\":5432,\"database\":\"d\",\"user\":\"u\",\"remember\":false}", 200);
      assertFalse(connection(id).get("passwordSaved").getAsBoolean());
      assertTrue(connection(id).get("hasPassword").getAsBoolean());
      // パスワード欄を空のまま保存するにしたら、入力済みのものを保存する
      assertTrue(api("POST", "/api/connections/save", "{\"id\":\"" + id + "\",\"name\":\"vault\",\"host\":\"h\",\"port\":5432,\"database\":\"d\",\"user\":\"u\",\"remember\":true}", 200)
          .get("passwordSaved").getAsBoolean());
      api("POST", "/api/connections/delete", "{\"id\":\"" + id + "\"}", 200);
      api("POST", "/api/vault/reset", "{}", 200);
      assertEquals("{\"exists\":false,\"unlocked\":false,\"minLength\":8}", api("GET", "/api/vault", null, 200).toString());
    } finally {
      server.useVault(PasswordVault.inMemory());
    }
  }

  private JsonObject connection(String id) throws Exception {
    for (com.google.gson.JsonElement e : api("GET", "/api/connections", null, 200).getAsJsonArray("connections")) {
      if (e.getAsJsonObject().get("id").getAsString().equals(id)) {
        return e.getAsJsonObject();
      }
    }
    throw new AssertionError(id);
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
  void 共通パラメータ() throws Exception {
    JsonObject created = api("POST", "/api/globals/create", "{\"name\":\"テナントA\"}", 200);
    String id = created.getAsJsonArray("sets").get(0).getAsJsonObject().get("id").getAsString();
    api("POST", "/api/globals/values", "{\"id\":\"" + id + "\",\"values\":{\"tenantId\":1.0}}", 200);
    JsonObject saved = api("POST", "/api/globals/statement",
        "{\"statement\":\"sample.UserMapper.search\",\"added\":[\"" + id + "\"],\"removed\":[],\"order\":[\"" + id + "\"]}", 200);
    assertEquals("{\"tenantId\":1.0}", saved.getAsJsonArray("sets").get(0).getAsJsonObject().get("values").toString());
    assertEquals("{\"sample.UserMapper.search\":{\"added\":[\"" + id + "\"],\"removed\":[],\"order\":[\"" + id + "\"]}}",
        saved.get("statements").toString());
    api("POST", "/api/globals/statement", "{\"statement\":\"sample.UserMapper.search\",\"added\":[],\"removed\":[],\"order\":null}", 200);
    assertEquals("true", api("POST", "/api/globals/default", "{\"id\":\"" + id + "\",\"on\":true}", 200)
        .getAsJsonArray("sets").get(0).getAsJsonObject().get("default").toString());
    api("POST", "/api/globals/reorder", "{\"ids\":[\"" + id + "\"]}", 200);

    api("POST", "/api/globals/values", "{\"id\":\"" + id + "\",\"values\":[1]}", 400);
    api("POST", "/api/globals/statement", "{\"statement\":\"x\",\"added\":\"a\"}", 400);
    api("POST", "/api/globals/create", "{\"name\":\"テナントA\"}", 400);
    api("POST", "/api/globals/delete", "{\"id\":\"" + id + "\"}", 200);
    api("POST", "/api/globals/delete", "{\"id\":\"" + id + "\"}", 400);
  }

  @Test
  void 項目名の集計() throws Exception {
    String names = api("GET", "/api/parameter-names", null, 200).toString();
    assertTrue(names.contains("{\"path\":\"cond.status\",\"count\":1,\"usages\":[\"BIND\",\"CONDITION\"]"), names);
  }

  @Test
  void エディタで開く() throws Exception {
    java.nio.file.Path out = java.nio.file.Files.createTempFile("open", ".txt");
    try {
      assertEquals("{\"available\":false,\"command\":null}", api("GET", "/api/editor", null, 200).toString());
      api("POST", "/api/open", "{\"statementId\":\"sample.UserMapper.bulkInsert\"}", 422);

      server.setEditor(EditorLauncher.of("sh -c 'printf \"%s\\n\" \"$0\" >> " + out + "' {file}:{line}"));
      assertTrue(api("GET", "/api/editor", null, 200).get("available").getAsBoolean());
      api("POST", "/api/open", "{\"statementId\":\"sample.UserMapper.bulkInsert\"}", 200);
      api("POST", "/api/open", "{\"file\":\"mappers/Common.xml\",\"line\":3}", 200);
      String opened = new String(java.nio.file.Files.readAllBytes(out), StandardCharsets.UTF_8);
      String base = Paths.get("src/test/resources").toAbsolutePath().normalize().toString();
      String[] lines = opened.split("\n");
      assertTrue(lines[0].startsWith(base + "/mappers/UserMapper.xml:") && !lines[0].endsWith(":1"), opened);
      assertEquals(base + "/mappers/Common.xml:3", lines[1]);

      // 読み込んでいない mapper XML 以外のファイルは開かない
      api("POST", "/api/open", "{\"file\":\"/etc/passwd\"}", 404);
      api("POST", "/api/open", "{\"file\":\"../pom.xml\"}", 404);
    } finally {
      server.setEditor(EditorLauncher.disabled());
      java.nio.file.Files.deleteIfExists(out);
    }
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
    return request(server.getPort(), method, path, headers, body);
  }

  private static Response request(int port, String method, String path, Map<String, String> headers, String body) throws Exception {
    byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    StringBuilder req = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
    for (Map.Entry<String, String> e : headers.entrySet()) {
      req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
    }
    req.append("Content-Length: ").append(payload.length).append("\r\nConnection: close\r\n\r\n");

    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
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
