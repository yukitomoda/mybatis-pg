package mybatispg;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * ユーザーが手で設定したリレーション。解析で推し量ったものより優先する。
 * <ul>
 * <li>追加: 列と列がつながっている（向きは持たない。親子は解析で拾ったものと同じく、一意かどうかと表の関係で決める）。
 * 手で追加したつながりで親が決まった列は、その列の源流に手で追加したものだけを使う</li>
 * <li>無効: 解析で推し量った、誤ったつながり（列の組）を使わない</li>
 * <li>表の関係: 2 つの表の間のつながりを、一意かどうかによらず、親子・1 対 1・兄弟のどれかとする</li>
 * </ul>
 *
 * <p>プロジェクトごとに保存し、接続先をまたいで使う。列は schema.table.column で持つが、照らし合わせるときはスキーマを除いた
 * table.column で比べる（テナントごとにスキーマを分けていても使えるように）。親が子と同じスキーマなら、親のスキーマは子に合わせる。
 * <pre>
 * {"version": 1, "project": "/path/to/project",
 *  "relations": [{"a": "schema.table.column", "b": "schema.table.column"}]（前の版は {"child", "parent"}。列の組として読む）,
 *  "tables": [{"child": "schema.table", "parent": "schema.table"} か {"a", "b", "kind": "one-to-one" | "sibling"}]（表の関係）,
 *  "disabled": [{"a": "schema.table.column", "b": "schema.table.column"}],
 *  "ignored": [{"a", "b"}]（疑わしい結合のうち、問題なしとして隠した組）}
 * </pre>
 */
final class ManualRelations {

  /** 手で追加したリレーション（列と列。向きは持たない） */
  static final class Relation {
    final String a;
    final String b;

    Relation(String a, String b) {
      this.a = a;
      this.b = b;
    }
  }

  /** 表の関係（kind が parent なら child の親が parent。one-to-one・sibling なら向きはない） */
  static final class TableKind {
    final String child;
    final String parent;
    final String kind;

    TableKind(String child, String parent, String kind) {
      this.child = child;
      this.parent = parent;
      this.kind = kind;
    }
  }

  /** null ならファイルに保存しない（テスト用） */
  private final Path file;
  private final String project;
  final List<Relation> relations = new ArrayList<>();
  /** 表の関係 */
  final List<TableKind> tables = new ArrayList<>();
  /** 無効にしたつながり（[a, b]。向きは問わない） */
  final List<String[]> disabled = new ArrayList<>();
  /** 疑わしい結合のうち、問題なしとして隠した組（[a, b]。向きは問わない） */
  final List<String[]> ignored = new ArrayList<>();
  /** 変えるたびに増やす（まとめたリレーションを作り直すため） */
  long version;

  private ManualRelations(Path file, String project) {
    this.file = file;
    this.project = project;
  }

