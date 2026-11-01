package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class SuspiciousJoinsTest {

  private static void join(Relations.Store store, String statement, String a, String b) {
    store.statements.put(statement, new Relations.Entry("h-" + statement, Collections.singletonList(new String[] {a, b, "Hash Cond"}), null));
  }

  @Test
  void ほかの根拠と源流が食い違う結合と_違う表のidどうしの結合を出す() throws Exception {
    Relations.Store store = Relations.Store.open(null, "proj", "conn");
    for (String pk : new String[] {"app.customers.id", "app.posts.id", "app.users.id", "app.employees.id", "app.profiles.employee_id"}) {
      store.keys.put(pk, 2);
    }
    join(store, "m.orders", "app.orders.customer_id", "app.customers.id");
    join(store, "m.ordersByCustomer", "app.orders.customer_id", "app.customers.id");
    // 誤り: いつもは customers.id を指す列を、posts.id と結合している
    join(store, "m.wrong", "app.orders.customer_id", "app.posts.id");
    // 誤り: 違う表の id どうし
    join(store, "m.ids", "app.users.id", "app.posts.id");
    // 正しい 1 対 1（ほかに根拠はないが、名前が違うので出さない）
    join(store, "m.profile", "app.profiles.employee_id", "app.employees.id");
    // 片方にしか根拠がない兄弟は、判断できないので出さない
    join(store, "m.points", "app.points.customer_id", "app.orders.customer_id");

    List<Relations.Link> links = Relations.links(store);
    List<String> got = new ArrayList<>();
    for (SuspiciousJoins.Suspicion s : SuspiciousJoins.find(col -> Relations.unique(store, col), links, null)) {
      got.add(s.a.replace("app.", "") + " = " + s.b.replace("app.", "") + " " + s.reason + " " + s.rootsA + " " + s.rootsB + " " + s.statements);
    }
    assertEquals(Arrays.asList(
        "orders.customer_id = posts.id 源流が食い違う [app.customers.id] [app.posts.id] [m.wrong]",
        "posts.id = users.id 違う表の、同じ名前の主キーどうし [app.posts.id] [app.users.id] [m.ids]"), sorted(got));
    // 判定の間だけ無効にしたつながりは、元に戻っている
    assertTrue(links.stream().noneMatch(l -> l.disabled));
  }

  @Test
  void 問題なしとして隠した組は印を付ける() throws Exception {
    Relations.Store store = Relations.Store.open(null, "proj", "conn");
    store.keys.put("app.users.id", 2);
    store.keys.put("app.posts.id", 2);
    join(store, "m.ids", "app.users.id", "app.posts.id");
    ManualRelations manual = ManualRelations.open(null, "proj");
    manual.ignore("app.posts.id", "app.users.id");
    List<SuspiciousJoins.Suspicion> list = SuspiciousJoins.find(col -> Relations.unique(store, col), Relations.links(store, manual), manual);
    assertEquals(1, list.size());
    assertTrue(list.get(0).ignored);
    manual.unignore("app.users.id", "app.posts.id");
    assertTrue(!SuspiciousJoins.find(col -> Relations.unique(store, col), Relations.links(store, manual), manual).get(0).ignored);
  }

  @Test
  void 根拠の数が同じなら_列の名前が相手の表の名前を含む組を正しいとみなす() throws Exception {
    Relations.Store store = Relations.Store.open(null, "proj", "conn");
    store.keys.put("app.employees.id", 2);
    store.keys.put("app.projects.id", 2);
    join(store, "m.owner", "app.clients.owner_employee_id", "app.employees.id");
    join(store, "m.wrong", "app.clients.owner_employee_id", "app.projects.id");
    List<String> got = new ArrayList<>();
    for (SuspiciousJoins.Suspicion s : SuspiciousJoins.find(col -> Relations.unique(store, col), Relations.links(store), null)) {
      got.add(s.a + " = " + s.b);
    }
    assertEquals(Arrays.asList("app.clients.owner_employee_id = app.projects.id"), got);
    assertTrue(SuspiciousJoins.namesTable("app.orders.category_id", "app.categories.id"));
    assertTrue(!SuspiciousJoins.namesTable("app.orders.id", "app.ids.id"));
  }

  private static List<String> sorted(List<String> xs) {
    List<String> out = new ArrayList<>(xs);
    Collections.sort(out);
    return out;
  }
}
