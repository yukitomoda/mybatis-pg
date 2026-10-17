package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class GlobalStoreTest {

  @TempDir
  Path dir;

  private static JsonObject json(String s) {
    return JsonParser.parseString(s).getAsJsonObject();
  }

  private static JsonObject set(GlobalStore s, int i) {
    return s.snapshot().getAsJsonArray("sets").get(i).getAsJsonObject();
  }

  @Test
  void プロジェクトごとに保存し数値は書かれたまま保つ() throws Exception {
    Path file = dir.resolve("globals.json");
    GlobalStore a = GlobalStore.load(file, "/project/a");
    String id = a.create("テナントA", null).get("id").getAsString();
    a.saveValues(id, json("{\"tenantId\":100,\"rate\":1.50}"));
    a.setScope(id, Arrays.asList("shop/order/", "shop.customer.CustomerMapper.search", "shop/order/"));
    a.setExcluded(id, "shop.order.OrderMapper.recent", true);
    a.setOrder("shop/order/", Arrays.asList(id));

    // 別のプロジェクトで保存しても、a の内容は残る
    GlobalStore b = GlobalStore.load(file, "/project/b");
    assertEquals("{\"sets\":[],\"order\":{}}", b.snapshot().toString());
    b.create("B", null);

    GlobalStore reloaded = GlobalStore.load(file, "/project/a");
    JsonObject s = set(reloaded, 0);
    assertEquals("{\"tenantId\":100,\"rate\":1.50}", s.get("values").toString());
    assertEquals("[\"shop/order/\",\"shop.customer.CustomerMapper.search\"]", s.get("scope").toString());
    assertEquals("[\"shop.order.OrderMapper.recent\"]", s.get("excludes").toString());
    assertEquals("{\"shop/order/\":[\"" + id + "\"]}", reloaded.snapshot().get("order").toString());
    assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("/project/b"));
  }

  @Test
  void 新しいものはスコープなし_複製はスコープと適用しないものも引き継ぐ() throws Exception {
    GlobalStore s = GlobalStore.inMemory();
    String a = s.create("A", null).get("id").getAsString();
    assertEquals("[]", set(s, 0).get("scope").toString());
    s.setScope(a, Arrays.asList("*"));
    s.setExcluded(a, "x.y", true);
    s.setExcluded(a, "x.y", true);
    s.create("B", a);
    assertEquals("[\"*\"]", set(s, 1).get("scope").toString());
    assertEquals("[\"x.y\"]", set(s, 1).get("excludes").toString());
    s.setExcluded(a, "x.y", false);
    assertEquals("[]", set(s, 0).get("excludes").toString());
    assertThrows(IllegalArgumentException.class, () -> s.setScope(a, Arrays.asList(" ")));
  }

  @Test
  void 削除すると適用順序からも消す() throws Exception {
    GlobalStore s = GlobalStore.inMemory();
    String a = s.create("A", null).get("id").getAsString();
    String b = s.create("B", null).get("id").getAsString();
    s.setOrder("*", Arrays.asList(b, a));
    s.delete(b);
    assertEquals("{\"*\":[\"" + a + "\"]}", s.snapshot().get("order").toString());
    assertThrows(IllegalArgumentException.class, () -> s.setOrder("*", Arrays.asList("no-such")));
  }

  private JsonArray migrate(String project) throws Exception {
    Path file = dir.resolve("globals.json");
    Files.write(file, ("{\"projects\":{\"/p\":" + project + "}}").getBytes(StandardCharsets.UTF_8));
    return GlobalStore.load(file, "/p").snapshot().getAsJsonArray("sets");
  }

  @Test
  void 一つを選ぶ形は選んでいたもののスコープを全体にする() throws Exception {
    JsonArray sets = migrate("{\"active\":\"b\",\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{}},"
        + "{\"id\":\"b\",\"name\":\"B\",\"values\":{}}]}");
    assertEquals("[]", sets.get(0).getAsJsonObject().get("scope").toString());
    assertEquals("[\"*\"]", sets.get(1).getAsJsonObject().get("scope").toString());
  }

  @Test
  void 範囲とオンオフの形はオンのものの範囲をスコープにする() throws Exception {
    JsonArray sets = migrate("{\"sets\":["
        + "{\"id\":\"a\",\"name\":\"A\",\"values\":{},\"enabled\":true,\"scope\":{\"all\":true,\"items\":[]}},"
        + "{\"id\":\"b\",\"name\":\"B\",\"values\":{},\"enabled\":true,\"scope\":{\"all\":false,\"items\":[\"shop/order/\"]}},"
        + "{\"id\":\"c\",\"name\":\"C\",\"values\":{},\"enabled\":false,\"scope\":{\"all\":true,\"items\":[]}}]}");
    assertEquals("{\"id\":\"a\",\"name\":\"A\",\"values\":{},\"scope\":[\"*\"],\"excludes\":[]}", sets.get(0).toString());
    assertEquals("[\"shop/order/\"]", sets.get(1).getAsJsonObject().get("scope").toString());
    assertEquals("[]", sets.get(2).getAsJsonObject().get("scope").toString());
  }

  @Test
  void 場所ごとの適用の形は適用する場所をスコープにし_statementでの適用しないを残す() throws Exception {
    JsonArray sets = migrate("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{},"
        + "\"rules\":{\"*\":\"on\",\"shop/x/\":\"off\",\"shop.x.M.find\":\"off\",\"shop.y.M.find\":\"on\"}}]}");
    JsonObject a = sets.get(0).getAsJsonObject();
    assertEquals("[\"*\",\"shop.y.M.find\"]", a.get("scope").toString());
    assertEquals("[\"shop.x.M.find\"]", a.get("excludes").toString());
    assertFalse(a.has("rules"));
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