  /**
   * @param root 保存先のディレクトリ（~/.mybatis-pg/relations）。null なら保存しない
   */
  static ManualRelations open(Path root, String project) throws IOException {
    Path file = root == null ? null : root.resolve(Relations.hash(project).substring(0, 16)).resolve("manual.json");
    ManualRelations m = new ManualRelations(file, project);
    if (file != null && Files.isRegularFile(file)) {
      try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        JsonObject o = JsonParser.parseReader(in).getAsJsonObject();
        for (JsonElement e : o.getAsJsonArray("relations")) {
          JsonObject r = e.getAsJsonObject();
          m.relations.add(r.has("a") ? new Relation(r.get("a").getAsString(), r.get("b").getAsString())
              : new Relation(r.get("child").getAsString(), r.get("parent").getAsString()));
        }
        if (o.has("tables")) {
          for (JsonElement e : o.getAsJsonArray("tables")) {
            JsonObject r = e.getAsJsonObject();
            m.tables.add(r.has("a") ? new TableKind(r.get("a").getAsString(), r.get("b").getAsString(),
                r.has("kind") ? r.get("kind").getAsString() : "one-to-one")
                : new TableKind(r.get("child").getAsString(), r.get("parent").getAsString(), "parent"));
          }
        }
        for (JsonElement e : o.getAsJsonArray("disabled")) {
          JsonObject r = e.getAsJsonObject();
          m.disabled.add(new String[] {r.get("a").getAsString(), r.get("b").getAsString()});
        }
        if (o.has("ignored")) {
          for (JsonElement e : o.getAsJsonArray("ignored")) {
            JsonObject r = e.getAsJsonObject();
            m.ignored.add(new String[] {r.get("a").getAsString(), r.get("b").getAsString()});
          }
        }
      } catch (JsonParseException | IllegalStateException | NullPointerException e) {
        throw new IOException("手で設定したリレーションのファイルを読み込めません: " + file + "\n" + e.getMessage(), e);
      }
    }
    return m;
  }

  /** スキーマを除いた table.column */
  static String withoutSchema(String key) {
    return key.substring(key.indexOf('.') + 1);
  }

  private static boolean samePair(String a1, String b1, String a2, String b2) {
    String x1 = withoutSchema(a1);
    String y1 = withoutSchema(b1);
    String x2 = withoutSchema(a2);
    String y2 = withoutSchema(b2);
    return x1.equals(x2) && y1.equals(y2) || x1.equals(y2) && y1.equals(x2);
  }

  synchronized void add(String a, String b) throws IOException {
    check(a);
    check(b);
    if (withoutSchema(a).equals(withoutSchema(b))) {
      throw new IllegalArgumentException("同じ列どうしはつなげられません");
    }
    for (Relation r : relations) {
      if (samePair(r.a, r.b, a, b)) {
        return;
      }
    }
    relations.add(new Relation(a, b));
    // 同じ組を無効にしていたら、有効に戻す
    disabled.removeIf(d -> samePair(d[0], d[1], a, b));
    save();
  }

  synchronized void remove(String a, String b) throws IOException {
    if (relations.removeIf(r -> samePair(r.a, r.b, a, b))) {
      save();
    }
  }

  /**
   * 表の関係を決める（kind: "parent" なら parent が child の親、"one-to-one" なら 1 対 1、"sibling" なら兄弟、null なら自動に戻す）。
   * 表は schema.table（照らし合わせるときはスキーマを除く）
   */
  synchronized void setTableKind(String child, String parent, String kind) throws IOException {
    checkTable(child);
    checkTable(parent);
    if (withoutSchema(child).equals(withoutSchema(parent))) {
      throw new IllegalArgumentException("同じ表どうしの関係は決められません");
    }
    if (kind != null && !"parent".equals(kind) && !"one-to-one".equals(kind) && !"sibling".equals(kind)) {
      throw new IllegalArgumentException("kind は parent・one-to-one・sibling・null のどれかにしてください");
    }
    tables.removeIf(t -> samePair(t.child, t.parent, child, parent));
    if (kind != null) {
      tables.add(new TableKind(child, parent, kind));
    }
    save();
  }

  /**
   * 表どうし（schema.table）の関係。[子の表, 親の表, "parent"]、1 対 1・兄弟なら [x, y, "one-to-one" | "sibling"]。決めていなければ null
   */
  synchronized String[] tableKind(String x, String y) {
    for (TableKind t : tables) {
      if (!samePair(t.child, t.parent, x, y)) {
        continue;
      }
      if (!"parent".equals(t.kind)) {
        return new String[] {x, y, t.kind};
      }
      boolean xIsChild = withoutSchema(t.child).equals(withoutSchema(x));
      return xIsChild ? new String[] {x, y, "parent"} : new String[] {y, x, "parent"};
    }
    return null;
  }

  /**
   * 列（schema.table.column）と手でつないだ列。つないだ相手が設定のときのこの列と同じスキーマなら、この列のスキーマに合わせる
   * （tenant0001.posts.user_id = tenant0001.users.id の設定は、tenant0002.posts.user_id には tenant0002.users.id として使う）。
   */
  synchronized List<String> pairsOf(String column) {
    List<String> out = new ArrayList<>();
    String schema = column.substring(0, column.indexOf('.'));
    for (Relation r : relations) {
      for (int side = 0; side < 2; side++) {
        String mine = side == 0 ? r.a : r.b;
        String other = side == 0 ? r.b : r.a;
        if (withoutSchema(mine).equals(withoutSchema(column))) {
          String mySchema = mine.substring(0, mine.indexOf('.'));
          String otherSchema = other.substring(0, other.indexOf('.'));
          String o = otherSchema.equals(mySchema) ? schema + "." + withoutSchema(other) : other;
          if (!out.contains(o)) {
            out.add(o);
          }
        }
      }
    }
    return out;
  }

  synchronized void disable(String a, String b) throws IOException {
    check(a);
    check(b);
    if (disabled.stream().noneMatch(d -> samePair(d[0], d[1], a, b))) {
      disabled.add(new String[] {a, b});
      save();
    }
  }

  synchronized void enable(String a, String b) throws IOException {
    if (disabled.removeIf(d -> samePair(d[0], d[1], a, b))) {
      save();
    }
  }

  /** 疑わしい結合を、問題なしとして隠す */
  synchronized void ignore(String a, String b) throws IOException {
    check(a);
    check(b);
    if (ignored.stream().noneMatch(d -> samePair(d[0], d[1], a, b))) {
      ignored.add(new String[] {a, b});
      save();
    }
  }

  synchronized void unignore(String a, String b) throws IOException {
    if (ignored.removeIf(d -> samePair(d[0], d[1], a, b))) {
      save();
    }
  }

  /** 疑わしい結合を、問題なしとして隠しているか */
  synchronized boolean isIgnored(String a, String b) {
    for (String[] d : ignored) {
      if (samePair(d[0], d[1], a, b)) {
        return true;
      }
    }
    return false;
  }

  /** 列の組（向きは問わない）を無効にしているか */
  synchronized boolean isDisabled(String a, String b) {
    for (String[] d : disabled) {
      if (samePair(d[0], d[1], a, b)) {
        return true;
      }
    }
    return false;
  }

  /** 手で追加したリレーションの表（schema.table） */
  synchronized Set<String> relationTables() {
    Set<String> out = new LinkedHashSet<>();
    for (Relation r : relations) {
      out.add(r.a.substring(0, r.a.lastIndexOf('.')));
      out.add(r.b.substring(0, r.b.lastIndexOf('.')));
    }
    return out;
  }

  boolean isEmpty() {
    return relations.isEmpty() && disabled.isEmpty() && tables.isEmpty();
  }

  private static void checkTable(String key) {
    if (key == null || key.split("\\.", -1).length != 2 || key.startsWith(".") || key.endsWith(".")) {
      throw new IllegalArgumentException("表は schema.table の形で指定してください: " + key);
    }
  }

  private static void check(String key) {
    if (key == null || key.split("\\.", -1).length != 3 || key.contains("..") || key.startsWith(".") || key.endsWith(".")) {
      throw new IllegalArgumentException("列は schema.table.column の形で指定してください: " + key);
    }
  }

  private void save() throws IOException {
    version++;
    if (file == null) {
      return;
    }
    JsonObject o = new JsonObject();
    o.addProperty("version", 1);
    o.addProperty("project", project);
    JsonArray rs = new JsonArray();
    for (Relation r : relations) {
      JsonObject j = new JsonObject();
      j.addProperty("a", r.a);
      j.addProperty("b", r.b);
      rs.add(j);
    }
    o.add("relations", rs);
    JsonArray ts = new JsonArray();
    for (TableKind t : tables) {
      JsonObject j = new JsonObject();
      if (!"parent".equals(t.kind)) {
        j.addProperty("a", t.child);
        j.addProperty("b", t.parent);
        j.addProperty("kind", t.kind);
      } else {
        j.addProperty("child", t.child);
        j.addProperty("parent", t.parent);
      }
      ts.add(j);
    }
    o.add("tables", ts);
    JsonArray ds = new JsonArray();
    for (String[] d : disabled) {
      JsonObject j = new JsonObject();
      j.addProperty("a", d[0]);
      j.addProperty("b", d[1]);
      ds.add(j);
    }
    o.add("disabled", ds);
    JsonArray is = new JsonArray();
    for (String[] d : ignored) {
      JsonObject j = new JsonObject();
      j.addProperty("a", d[0]);
      j.addProperty("b", d[1]);
      is.add(j);
    }
    o.add("ignored", is);
    Files.createDirectories(file.getParent());
    Path tmp = Files.createTempFile(file.getParent(), "manual", ".tmp");
    try {
      try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o, out);
      }
      try {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
