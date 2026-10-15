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
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * DB の接続先（PostgreSQL）を管理する。
 *
 * <p>接続先はファイルに保存するが、パスワードは保存せず、ツールの起動中だけメモリに持つ。
 */
public final class ConnectionStore {

  /** 接続先の設定。パスワードは含まない */
  public static final class Profile {
    public String id;
    public String name;
    public String host;
    public int port = 5432;
    public String database;
    public String user;
    /** JDBC URL に付ける接続パラメータ（例: stringtype=unspecified&sslmode=require） */
    public String params;

    Profile copy() {
      Profile p = new Profile();
      p.id = id;
      p.name = name;
      p.host = host;
      p.port = port;
      p.database = database;
      p.user = user;
      p.params = params;
      return p;
    }
  }

  private static final class FileContent {
    List<Profile> connections = new ArrayList<>();
  }

  /** null ならファイルに保存しない */
  private final Path file;
  private final Map<String, Profile> profiles = new LinkedHashMap<>();
  private final Map<String, String> passwords = new ConcurrentHashMap<>();

  private ConnectionStore(Path file) {
    this.file = file;
  }

  /** ファイルから読み込む。ファイルがなければ空で始め、保存時に作る */
  public static ConnectionStore load(Path file) throws IOException {
    ConnectionStore store = new ConnectionStore(file);
    if (Files.exists(file)) {
      try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        FileContent content = Json.GSON.fromJson(in, FileContent.class);
        if (content != null && content.connections != null) {
          for (Profile p : content.connections) {
            if (p != null && p.id != null) {
              store.profiles.put(p.id, p);
            }
          }
        }
      } catch (JsonParseException e) {
        throw new IOException("接続先の設定ファイルを読み込めません: " + file + "\n" + e.getMessage(), e);
      }
    }
    return store;
  }

  /** ファイルに保存しない（テスト用） */
  public static ConnectionStore inMemory() {
    return new ConnectionStore(null);
  }

  public synchronized List<Profile> list() {
    List<Profile> result = new ArrayList<>();
    for (Profile p : profiles.values()) {
      result.add(p.copy());
    }
    return result;
  }

  public synchronized Profile get(String id) {
    Profile p = profiles.get(id);
    return p == null ? null : p.copy();
  }

  public boolean hasPassword(String id) {
    return passwords.containsKey(id);
  }

  /**
   * 接続先を追加または更新する。id がなければ新しく振る。
   *
   * @param password null なら今のパスワードのまま。空文字ならパスワードを消す
   */
  public synchronized Profile save(Profile profile, String password) throws IOException {
    validate(profile);
    Profile p = profile.copy();
    if (p.id == null || p.id.isEmpty()) {
      p.id = UUID.randomUUID().toString();
    } else if (!profiles.containsKey(p.id)) {
      throw new IllegalArgumentException("接続先がありません: " + p.id);
    }
    p.name = p.name.trim();
    p.host = p.host.trim();
    p.database = p.database.trim();
    p.user = p.user == null ? "" : p.user.trim();
    p.params = p.params == null ? "" : p.params.trim();
    profiles.put(p.id, p);
    write();
    if (password != null) {
      if (password.isEmpty()) {
        passwords.remove(p.id);
      } else {
        passwords.put(p.id, password);
      }
    }
    return p.copy();
  }

  public synchronized boolean delete(String id) throws IOException {
    if (profiles.remove(id) == null) {
      return false;
    }
    passwords.remove(id);
    write();
    return true;
  }

  /** 接続を開く。自動コミットは呼び出し側で切り替える */
  public Connection open(String id) throws SQLException {
    Profile p = get(id);
    if (p == null) {
      throw new IllegalArgumentException("接続先がありません: " + id);
    }
    Properties props = new Properties();
    if (!p.user.isEmpty()) {
      props.setProperty("user", p.user);
    }
    String password = passwords.get(id);
    if (password != null) {
      props.setProperty("password", password);
    }
    props.setProperty("connectTimeout", "10");
    props.setProperty("ApplicationName", "mybatis-pg");
    // シェードした jar では DriverManager のドライバ探索に頼らず、直接使う
    Connection c = new org.postgresql.Driver().connect(jdbcUrl(p), props);
    if (c == null) {
      throw new SQLException("JDBC URL を解釈できません: " + jdbcUrl(p));
    }
    return c;
  }

  static String jdbcUrl(Profile p) {
    String host = p.host.contains(":") && !p.host.startsWith("[") ? "[" + p.host + "]" : p.host;
    String url = "jdbc:postgresql://" + host + ":" + p.port + "/" + p.database;
    return p.params == null || p.params.isEmpty() ? url : url + "?" + p.params;
  }

  private static void validate(Profile p) {
    if (isBlank(p.name)) {
      throw new IllegalArgumentException("名前を入力してください");
    }
    if (isBlank(p.host) || p.host.contains("/") || p.host.contains("?")) {
      throw new IllegalArgumentException("ホストを正しく入力してください");
    }
    if (p.port < 1 || p.port > 65535) {
      throw new IllegalArgumentException("ポートは 1〜65535 で入力してください");
    }
    if (isBlank(p.database) || p.database.contains("/") || p.database.contains("?")) {
      throw new IllegalArgumentException("データベース名を正しく入力してください");
    }
    if (p.params != null && (p.params.startsWith("?") || p.params.startsWith("&"))) {
      throw new IllegalArgumentException("接続パラメータは先頭の ? や & を付けずに入力してください（例: sslmode=require&stringtype=unspecified）");
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  /** 一時ファイルに書いてから置き換える。自分だけが読めるようにする */
  private void write() throws IOException {
    if (file == null) {
      return;
    }
    Path dir = file.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    restrict(dir, "rwx------");
    FileContent content = new FileContent();
    content.connections = new ArrayList<>(profiles.values());
    Path tmp = Files.createTempFile(dir, "connections", ".tmp");
    try {
      restrict(tmp, "rw-------");
      try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(content, out);
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

  private static void restrict(Path path, String permissions) {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    } catch (UnsupportedOperationException | IOException e) {
      // Windows など POSIX の権限がない環境では設定しない
    }
  }
}
