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
import java.util.List;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * ユーザーが手で設定したリレーション。解析で推し量ったものより優先する。
 * <ul>
 * <li>追加: 子の列 → 親の列。子の列に手で親を設定したら、その列の源流は手で設定したものだけを使う</li>
 * <li>無効: 解析で推し量った、誤ったつながり（列の組）を使わない</li>
 * </ul>
 *
 * <p>プロジェクトごとに保存し、接続先をまたいで使う。列は schema.table.column で持つが、照らし合わせるときはスキーマを除いた
 * table.column で比べる（テナントごとにスキーマを分けていても使えるように）。親が子と同じスキーマなら、親のスキーマは子に合わせる。
 * <pre>
 * {"version": 1, "project": "/path/to/project",
 *  "relations": [{"child": "schema.table.column", "parent": "schema.table.column"}],
 *  "disabled": [{"a": "schema.table.column", "b": "schema.table.column"}]}
 * </pre>
 */
final class ManualRelations {

  /** 手で追加したリレーション */
  static final class Relation {
    final String child;
    final String parent;

    Relation(String child, String parent) {
      this.child = child;
      this.parent = parent;
    }
  }

  /** null ならファイルに保存しない（テスト用） */
  private final Path file;
  private final String project;
  final List<Relation> relations = new ArrayList<>();
  /** 無効にしたつながり（[a, b]。向きは問わない） */
  final List<String[]> disabled = new ArrayList<>();
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
          m.relations.add(new Relation(r.get("child").getAsString(), r.get("parent").getAsString()));
        }
        for (JsonElement e : o.getAsJsonArray("disabled")) {
          JsonObject r = e.getAsJsonObject();
          m.disabled.add(new String[] {r.get("a").getAsString(), r.get("b").getAsString()});
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

  synchronized void add(String child, String parent) throws IOException {
    check(child);
    check(parent);
    if (withoutSchema(child).equals(withoutSchema(parent))) {
      throw new IllegalArgumentException("子と親に同じ列は指定できません");
    }
    for (Relation r : relations) {
      if (withoutSchema(r.child).equals(withoutSchema(child)) && withoutSchema(r.parent).equals(withoutSchema(parent))) {
        return;
      }
    }
    relations.add(new Relation(child, parent));
    // 同じ組を無効にしていたら、有効に戻す
    disabled.removeIf(d -> samePair(d[0], d[1], child, parent));
    save();
  }

  synchronized void remove(String child, String parent) throws IOException {
    if (relations.removeIf(r -> withoutSchema(r.child).equals(withoutSchema(child)) && withoutSchema(r.parent).equals(withoutSchema(parent)))) {
      save();
    }
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

  /** 列の組（向きは問わない）を無効にしているか */
  synchronized boolean isDisabled(String a, String b) {
    for (String[] d : disabled) {
      if (samePair(d[0], d[1], a, b)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 列（schema.table.column）に手で設定した親。親が設定のときの子と同じスキーマなら、この列のスキーマに合わせる
   * （tenant0001.posts.user_id → tenant0001.users.id の設定は、tenant0002.posts.user_id には tenant0002.users.id として使う）。
   */
  synchronized List<String> parentsOf(String column) {
    List<String> out = new ArrayList<>();
    String schema = column.substring(0, column.indexOf('.'));
    for (Relation r : relations) {
      if (withoutSchema(r.child).equals(withoutSchema(column))) {
        String childSchema = r.child.substring(0, r.child.indexOf('.'));
        String parentSchema = r.parent.substring(0, r.parent.indexOf('.'));
        String parent = parentSchema.equals(childSchema) ? schema + "." + withoutSchema(r.parent) : r.parent;
        if (!out.contains(parent)) {
          out.add(parent);
        }
      }
    }
    return out;
  }

  boolean isEmpty() {
    return relations.isEmpty() && disabled.isEmpty();
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
      j.addProperty("child", r.child);
      j.addProperty("parent", r.parent);
      rs.add(j);
    }
    o.add("relations", rs);
    JsonArray ds = new JsonArray();
    for (String[] d : disabled) {
      JsonObject j = new JsonObject();
      j.addProperty("a", d[0]);
      j.addProperty("b", d[1]);
      ds.add(j);
    }
    o.add("disabled", ds);
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
