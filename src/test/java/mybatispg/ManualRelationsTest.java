package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualRelationsTest {

  /** 解析の結果: posts.user_id → members.id（誤り）、post_histories.user_id ＝ posts.user_id（兄弟） */
  private static Relations.Store analyzed() throws Exception {
    Relations.Store store = Relations.Store.open(null, "proj", "conn");
    store.statements.put("m.a", new Relations.Entry("h1",
        Collections.singletonList(new String[] {"t1.posts.user_id", "t1.members.id", "Hash Cond"}), null));
    store.statements.put("m.b", new Relations.Entry("h2",
        Collections.singletonList(new String[] {"t1.post_histories.user_id", "t1.posts.user_id", "Hash Cond"}), null));
    store.keys.put("t1.members.id", 2);
    store.keys.put("t1.users.id", 2);
    return store;
  }

  private static List<String> origins(Relations.Store store, ManualRelations manual, String column) {
    Relations.Graph g = new Relations.Graph(store, Relations.links(store, manual), manual);
    List<String> out = new ArrayList<>();
    for (Relations.Origin o : g.origins(column)) {
      out.add(o.column + (o.manual ? "（手）" : ""));
    }
    return out;
  }

  @Test
  void 手で設定した親を_推し量った源流より優先する(@TempDir Path dir) throws Exception {
    Relations.Store store = analyzed();
    ManualRelations manual = ManualRelations.open(dir, "proj");
    assertEquals(Collections.singletonList("t1.members.id"), origins(store, manual, "t1.posts.user_id"));

    manual.add("t1.posts.user_id", "t1.users.id");
    assertEquals(Collections.singletonList("t1.users.id（手）"), origins(store, manual, "t1.posts.user_id"));
    // 兄弟をたどった先の手で設定した親も使う
    assertEquals(Collections.singletonList("t1.users.id（手）"), origins(store, manual, "t1.post_histories.user_id"));
    // 一覧では、手で追加したものに印が付く
    assertTrue(Relations.links(store, manual).stream().anyMatch(l -> l.manual && l.a.equals("t1.posts.user_id") && l.b.equals("t1.users.id")));

    // 保存したものを読み直せる
    ManualRelations again = ManualRelations.open(dir, "proj");
    assertEquals(1, again.relations.size());
    assertEquals(Collections.singletonList("t1.users.id（手）"), origins(store, again, "t1.posts.user_id"));

    // 消せば、推し量ったものに戻る
    manual.remove("t1.posts.user_id", "t1.users.id");
    assertEquals(Collections.singletonList("t1.members.id"), origins(store, manual, "t1.posts.user_id"));
  }

  @Test
  void 無効にしたつながりは使わない() throws Exception {
    Relations.Store store = analyzed();
    ManualRelations manual = ManualRelations.open(null, "proj");
    assertEquals(Collections.singletonList("t1.members.id"), origins(store, manual, "t1.post_histories.user_id"));
    // 向きを問わず、スキーマを除いて比べる
    manual.disable("t9.members.id", "t9.posts.user_id");
    assertTrue(Relations.links(store, manual).stream().anyMatch(l -> l.disabled && l.a.equals("t1.posts.user_id")));
    assertEquals(Collections.emptyList(), origins(store, manual, "t1.posts.user_id"));
    assertEquals(Collections.emptyList(), origins(store, manual, "t1.post_histories.user_id"));
    manual.enable("t1.posts.user_id", "t1.members.id");
    assertEquals(Collections.singletonList("t1.members.id"), origins(store, manual, "t1.posts.user_id"));
  }

  @Test
  void スキーマを除いて照らし合わせ_親が子と同じスキーマなら子のスキーマに合わせる() throws Exception {
    ManualRelations manual = ManualRelations.open(null, "proj");
    manual.add("tenant0001.posts.user_id", "tenant0001.users.id");
    manual.add("tenant0001.posts.office_id", "common.offices.id");
    assertEquals(Collections.singletonList("tenant0002.users.id"), manual.parentsOf("tenant0002.posts.user_id"));
    assertEquals(Collections.singletonList("common.offices.id"), manual.parentsOf("tenant0002.posts.office_id"));
    assertEquals(Collections.emptyList(), manual.parentsOf("tenant0002.posts.title"));
    assertThrows(IllegalArgumentException.class, () -> manual.add("posts.user_id", "users.id"));
    assertThrows(IllegalArgumentException.class, () -> manual.add("a.posts.user_id", "b.posts.user_id"));
  }

  @Test
  void 解析していなくても_手で設定したリレーションだけで源流を出す() throws Exception {
    Relations.Store empty = Relations.Store.open(null, "proj", "conn");
    ManualRelations manual = ManualRelations.open(null, "proj");
    manual.add("app.orders.customer_id", "app.customers.id");
    Relations.Graph g = new Relations.Graph(empty, Relations.links(empty, manual), manual);
    java.util.Map<String, List<ParamSources.Source>> byProperty = new java.util.LinkedHashMap<>();
    byProperty.put("customerId", new ArrayList<>(Arrays.asList(new ParamSources.Source("app", "orders", "customer_id", "(o.customer_id = $1)", true))));
    List<ParamSources.Source> got = Relations.candidates(byProperty, g).get("customerId");
    assertEquals("customers.id origin true", got.get(0).table + "." + got.get(0).column + " " + got.get(0).role + " " + got.get(0).manual);
    assertEquals("orders.customer_id", got.get(1).table + "." + got.get(1).column);
  }
}
