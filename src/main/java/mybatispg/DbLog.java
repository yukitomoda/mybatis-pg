package mybatispg;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DB への操作と、画面の操作で起きたエラーのログ。接続・切断、送ったすべての SQL（PreparedStatement の値も）、コミット・
 * ロールバックなどを、かかった時間とエラーと一緒に 1 件 1 行の JSON で書く。遅いときや止まったときに、何をしていたかを追えるようにし、
 * 画面の「ログ」で読む（{@link LogReader}）。
 *
 * <p>~/.mybatis-pg/logs/db-YYYY-MM-DD.jsonl に日ごとに書き、起動したときと日が変わったときに、古い日のファイルを消す
 * （前の版の db-YYYY-MM-DD.log も同じく消す）。SQL には値がそのまま入るので、ファイルは自分だけが読めるようにする。
 * <pre>
 * {"t":"2026-10-27 10:15:03.123","src":"画面","kind":"query","conn":"project:default","n":12,"ms":35,"sql":"SELECT ...","values":["1","x"]}
 * {"t":"...","src":"解析","kind":"execute","conn":"...","n":13,"ms":2,"sql":"...","err":"...","state":"42P01"}
 * {"t":"...","src":"解析","kind":"event","who":"relations","msg":"途中の結果を保存（50 件）"}
 * {"t":"...","src":"画面","kind":"api","path":"/api/execute","status":422,"err":"..."}
 * </pre>
 * 1 件の id は「ファイル名:その行の先頭のバイト位置」。
 */
final class DbLog {

  /** SQL を切る長さ */
  private static final int MAX_SQL = 20_000;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
  static final Pattern FILE = Pattern.compile("db-(\\d{4}-\\d{2}-\\d{2})\\.(log|jsonl)");
  /** 時間を測ってログに書く Connection の操作 */
  private static final Set<String> CONNECTION_OPS = new HashSet<>(Arrays.asList(
      "commit", "rollback", "setAutoCommit", "setSavepoint", "releaseSavepoint", "close", "setReadOnly", "setTransactionIsolation"));
  private static final Set<String> STATEMENT_OPS = new HashSet<>(Arrays.asList(
      "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch"));

  private static Path dir;
  private static int keepDays;
  private static LocalDate today;
  private static java.io.OutputStream out;
  /** 今のファイルの名前と、次に書く行の先頭の位置 */
  private static String fileName;
  private static long size;
  private static final AtomicInteger SEQ = new AtomicInteger();
  /** どこからの操作か（解析のスレッドでは「解析」。それ以外は「画面」） */
  private static final ThreadLocal<String> ORIGIN = ThreadLocal.withInitial(() -> "画面");

  /** このスレッドの操作を、どこからの操作として書くか（解析のスレッドで「解析」にする） */
  static void origin(String source) {
    ORIGIN.set(source);
  }

  private DbLog() {
  }

  /**
   * ログを書き始める。呼ばなければ書かない（テストなど）。
   *
   * @param keep 残す日数（今日を含む）
   */
  static synchronized void start(Path logDir, int keep) throws IOException {
    dir = logDir;
    keepDays = keep;
    Files.createDirectories(dir);
    open(LocalDate.now());
  }

  /** ログを止める（テスト用） */
  static synchronized void stop() {
    if (out != null) {
      try {
        out.close();
      } catch (IOException ignored) {
        // 閉じられなくても止める
      }
    }
    out = null;
    dir = null;
    today = null;
  }

  static synchronized boolean enabled() {
    return dir != null;
  }

  /** ログのディレクトリ（書いていなければ null） */
  static synchronized Path dir() {
    return dir;
  }

  /** アプリの動き（解析の開始・途中の保存など）を書く */
  static void event(String who, String message) {
    com.google.gson.JsonObject e = entry("event");
    e.addProperty("who", who);
    e.addProperty("msg", message);
    write(e);
  }

  /** 画面の操作で起きたエラー（API がエラーを返した）を書く。書いた行の id を返す（書いていなければ null） */
  static synchronized String apiError(String path, int status, String message) {
    // 入力のたびに同じエラーになる（SQL を組み立てられないなど）ときは、続けて書かない
    long now = System.currentTimeMillis();
    String key = path + "\u0000" + message;
    if (key.equals(lastApiError) && now - lastApiErrorAt < 30_000 && lastApiErrorId != null) {
      lastApiErrorAt = now;
      return lastApiErrorId;
    }
    com.google.gson.JsonObject e = entry("api");
    e.addProperty("path", path);
    e.addProperty("status", status);
    e.addProperty("err", message);
    lastApiError = key;
    lastApiErrorAt = now;
    lastApiErrorId = write(e);
    return lastApiErrorId;
  }

