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
import java.util.UUID;

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
 * {"projects": {"/path/to/project": {"sets": [
 *   {"id": "...", "name": "...", "values": {...}, "enabled": true, "scope": {"all": false, "items": ["shop/order/", ...]}}
 * ]}}}
 * </pre>
 * 範囲（scope）の items は、フォルダ（末尾が /）・ファイルの相対パスか、statement の id。
 * 以前の形（"active" で 1 つを選ぶ）は、読み込んだときに、選んでいたものをオン・範囲を全体として引き継ぐ。
 * <pre>
 * </pre>
 * 値は JSON のまま持ち、数値は書かれた文字のまま保つ。
 */
public final class GlobalStore {

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

  /** このプロジェクトの内容: {"sets": [...]}（並び順は優先順位に使う） */
  public synchronized JsonObject snapshot() {
    JsonObject copy = new JsonObject();
    copy.add("sets", project().get("sets").deepCopy());
    return copy;
  }

  /**
   * 作る。
   *
   * 作る。新しいものはオンで、範囲は全体。
   *
   * @param copyFrom null でなければ、その共通パラメータの値と範囲を引き継ぐ
   */
  public synchronized JsonObject create(String name, String copyFrom) throws IOException {
    String trimmed = validName(name, null);
    JsonObject values = new JsonObject();
    JsonObject scope = allScope();
    if (copyFrom != null) {
      JsonObject from = find(copyFrom);
      values = from.getAsJsonObject("values").deepCopy();
      scope = from.getAsJsonObject("scope").deepCopy();
    }
    JsonObject set = new JsonObject();
    set.addProperty("id", UUID.randomUUID().toString());
    set.addProperty("name", trimmed);
    set.add("values", values);
    set.addProperty("enabled", true);
    set.add("scope", scope);
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
    write();
  }

  public synchronized void setEnabled(String id, boolean enabled) throws IOException {
    find(id).addProperty("enabled", enabled);
    write();
  }

  /**
   * 範囲を変える。
   *
   * @param items all が false のときに効く、フォルダ（末尾が /）・ファイルの相対パスか statement の id
   */
  public synchronized void setScope(String id, boolean all, java.util.List<String> items) throws IOException {
    JsonObject set = find(id);
    JsonObject scope = new JsonObject();
    scope.addProperty("all", all);
    JsonArray list = new JsonArray();
    for (String item : items) {
      if (item != null && !item.trim().isEmpty()) {
        list.add(item.trim());
      }
    }
    scope.add("items", list);
    set.add("scope", scope);
    write();
  }

  /** 並び順を変える（同じ範囲の狭さなら、下にあるほうが優先） */
  public synchronized void move(String id, int offset) throws IOException {
    JsonArray sets = sets();
    JsonObject set = find(id);
    int from = indexOf(sets, set);
    int to = Math.max(0, Math.min(sets.size() - 1, from + offset));
    if (from == to) {
      return;
    }
    sets.remove(from);
    JsonArray reordered = new JsonArray();
    for (int i = 0; i < sets.size(); i++) {
      if (i == to) {
        reordered.add(set);
      }
      reordered.add(sets.get(i));
    }
    if (to >= sets.size()) {
      reordered.add(set);
    }
    project().add("sets", reordered);
    write();
  }

  private static int indexOf(JsonArray sets, JsonObject set) {
    for (int i = 0; i < sets.size(); i++) {
      if (sets.get(i) == set) {
        return i;
      }
    }
    return -1;
  }

  private static JsonObject allScope() {
    JsonObject scope = new JsonObject();
    scope.addProperty("all", true);
    scope.add("items", new JsonArray());
    return scope;
  }

  public synchronized void saveValues(String id, JsonObject values) throws IOException {
    find(id).add("values", values.deepCopy());
    write();
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
    // 以前の形（"active" で 1 つを選ぶ）を引き継ぐ: 選んでいたものをオンにし、範囲は全体にする
    JsonElement active = p.remove("active");
    String activeId = active == null || active.isJsonNull() ? null : active.getAsString();
    for (JsonElement e : p.getAsJsonArray("sets")) {
      JsonObject set = e.getAsJsonObject();
      if (!set.has("enabled")) {
        set.addProperty("enabled", active == null || set.get("id").getAsString().equals(activeId));
      }
      if (!set.has("scope") || !set.get("scope").isJsonObject()) {
        set.add("scope", allScope());
      }
    }
    return p;
  }

  private JsonArray sets() {
    return project().getAsJsonArray("sets");
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
