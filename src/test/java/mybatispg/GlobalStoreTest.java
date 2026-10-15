package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

  @Test
  void プロジェクトごとに保存し数値は書かれたまま保つ() throws Exception {
    Path file = dir.resolve("globals.json");
    GlobalStore a = GlobalStore.load(file, "/project/a");
    String id = a.create("テナントA", null).get("id").getAsString();
    a.saveValues(id, json("{\"tenantId\":100,\"rate\":1.50,\"cond\":{\"status\":null}}"));
    a.select(id);

    // 別のプロジェクトで保存しても、a の内容は残る
    GlobalStore b = GlobalStore.load(file, "/project/b");
    assertEquals("{\"active\":null,\"sets\":[]}", b.snapshot().toString());
    b.create("B", null);

    JsonObject reloaded = GlobalStore.load(file, "/project/a").snapshot();
    assertEquals(id, reloaded.get("active").getAsString());
    JsonObject set = reloaded.getAsJsonArray("sets").get(0).getAsJsonObject();
    assertEquals("テナントA", set.get("name").getAsString());
    assertEquals("{\"tenantId\":100,\"rate\":1.50,\"cond\":{\"status\":null}}", set.get("values").toString());
    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    assertTrue(text.contains("/project/b"), text);
  }

  @Test
  void 複製_名前の変更_削除() throws Exception {
    GlobalStore s = GlobalStore.inMemory();
    String a = s.create("A", null).get("id").getAsString();
    s.saveValues(a, json("{\"x\":1}"));
    String b = s.create("B", a).get("id").getAsString();
    assertEquals("{\"x\":1}", s.snapshot().getAsJsonArray("sets").get(1).getAsJsonObject().get("values").toString());

    assertThrows(IllegalArgumentException.class, () -> s.create(" A ", null));
    assertThrows(IllegalArgumentException.class, () -> s.rename(b, "A"));
    assertThrows(IllegalArgumentException.class, () -> s.create("", null));
    s.rename(a, "A");
    s.rename(b, "B2");

    // 選んでいるものを消すと「なし」になる
    s.select(b);
    s.delete(b);
    assertTrue(s.snapshot().get("active").isJsonNull());
    assertEquals(1, s.snapshot().getAsJsonArray("sets").size());
    assertThrows(IllegalArgumentException.class, () -> s.select("no-such"));
  }
}