  /** 最後に書いた、画面の操作のエラー（同じものを続けて書かないため） */
  private static String lastApiError;
  private static long lastApiErrorAt;
  private static String lastApiErrorId;

  private static com.google.gson.JsonObject entry(String kind) {
    com.google.gson.JsonObject e = new com.google.gson.JsonObject();
    e.addProperty("kind", kind);
    return e;
  }

  /** 1 件を 1 行で書く。書いた行の id（ファイル名:位置）を返す。書いていなければ null */
  private static synchronized String write(com.google.gson.JsonObject entry) {
    if (dir == null) {
      return null;
    }
    LocalDateTime now = LocalDateTime.now();
    try {
      if (!now.toLocalDate().equals(today)) {
        open(now.toLocalDate());
      }
      com.google.gson.JsonObject line = new com.google.gson.JsonObject();
      line.addProperty("t", TIME.format(now));
      line.addProperty("src", ORIGIN.get());
      for (java.util.Map.Entry<String, com.google.gson.JsonElement> x : entry.entrySet()) {
        line.add(x.getKey(), x.getValue());
      }
      byte[] bytes = (Json.GSON.toJson(line) + "\n").getBytes(StandardCharsets.UTF_8);
      String id = fileName + ":" + size;
      out.write(bytes);
      // 止まったときにも、そこまでが読めるように、行ごとに書き出す
      out.flush();
      size += bytes.length;
      return id;
    } catch (IOException e) {
      // ログを書けなくても、操作は続ける
      return null;
    }
  }

