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
import java.util.Set;
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
 *   "sets": [{"id": "...", "name": "...", "values": {...}, "scope": ["*", "shop/order/"], "excludes": ["shop.order.OrderMapper.recent"]}],
 *   "order": {"*": ["id1", "id2"], "shop/order/": ["id3"]}
 * }}}
 * </pre>
 * <ul>
 * <li>scope: 対象とする場所。"*"（全体）、フォルダ（末尾が /）・ファイルの相対パス、statement の id</li>
 * <li>excludes: スコープに入っていても適用しない statement の id</li>
 * <li>order: 場所ごとの適用順序（同じ場所をスコープにした共通パラメータどうしの順。下ほど優先）</li>
 * </ul>
 * 以前の形（"active" で 1 つを選ぶ形、"enabled" と範囲を持つ形、場所ごとの "rules" を持つ形）は、読み込んだときに直す。
 * 値は JSON のまま持ち、数値は書かれた文字のまま保つ。
 */
public final class GlobalStore {

  /** 全体を表す場所 */
  public static final String ROOT = "*";

  /** 場所の名前。改行などの制御文字は受け付けない */
  private static final Pattern PLACE = Pattern.compile("\\*|[^\\s\\u0000-\\u001f][^\\u0000-\\u001f]{0,500}");

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

  /** このプロジェクトの内容: {"sets": [...], "order": {...}} */
  public synchronized JsonObject snapshot() {
    JsonObject p = project();
    JsonObject copy = new JsonObject();
    copy.add("sets", p.get("sets").deepCopy());
    copy.add("order", p.get("order").deepCopy());
    return copy;
  }

  /**
   * 作る。新しいものはスコープなし（どこにも適用しない）。
   *
   * @param copyFrom null でなければ、その共通パラメータの値・スコープ・適用しない statement を引き継ぐ
   */
  public synchronized JsonObject create(String name, String copyFrom) throws IOException {
    String trimmed = validName(name, null);
    JsonObject set = new JsonObject();
    set.addProperty("id", UUID.randomUUID().toString());
    set.addProperty("name", trimmed);
    if (copyFrom != null) {
      JsonObject from = find(copyFrom);
      set.add("values", from.getAsJsonObject("values").deepCopy());
      set.add("scope", from.getAsJsonArray("scope").deepCopy());
      set.add("excludes", from.getAsJsonArray("excludes").deepCopy());
    } else {
      set.add("values", new JsonObject());
      set.add("scope", new JsonArray());
      set.add("excludes", new JsonArray());
    }
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
    for (Map.Entry<String, JsonElement> e : order().entrySet()) {
      remove(e.getValue().getAsJsonArray(), id);
    }
    write();
  }

  public synchronized void saveValues(String id, JsonObject values) throws IOException {
    find(id).add("values", values.deepCopy());
    write();
  }

  /** スコープを変える */
  public synchronized void setScope(String id, List<String> places) throws IOException {
    JsonObject set = find(id);
    Set<String> unique = new LinkedHashSet<>();
    for (String p : places) {
      unique.add(validPlace(p));
    }
    JsonArray scope = new JsonArray();
    unique.forEach(scope::add);
    set.add("scope", scope);
    write();
  }

  /** statement で適用しない（excluded が true）か、スコープどおり適用するかを決める */
  public synchronized void setExcluded(String id, String statementId, boolean excluded) throws IOException {
    JsonArray excludes = find(id).getAsJsonArray("excludes");
    remove(excludes, validPlace(statementId));
    if (excluded) {
      excludes.add(statementId);
    }
    write();
  }

  /** 場所の適用順序を決める（下ほど優先）。ids にない共通パラメータの扱いは画面で決める */
  public synchronized void setOrder(String place, List<String> ids) throws IOException {
    JsonArray list = new JsonArray();
    for (String id : new LinkedHashSet<>(ids)) {
      find(id);
      list.add(id);
    }
    order().add(validPlace(place), list);
    write();
  }

  private static String validPlace(String place) {
    if (place == null || !PLACE.matcher(place).matches()) {
      throw new IllegalArgumentException("場所が正しくありません: " + place);
    }
    return place;
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
    if (!p.has("order") || !p.get("order").isJsonObject()) {
      p.add("order", new JsonObject());
    }
    migrate(p);
    return p;
  }

  /**
   * 以前の形を引き継ぐ。
   * <ol>
   * <li>"active" で 1 つを選ぶ形: 選んでいたもののスコープを全体にする</li>
   * <li>"enabled" と "scope"（{all, items}）の形: オンのものの範囲をスコープにする（オフのものはスコープなし）</li>
   * <li>場所ごとの "rules" の形: 「適用する」の場所をスコープにし、statement での「適用しない」を excludes にする</li>
   * </ol>
   */
  private static void migrate(JsonObject p) {
    JsonElement active = p.remove("active");
    String activeId = active == null || active.isJsonNull() ? null : active.getAsString();
    for (JsonElement e : p.getAsJsonArray("sets")) {
      JsonObject set = e.getAsJsonObject();
      if (set.has("scope") && set.get("scope").isJsonArray()) {
        if (!set.has("excludes")) {
          set.add("excludes", new JsonArray());
        }
        continue;
      }
      List<String> scope = new ArrayList<>();
      JsonArray excludes = new JsonArray();
      JsonElement rules = set.remove("rules");
      JsonElement enabled = set.remove("enabled");
      JsonElement oldScope = set.remove("scope");
      if (rules != null && rules.isJsonObject()) {
        for (Map.Entry<String, JsonElement> r : rules.getAsJsonObject().entrySet()) {
          String place = r.getKey();
          if ("on".equals(r.getValue().getAsString())) {
            scope.add(place);
          } else if (!ROOT.equals(place) && !place.endsWith("/") && !place.endsWith(".xml")) {
            // statement での「適用しない」（フォルダ・ファイルでの「適用しない」は表せないので捨てる）
            excludes.add(place);
          }
        }
      } else {
        boolean on = enabled != null ? enabled.getAsBoolean() : active == null || set.get("id").getAsString().equals(activeId);
        if (on) {
          JsonObject s = oldScope != null && oldScope.isJsonObject() ? oldScope.getAsJsonObject() : null;
          if (s == null || !s.has("all") || s.get("all").getAsBoolean()) {
            scope.add(ROOT);
          } else if (s.has("items") && s.get("items").isJsonArray()) {
            for (JsonElement item : s.getAsJsonArray("items")) {
              scope.add(item.getAsString());
            }
          }
        }
      }
      JsonArray array = new JsonArray();
      scope.forEach(array::add);
      set.add("scope", array);
      set.add("excludes", excludes);
    }
  }

  private JsonArray sets() {
    return project().getAsJsonArray("sets");
  }

  private JsonObject order() {
    return project().getAsJsonObject("order");
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
