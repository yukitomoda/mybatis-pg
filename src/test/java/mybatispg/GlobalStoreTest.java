package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

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
    a.setScope(id, false, Arrays.asList("shop/order/", "shop.customer.CustomerMapper.search", " "));

    // 別のプロジェクトで保存しても、a の内容は残る
    GlobalStore b = GlobalStore.load(file, "/project/b");
    assertEquals("{\"sets\":[]}", b.snapshot().toString());
    b.create("B", null);

    JsonObject reloaded = set(GlobalStore.load(file, "/project/a"), 0);
    assertEquals("テナントA", reloaded.get("name").getAsString());
    assertEquals("{\"tenantId\":100,\"rate\":1.50,\"cond\":{\"status\":null}}", reloaded.get("values").toString());
    assertTrue(reloaded.get("enabled").getAsBoolean());
    assertEquals("{\"all\":false,\"items\":[\"shop/order/\",\"shop.customer.CustomerMapper.search\"]}", reloaded.get("scope").toString());
    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    assertTrue(text.contains("/project/b"), text);
  }

  @Test
  void 新しいものはオンで範囲は全体_複製は範囲も引き継ぐ() throws Exception {
    GlobalStore s = GlobalStore.inMemory();
    String a = s.create("A", null).get("id").getAsString();
    assertEquals("{\"all\":true,\"items\":[]}", set(s, 0).get("scope").toString());
    s.setScope(a, false, Collections.singletonList("shop/"));
    s.saveValues(a, json("{\"x\":1}"));
    s.create("B", a);
    assertEquals("{\"x\":1}", set(s, 1).get("values").toString());
    assertEquals("{\"all\":false,\"items\":[\"shop/\"]}", set(s, 1).get("scope").toString());

    s.setEnabled(a, false);
    assertFalse(set(s, 0).get("enabled").getAsBoolean());
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
  void 以前の形は選んでいたものをオン_範囲を全体として引き継ぐ() throws Exception {
    Path file = dir.resolve("globals.json");
    Files.write(file, ("{\"projects\":{\"/p\":{\"active\":\"b\",\"sets\":["
        + "{\"id\":\"a\",\"name\":\"A\",\"values\":{}},{\"id\":\"b\",\"name\":\"B\",\"values\":{\"x\":1}}]}}}")
        .getBytes(StandardCharsets.UTF_8));
    GlobalStore s = GlobalStore.load(file, "/p");
    JsonArray sets = s.snapshot().getAsJsonArray("sets");
    assertFalse(sets.get(0).getAsJsonObject().get("enabled").getAsBoolean());
    assertTrue(sets.get(1).getAsJsonObject().get("enabled").getAsBoolean());
    assertEquals("{\"all\":true,\"items\":[]}", sets.get(1).getAsJsonObject().get("scope").toString());
    assertFalse(s.snapshot().has("active"));
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
    assertThrows(IllegalArgumentException.class, () -> s.setEnabled("no-such", true));
  }
}
