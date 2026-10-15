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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * 共通パラメータ（複数の statement で共有する値の集まり）を管理する。
 *
 * <p>ファイルにはプロジェクトごとに保存する。他のプロジェクトの内容はそのまま残す。
 * <pre>
 * {"projects": {"/path/to/project": {"active": "id", "sets": [{"id": "...", "name": "...", "values": {...}}]}}}
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

  /** このプロジェクトの内容: {"active": id または null, "sets": [...]} */
  public synchronized JsonObject snapshot() {
    JsonObject p = project();
    JsonObject copy = new JsonObject();
    copy.add("active", p.get("active"));
    copy.add("sets", p.get("sets").deepCopy());
    return copy;
  }

  /**
   * 作る。
   *
   * @param copyFrom null でなければ、その共通パラメータの値を引き継ぐ
   */
  public synchronized JsonObject create(String name, String copyFrom) throws IOException {
    String trimmed = validName(name, null);
    JsonObject values = new JsonObject();
    if (copyFrom != null) {
      values = find(copyFrom).getAsJsonObject("values").deepCopy();
    }
    JsonObject set = new JsonObject();
    set.addProperty("id", UUID.randomUUID().toString());
    set.addProperty("name", trimmed);
    set.add("values", values);
    sets().add(set);
    write();
    return set.deepCopy();
  }

  public synchronized void rename(String id, String name) throws IOException {
    JsonObject set = find(id);
    set.addProperty("name", validName(name, id));
    write();
  }

  /** 選んでいるものを消したら、選択を「なし」にする */
  public synchronized void delete(String id) throws IOException {
    JsonArray sets = sets();
    sets.remove(find(id));
    if (id.equals(activeId())) {
      project().add("active", JsonNull.INSTANCE);
    }
    write();
  }

  /** @param id null なら「なし」 */
  public synchronized void select(String id) throws IOException {
    if (id != null) {
      find(id);
    }
    project().add("active", id == null ? JsonNull.INSTANCE : new JsonPrimitive(id));
    write();
  }

  public synchronized void saveValues(String id, JsonObject values) throws IOException {
    find(id).add("values", values.deepCopy());
    write();
  }

  private String activeId() {
    JsonElement a = project().get("active");
    return a == null || a.isJsonNull() ? null : a.getAsString();
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
    if (!p.has("active")) {
      p.add("active", JsonNull.INSTANCE);
    }
    if (!p.has("sets") || !p.get("sets").isJsonArray()) {
      p.add("sets", new JsonArray());
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
