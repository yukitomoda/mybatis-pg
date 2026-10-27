package mybatispg;

import java.io.BufferedWriter;
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
 * DB への操作のログ。接続・切断、送ったすべての SQL（PreparedStatement の値も）、コミット・ロールバックなどを、
 * かかった時間とエラーと一緒に 1 行ずつ書く。遅いときや止まったときに、何をしていたかを追えるようにする。
 *
 * <p>~/.mybatis-pg/logs/db-YYYY-MM-DD.log に日ごとに書き、起動したときと日が変わったときに、古い日のファイルを消す。
 * SQL には値がそのまま入るので、ファイルは自分だけが読めるようにする。
 * <pre>
 * 2026-10-27 10:15:03.123 [#12 project:default] query 35ms SELECT ... | 値: [1, x]
 * 2026-10-27 10:15:03.200 [#12 project:default] rollback 1ms
 * 2026-10-27 10:15:04.000 [relations] 途中の結果を保存（50 件）
 * </pre>
 */
final class DbLog {

  /** SQL を切る長さ */
  private static final int MAX_SQL = 20_000;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
  private static final Pattern FILE = Pattern.compile("db-(\\d{4}-\\d{2}-\\d{2})\\.log");
  /** 時間を測ってログに書く Connection の操作 */
  private static final Set<String> CONNECTION_OPS = new HashSet<>(Arrays.asList(
      "commit", "rollback", "setAutoCommit", "setSavepoint", "releaseSavepoint", "close", "setReadOnly", "setTransactionIsolation"));
  private static final Set<String> STATEMENT_OPS = new HashSet<>(Arrays.asList(
      "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch"));

  private static Path dir;
  private static int keepDays;
  private static LocalDate today;
  private static BufferedWriter out;
  private static final AtomicInteger SEQ = new AtomicInteger();

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

  /** アプリの動き（解析の開始・途中の保存など）を書く */
  static void event(String who, String message) {
    write("[" + who + "] " + message);
  }

  private static synchronized void write(String line) {
    if (dir == null) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    try {
      if (!now.toLocalDate().equals(today)) {
        open(now.toLocalDate());
      }
      out.write(TIME.format(now) + " " + line);
      out.newLine();
      // 止まったときにも、そこまでが読めるように、行ごとに書き出す
      out.flush();
    } catch (IOException e) {
      // ログを書けなくても、操作は続ける
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
    Path file = dir.resolve("db-" + date + ".log");
    boolean created = !Files.exists(file);
    out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
    try (DirectoryStream<Path> files = Files.newDirectoryStream(logDir, "db-*.log")) {
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
    String tag = "#" + SEQ.incrementAndGet() + " " + connectionId;
    write("[" + tag + "] connect " + openMillis + "ms");
    return (Connection) Proxy.newProxyInstance(DbLog.class.getClassLoader(), new Class<?>[] {Connection.class},
        new ConnectionHandler(c, tag));
  }

  /** 接続を開けなかったことを書く */
  static void connectFailed(String connectionId, long millis, Exception e) {
    write("[" + connectionId + "] connect 失敗 " + millis + "ms " + error(e));
  }

  private static String error(Throwable e) {
    String state = e instanceof SQLException && ((SQLException) e).getSQLState() != null ? "(" + ((SQLException) e).getSQLState() + ") " : "";
    return "エラー " + state + oneLine(String.valueOf(e.getMessage()));
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
    private final String tag;

    ConnectionHandler(Connection c, String tag) {
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
      try {
        Object r = call(c, m, args);
        write("[" + tag + "] " + what + " " + (System.nanoTime() - t0) / 1_000_000 + "ms");
        return r;
      } catch (Throwable e) {
        write("[" + tag + "] " + what + " " + (System.nanoTime() - t0) / 1_000_000 + "ms " + error(e));
        throw e;
      }
    }
  }

  private static final class StatementHandler implements InvocationHandler {
    private final Statement s;
    private final String tag;
    /** PreparedStatement の SQL（Statement なら null） */
    private final String prepared;
    /** PreparedStatement に入れた値（番号順） */
    private final TreeMap<Integer, Object> values = new TreeMap<>();

    StatementHandler(Statement s, String tag, String prepared) {
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
      String text = (name.equals("executeQuery") ? "query" : name.startsWith("executeUpdate") ? "update" : name) + " ";
      String tail = sql == null ? "" : " " + oneLine(sql) + (prepared != null && sql == prepared && !values.isEmpty() ? " | 値: " + values.values() : "");
      long t0 = System.nanoTime();
      try {
        Object r = call(s, m, args);
        write("[" + tag + "] " + text + (System.nanoTime() - t0) / 1_000_000 + "ms" + tail);
        return r;
      } catch (Throwable e) {
        write("[" + tag + "] " + text + (System.nanoTime() - t0) / 1_000_000 + "ms " + error(e) + " |" + tail);
        throw e;
      }
    }
  }
}
