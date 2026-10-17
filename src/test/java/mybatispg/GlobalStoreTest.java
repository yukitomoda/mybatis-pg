package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class GlobalStoreTest {

  @TempDir
  Path dir;

  private static JsonObject json(String s) {
    return JsonParser.parseString(s).getAsJsonObject();
  }

  private static String names(GlobalStore s) {
    java.util.List<String> out = new java.util.ArrayList<>();
    for (com.google.gson.JsonElement e : s.snapshot().getAsJsonArray("sets")) {
      out.add(e.getAsJsonObject().get("name").getAsString());
    }
    return out.toString();
  }

  @Test
  void プロジェクトごとに保存し数値は書かれたまま保つ() throws Exception {
    Path file = dir.resolve("globals.json");
    GlobalStore a = GlobalStore.load(file, "/project/a");
    String id = a.create("テナントA", null).get("id").getAsString();
    String other = a.create("B", null).get("id").getAsString();
    a.saveValues(id, json("{\"tenantId\":100,\"rate\":1.50}"));
    a.setDefault(id, true);
    a.setStatement("shop.order.OrderMapper.recent", Arrays.asList(other), Arrays.asList(id), Arrays.asList(other));

    GlobalStore b = GlobalStore.load(file, "/project/b");
    assertEquals("{\"sets\":[],\"statements\":{}}", b.snapshot().toString());
    b.create("B", null);

    JsonObject reloaded = GlobalStore.load(file, "/project/a").snapshot();
    assertEquals("true", reloaded.getAsJsonArray("sets").get(0).getAsJsonObject().get("default").toString());
    assertEquals("{\"tenantId\":100,\"rate\":1.50}", reloaded.getAsJsonArray("sets").get(0).getAsJsonObject().get("values").toString());
    assertEquals("{\"shop.order.OrderMapper.recent\":{\"added\":[\"" + other + "\"],\"removed\":[\"" + id + "\"],\"order\":[\"" + other + "\"]}}",
        reloaded.get("statements").toString());
    assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("/project/b"));
  }

  @Test
  void statementの設定は何もなければ消す_削除するとstatementの設定からも消す() throws Exception {
    GlobalStore s = GlobalStore.inMemory();
    String a = s.create("A", null).get("id").getAsString();
    s.setDefault(a, true);
    String b = s.create("B", a).get("id").getAsString();
    assertEquals("true", s.snapshot().getAsJsonArray("sets").get(1).getAsJsonObject().get("default").toString());
    String c = s.create("C", null).get("id").getAsString();
    assertEquals("false", s.snapshot().getAsJsonArray("sets").get(2).getAsJsonObject().get("default").toString());
    s.setStatement("x.y", Arrays.asList(c), Arrays.asList(b), Arrays.asList(c, a));
    s.delete(c);
    assertEquals("{\"x.y\":{\"added\":[],\"removed\":[\"" + b + "\"],\"order\":[\"" + a + "\"]}}", s.snapshot().get("statements").toString());
    s.setStatement("x.y", Collections.<String>emptyList(), Collections.<String>emptyList(), null);
    assertEquals("{}", s.snapshot().get("statements").toString());
    assertThrows(IllegalArgumentException.class, () -> s.setStatement("x.y", Arrays.asList("no-such"), Collections.<String>emptyList(), null));
    assertThrows(IllegalArgumentException.class, () -> s.setStatement(" ", Collections.<String>emptyList(), Collections.<String>emptyList(), null));
  }

  @Test
  void 既定の順番を変える() throws Exception {
    GlobalStore s = GlobalStore.inMemory();
    String a = s.create("A", null).get("id").getAsString();
    String b = s.create("B", null).get("id").getAsString();
    String c = s.create("C", null).get("id").getAsString();
    s.reorder(Arrays.asList(c, a, b));
    assertEquals("[C, A, B]", names(s));
    assertThrows(IllegalArgumentException.class, () -> s.reorder(Arrays.asList(c, a)));
    assertThrows(IllegalArgumentException.class, () -> s.reorder(Arrays.asList(c, a, a)));
  }

  @Test
  void 無効にしていた形は外したものにする() throws Exception {
    JsonObject p = migrate("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{}}],"
        + "\"statements\":{\"x.y\":{\"disabled\":[\"a\"],\"order\":[\"a\"]}}}");
    assertEquals("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{},\"default\":true}],"
        + "\"statements\":{\"x.y\":{\"order\":[\"a\"],\"removed\":[\"a\"],\"added\":[]}}}", p.toString());
  }

  private JsonObject migrate(String project) throws Exception {
    Path file = dir.resolve("globals.json");
    Files.write(file, ("{\"projects\":{\"/p\":" + project + "}}").getBytes(StandardCharsets.UTF_8));
    return GlobalStore.load(file, "/p").snapshot();
  }

  @Test
  void スコープの形は適用しないものをstatementで外したものにする() throws Exception {
    JsonObject p = migrate("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{},\"scope\":[\"shop/order/\"],\"excludes\":[\"x.y\"]}],"
        + "\"order\":{\"*\":[\"a\"]}}");
    assertEquals("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{},\"default\":true}],\"statements\":{\"x.y\":{\"added\":[],\"removed\":[\"a\"]}}}", p.toString());
  }

  @Test
  void 場所ごとの適用の形はstatementでの適用しないを外したものにする() throws Exception {
    JsonObject p = migrate("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{},"
        + "\"rules\":{\"*\":\"on\",\"shop/x/\":\"off\",\"shop.x.M.find\":\"off\"}}]}");
    assertEquals("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{},\"default\":true}],\"statements\":{\"shop.x.M.find\":{\"added\":[],\"removed\":[\"a\"]}}}", p.toString());
  }

  @Test
  void 一つを選ぶ形は選んでいたものだけを既定で入れる() throws Exception {
    // v0.2.1 までの形
    JsonObject p = migrate("{\"active\":\"a\",\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{}},{\"id\":\"b\",\"name\":\"B\",\"values\":{}}]}");
    assertEquals("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{},\"default\":true},"
        + "{\"id\":\"b\",\"name\":\"B\",\"values\":{},\"default\":false}],\"statements\":{}}", p.toString());
    // 何も選んでいなければ、どれも入れない
    JsonObject none = migrate("{\"active\":null,\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{}}]}");
    assertEquals("false", none.getAsJsonArray("sets").get(0).getAsJsonObject().get("default").toString());
  }

  @Test
  void 範囲とオンオフの形は既定で入れる() throws Exception {
    JsonObject p = migrate("{\"sets\":[{\"id\":\"b\",\"name\":\"B\",\"values\":{},\"enabled\":false,\"scope\":{\"all\":true,\"items\":[]}}]}");
    assertEquals("{\"sets\":[{\"id\":\"b\",\"name\":\"B\",\"values\":{},\"default\":true}],\"statements\":{}}", p.toString());
  }

  @Test
  void 名前の検証() throws Exception {
    GlobalStore s = GlobalStore.inMemory();
    String a = s.create("A", null).get("id").getAsString();
    String b = s.create("B", null).get("id").getAsString();
    assertThrows(IllegalArgumentException.class, () -> s.create(" A ", null));
    assertThrows(IllegalArgumentException.class, () -> s.rename(b, "A"));
    assertThrows(IllegalArgumentException.class, () -> s.create("", null));
    s.rename(a, "A");
  }
}
