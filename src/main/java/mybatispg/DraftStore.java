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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * 保存していない mapper XML の編集内容（下書き）。元のファイルは変えない。
 *
 * <p>プロジェクトごとのディレクトリに、ファイルごとに 1 つの JSON で置く（編集したファイルの分だけを書き込む）。
 * <pre>
 * {"project": "/path/to/project", "file": "src/.../OrderMapper.xml", "base": 編集を始めたときの内容, "content": 編集中の内容, "updatedAt": ミリ秒}
 * </pre>
 */
public final class DraftStore {

  public static final class Draft {
    /** 画面のパス（基準ディレクトリからの相対パス） */
    public final String file;
    /** 編集を始めたときのファイルの内容 */
    public final String base;
    public final String content;
    public final long updatedAt;

    Draft(String file, String base, String content, long updatedAt) {
      this.file = file;
      this.base = base;
      this.content = content;
      this.updatedAt = updatedAt;
    }
  }

  /** null ならファイルに保存しない */
  private final Path dir;
  private final String projectKey;
  private final Map<String, Draft> memory = new LinkedHashMap<>();

  private DraftStore(Path dir, String projectKey) {
    this.dir = dir;
    this.projectKey = projectKey;
  }

  /**
   * @param root 下書きを置くディレクトリ（~/.mybatis-pg/drafts）
   * @param projectKey プロジェクトを区別する文字列（基準ディレクトリの絶対パス）
   */
  public static DraftStore open(Path root, String projectKey) {
    return new DraftStore(root.resolve(hash(projectKey).substring(0, 16)), projectKey);
  }

  /** ファイルに保存しない（テスト用） */
  public static DraftStore inMemory() {
    return new DraftStore(null, "");
  }

  /** 更新の新しい順 */
  public synchronized List<Draft> list() throws IOException {
    List<Draft> out = new ArrayList<>();
    if (dir == null) {
      out.addAll(memory.values());
    } else if (Files.isDirectory(dir)) {
      try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
        for (Path p : files) {
          Draft d = read(p);
          if (d != null) {
            out.add(d);
          }
        }
      }
    }
    out.sort(Comparator.comparingLong((Draft d) -> d.updatedAt).reversed());
    return out;
  }

  /** 保存する。元の内容と同じなら消す */
  public synchronized void save(String file, String base, String content) throws IOException {
    if (content.equals(base)) {
      delete(file);
      return;
    }
    Draft d = new Draft(file, base, content, System.currentTimeMillis());
    if (dir == null) {
      memory.put(file, d);
      return;
    }
    JsonObject json = new JsonObject();
    json.addProperty("project", projectKey);
    json.addProperty("file", file);
    json.addProperty("base", base);
    json.addProperty("content", content);
    json.addProperty("updatedAt", d.updatedAt);
    write(pathOf(file), json);
  }

  public synchronized void delete(String file) throws IOException {
    if (dir == null) {
      memory.remove(file);
    } else {
      Files.deleteIfExists(pathOf(file));
    }
  }

  private Path pathOf(String file) {
    return dir.resolve(hash(file) + ".json");
  }

  /** 読めないもの・別のプロジェクトのものは無視する */
  private Draft read(Path p) {
    try (Reader in = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
      JsonObject o = JsonParser.parseReader(in).getAsJsonObject();
      if (!projectKey.equals(o.get("project").getAsString())) {
        return null;
      }
      return new Draft(o.get("file").getAsString(), o.get("base").getAsString(), o.get("content").getAsString(),
          o.get("updatedAt").getAsLong());
    } catch (IOException | JsonParseException | IllegalStateException | NullPointerException | UnsupportedOperationException e) {
      return null;
    }
  }

  /** 一時ファイルに書いてから置き換える。自分だけが読めるようにする */
  private void write(Path file, JsonObject json) throws IOException {
    Files.createDirectories(dir);
    Path tmp = Files.createTempFile(dir, "draft", ".tmp");
    try {
      try {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException e) {
        // Windows など POSIX の権限がない環境では設定しない
      }
      try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        new GsonBuilder().disableHtmlEscaping().create().toJson(json, out);
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

  private static String hash(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : d) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
