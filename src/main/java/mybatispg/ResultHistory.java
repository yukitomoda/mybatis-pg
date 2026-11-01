package mybatispg;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * クエリの実行の履歴（結果ビューで見る）。実行タブとサブクエリの部分の実行を、結果と一緒に保存する。
 *
 * <p>プロジェクトごとのディレクトリ（~/.mybatis-pg/results/&lt;プロジェクト&gt;/）に、1 件を 1 つの JSON で置き、
 * 新しいものから {@link #KEEP} 件を残す。DB の値がそのまま入るので、ファイルは自分だけが読めるようにする。
 * <pre>
 * {"id": "時刻-連番", "kind": "execute" | "part", "label": 部分の名前, "statementId", "draft": 編集中の下書きで実行したか,
 *  "connectionId", "connectionName", "executedAt": ミリ秒, "elapsedMillis", "parameters": 送られた値（型付きの JSON）,
 *  "sql", "resultSets": [{"columns", "columnTypes", "rows", "truncated"}], "updateCounts", "warnings", "committed",
 *  "total": 部分の全体の件数, "error": 失敗したときの理由}
 * </pre>
 */
final class ResultHistory {

  /** 残す件数 */
  static final int KEEP = 50;
  private static final Pattern FILE = Pattern.compile("\\d{13}-\\d{4}\\.json");

  /** null ならファイルに保存しない（テスト用。メモリに持つ） */
  private final Path dir;
  private final List<JsonObject> memory = new ArrayList<>();
  private int seq;

  private ResultHistory(Path dir) {
    this.dir = dir;
  }

  /**
   * @param root 保存先（~/.mybatis-pg/results）。null なら保存しない
   */
  static ResultHistory open(Path root, String projectKey) {
    return new ResultHistory(root == null ? null : root.resolve(Relations.hash(projectKey).substring(0, 16)));
  }

  /** 保存して、id を返す */
  synchronized String add(JsonObject entry) throws IOException {
    String id = String.format("%013d-%04d", System.currentTimeMillis(), seq++ % 10000);
    entry.addProperty("id", id);
    if (dir == null) {
      memory.add(entry);
      while (memory.size() > KEEP) {
        memory.remove(0);
      }
      return id;
    }
    Files.createDirectories(dir);
    write(dir.resolve(id + ".json"), entry);
    // 古いものを消す
    List<Path> files = files();
    for (int i = 0; i < files.size() - KEEP; i++) {
      Files.deleteIfExists(files.get(i));
    }
    return id;
  }

  /** 自分だけが読めるファイルに、置き換えて書く */
  private void write(Path file, JsonObject entry) throws IOException {
    Path tmp = Files.createTempFile(dir, "result", ".tmp");
    try {
      try {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException e) {
        // Windows など
      }
      try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        Json.GSON.toJson(entry, out);
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

  /** 古い順 */
  private List<Path> files() throws IOException {
    List<Path> out = new ArrayList<>();
    if (dir == null || !Files.isDirectory(dir)) {
      return out;
    }
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
      for (Path p : ds) {
        if (FILE.matcher(p.getFileName().toString()).matches()) {
          out.add(p);
        }
      }
    }
    Collections.sort(out);
    return out;
  }

  /** 1 件を書き換える（続きを取った、取り直した） */
  synchronized void replace(String id, JsonObject entry) throws IOException {
    if (dir == null) {
      for (int i = 0; i < memory.size(); i++) {
        if (memory.get(i).get("id").getAsString().equals(id)) {
          memory.set(i, entry);
        }
      }
      return;
    }
    if (!id.matches("\\d{13}-\\d{4}") || !Files.isRegularFile(dir.resolve(id + ".json"))) {
      throw new IOException("履歴がありません: " + id);
    }
    write(dir.resolve(id + ".json"), entry);
  }

  /** 1 件。なければ null */
  synchronized JsonObject get(String id) throws IOException {
    if (dir == null) {
      for (JsonObject o : memory) {
        if (o.get("id").getAsString().equals(id)) {
          return o;
        }
      }
      return null;
    }
    if (!id.matches("\\d{13}-\\d{4}")) {
      return null;
    }
    Path file = dir.resolve(id + ".json");
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(in).getAsJsonObject();
    } catch (JsonParseException | IllegalStateException e) {
      return null;
    }
  }

  /** 一覧（新しい順）。結果の行は含めず、行数だけ */
  synchronized List<Map<String, Object>> list() throws IOException {
    List<JsonObject> entries = new ArrayList<>();
    if (dir == null) {
      entries.addAll(memory);
    } else {
      for (Path p : files()) {
        try (Reader in = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
          entries.add(JsonParser.parseReader(in).getAsJsonObject());
        } catch (JsonParseException | IllegalStateException e) {
          // 壊れたものは出さない
        }
      }
    }
    Collections.reverse(entries);
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonObject o : entries) {
      Map<String, Object> m = new LinkedHashMap<>();
      for (String k : new String[] {"id", "kind", "label", "statementId", "connectionName", "executedAt", "elapsedMillis", "committed"}) {
        JsonElement v = o.get(k);
        m.put(k, v == null || v.isJsonNull() ? null : Json.GSON.fromJson(v, Object.class));
      }
      int rows = 0;
      if (o.has("resultSets")) {
        for (JsonElement rs : o.getAsJsonArray("resultSets")) {
          rows += rs.getAsJsonObject().getAsJsonArray("rows").size();
        }
      }
      m.put("rows", rows);
      m.put("error", o.has("error") && !o.get("error").isJsonNull() ? o.get("error").getAsString().split("\n")[0] : null);
      out.add(m);
    }
    return out;
  }

  synchronized void clear() throws IOException {
    memory.clear();
    for (Path p : files()) {
      Files.deleteIfExists(p);
    }
  }
}
