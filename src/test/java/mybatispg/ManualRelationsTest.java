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
  void 手で追加したリレーションの親子は一意かどうかで決め_推し量った源流より優先する(@TempDir Path dir) throws Exception {
    Relations.Store store = analyzed();
    ManualRelations manual = ManualRelations.open(dir, "proj");
    assertEquals(Collections.singletonList("t1.members.id"), origins(store, manual, "t1.posts.user_id"));

    // 向きは持たない（親を先に書いても、一意な users.id が親になる）
    manual.add("t1.users.id", "t1.posts.user_id");
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
    assertEquals(0, manual.relations.size());
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
  void スキーマを除いて照らし合わせ_相手が同じスキーマならこの列のスキーマに合わせる() throws Exception {
    ManualRelations manual = ManualRelations.open(null, "proj");
    manual.add("tenant0001.posts.user_id", "tenant0001.users.id");
    manual.add("tenant0001.posts.office_id", "common.offices.id");
    assertEquals(Collections.singletonList("tenant0002.users.id"), manual.pairsOf("tenant0002.posts.user_id"));
    assertEquals(Collections.singletonList("tenant0002.posts.user_id"), manual.pairsOf("tenant0002.users.id"));
    assertEquals(Collections.singletonList("common.offices.id"), manual.pairsOf("tenant0002.posts.office_id"));
    assertEquals(Collections.emptyList(), manual.pairsOf("tenant0002.posts.title"));
    assertThrows(IllegalArgumentException.class, () -> manual.add("posts.user_id", "users.id"));
    assertThrows(IllegalArgumentException.class, () -> manual.add("a.posts.user_id", "b.posts.user_id"));
  }

  @Test
  void 解析していなくても_手で設定したリレーションだけで源流を出す() throws Exception {
    Relations.Store empty = Relations.Store.open(null, "proj", "conn");
    // 手で追加したときに、その表の主キーを読んでおく
    empty.keys.put("app.customers.id", 2);
    ManualRelations manual = ManualRelations.open(null, "proj");
    manual.add("app.orders.customer_id", "app.customers.id");
    Relations.Graph g = new Relations.Graph(empty, Relations.links(empty, manual), manual);
    java.util.Map<String, List<ParamSources.Source>> byProperty = new java.util.LinkedHashMap<>();
    byProperty.put("customerId", new ArrayList<>(Arrays.asList(new ParamSources.Source("app", "orders", "customer_id", "(o.customer_id = $1)", true))));
    List<ParamSources.Source> got = Relations.candidates(byProperty, g).get("customerId");
    assertEquals("customers.id origin true", got.get(0).table + "." + got.get(0).column + " " + got.get(0).role + " " + got.get(0).manual);
    assertEquals("orders.customer_id", got.get(1).table + "." + got.get(1).column);
  }

  @Test
  void 表の関係を決めると_一意かどうかによらず_その表の間のつながりの向きと種類になる(@TempDir Path dir) throws Exception {
    // posts.user_id ＝ members.id（members.id が一意なので、members が親）と、
    // 主キーのない表どうし: invoices.order_no ＝ legacy_orders.order_no（兄弟）
    Relations.Store store = analyzed();
    store.statements.put("m.c", new Relations.Entry("h3",
        Collections.singletonList(new String[] {"t1.invoices.order_no", "t1.legacy_orders.order_no", "Hash Cond"}), null));
    ManualRelations manual = ManualRelations.open(dir, "proj");
    assertEquals(Collections.emptyList(), origins(store, manual, "t1.invoices.order_no"));

    // legacy_orders を invoices の親にする（スキーマは問わない）
    manual.setTableKind("t9.invoices", "t9.legacy_orders", "parent");
    Relations.Link l = Relations.links(store, manual).stream().filter(x -> x.a.endsWith("order_no")).findFirst().get();
    assertEquals("t1.invoices.order_no parent t1.legacy_orders.order_no true", l.a + " " + l.kind + " " + l.b + " " + l.byTable);
    assertEquals(Collections.singletonList("t1.legacy_orders.order_no"), origins(store, manual, "t1.invoices.order_no"));

    // 一意かどうかに逆らう向きにもできる（members を posts の子に）。保存したものを読み直せる
    manual.setTableKind("t1.members", "t1.posts", "parent");
    ManualRelations again = ManualRelations.open(dir, "proj");
    Relations.Link m = Relations.links(store, again).stream().filter(x -> x.a.endsWith("members.id")).findFirst().get();
    assertEquals("t1.members.id parent t1.posts.user_id", m.a + " " + m.kind + " " + m.b);

    // 兄弟にすると、一意な members.id でも親にしない（源流にならない）
    manual.setTableKind("t1.posts", "t1.members", "sibling");
    Relations.Link sib = Relations.links(store, manual).stream().filter(x -> x.b.endsWith("members.id") || x.a.endsWith("members.id")).findFirst().get();
    assertEquals("sibling", sib.kind);
    assertEquals(Collections.emptyList(), origins(store, manual, "t1.posts.user_id"));
    assertEquals("sibling", ManualRelations.open(dir, "proj").tableKind("t1.members", "t1.posts")[2]);

    // 1 対 1、自動に戻す
    manual.setTableKind("t1.invoices", "t1.legacy_orders", "one-to-one");
    assertEquals("one-to-one", Relations.links(store, manual).stream().filter(x -> x.a.endsWith("order_no")).findFirst().get().kind);
    manual.setTableKind("t1.legacy_orders", "t1.invoices", null);
    assertEquals("sibling", Relations.links(store, manual).stream().filter(x -> x.a.endsWith("order_no")).findFirst().get().kind);
    assertThrows(IllegalArgumentException.class, () -> manual.setTableKind("t1.posts", "t2.posts", "parent"));
  }

  @Test
  void 前の版の_子と親で保存したリレーションを_列の組として読む(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(Relations.hash("proj").substring(0, 16)).resolve("manual.json");
    java.nio.file.Files.createDirectories(file.getParent());
    java.nio.file.Files.write(file, ("{\"version\": 1, \"project\": \"proj\", \"relations\": [{\"child\": \"t1.posts.user_id\","
        + " \"parent\": \"t1.users.id\"}], \"disabled\": []}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ManualRelations manual = ManualRelations.open(dir, "proj");
    assertEquals(Collections.singletonList("t1.users.id（手）"), origins(analyzed(), manual, "t1.posts.user_id"));
  }
}
