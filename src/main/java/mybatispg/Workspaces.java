package mybatispg;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonParseException;

/**
 * ワークスペースの定義（名前と、mapper XML を読むソースの一覧）を保存する。
 *
 * <p>~/.mybatis-pg/workspaces/&lt;id&gt;/workspace.json に 1 つずつ置き、最後に開いたものを
 * ~/.mybatis-pg/workspaces/last に書く。
 * <pre>
 * {"id", "name", "key": データを分ける鍵, "lastOpenedAt": ミリ秒,
 *  "sources": [{"id", "name", "type": "project" | "jar" | "files", "path", "enabled"}]}
 * </pre>
 * key は、共通パラメータ・下書き・プレイグラウンド・実行の履歴・リレーションを分けるのに使う。
 * --project などから作ったワークスペースは、前の版と同じ鍵（プロジェクトの場所など）にして、そのデータを引き継ぐ。
 */
final class Workspaces {

  static final String PROJECT = "project";
  static final String JAR = "jar";
  static final String FILES = "files";

  /** mapper XML を読むソース */
  static final class Source {
    String id;
    /** 一覧に出す名前（ワークスペースの中で重ならない） */
    String name;
    /** project（Spring Boot のプロジェクトのディレクトリ）、jar（JAR・war）、files（mapper XML のファイルかディレクトリ） */
    String type;
    /** 絶対パス */
    String path;
    boolean enabled = true;

    Source() {
    }

    Source(String id, String name, String type, String path, boolean enabled) {
      this.id = id;
      this.name = name;
      this.type = type;
      this.path = path;
      this.enabled = enabled;
    }
  }

  /** ワークスペース */
  static final class Def {
    String id;
    String name;
    /** データを分ける鍵 */
    String key;
    long lastOpenedAt;
    List<Source> sources = new ArrayList<>();
  }

  private static final SecureRandom RANDOM = new SecureRandom();

  /** null ならファイルに保存しない（テスト用） */
  private final Path dir;
  private final List<Def> memory = new ArrayList<>();

  private Workspaces(Path dir) {
    this.dir = dir;
  }

  /** @param dir 保存先（~/.mybatis-pg/workspaces）。null ならメモリに持つ */
  static Workspaces open(Path dir) {
    return new Workspaces(dir);
  }

  static String newId() {
    byte[] b = new byte[6];
    RANDOM.nextBytes(b);
    StringBuilder sb = new StringBuilder();
    for (byte x : b) {
      sb.append(String.format("%02x", x));
    }
    return sb.toString();
  }

  /** 最近開いた順 */
  synchronized List<Def> list() throws IOException {
    List<Def> out = new ArrayList<>();
    if (dir == null) {
      out.addAll(memory);
    } else if (Files.isDirectory(dir)) {
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
        for (Path p : ds) {
          Path f = p.resolve("workspace.json");
          if (Files.isRegularFile(f)) {
            try (Reader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
              Def d = Json.GSON.fromJson(in, Def.class);
              if (d != null && d.id != null) {
                if (d.sources == null) {
                  d.sources = new ArrayList<>();
                }
                out.add(d);
              }
            } catch (JsonParseException | IllegalStateException e) {
              // 壊れたものは出さない
            }
          }
        }
      }
    }
    out.sort(Comparator.comparingLong((Def d) -> -d.lastOpenedAt).thenComparing(d -> d.name));
    return out;
  }

  synchronized Def get(String id) throws IOException {
    for (Def d : list()) {
      if (d.id.equals(id)) {
        return d;
      }
    }
    return null;
  }

  /** id か名前で探す */
  synchronized Def find(String idOrName) throws IOException {
    for (Def d : list()) {
      if (d.id.equals(idOrName) || d.name.equals(idOrName)) {
        return d;
      }
    }
    return null;
  }

  synchronized Def findByKey(String key) throws IOException {
    for (Def d : list()) {
      if (key.equals(d.key)) {
        return d;
      }
    }
    return null;
  }

  /** 最後に開いたもの（なければ null） */
  synchronized Def last() throws IOException {
    List<Def> all = list();
    return all.isEmpty() ? null : all.get(0);
  }

  /** 作る。key が null なら、新しい鍵（workspace:id）にする */
  synchronized Def create(String name, String key, List<Source> sources) throws IOException {
    Def d = new Def();
    d.id = newId();
    d.name = uniqueName(name, null);
    d.key = key != null ? key : "workspace:" + d.id;
    d.sources = new ArrayList<>(sources);
    save(d);
    return d;
  }

  /** ほかのワークスペースと名前が重ならないようにする（重なれば「名前 (2)」など） */
  synchronized String uniqueName(String name, String exceptId) throws IOException {
    Set<String> used = new HashSet<>();
    for (Def d : list()) {
      if (!d.id.equals(exceptId)) {
        used.add(d.name);
      }
    }
    String n = name;
    for (int i = 2; used.contains(n); i++) {
      n = name + " (" + i + ")";
    }
    return n;
  }

  synchronized void save(Def d) throws IOException {
    if (dir == null) {
      memory.removeIf(x -> x.id.equals(d.id));
      memory.add(d);
      return;
    }
    Path wd = dir.resolve(d.id);
    Files.createDirectories(wd);
    Path tmp = Files.createTempFile(wd, "workspace", ".tmp");
    try {
      try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        Json.GSON.toJson(d, out);
      }
      Path f = wd.resolve("workspace.json");
      try {
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  /** 開いたことを記録する（最近開いた順の先頭にする） */
  synchronized void touch(Def d) throws IOException {
    d.lastOpenedAt = System.currentTimeMillis();
    save(d);
  }

  /** 消す（データを分ける鍵で保存したリレーションなどは残す。同じ鍵で作り直せば、また使える） */
  synchronized boolean delete(String id) throws IOException {
    if (dir == null) {
      return memory.removeIf(x -> x.id.equals(id));
    }
    Path wd = dir.resolve(id);
    if (!id.matches("[0-9a-f]{12}") || !Files.isRegularFile(wd.resolve("workspace.json"))) {
      return false;
    }
    Files.delete(wd.resolve("workspace.json"));
    try {
      Files.deleteIfExists(wd);
    } catch (IOException e) {
      // ほかのファイルがあれば、ディレクトリは残す
    }
    return true;
  }

  /** ソースの名前の候補（ディレクトリかファイルの名前） */
  static String defaultName(String path) {
    Path p = Paths.get(path).getFileName();
    return p == null ? path : p.toString();
  }

  /** ソースが使えるか（使えなければ理由） */
  static String check(Source s) {
    if (s.path == null || s.path.trim().isEmpty()) {
      return "パスを入れてください";
    }
    Path p;
    try {
      p = Paths.get(s.path);
    } catch (RuntimeException e) {
      return "パスの形が正しくありません: " + s.path;
    }
    if (!p.isAbsolute()) {
      return "絶対パスで入れてください: " + s.path;
    }
    if (PROJECT.equals(s.type)) {
      return Files.isDirectory(p) ? null : "ディレクトリがありません: " + s.path;
    }
    if (JAR.equals(s.type)) {
      if (!Files.isRegularFile(p)) {
        return "ファイルがありません: " + s.path;
      }
      String n = p.getFileName().toString().toLowerCase();
      return n.endsWith(".jar") || n.endsWith(".war") ? null : "JAR か war を指定してください: " + s.path;
    }
    if (FILES.equals(s.type)) {
      return Files.exists(p) ? null : "ファイルかディレクトリがありません: " + s.path;
    }
    return "種類が正しくありません: " + s.type;
  }
}
