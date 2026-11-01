package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RelatedDataTest {

  private static Relations.Link link(String a, String b, String kind, String compositeKey) {
    Relations.Link l = new Relations.Link(a, b, kind);
    l.compositeKey = compositeKey;
    return l;
  }

  @Test
  void 隣の表は親と子に分け_複合キーは列の組にまとめ_無効と兄弟は使わない() {
    List<Relations.Link> links = new ArrayList<>();
    links.add(link("app.tasks.project_id", "app.projects.id", "parent", null));
    links.add(link("app.comments.project_id", "app.tasks.project_id", "parent", "app.tasks の主キー（project_id, task_no）"));
    links.add(link("app.comments.task_no", "app.tasks.task_no", "parent", "app.tasks の主キー（project_id, task_no）"));
    links.add(link("app.tasks.project_id", "app.invoices.project_id", "sibling", null));
    Relations.Link off = link("app.tasks.owner_id", "app.users.id", "parent", null);
    off.disabled = true;
    links.add(off);
    List<String> got = new ArrayList<>();
    for (RelatedData.Neighbor n : RelatedData.neighbors("app.tasks", links)) {
      got.add(n.direction + " " + n.table + " " + n.columns + "->" + n.otherColumns);
    }
    assertEquals(Arrays.asList("parent app.projects [project_id]->[id]", "child app.comments [project_id, task_no]->[project_id, task_no]"), got);
  }

  @Test
  void 自己参照は親と子の両方に出す() {
    List<String> got = new ArrayList<>();
    for (RelatedData.Neighbor n : RelatedData.neighbors("app.emp", Arrays.asList(link("app.emp.manager_id", "app.emp.id", "parent", null)))) {
      got.add(n.direction + " " + n.columns + "->" + n.otherColumns);
    }
    assertEquals(Arrays.asList("parent [manager_id]->[id]", "child [id]->[manager_id]"), got);
  }

  @Test
  void 値の組で引くSQLは型を付け_主キーの順に並べる() {
    Map<String, String> types = new LinkedHashMap<>();
    types.put("project_id", "integer");
    types.put("task_no", "integer");
    assertEquals("SELECT * FROM \"app\".\"comments\" WHERE (\"project_id\", \"task_no\") IN ((?::integer, ?::integer), (?::integer, ?::integer))"
        + " ORDER BY \"project_id\", \"task_no\"",
        RelatedData.rowsSql("app.comments", Arrays.asList("project_id", "task_no"), 2, types, Arrays.asList("project_id", "task_no"), false));
    assertEquals(Arrays.asList(Arrays.asList("1", "2")),
        RelatedData.distinctTuples(Arrays.asList(Arrays.asList("1", "2"), Arrays.asList("1", "2"), Arrays.asList("1", null))));
  }
}
