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
    a.setStatement("shop.order.OrderMapper.recent", Arrays.asList(id), Arrays.asList(other, id));

    GlobalStore b = GlobalStore.load(file, "/project/b");
    assertEquals("{\"sets\":[],\"statements\":{}}", b.snapshot().toString());
    b.create("B", null);

    JsonObject reloaded = GlobalStore.load(file, "/project/a").snapshot();
    assertEquals("{\"tenantId\":100,\"rate\":1.50}", reloaded.getAsJsonArray("sets").get(0).getAsJsonObject().get("values").toString());
    assertEquals("{\"shop.order.OrderMapper.recent\":{\"disabled\":[\"" + id + "\"],\"order\":[\"" + other + "\",\"" + id + "\"]}}",
        reloaded.get("statements").toString());
    assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("/project/b"));
  }

  @Test
  void statementの設定は何もなければ消す_削除するとstatementの設定からも消す() throws Exception {
    GlobalStore s = GlobalStore.inMemory();
    String a = s.create("A", null).get("id").getAsString();
    String b = s.create("B", a).get("id").getAsString();
    s.setStatement("x.y", Arrays.asList(a, b), Arrays.asList(b, a));
    s.delete(b);
    assertEquals("{\"x.y\":{\"disabled\":[\"" + a + "\"],\"order\":[\"" + a + "\"]}}", s.snapshot().get("statements").toString());
    s.setStatement("x.y", Collections.<String>emptyList(), null);
    assertEquals("{}", s.snapshot().get("statements").toString());
    assertThrows(IllegalArgumentException.class, () -> s.setStatement("x.y", Arrays.asList("no-such"), null));
    assertThrows(IllegalArgumentException.class, () -> s.setStatement(" ", Collections.<String>emptyList(), null));
  }

  @Test
  void 既定の順番を変える() throws Exception {
    GlobalStore s = GlobalStore.inMemory();
    String a = s.create("A", null).get("id").getAsString();
    s.create("B", null);
    String c = s.create("C", null).get("id").getAsString();
    s.move(c, -1);
    assertEquals("[A, C, B]", names(s));
    s.move(a, 5);
    assertEquals("[C, B, A]", names(s));
  }

  private JsonObject migrate(String project) throws Exception {
    Path file = dir.resolve("globals.json");
    Files.write(file, ("{\"projects\":{\"/p\":" + project + "}}").getBytes(StandardCharsets.UTF_8));
    return GlobalStore.load(file, "/p").snapshot();
  }

  @Test
  void スコープの形は適用しないものをstatementの無効にする() throws Exception {
    JsonObject p = migrate("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{},\"scope\":[\"shop/order/\"],\"excludes\":[\"x.y\"]}],"
        + "\"order\":{\"*\":[\"a\"]}}");
    assertEquals("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{}}],\"statements\":{\"x.y\":{\"disabled\":[\"a\"]}}}", p.toString());
  }

  @Test
  void 場所ごとの適用の形はstatementでの適用しないを無効にする() throws Exception {
    JsonObject p = migrate("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{},"
        + "\"rules\":{\"*\":\"on\",\"shop/x/\":\"off\",\"shop.x.M.find\":\"off\"}}]}");
    assertEquals("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{}}],\"statements\":{\"shop.x.M.find\":{\"disabled\":[\"a\"]}}}", p.toString());
  }

  @Test
  void 一つを選ぶ形と範囲とオンオフの形はすべてグローバルにする() throws Exception {
    JsonObject p = migrate("{\"active\":\"a\",\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{}},"
        + "{\"id\":\"b\",\"name\":\"B\",\"values\":{},\"enabled\":false,\"scope\":{\"all\":true,\"items\":[]}}]}");
    assertEquals("{\"sets\":[{\"id\":\"a\",\"name\":\"A\",\"values\":{}},{\"id\":\"b\",\"name\":\"B\",\"values\":{}}],\"statements\":{}}", p.toString());
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
