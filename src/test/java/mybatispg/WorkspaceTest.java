package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class WorkspaceTest {

  private static final String TOKEN = "test-token";
  private static final Path RES = Paths.get("src/test/resources").toAbsolutePath().normalize();

  private static Workspaces.Source source(String name, String type, Path path) {
    return new Workspaces.Source(Workspaces.newId(), name, type, path.toString(), true);
  }

  @Test
  void 複数のソースをまとめて読み込み_ファイルはソースの名前を付けて見せる() throws Exception {
    Workspaces.Def def = new Workspaces.Def();
    def.id = "a";
    def.key = "workspace:a";
    def.sources = Arrays.asList(source("users", Workspaces.FILES, RES.resolve("mappers")),
        source("params", Workspaces.FILES, RES.resolve("params")));
    MapperWorkspace ws = MapperWorkspace.of(def);
    assertTrue(ws.renderer().statementIds().contains("sample.UserMapper.findStatic"));
    Path user = RES.resolve("mappers/UserMapper.xml");
    assertEquals("users/UserMapper.xml", ws.displayPath(user.toString()));
    assertEquals(null, ws.baseDir());
    assertFalse(ws.isReadOnly(user));

    // ソースが 1 つなら、前と同じく基準からの相対パス
    def.sources = Arrays.asList(source("users", Workspaces.FILES, RES.resolve("mappers")));
    ws.configure(def);
    assertEquals("UserMapper.xml", ws.displayPath(user.toString()));
    // 無効なソースと、ないパスのソースは読まない。定義したソースが 2 つ以上なら、有効なものが 1 つでも名前を付ける
    Workspaces.Source off = source("params", Workspaces.FILES, RES.resolve("params"));
    off.enabled = false;
    def.sources = Arrays.asList(source("users", Workspaces.FILES, RES.resolve("mappers")), off,
        source("none", Workspaces.FILES, RES.resolve("no-such")));
    ws.configure(def);
    assertEquals("users/UserMapper.xml", ws.displayPath(user.toString()));
    assertEquals(Arrays.asList("users"), ws.sourceNames());
    assertFalse(ws.renderer().statementIds().stream().anyMatch(id -> id.startsWith("sample.ParamMapper.")));
  }

  @Test
  void ソースどうしで同じnamespaceのmapperがあれば_どれと重なったかをエラーにする(@TempDir Path dir) throws Exception {
    java.nio.file.Files.copy(RES.resolve("mappers/UserMapper.xml"), dir.resolve("UserMapper.xml"));
    Workspaces.Def def = new Workspaces.Def();
    def.id = "a";
    def.key = "workspace:a";
    def.sources = Arrays.asList(source("users", Workspaces.FILES, RES.resolve("mappers")), source("copy", Workspaces.FILES, dir));
    MapperWorkspace ws = MapperWorkspace.of(def);
    LoadError e = ws.renderer().getLoadErrors().get(0);
    assertEquals("copy/UserMapper.xml", e.source);
    assertTrue(e.message.contains("が「users/UserMapper.xml」と重なるため"), e.message);
  }

  @Test
  void 定義を保存し_最後に開いたものから並べる(@TempDir Path dir) throws Exception {
    Workspaces store = Workspaces.open(dir);
    Workspaces.Def a = store.create("受発注", null, new ArrayList<Workspaces.Source>());
    Workspaces.Def b = store.create("受発注", "/work/project", Arrays.asList(source("p", Workspaces.PROJECT, dir)));
    assertEquals("受発注 (2)", b.name);
    assertEquals("workspace:" + a.id, a.key);
    store.touch(a);
    assertEquals(a.id, Workspaces.open(dir).last().id);
    assertEquals(b.id, store.findByKey("/work/project").id);
    assertEquals(dir.toString(), store.find("受発注 (2)").sources.get(0).path);
    assertTrue(store.delete(b.id));
    assertEquals(1, store.list().size());
    assertEquals("絶対パスで入れてください: x", Workspaces.check(new Workspaces.Source("i", "n", Workspaces.PROJECT, "x", true)));
  }

  @Test
  void 画面からソースを変え_ワークスペースを作って切り替える(@TempDir Path config) throws Exception {
    Workspaces store = Workspaces.open(config.resolve("workspaces"));
    Workspaces.Def def = store.create("受発注", null,
        Arrays.asList(source("users", Workspaces.FILES, RES.resolve("mappers")), source("params", Workspaces.FILES, RES.resolve("params"))));
    MapperWorkspace ws = MapperWorkspace.of(def);
    ConnectionStore connections = ConnectionStore.inMemory();
    String db1 = connections.save(profile("検証1"), null).id;
    String db2 = connections.save(profile("検証2"), null).id;
    ToolServer server = ToolServer.start(ws, connections, GlobalStore.inMemory(), 0, TOKEN);
    try {
      server.useWorkspaces(config, store, def);
      String base = "http://127.0.0.1:" + server.getPort();
      JsonObject st = call(base, "GET", "/api/statements", null, 200);
      assertEquals("受発注", st.get("workspace").getAsString());
      TreeSet<String> sources = topDirs(st);
      assertEquals(new TreeSet<>(Arrays.asList("params", "users")), sources);

      JsonObject snap = call(base, "GET", "/api/workspace", null, 200);
      JsonObject cur = snap.getAsJsonObject("current");
      assertEquals(2, cur.getAsJsonArray("sources").size());
      assertTrue(cur.getAsJsonArray("sources").get(0).getAsJsonObject().get("files").getAsInt() > 0);

      // params を無効にすると、そのファイルは読まない
      String users = cur.getAsJsonArray("sources").get(0).getAsJsonObject().get("id").getAsString();
      String params = cur.getAsJsonArray("sources").get(1).getAsJsonObject().get("id").getAsString();
      call(base, "POST", "/api/workspace/save", "{\"name\":\"受発注\",\"sources\":[{\"id\":\"" + users + "\",\"name\":\"users\",\"type\":\"files\",\"path\":"
          + str(RES.resolve("mappers")) + "},{\"id\":\"" + params + "\",\"name\":\"params\",\"type\":\"files\",\"path\":"
          + str(RES.resolve("params")) + ",\"enabled\":false}]}", 200);
      for (JsonElement e : call(base, "GET", "/api/statements", null, 200).getAsJsonArray("statements")) {
        assertFalse(e.getAsJsonObject().get("file").getAsString().startsWith("params/"), e.toString());
      }
      assertFalse(store.get(def.id).sources.get(1).enabled);
      // ないパスや、重なる名前は保存しない
      JsonObject bad = call(base, "POST", "/api/workspace/save",
          "{\"name\":\"受発注\",\"sources\":[{\"name\":\"x\",\"type\":\"project\",\"path\":" + str(RES.resolve("no-such")) + "}]}", 400);
      assertTrue(bad.get("error").getAsString().startsWith("ディレクトリがありません"), bad.toString());

      // 作って開く: ソースはなく、statement もない
      JsonObject created = call(base, "POST", "/api/workspace/create", "{\"name\":\"在庫\"}", 200);
      assertEquals("在庫", created.getAsJsonObject("current").get("name").getAsString());
      assertEquals(0, call(base, "GET", "/api/statements", null, 200).getAsJsonArray("statements").size());
      assertEquals(2, created.getAsJsonArray("workspaces").size());
      // 開いているものは消せない
      String other = created.getAsJsonObject("current").get("id").getAsString();
      call(base, "POST", "/api/workspace/delete", "{\"id\":\"" + other + "\"}", 400);
      // 前のものを開き直すと、ソースも戻る
      call(base, "POST", "/api/workspace/open", "{\"id\":\"" + def.id + "\"}", 200);
      // 前のものを開き直すと、ソースも戻る（params は無効にしたので、users だけ）
      assertEquals(new TreeSet<>(Arrays.asList("users/UserMapper.xml")), files(call(base, "GET", "/api/statements", null, 200)));
      call(base, "POST", "/api/workspace/delete", "{\"id\":\"" + other + "\"}", 200);
      assertEquals(1, store.list().size());

      // リレーションの解析の結果を、すべての接続先で 1 つの接続先のものを使う
      assertTrue(call(base, "POST", "/api/relations", "{\"connectionId\":\"" + db2 + "\"}", 200).get("sharedFrom").isJsonNull());
      JsonObject shared = call(base, "POST", "/api/workspace/save", "{\"name\":\"受発注\",\"relationConnection\":\"" + db1 + "\",\"sources\":[{\"id\":\"" + users
          + "\",\"name\":\"users\",\"type\":\"files\",\"path\":" + str(RES.resolve("mappers")) + "}]}", 200);
      assertEquals(db1, shared.getAsJsonObject("current").get("relationConnection").getAsString());
      assertEquals(db1, call(base, "POST", "/api/relations", "{\"connectionId\":\"" + db2 + "\"}", 200).get("sharedFrom").getAsString());
      assertTrue(call(base, "POST", "/api/relations", "{\"connectionId\":\"" + db1 + "\"}", 200).get("sharedFrom").isJsonNull());
    } finally {
      server.stop();
    }
  }

  @Test
  void プロジェクトが2つ以上なら_接続先の名前にプロジェクトの名前を付け_2つ目からはidにも付ける(@TempDir Path dir) throws Exception {
    java.util.Map<String, Path> projects = new java.util.LinkedHashMap<>();
    for (String n : new String[] {"order-api", "batch"}) {
      Path res = dir.resolve(n).resolve("src/main/resources");
      java.nio.file.Files.createDirectories(res);
      java.nio.file.Files.write(res.resolve("application.yml"),
          "spring:\n  datasource:\n    url: jdbc:postgresql://127.0.0.1:5432/app\n    username: u\n".getBytes(StandardCharsets.UTF_8));
      projects.put(n, dir.resolve(n));
    }
    ConnectionStore store = ConnectionStore.inMemory();
    store.useProjects(projects);
    List<String> got = new ArrayList<>();
    for (ConnectionStore.Profile p : store.list()) {
      got.add(p.id + " " + p.name);
    }
    assertEquals(Arrays.asList("project:default order-api: application.yml", "project:batch/default batch: application.yml"), got);
    // 1 つなら、前と同じ
    store.useProjects(java.util.Collections.singletonMap("order-api", dir.resolve("order-api")));
    assertEquals("project:default application.yml", store.list().get(0).id + " " + store.list().get(0).name);
  }

  private static ConnectionStore.Profile profile(String name) {
    ConnectionStore.Profile p = new ConnectionStore.Profile();
    p.name = name;
    p.host = "127.0.0.1";
    p.database = "app";
    p.user = "u";
    p.params = "";
    return p;
  }

  private static String str(Path p) {
    return new com.google.gson.Gson().toJson(p.toString());
  }

  private static TreeSet<String> topDirs(JsonObject statements) {
    TreeSet<String> out = new TreeSet<>();
    for (JsonElement e : statements.getAsJsonArray("statements")) {
      String f = e.getAsJsonObject().get("file").getAsString();
      out.add(f.substring(0, f.indexOf('/')));
    }
    return out;
  }

  private static TreeSet<String> files(JsonObject statements) {
    TreeSet<String> out = new TreeSet<>();
    for (JsonElement e : statements.getAsJsonArray("statements")) {
      out.add(e.getAsJsonObject().get("file").getAsString());
    }
    return out;
  }

  private static JsonObject call(String base, String method, String path, String body, int expected) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
    c.setRequestMethod(method);
    c.setRequestProperty("X-Auth-Token", TOKEN);
    if (body != null) {
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      try (OutputStream out = c.getOutputStream()) {
        out.write(body.getBytes(StandardCharsets.UTF_8));
      }
    }
    assertEquals(expected, c.getResponseCode(), path);
    InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    for (int n; (n = in.read(b)) > 0;) {
      buf.write(b, 0, n);
    }
    return JsonParser.parseString(new String(buf.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
  }
}
