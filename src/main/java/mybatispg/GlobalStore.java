package mybatispg;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * 共通パラメータ（複数の statement で共有する値の集まり）を管理する。
 *
 * <p>ファイルにはプロジェクトごとに保存する。他のプロジェクトの内容はそのまま残す。
 * <pre>
 * {"projects": {"/path/to/project": {
 *   "sets": [{"id": "...", "name": "...", "default": true, "values": {...}}],
 *   "statements": {"shop.order.OrderMapper.recent": {"added": ["id2"], "removed": ["id1"], "order": ["id2", "id3"]}}
 * }}}
 * </pre>
 * <ul>
 * <li>sets: 共通パラメータ。default が true のものは、すべての statement に既定で入れる。並び順が既定の順番（下ほど優先）</li>
 * <li>statements: statement ごとの設定。added は追加したもの、removed は既定で入るのに外したもの、order はその statement での順番</li>
 * </ul>
 * 以前の形は、読み込んだときに直す。スコープなどは捨てて既定で入れるものとし、statement で無効・適用しないにしていたものは外したものにする。
 * 値は JSON のまま持ち、数値は書かれた文字のまま保つ。
 */
public final class GlobalStore {

  /** 以前の形で、全体を表していた場所 */
  private static final String ROOT = "*";

  /** statement の id。改行などの制御文字は受け付けない */
  private static final Pattern STATEMENT_ID = Pattern.compile("[^\\s\\u0000-\\u001f][^\\u0000-\\u001f]{0,500}");

  /** null ならファイルに保存しない */
  private final Path file;
  private final String projectKey;
  /** ファイル全体（他のプロジェクトを含む） */
  private final JsonObject root;

  private GlobalStore(Path file, String projectKey, JsonObject root) {
    this.file = file;
    this.projectKey = projectKey;
    this.root = root;
  }

