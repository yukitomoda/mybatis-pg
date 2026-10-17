package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
    a.saveValues(id, json("{\"tenantId\":100,\"rate\":1.50,\"cond\":{\"status\":null}}"));
    a.setRule(id, "*", "off");
    a.setRule(id, "shop/order/", "on");
    a.setRule(id, "shop.customer.CustomerMapper.search", "on");

    // 別のプロジェクトで保存しても、a の内容は残る
    GlobalStore b = GlobalStore.load(file, "/project/b");
    assertEquals("{\"sets\":[]}", b.snapshot().toString());
    b.create("B", null);

    JsonObject reloaded = set(GlobalStore.load(file, "/project/a"), 0);
    assertEquals("テナントA", reloaded.get("name").getAsString());
    assertEquals("{\"tenantId\":100,\"rate\":1.50,\"cond\":{\"status\":null}}", reloaded.get("values").toString());
    assertEquals("{\"*\":\"off\",\"shop/order/\":\"on\",\"shop.customer.CustomerMapper.search\":\"on\"}", reloaded.get("rules").toString());
    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    assertTrue(text.contains("/project/b"), text);
  }

  @Test
  void 新しいものはどこにも適用しない_複製は適用する場所も引き継ぐ() throws Exception {
    GlobalStore s = GlobalStore.inMemory();
    String a = s.create("A", null).get("id").getAsString();
    assertEquals("{}", set(s, 0).get("rules").toString());
    s.setRule(a, "shop/", "on");
    s.saveValues(a, json("{\"x\":1}"));
    s.create("B", a);
    assertEquals("{\"x\":1}", set(s, 1).get("values").toString());
    assertEquals("{\"shop/\":\"on\"}", set(s, 1).get("rules").toString());

    // null で決めない（外側に従う）に戻す
    s.setRule(a, "shop/", null);
    assertEquals("{}", set(s, 0).get("rules").toString());
    assertThrows(IllegalArgumentException.class, () -> s.setRule(a, "shop/", "maybe"));
    assertThrows(IllegalArgumentException.class, () -> s.setRule(a, " ", "on"));
  }

  @Test
  void 並び順を変える() throws Exception {
    GlobalStore s = GlobalStore.inMemory();
    String a = s.create("A", null).get("id").getAsString();
    s.create("B", null);
    String c = s.create("C", null).get("id").getAsString();
    s.move(c, -1);
    assertEquals("[A, C, B]", names(s));
    s.move(a, 5);
    assertEquals("[C, B, A]", names(s));
    s.move(c, -1);
    assertEquals("[C, B, A]", names(s));
  }

  private static String names(GlobalStore s) {
    java.util.List<String> out = new java.util.ArrayList<>();
    for (com.google.gson.JsonElement e : s.snapshot().getAsJsonArray("sets")) {
      out.add(e.getAsJsonObject().get("name").getAsString());
    }
    return out.toString();
  }

  @Test
  void 一つを選ぶ形は選んでいたものを全体で適用しほかは適用しない() throws Exception {
    Path file = dir.resolve("globals.json");
    Files.write(file, ("{\"projects\":{\"/p\":{\"active\":\"b\",\"sets\":["
        + "{\"id\":\"a\",\"name\":\"A\",\"values\":{}},{\"id\":\"b\",\"name\":\"B\",\"values\":{\"x\":1}}]}}}")
        .getBytes(StandardCharsets.UTF_8));
    GlobalStore s = GlobalStore.load(file, "/p");
    JsonArray sets = s.snapshot().getAsJsonArray("sets");
    assertEquals("{\"*\":\"off\"}", sets.get(0).getAsJsonObject().get("rules").toString());
    assertEquals("{\"*\":\"on\"}", sets.get(1).getAsJsonObject().get("rules").toString());
    assertFalse(s.snapshot().has("active"));
  }

  @Test
  void 範囲とオンオフの形は場所ごとの適用に直す() throws Exception {
    Path file = dir.resolve("globals.json");
    Files.write(file, ("{\"projects\":{\"/p\":{\"sets\":["
        + "{\"id\":\"a\",\"name\":\"A\",\"values\":{},\"enabled\":true,\"scope\":{\"all\":true,\"items\":[]}},"
        + "{\"id\":\"b\",\"name\":\"B\",\"values\":{},\"enabled\":true,\"scope\":{\"all\":false,\"items\":[\"shop/order/\",\"x.y.z\"]}},"
        + "{\"id\":\"c\",\"name\":\"C\",\"values\":{},\"enabled\":false,\"scope\":{\"all\":false,\"items\":[\"shop/\"]}}]}}}")
        .getBytes(StandardCharsets.UTF_8));
    JsonArray sets = GlobalStore.load(file, "/p").snapshot().getAsJsonArray("sets");
    assertEquals("{\"id\":\"a\",\"name\":\"A\",\"values\":{},\"rules\":{\"*\":\"on\"}}", sets.get(0).toString());
    assertEquals("{\"shop/order/\":\"on\",\"x.y.z\":\"on\"}", sets.get(1).getAsJsonObject().get("rules").toString());
    assertEquals("{\"*\":\"off\"}", sets.get(2).getAsJsonObject().get("rules").toString());
  }

  @Test
  void 名前の検証と削除() throws Exception {
    GlobalStore s = GlobalStore.inMemory();
    String a = s.create("A", null).get("id").getAsString();
    String b = s.create("B", null).get("id").getAsString();
    assertThrows(IllegalArgumentException.class, () -> s.create(" A ", null));
    assertThrows(IllegalArgumentException.class, () -> s.rename(b, "A"));
    assertThrows(IllegalArgumentException.class, () -> s.create("", null));
    s.rename(a, "A");
    s.rename(b, "B2");
    s.delete(b);
    assertEquals(1, s.snapshot().getAsJsonArray("sets").size());
    assertThrows(IllegalArgumentException.class, () -> s.setRule("no-such", "*", "on"));
  }
}