  /** その日のファイルを開き、古い日のファイルを消す */
  private static void open(LocalDate date) throws IOException {
    if (out != null) {
      try {
        out.close();
      } catch (IOException ignored) {
        // 閉じられなくても、新しいファイルに書く
      }
    }
    today = date;
    fileName = "db-" + date + ".jsonl";
    Path file = dir.resolve(fileName);
    boolean created = !Files.exists(file);
    out = new java.io.BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
    size = Files.size(file);
    if (created) {
      try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException | IOException e) {
        // Windows など
      }
    }
    deleteOld(dir, date, keepDays);
  }

  /** keep 日より前（今日を 1 日目として数える）のファイルを消す。消した数を返す */
  static int deleteOld(Path logDir, LocalDate today, int keep) {
    int deleted = 0;
    LocalDate oldest = today.minusDays(keep - 1);
    try (DirectoryStream<Path> files = Files.newDirectoryStream(logDir, "db-*.{log,jsonl}")) {
      for (Path f : files) {
        Matcher m = FILE.matcher(f.getFileName().toString());
        if (m.matches()) {
          try {
            if (LocalDate.parse(m.group(1)).isBefore(oldest)) {
              Files.deleteIfExists(f);
              deleted++;
            }
          } catch (RuntimeException | IOException ignored) {
            // 名前の日付が読めない・消せないものは残す
          }
        }
      }
    } catch (IOException ignored) {
      // ディレクトリを読めなければ消さない
    }
    return deleted;
  }

  // ---- 接続の包み ----

  /** 接続を開いたことを書き、以後の操作を書く接続にして返す。ログを書いていなければ、そのまま返す */
  static Connection wrap(Connection c, String connectionId, long openMillis) {
    if (!enabled()) {
      return c;
    }
    Tag tag = new Tag(connectionId, SEQ.incrementAndGet());
    com.google.gson.JsonObject en = tag.entry("connect");
    en.addProperty("ms", openMillis);
    write(en);
    return (Connection) Proxy.newProxyInstance(DbLog.class.getClassLoader(), new Class<?>[] {Connection.class},
        new ConnectionHandler(c, tag));
  }

  /** 接続を開けなかったことを書く */
  static void connectFailed(String connectionId, long millis, Exception e) {
    com.google.gson.JsonObject en = entry("connect");
    en.addProperty("conn", connectionId);
    en.addProperty("ms", millis);
    error(en, e);
    write(en);
  }

  /** エラーの文と SQLState を入れる */
  private static void error(com.google.gson.JsonObject en, Throwable e) {
    en.addProperty("err", String.valueOf(e.getMessage()));
    if (e instanceof SQLException && ((SQLException) e).getSQLState() != null) {
      en.addProperty("state", ((SQLException) e).getSQLState());
    }
  }

  /** 接続先と、接続の通し番号 */
  private static final class Tag {
    final String connectionId;
    final int n;

    Tag(String connectionId, int n) {
      this.connectionId = connectionId;
      this.n = n;
    }

    com.google.gson.JsonObject entry(String kind) {
      com.google.gson.JsonObject e = DbLog.entry(kind);
      e.addProperty("conn", connectionId);
      e.addProperty("n", n);
      return e;
    }
  }

  /** 改行と続く空白を 1 つの空白にし、長ければ切る */
  static String oneLine(String sql) {
    String s = sql.replaceAll("\\s+", " ").trim();
    return s.length() > MAX_SQL ? s.substring(0, MAX_SQL) + " …（" + s.length() + " 文字）" : s;
  }

  private static Object call(Object target, Method m, Object[] args) throws Throwable {
    try {
      return m.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private static final class ConnectionHandler implements InvocationHandler {
    private final Connection c;
    private final Tag tag;

    ConnectionHandler(Connection c, Tag tag) {
      this.c = c;
      this.tag = tag;
    }

    @Override
    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
      String name = m.getName();
      if (name.equals("createStatement")) {
        Statement s = (Statement) call(c, m, args);
        return Proxy.newProxyInstance(DbLog.class.getClassLoader(), new Class<?>[] {Statement.class},
            new StatementHandler(s, tag, null));
      }
      if (name.equals("prepareStatement") || name.equals("prepareCall")) {
        Statement s = (Statement) call(c, m, args);
        Class<?> type = name.equals("prepareCall") ? CallableStatement.class : PreparedStatement.class;
        return Proxy.newProxyInstance(DbLog.class.getClassLoader(), new Class<?>[] {type},
            new StatementHandler(s, tag, (String) args[0]));
      }
      if (!CONNECTION_OPS.contains(name)) {
        return call(c, m, args);
      }
      String what = name + (args != null && args.length == 1 && !(args[0] instanceof java.sql.Savepoint) ? " " + args[0] : "");
      long t0 = System.nanoTime();
      com.google.gson.JsonObject en = tag.entry(what);
      try {
        Object r = call(c, m, args);
        en.addProperty("ms", (System.nanoTime() - t0) / 1_000_000);
        write(en);
        return r;
      } catch (Throwable e) {
        en.addProperty("ms", (System.nanoTime() - t0) / 1_000_000);
        error(en, e);
        write(en);
        throw e;
      }
    }
  }

  private static final class StatementHandler implements InvocationHandler {
    private final Statement s;
    private final Tag tag;
    /** PreparedStatement の SQL（Statement なら null） */
    private final String prepared;
    /** PreparedStatement に入れた値（番号順） */
    private final TreeMap<Integer, Object> values = new TreeMap<>();

    StatementHandler(Statement s, Tag tag, String prepared) {
      this.s = s;
      this.tag = tag;
      this.prepared = prepared;
    }

    @Override
    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
      String name = m.getName();
      if (prepared != null && name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
        values.put((Integer) args[0], name.equals("setNull") ? null : args[1]);
      } else if (name.equals("clearParameters")) {
        values.clear();
      }
      if (!STATEMENT_OPS.contains(name)) {
        return call(s, m, args);
      }
      String sql = args != null && args.length >= 1 && args[0] instanceof String ? (String) args[0] : prepared;
      com.google.gson.JsonObject en = tag.entry(name.equals("executeQuery") ? "query" : name.startsWith("executeUpdate") ? "update" : name);
      if (sql != null) {
        en.addProperty("sql", oneLine(sql));
        if (prepared != null && sql == prepared && !values.isEmpty()) {
          com.google.gson.JsonArray vs = new com.google.gson.JsonArray();
          for (Object v : values.values()) {
            vs.add(v == null ? null : String.valueOf(v));
          }
          en.add("values", vs);
        }
      }
      long t0 = System.nanoTime();
      try {
        Object r = call(s, m, args);
        en.addProperty("ms", (System.nanoTime() - t0) / 1_000_000);
        write(en);
        return r;
      } catch (Throwable e) {
        en.addProperty("ms", (System.nanoTime() - t0) / 1_000_000);
        error(en, e);
        write(en);
        throw e;
      }
    }
  }
}