  /** @param projectKey プロジェクトを区別する文字列（基準ディレクトリの絶対パス） */
  public static GlobalStore load(Path file, String projectKey) throws IOException {
    JsonObject root = new JsonObject();
    if (Files.exists(file)) {
      try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        JsonElement e = JsonParser.parseReader(in);
        if (e.isJsonObject()) {
          root = e.getAsJsonObject();
        }
      } catch (JsonParseException | IllegalStateException e) {
        throw new IOException("共通パラメータのファイルを読み込めません: " + file + "\n" + e.getMessage(), e);
      }
    }
    return new GlobalStore(file, projectKey, root);
  }

  /** ファイルに保存しない（テスト用） */
  public static GlobalStore inMemory() {
    return new GlobalStore(null, "", new JsonObject());
  }

  /** このプロジェクトの内容: {"sets": [...], "statements": {...}} */
  public synchronized JsonObject snapshot() {
    JsonObject p = project();
    JsonObject copy = new JsonObject();
    copy.add("sets", p.get("sets").deepCopy());
    copy.add("statements", p.get("statements").deepCopy());
    return copy;
  }

  /**
   * 作る。
   *
   * @param copyFrom null でなければ、その共通パラメータの値と既定で入れるかを引き継ぐ。null なら既定では入れない
   */
  public synchronized JsonObject create(String name, String copyFrom) throws IOException {
    String trimmed = validName(name, null);
    JsonObject set = new JsonObject();
    set.addProperty("id", UUID.randomUUID().toString());
    set.addProperty("name", trimmed);
    set.addProperty("default", copyFrom != null && find(copyFrom).get("default").getAsBoolean());
    set.add("values", copyFrom != null ? find(copyFrom).getAsJsonObject("values").deepCopy() : new JsonObject());
    sets().add(set);
    write();
    return set.deepCopy();
  }

  public synchronized void rename(String id, String name) throws IOException {
    JsonObject set = find(id);
    set.addProperty("name", validName(name, id));
    write();
  }

  public synchronized void delete(String id) throws IOException {
    sets().remove(find(id));
    for (Map.Entry<String, JsonElement> e : statements().entrySet()) {
      for (String key : new String[] {"added", "removed", "order"}) {
        JsonElement list = e.getValue().getAsJsonObject().get(key);
        if (list != null && list.isJsonArray()) {
          remove(list.getAsJsonArray(), id);
        }
      }
    }
    write();
  }

  public synchronized void saveValues(String id, JsonObject values) throws IOException {
    find(id).add("values", values.deepCopy());
    write();
  }

  /** すべての statement に既定で入れるか */
  public synchronized void setDefault(String id, boolean on) throws IOException {
    find(id).addProperty("default", on);
    write();
  }

  /** 既定の順番を変える（下ほど優先）。ids はすべての共通パラメータの id を並べたもの */
  public synchronized void reorder(List<String> ids) throws IOException {
    JsonArray sets = sets();
    if (ids.size() != sets.size() || new LinkedHashSet<>(ids).size() != ids.size()) {
      throw new IllegalArgumentException("すべての共通パラメータを 1 回ずつ並べてください");
    }
    JsonArray reordered = new JsonArray();
    for (String id : ids) {
      reordered.add(find(id));
    }
    project().add("sets", reordered);
    write();
  }

  /**
   * statement ごとの設定を決める。
   *
   * @param added 追加する共通パラメータの id
   * @param removed 既定で入るのに外す共通パラメータの id
   * @param order その statement での順番（null なら既定の順番に従う）
   */
  public synchronized void setStatement(String statementId, List<String> added, List<String> removed, List<String> order)
      throws IOException {
    validStatementId(statementId);
    JsonObject settings = new JsonObject();
    settings.add("added", ids(added));
    settings.add("removed", ids(removed));
    if (order != null) {
      settings.add("order", ids(order));
    }
    if (added.isEmpty() && removed.isEmpty() && order == null) {
      statements().remove(statementId);
    } else {
      statements().add(statementId, settings);
    }
    write();
  }

  /** 重なりを除き、あるものか確かめる */
  private JsonArray ids(List<String> list) {
    JsonArray out = new JsonArray();
    for (String id : new LinkedHashSet<>(list)) {
      find(id);
      out.add(id);
    }
    return out;
  }

  private static void validStatementId(String id) {
    if (id == null || !STATEMENT_ID.matcher(id).matches()) {
      throw new IllegalArgumentException("statement の id が正しくありません: " + id);
    }
  }

  private static void remove(JsonArray array, String value) {
    for (int i = array.size() - 1; i >= 0; i--) {
      if (array.get(i).getAsString().equals(value)) {
        array.remove(i);
      }
    }
  }

  private JsonObject project() {
    JsonObject projects = root.getAsJsonObject("projects");
    if (projects == null) {
      projects = new JsonObject();
      root.add("projects", projects);
    }
    JsonObject p = projects.getAsJsonObject(projectKey);
    if (p == null) {
      p = new JsonObject();
      projects.add(projectKey, p);
    }
    if (!p.has("sets") || !p.get("sets").isJsonArray()) {
      p.add("sets", new JsonArray());
    }
    if (!p.has("statements") || !p.get("statements").isJsonObject()) {
      p.add("statements", new JsonObject());
    }
    migrate(p);
    return p;
  }

  /**
   * 以前の形を引き継ぐ。スコープや適用する場所の設定は捨て、既定で入れるものとする。
   * statement で無効・適用しないにしていたもの（disabled、excludes、rules の statement での off）は、その statement で外したものにする。
   */
  private static void migrate(JsonObject p) {
    p.remove("active");
    p.remove("order");
    JsonObject statements = p.getAsJsonObject("statements");
    for (Map.Entry<String, JsonElement> e : statements.entrySet()) {
      JsonObject settings = e.getValue().getAsJsonObject();
      JsonElement disabled = settings.remove("disabled");
      if (!settings.has("removed")) {
        settings.add("removed", disabled != null && disabled.isJsonArray() ? disabled : new JsonArray());
      }
      if (!settings.has("added")) {
        settings.add("added", new JsonArray());
      }
    }
    for (JsonElement e : p.getAsJsonArray("sets")) {
      JsonObject set = e.getAsJsonObject();
      String id = set.get("id").getAsString();
      List<String> off = new ArrayList<>();
      JsonElement excludes = set.remove("excludes");
      if (excludes != null && excludes.isJsonArray()) {
        excludes.getAsJsonArray().forEach(x -> off.add(x.getAsString()));
      }
      JsonElement rules = set.remove("rules");
      if (rules != null && rules.isJsonObject()) {
        for (Map.Entry<String, JsonElement> r : rules.getAsJsonObject().entrySet()) {
          String place = r.getKey();
          if ("off".equals(r.getValue().getAsString()) && !ROOT.equals(place) && !place.endsWith("/") && !place.endsWith(".xml")) {
            off.add(place);
          }
        }
      }
      set.remove("scope");
      set.remove("enabled");
      if (!set.has("default")) {
        set.addProperty("default", true);
      }
      for (String statementId : off) {
        JsonObject settings = statements.getAsJsonObject(statementId);
        if (settings == null) {
          settings = new JsonObject();
          settings.add("added", new JsonArray());
          settings.add("removed", new JsonArray());
          statements.add(statementId, settings);
        }
        settings.getAsJsonArray("removed").add(id);
      }
    }
  }

  private JsonArray sets() {
    return project().getAsJsonArray("sets");
  }

  private JsonObject statements() {
    return project().getAsJsonObject("statements");
  }

  private JsonObject find(String id) {
    for (JsonElement e : sets()) {
      JsonObject set = e.getAsJsonObject();
      if (set.get("id").getAsString().equals(id)) {
        return set;
      }
    }
    throw new IllegalArgumentException("共通パラメータがありません: " + id);
  }

  /** 空でなく、他と重ならない名前か確かめる */
  private String validName(String name, String selfId) {
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("名前を入力してください");
    }
    for (JsonElement e : sets()) {
      JsonObject set = e.getAsJsonObject();
      if (set.get("name").getAsString().equals(trimmed) && !set.get("id").getAsString().equals(selfId)) {
        throw new IllegalArgumentException("同じ名前の共通パラメータがあります: " + trimmed);
      }
    }
    return trimmed;
  }

  /** 一時ファイルに書いてから置き換える。自分だけが読めるようにする */
  private void write() throws IOException {
    if (file == null) {
      return;
    }
    Path dir = file.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    Path tmp = Files.createTempFile(dir, "globals", ".tmp");
    try {
      try {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException e) {
        // Windows など POSIX の権限がない環境では設定しない
      }
      try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(root, out);
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
