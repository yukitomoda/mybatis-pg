package mybatispg;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * パラメータが、どの表のどの列と比べられているかを推論し、その列の値の候補を出す（試作）。
 *
 * <p>推論: バインド変数のままの SQL を PREPARE し、PostgreSQL が決めたパラメータの型ごとに、見分けやすい値（目印）を入れて
 * EXPLAIN (VERBOSE) する（実行はしない）。計画の条件（Index Cond・Filter など）で目印と比べられている列を探し、
 * 列の別名を計画の表（Schema・Relation Name・Alias）に結び付ける。PostgreSQL が別名のない列やビューを解決した後の形を使うので、
 * SQL を自前で読むより確か。
 */
final class ParamSources {

  private static final String PREPARED = "mybatis_pg_infer";
  private static final String[] CONDITION_KEYS = {
    "Index Cond", "Recheck Cond", "Filter", "Join Filter", "Hash Cond", "Merge Cond", "One-Time Filter", "TID Cond"};
  /** 修飾付きの列（alias.column。引用符付きも） */
  private static final Pattern COLUMN = Pattern.compile("(?:\"((?:[^\"]|\"\")+)\"|([A-Za-z_][A-Za-z0-9_$]*))\\.(?:\"((?:[^\"]|\"\")+)\"|([A-Za-z_][A-Za-z0-9_$]*))(?!\\s*\\()");

  private ParamSources() {
  }

  /** 推論した列 */
  static final class Source {
    final String schema;
    final String table;
    final String column;
    /** 見つけた条件（例: (o.customer_id = $3)） */
    final String condition;
    /** 列そのものと比べている（関数や演算で包んでいない）。false なら、列の値をそのまま入れても合わないことがある */
    final boolean direct;

    /** compared: パラメータと比べている列、origin: リレーションでたどった源流の列 */
    final String role;
    /** 源流のとき、比べている列から源流の手前までにたどった列（schema.table.column）。比べている列なら空 */
    final List<String> via;
    /** 源流のとき、根拠の SQL の数（たどったつながりのうち、最も少ないもの） */
    final int count;
    /** 源流を、手で設定したリレーションで見つけた */
    final boolean manual;

    Source(String schema, String table, String column, String condition, boolean direct) {
      this(schema, table, column, condition, direct, "compared", new ArrayList<String>(), 0);
    }

    Source(String schema, String table, String column, String condition, boolean direct, String role, List<String> via, int count) {
      this(schema, table, column, condition, direct, role, via, count, false);
    }

    Source(String schema, String table, String column, String condition, boolean direct, String role, List<String> via, int count,
        boolean manual) {
      this.manual = manual;
      this.schema = schema;
      this.table = table;
      this.column = column;
      this.condition = condition;
      this.direct = direct;
      this.role = role;
      this.via = via;
      this.count = count;
    }

    boolean same(Source o) {
      return schema.equals(o.schema) && table.equals(o.table) && column.equals(o.column);
    }
  }

  /**
   * @param numberedSql バインド変数を $1, $2, ... にした SQL
   * @param count バインド変数の数
   * @return バインド変数の番号（1 始まり）ごとの、推論した列
   */
  /**
   * @param actualValues 今の値の文字（目印を作れないパラメータに使う。NULL だと条件がまとめて消えることがあるため。値がなければ null）
   */
  static Map<Integer, List<Source>> infer(String numberedSql, int count, List<String> fallbackTypes, List<String> actualValues,
      QueryExecutor.ConnectionFactory connections) throws QueryExecutor.ExecutionException {
    if (count == 0) {
      return new LinkedHashMap<>();
    }
    Connection c = open(connections);
    try {
      return infer(numberedSql, count, fallbackTypes, actualValues, c);
    } finally {
      try {
        c.close();
      } catch (SQLException ignored) {
        // 閉じられなくても結果は変わらない
      }
    }
  }

  static Connection open(QueryExecutor.ConnectionFactory connections) throws QueryExecutor.ExecutionException {
    try {
      return connections.open();
    } catch (SQLException | RuntimeException e) {
      throw new QueryExecutor.ExecutionException("接続できません\n" + Errors.describe(e), e);
    }
  }

  /** 開いている接続で推論する（接続は閉じない。続けて何回か推論できる） */
  static Map<Integer, List<Source>> infer(String numberedSql, int count, List<String> fallbackTypes, List<String> actualValues,
      Connection c) throws QueryExecutor.ExecutionException {
    if (count == 0) {
      return new LinkedHashMap<>();
    }
    Explained e = explain(numberedSql, count, fallbackTypes, actualValues, c);
    return fromPlan(e.plan, e.markers);
  }

  /** 計画（EXPLAIN VERBOSE の JSON）と、パラメータに入れた目印 */
  static final class Explained {
    final String plan;
    final List<String> markers;
    /** 計画を作った時間（ミリ秒。EXPLAIN (SUMMARY) のときだけ。ほかは 0） */
    final double planMillis;

    Explained(String plan, List<String> markers) {
      this(plan, markers, 0);
    }

    Explained(String plan, List<String> markers, double planMillis) {
      this.plan = plan;
      this.markers = markers;
      this.planMillis = planMillis;
    }
  }

  /**
   * 目印の値で計画を作る（実行はしない）。開いている接続を使い、閉じない。
   * パラメータがなくても計画を作る（リレーションの解析で使う）。
   */
  static Explained explain(String numberedSql, int count, List<String> fallbackTypes, List<String> actualValues, Connection c)
      throws QueryExecutor.ExecutionException {
    try {
      c.setAutoCommit(false);
      String plan;
      List<String> markers = new ArrayList<>();
      try (Statement s = c.createStatement()) {
        s.execute("SET TRANSACTION READ ONLY");
        s.setQueryTimeout(30);
        // 型は書かず、PostgreSQL に推し量らせる（比べる列の型になる）。決められないパラメータ（$1 IS NULL など）があれば、
        // そのパラメータにだけ値から決めた型を書いて作り直す。ほかは unknown（文脈から決めさせる）のままにする
        // （仮の値の 1 から決めた integer を全部に書くと、#{from}::date などで「integer から date に変換できない」になる）
        List<String> explicit = new ArrayList<>();
        for (int i = 0; i < count; i++) {
          explicit.add("unknown");
        }
        int[] retry = {-1, 0, 0};
        for (int attempt = 0;; attempt++) {
          s.execute("SAVEPOINT infer");
          try {
            s.execute(attempt == 0 ? "PREPARE " + PREPARED + " AS " + numberedSql
                : "PREPARE " + PREPARED + "(" + String.join(", ", explicit) + ") AS " + numberedSql);
            break;
          } catch (SQLException e) {
            s.execute("ROLLBACK TO SAVEPOINT infer");
            if (!retryTypes(e, explicit, fallbackTypes, retry)) {
              throw e;
            }
          }
        }
        List<String> types = new ArrayList<>();
        try (ResultSet rs = s.executeQuery("SELECT parameter_types FROM pg_prepared_statements WHERE name = '" + PREPARED + "'")) {
          if (rs.next()) {
            Array a = rs.getArray(1);
            for (Object t : (Object[]) a.getArray()) {
              types.add(String.valueOf(t));
            }
          }
        }
        // 目印が列の範囲を超える（numeric(10,2) や、日数として足す値など）と計画を作れないので、小さな目印、数値の目印なしの順に試す
        plan = null;
        SQLException last = null;
        for (int level = 0; level < 3 && plan == null; level++) {
          markers.clear();
          List<String> literals = new ArrayList<>();
          for (int i = 1; i <= count; i++) {
            String type = i <= types.size() ? types.get(i - 1) : "unknown";
            String marker = marker(i, type, level);
            markers.add(marker);
            literals.add(marker != null ? "'" + marker + "'" : nonNull(s, type, i <= actualValues.size() ? actualValues.get(i - 1) : null));
          }
          s.execute("SAVEPOINT explain");
          String sql = "EXPLAIN (VERBOSE, FORMAT JSON) EXECUTE " + PREPARED + (literals.isEmpty() ? "" : "(" + String.join(", ", literals) + ")");
          try (ResultSet rs = s.executeQuery(sql)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
              sb.append(rs.getString(1));
            }
            plan = sb.toString();
          } catch (SQLException e) {
            last = e;
            s.execute("ROLLBACK TO SAVEPOINT explain");
          }
        }
        if (plan == null) {
          throw last;
        }
      }
      c.rollback();
      return new Explained(plan, markers);
    } catch (SQLException | RuntimeException e) {
      throw new QueryExecutor.ExecutionException(Errors.describe(e), e);
    } finally {
      // PREPARE はロールバックしても残るので、続けて推論できるように消す
      try {
        c.rollback();
        try (Statement s = c.createStatement()) {
          s.execute("DEALLOCATE " + PREPARED);
        }
      } catch (SQLException ignored) {
        // PREPARE の前に失敗したなど
      }
      try {
        c.rollback();
      } catch (SQLException ignored) {
        // 読むだけなので、ロールバックできなくても変更は残らない
      }
    }
  }

  // ---- リレーションの解析のための計画（往復を減らす） ----

  /**
   * 続けて何件も計画を作るときの、接続の状態。DB との往復を減らすため、次のようにする。
   * <ul>
   * <li>読み取り専用のトランザクションを、50 件か 30 秒ごとに区切って使い回す（件ごとに開いて閉じない。
   *     長く開けたままにすると、共有の DB で VACUUM が不要な行を片付けられなくなるので、区切る）</li>
   * <li>件ごとに SAVEPOINT を置き、失敗したら次の件の最初にそこへ戻す</li>
   * <li>PREPARE の名前を件ごとに変え、前の件の DEALLOCATE は次の件の送信にまとめる</li>
   * <li>続けて送れる文（SAVEPOINT、PREPARE、型の読み出し）は 1 回で送る</li>
   * <li>列挙型の最初の値は、型ごとに覚える</li>
   * </ul>
   */
  static final class Session {
    private static final int MAX_STATEMENTS = 50;
    private static final long MAX_MILLIS = 30_000;
    final Connection c;
    private boolean open;
    private long openedAt;
    private int count;
    /** 前の件で失敗し、トランザクションが失敗の状態のまま（次の送信の最初に SAVEPOINT へ戻す） */
    private boolean aborted;
    private String pendingDeallocate;
    private int seq;
    private final Map<String, String> enumFirst = new HashMap<>();

    Session(Connection c) {
      this.c = c;
    }

    /** 件の始めに呼ぶ。トランザクションが古ければ区切り、なければ開く */
    void begin() throws SQLException {
      if (open && (count >= MAX_STATEMENTS || System.currentTimeMillis() - openedAt > MAX_MILLIS)) {
        end();
      }
      if (!open) {
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
          s.execute("SET TRANSACTION READ ONLY");
        }
        open = true;
        openedAt = System.currentTimeMillis();
        count = 0;
      }
      count++;
    }

    /** トランザクションを閉じる（PREPARE はトランザクションの外なので、DEALLOCATE は次の送信にまとめたまま） */
    void end() throws SQLException {
      c.rollback();
      open = false;
      aborted = false;
    }

    /** 終わり。トランザクションを閉じ、残っている PREPARE を消す */
    void close() {
      try {
        end();
        if (pendingDeallocate != null) {
          try (Statement s = c.createStatement()) {
            s.execute("DEALLOCATE " + pendingDeallocate);
          }
          pendingDeallocate = null;
        }
        c.rollback();
      } catch (SQLException ignored) {
        // 接続を閉じれば消える
      }
    }
  }

  /** 何文かを 1 回で送り、結果の表ごとに、1 列目の値を返す（配列は文字列のリストにする） */
  private static List<List<Object>> runAll(Statement s, String sql) throws SQLException {
    List<List<Object>> out = new ArrayList<>();
    boolean isResult = s.execute(sql);
    while (true) {
      if (isResult) {
        List<Object> rows = new ArrayList<>();
        try (ResultSet rs = s.getResultSet()) {
          while (rs.next()) {
            Object v = rs.getObject(1);
            if (v instanceof Array) {
              List<String> list = new ArrayList<>();
              for (Object x : (Object[]) ((Array) v).getArray()) {
                list.add(String.valueOf(x));
              }
              v = list;
            }
            rows.add(v);
          }
        }
        out.add(rows);
      } else if (s.getUpdateCount() == -1) {
        break;
      }
      isResult = s.getMoreResults();
    }
    return out;
  }

  /**
   * リレーションの解析のための計画。パラメータを、毎回変わりうる式（random() を含む CASE）で包んで計画を作る。
   * 値と比べている列は、PostgreSQL が「等しい列どうし」をまとめるときに結合の条件を消してしまう
   * （h.user_id = $1 AND p.user_id = h.user_id が、p.user_id = $1 になる）ので、これを防ぐ。
   * 型の決め方・目印の値は {@link #explain} と同じ。計画を作った時間（ミリ秒）も返す。
   */
  static Explained explainForJoins(Session ses, String numberedSql, int count, List<String> fallbackTypes, List<String> actualValues)
      throws QueryExecutor.ExecutionException {
    Connection c = ses.c;
    String name = PREPARED + "_" + (++ses.seq);
    try (Statement s = c.createStatement()) {
      ses.begin();
      s.setQueryTimeout(30);
      // 前の件の後片付け（失敗の状態から戻す、前の PREPARE を消す）と、この件の SAVEPOINT・PREPARE・型の読み出しを 1 回で送る
      StringBuilder head = new StringBuilder();
      if (ses.aborted) {
        head.append("ROLLBACK TO SAVEPOINT st; ");
        ses.aborted = false;
      }
      if (ses.pendingDeallocate != null) {
        head.append("DEALLOCATE ").append(ses.pendingDeallocate).append("; ");
        ses.pendingDeallocate = null;
      }
      head.append("SAVEPOINT st; ");
      String typesSql = "; SELECT parameter_types FROM pg_prepared_statements WHERE name = '" + name + "'";
      List<String> explicit = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        explicit.add("unknown");
      }
      List<String> types = new ArrayList<>();
      int[] retry = {-1, 0, 0};
      for (int attempt = 0;; attempt++) {
        String prepare = attempt == 0 ? "PREPARE " + name + " AS " + numberedSql
            : "PREPARE " + name + "(" + String.join(", ", explicit) + ") AS " + numberedSql;
        try {
          List<List<Object>> r = runAll(s, (attempt == 0 ? head.toString() : "ROLLBACK TO SAVEPOINT st; ") + prepare + typesSql);
          ses.pendingDeallocate = name;
          List<Object> rows = r.isEmpty() ? new ArrayList<>() : r.get(r.size() - 1);
          if (!rows.isEmpty()) {
            for (Object t : (List<?>) rows.get(0)) {
              types.add(String.valueOf(t));
            }
          }
          break;
        } catch (SQLException e) {
          ses.aborted = true;
          if (!retryTypes(e, explicit, fallbackTypes, retry)) {
            throw e;
          }
          ses.aborted = false;
        }
      }
      // 目印が列の範囲を超えると計画を作れないので、小さな目印、数値の目印なしの順に試す（explain と同じ）
      SQLException last = null;
      for (int level = 0; level < 3; level++) {
        List<String> markers = new ArrayList<>();
        List<String> literals = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
          String type = i <= types.size() ? types.get(i - 1) : "unknown";
          String marker = marker(i, type, level);
          markers.add(marker);
          literals.add(marker != null ? "'" + marker + "'" : nonNull(ses, type, i <= actualValues.size() ? actualValues.get(i - 1) : null));
        }
        // $10 を $1 と取り違えないように、数字の続きまで見る
        Matcher m = Pattern.compile("\\$(\\d+)(?!\\d)").matcher(numberedSql);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
          int n = Integer.parseInt(m.group(1));
          String type = n <= types.size() ? types.get(n - 1) : "text";
          String lit = n <= literals.size() ? literals.get(n - 1) : "NULL";
          m.appendReplacement(sb, Matcher.quoteReplacement("(CASE WHEN random() < 2 THEN " + lit + "::"
              + ("unknown".equals(type) ? "text" : type) + " END)"));
        }
        m.appendTail(sb);
        // 失敗したら、SAVEPOINT へ戻すのを次の送信にまとめる（PREPARE はトランザクションの外なので残る）
        String sql = (ses.aborted ? "ROLLBACK TO SAVEPOINT st; " : "") + "EXPLAIN (VERBOSE, FORMAT JSON, SUMMARY) " + sb;
        try {
          List<List<Object>> r = runAll(s, sql);
          ses.aborted = false;
          StringBuilder plan = new StringBuilder();
          for (Object row : r.get(r.size() - 1)) {
            plan.append(row);
          }
          return new Explained(plan.toString(), markers, planningMillis(plan.toString()));
        } catch (SQLException e) {
          last = e;
          ses.aborted = true;
        }
      }
      throw last;
    } catch (SQLException | RuntimeException e) {
      ses.aborted = true;
      throw new QueryExecutor.ExecutionException(Errors.describe(e), e);
    }
  }

  /** 型を決められないパラメータに、順に試す型 */
  private static final String[] RETRY_TYPES = {
    "text", "integer", "bigint", "numeric", "boolean", "date", "timestamp without time zone", "time without time zone"};

  /**
   * PREPARE が失敗したとき、パラメータの型を書き換えて作り直すか。
   * <ul>
   * <li>42P18・42P08（型を決められない）: 言われたパラメータに、値から決めた型（なければ text）を書く</li>
   * <li>その型が合わない（42883 演算子・関数がない、42804 型が合わない、42725 一意でない、22P02・22007 値が読めない）:
   *     そのパラメータに、ほかの型を順に試す</li>
   * </ul>
   * メッセージは DB の言語によるので、パラメータの番号だけを読む。
   *
   * @param state [最後に型を書いたパラメータ（0 始まり。なければ -1）, 次に試す型の番号, 作り直した回数]
   */
  private static boolean retryTypes(SQLException e, List<String> explicit, List<String> fallbackTypes, int[] state) {
    if (++state[2] > explicit.size() * (RETRY_TYPES.length + 2) + 2) {
      return false;
    }
    String code = String.valueOf(e.getSQLState());
    if (code.equals("42P18") || code.equals("42P08")) {
      Matcher pm = Pattern.compile("\\$(\\d+)").matcher(String.valueOf(e.getMessage()));
      if (!pm.find()) {
        return false;
      }
      int n = Integer.parseInt(pm.group(1));
      if (n < 1 || n > explicit.size() || !"unknown".equals(explicit.get(n - 1))) {
        return false;
      }
      String t = n <= fallbackTypes.size() ? fallbackTypes.get(n - 1) : "unknown";
      explicit.set(n - 1, "unknown".equals(t) ? "text" : t);
      state[0] = n - 1;
      state[1] = 0;
      return true;
    }
    boolean mismatch = code.equals("42883") || code.equals("42804") || code.equals("42725") || code.equals("22P02") || code.equals("22007");
    if (!mismatch || state[0] < 0) {
      return false;
    }
    // 書いた型が合わなかった。まだ試していない型を書く
    String current = explicit.get(state[0]);
    while (state[1] < RETRY_TYPES.length) {
      String t = RETRY_TYPES[state[1]++];
      if (!t.equals(current)) {
        explicit.set(state[0], t);
        return true;
      }
    }
    return false;
  }

  /** EXPLAIN (SUMMARY) の Planning Time（ミリ秒）。なければ 0 */
  private static double planningMillis(String planJson) {
    try {
      JsonElement root = JsonParser.parseString(planJson);
      JsonObject top = root.isJsonArray() ? root.getAsJsonArray().get(0).getAsJsonObject() : root.getAsJsonObject();
      return top.has("Planning Time") ? top.get("Planning Time").getAsDouble() : 0;
    } catch (RuntimeException e) {
      return 0;
    }
  }

  /** {@link #nonNull(Statement, String, String)} と同じ。列挙型の最初の値は、型ごとに覚える */
  private static String nonNull(Session ses, String type, String actual) throws SQLException {
    String label = ses.enumFirst.get(type);
    if (label == null && !ses.enumFirst.containsKey(type)) {
      // 失敗の状態のトランザクションでは読めないので、先に戻す
      try (Statement s = ses.c.createStatement()) {
        if (ses.aborted) {
          s.execute("ROLLBACK TO SAVEPOINT st");
          ses.aborted = false;
        }
        try (ResultSet rs = s.executeQuery("SELECT e.enumlabel FROM pg_enum e WHERE e.enumtypid = '" + type.replace("'", "''")
            + "'::regtype ORDER BY e.enumsortorder LIMIT 1")) {
          label = rs.next() ? rs.getString(1) : null;
        }
      }
      ses.enumFirst.put(type, label);
    }
    if (label != null) {
      return "'" + label.replace("'", "''") + "'";
    }
    if (type.equalsIgnoreCase("boolean")) {
      return "true".equalsIgnoreCase(actual) || "false".equalsIgnoreCase(actual) ? actual : "true";
    }
    if (type.endsWith("[]")) {
      return actual != null && actual.startsWith("{") ? "'" + actual.replace("'", "''") + "'" : "'{}'";
    }
    return actual == null ? "NULL" : "'" + actual.replace("'", "''") + "'";
  }

  /**
   * 目印を作れないパラメータに入れる値。NULL と比べる条件は計画を作るときに「必ず偽」にまとめられ、ほかの条件も消えてしまうので、
   * 今の値、なければ列挙型の最初の値や true を入れる
   */
  private static String nonNull(Statement s, String type, String actual) throws SQLException {
    // 列挙型は、今の値が型に合わないこともある（仮の値など）ので、最初の値を使う
    try (ResultSet rs = s.executeQuery("SELECT e.enumlabel FROM pg_enum e WHERE e.enumtypid = '" + type.replace("'", "''")
        + "'::regtype ORDER BY e.enumsortorder LIMIT 1")) {
      if (rs.next()) {
        return "'" + rs.getString(1).replace("'", "''") + "'";
      }
    }
    if (type.equalsIgnoreCase("boolean")) {
      return "true".equalsIgnoreCase(actual) || "false".equalsIgnoreCase(actual) ? actual : "true";
    }
    if (type.endsWith("[]")) {
      // 配列の型: 今の値が配列の形でなければ空の配列
      return actual != null && actual.startsWith("{") ? "'" + actual.replace("'", "''") + "'" : "'{}'";
    }
    // 型を付けない文字のリテラルにして、パラメータの型に読み替えさせる
    return actual == null ? "NULL" : "'" + actual.replace("'", "''") + "'";
  }

  /**
   * 型ごとの目印の値（文字列のリテラルの中身）。見分けられる値を作れない型（真偽値など）は null。
   *
   * @param level 0: 大きな数（ほかの値と取り違えにくい）、1: 小さな数（範囲の狭い列や、日数として足す値でもあふれない）、2: 数値には付けない
   */
  static String marker(int n, String type, int level) {
    String t = type.toLowerCase(java.util.Locale.ROOT);
    boolean number = t.equals("smallint") || t.equals("integer") || t.equals("bigint") || t.startsWith("numeric") || t.equals("real")
        || t.equals("double precision");
    if (number) {
      if (level >= 2) {
        return null;
      }
      if (level == 1) {
        return String.valueOf(70 + n);
      }
      return String.valueOf(t.equals("smallint") ? 31000 + n : 987654300 + n);
    }
    if (t.startsWith("timestamp")) {
      return String.format("2999-12-31 %02d:%02d:%02d", n / 3600 % 24, n / 60 % 60, n % 60);
    }
    if (t.equals("date")) {
      return java.time.LocalDate.of(2999, 1, 1).plusDays(n).toString();
    }
    if (t.startsWith("time")) {
      // time without time zone・time with time zone（目印は秒以下で見分ける）
      return String.format("12:34:56.%06d", 100000 + n);
    }
    if (t.equals("uuid")) {
      return String.format("00000000-0000-4000-8000-%012d", 987654300L + n);
    }
    if (t.equals("text") || t.startsWith("character") || t.equals("unknown") || t.equals("name") || t.equals("citext")) {
      return "mbpg_p" + n + "_x";
    }
    return null;
  }

  /** 計画から、目印と比べられている列を探す */
  static Map<Integer, List<Source>> fromPlan(String planJson, List<String> markers) {
    JsonElement root = JsonParser.parseString(planJson);
    JsonObject top = root.isJsonArray() ? root.getAsJsonArray().get(0).getAsJsonObject() : root.getAsJsonObject();
    Map<String, String[]> aliases = new LinkedHashMap<>();
    List<String> conditions = new ArrayList<>();
    walk(top.getAsJsonObject("Plan"), aliases, conditions);

    Map<Integer, List<Source>> out = new LinkedHashMap<>();
    for (int i = 0; i < markers.size(); i++) {
      String marker = markers.get(i);
      if (marker == null) {
        continue;
      }
      List<Source> found = new ArrayList<>();
      Pattern p = Pattern.compile("(?<![0-9A-Za-z_])" + Pattern.quote(marker) + "(?![0-9A-Za-z_])");
      for (String cond : conditions) {
        Matcher m = p.matcher(cond);
        while (m.find()) {
          String[] where = new String[1];
          for (String[] col : columnsNear(cond, m.start(), where)) {
            String[] rel = aliases.get(col[0]);
            if (rel == null) {
              continue;
            }
            String text = where[0].replace("'" + marker + "'", "$" + (i + 1)).replaceAll(Pattern.quote(marker), "\\$" + (i + 1));
            Source s = new Source(rel[0], rel[1], col[1], text, isDirect(text, col));
            if (found.stream().noneMatch(f -> f.schema.equals(s.schema) && f.table.equals(s.table) && f.column.equals(s.column))) {
              found.add(s);
            }
          }
        }
      }
      if (!found.isEmpty()) {
        out.put(i + 1, found);
      }
    }
    return out;
  }

  /** 型の変換（::bigint など）を除いて、(列 演算子 $n) か ($n 演算子 列) か、列 = ANY (...) の形か */
  private static boolean isDirect(String condition, String[] col) {
    String c = condition.replaceAll("::[A-Za-z_][A-Za-z0-9_ ]*(\\[\\])?", "").replaceAll("\\((\\w+\\.\\w+)\\)", "$1");
    String colRe = "(?:\"?" + Pattern.quote(col[0]) + "\"?)\\.(?:\"?" + Pattern.quote(col[1]) + "\"?)";
    String op = "(?:=|<>|!=|<=|>=|<|>|~~\\*?|!~~\\*?)";
    // 目印は、時刻帯（+00）などを付けて出ることがある
    String param = "(?:'\\$\\d+[^']*'|\\$\\d+|ANY \\(.*\\))";
    return c.matches("\\(\\s*" + colRe + "\\s*" + op + "\\s*" + param + "\\s*\\)")
        || c.matches("\\(\\s*" + param + "\\s*" + op + "\\s*" + colRe + "\\s*\\)");
  }

  private static void walk(JsonObject node, Map<String, String[]> aliases, List<String> conditions) {
    if (node.has("Relation Name") && node.has("Alias")) {
      aliases.putIfAbsent(node.get("Alias").getAsString(),
          new String[] {node.has("Schema") ? node.get("Schema").getAsString() : "public", node.get("Relation Name").getAsString()});
    }
    for (String key : CONDITION_KEYS) {
      if (node.has(key) && node.get(key).isJsonPrimitive()) {
        conditions.add(node.get(key).getAsString());
      }
    }
    JsonElement plans = node.get("Plans");
    if (plans != null && plans.isJsonArray()) {
      for (JsonElement child : (JsonArray) plans) {
        walk(child.getAsJsonObject(), aliases, conditions);
      }
    }
  }

  /** 位置を含む、いちばん内側の括弧の中身 */
  private static String group(String s, int pos) {
    int open = -1;
    int depth = 0;
    for (int i = pos; i >= 0; i--) {
      char ch = s.charAt(i);
      if (ch == ')') {
        depth++;
      } else if (ch == '(') {
        if (depth == 0) {
          open = i;
          break;
        }
        depth--;
      }
    }
    if (open < 0) {
      return s;
    }
    depth = 0;
    for (int i = open; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == '(') {
        depth++;
      } else if (ch == ')' && --depth == 0) {
        return s.substring(open, i + 1);
      }
    }
    return s.substring(open);
  }

  /**
   * 目印の近くの列（比べている相手）。目印を含む括弧の中から探し、なければ 1 つ外側の括弧へ広げる
   * （ANY ('{...}'::bigint[]) や ::date のような型変換で、括弧が重なるため）。
   */
  private static List<String[]> columnsNear(String cond, int pos, String[] where) {
    String inner = group(cond, pos);
    List<String[]> cols = columns(inner);
    int start = cond.indexOf(inner);
    for (int tries = 0; cols.isEmpty() && tries < 3 && start > 0; tries++) {
      inner = group(cond, start - 1);
      cols = columns(inner);
      start = cond.indexOf(inner);
    }
    where[0] = inner;
    return cols;
  }

  private static List<String[]> columns(String text) {
    List<String[]> out = new ArrayList<>();
    // 文字列のリテラルの中は除く
    Matcher m = COLUMN.matcher(text.replaceAll("'(?:[^']|'')*'", "''"));
    while (m.find()) {
      String alias = m.group(1) != null ? m.group(1).replace("\"\"", "\"") : m.group(2);
      String col = m.group(3) != null ? m.group(3).replace("\"\"", "\"") : m.group(4);
      out.add(new String[] {alias, col});
    }
    return out;
  }

  // ---- if をすべて通す仮の値 ----

  /**
   * 推論のための仮の値。今の値はそのまま使い、未設定の項目には、XML から推し量った型の仮の値を入れる
   * （if test="x != null" などをすべて通し、条件を SQL に出すため）。値は if を通すためだけに使い、列を探すときは目印に置き換える。
   *
   * @param rawGuess ${} の項目（パス）の仮の値。推し量れなければ null（値を入れないので、SQL が壊れることがある）
   */
  static Object probe(ParamNode node, Object actual, java.util.function.Function<String, String> rawGuess) {
    return probe(node, actual, "", rawGuess, path -> false, false);
  }

  /**
   * @param collection XML の中で .size() や .isEmpty() を呼んでいる項目（パス）か。そうなら、要素が 1 つのリストを入れる
   * @param minimal if を通すための値を入れない（今の値と、${} と、リストだけ）。if をすべて通すと SQL が壊れるときに使う
   */
  static Object probe(ParamNode node, Object actual, java.util.function.Function<String, String> rawGuess,
      java.util.function.Predicate<String> collection, boolean minimal) {
    return probe(node, actual, "", rawGuess, collection, minimal);
  }

  /** XML の中で .size() や .isEmpty() を呼んでいる項目（cond.list.size() なら cond.list） */
  static java.util.Set<String> collectionPaths(String script) {
    java.util.Set<String> out = new java.util.HashSet<>();
    Matcher m = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*\\.\\s*(?:size|isEmpty)\\s*\\(\\s*\\)").matcher(script);
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Object probe(ParamNode node, Object actual, String path, java.util.function.Function<String, String> rawGuess,
      java.util.function.Predicate<String> collection, boolean minimal) {
    if (node.getUsages().contains(ParamNode.Usage.ITERATE)) {
      if (actual instanceof java.util.Collection && !((java.util.Collection<?>) actual).isEmpty()) {
        return actual;
      }
      List<Object> list = new ArrayList<>();
      list.add(node.getElement() == null ? "1" : probe(node.getElement(), null, path + "[0]", rawGuess, collection, false));
      return list;
    }
    if (!node.getProperties().isEmpty()) {
      Map<String, Object> out = new LinkedHashMap<>();
      if (actual instanceof Map) {
        out.putAll((Map<String, Object>) actual);
      }
      for (ParamNode child : node.getProperties().values()) {
        Object v = probe(child, out.get(child.getName()), path.isEmpty() ? child.getName() : path + "." + child.getName(), rawGuess,
            collection, minimal);
        if (v != null) {
          out.put(child.getName(), v);
        }
      }
      return out;
    }
    if (actual != null) {
      return actual;
    }
    if (node.getUsages().contains(ParamNode.Usage.RAW)) {
      return rawGuess.apply(path);
    }
    if (collection.test(path)) {
      // list != null and list.size() > 0 などを通す
      List<Object> list = new ArrayList<>();
      list.add("1");
      return list;
    }
    if (minimal) {
      return null;
    }
    if (node.getTypeHints().contains(ParamNode.TypeHint.BOOLEAN)) {
      return Boolean.TRUE;
    }
    if (node.getTypeHints().contains(ParamNode.TypeHint.TIMESTAMP)) {
      return java.sql.Timestamp.valueOf("2000-01-01 00:00:00");
    }
    if (node.getTypeHints().contains(ParamNode.TypeHint.DATE)) {
      return java.sql.Date.valueOf("2000-01-01");
    }
    if (node.getTypeHints().contains(ParamNode.TypeHint.NUMBER)) {
      return 1;
    }
    // 文字か、型が分からなければ、数としても文字としても通る文字列 "1" にする
    // （!= null、!= ''、> 0、== 'empty' などの判定で、数と文字の取り違えで失敗しないように）
    return "1";
  }

  private static final Pattern TABLE_BEFORE = Pattern.compile("(?i)\\b(from|join|update|into|table|using)\\s*$");
  /** 条件の始まり（ここには列や式が入る） */
  private static final Pattern COLUMN_BEFORE = Pattern.compile("(?i)(\\b(where|and|or|on|not|having)|\\()\\s*$");
  /** 比べる演算子が続く（左側には列や式が入る） */
  private static final Pattern COMPARE_AFTER = Pattern.compile("(?i)^\\s*(=|<|>|!=|\\b(in|like|ilike|is|between)\\b)");
  /** ORDER BY の直後や、並べる項目の区切りの直後（ここには項目が入る） */
  private static final Pattern AFTER_BY_OR_COMMA = Pattern.compile("(?i)(\\bby|,)\\s*$");
  private static final Pattern ORDER_ITEM_BEFORE = Pattern.compile("(?is)\\border\\s+by\\b(?:(?!\\b(?:limit|offset|fetch|for|union|intersect|except)\\b)[^;])*[\\w)\"]\\s+$");

  /**
   * ${name} の前後から、SQL が壊れない仮の値を推し量る。推し量れなければ null。
   * <ul>
   * <li>FROM・JOIN・UPDATE・INTO などの後（表の名前が要る）: null</li>
   * <li>${name}. の形（スキーマや表の名前が要る）: public</li>
   * <li>ORDER BY の中で、列などの直後（並べる向きが要る）: ASC</li>
   * <li>それ以外（ORDER BY の直後、LIMIT、比較の右側、SELECT の列など）: 1</li>
   * </ul>
   * 何か所かで使われていれば、すべてで壊れない値にする（どこかで推し量れなければ null）。
   */
  static String rawGuess(String script, String name) {
    return rawGuess(script, name, tables -> "public");
  }

  private static final String SCHEMA = "\u0000schema";
  private static final Pattern TABLE_AFTER_DOT = Pattern.compile("^\\.\\s*(\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)");

  /**
   * @param schemaOf ${name}.表 の形のとき、その表（何か所かあればすべて）を持つスキーマを返す。分からなければ null
   */
  static String rawGuess(String script, String name, java.util.function.Function<List<String>, String> schemaOf) {
    List<String> tables = new ArrayList<>();
    Matcher m = Pattern.compile("\\$\\{\\s*" + Pattern.quote(name) + "\\s*(?:,[^}]*)?\\}").matcher(script);
    String guess = null;
    boolean found = false;
    while (m.find()) {
      found = true;
      String before = script.substring(Math.max(0, m.start() - 300), m.start());
      String rest = script.substring(m.end(), Math.min(script.length(), m.end() + 40));
      String after = rest.isEmpty() ? "" : rest.substring(0, 1);
      String g;
      if (after.equals(".")) {
        // スキーマの名前（FROM ${schema}.orders など）。表を持つスキーマは DB に聞く
        Matcher t = TABLE_AFTER_DOT.matcher(rest);
        if (t.find()) {
          String table = t.group(1);
          tables.add(table.startsWith("\"") ? table.substring(1, table.length() - 1).replace("\"\"", "\"") : table.toLowerCase(java.util.Locale.ROOT));
        }
        g = SCHEMA;
      } else if (TABLE_BEFORE.matcher(before).find() || before.endsWith(".")) {
        // 表の名前（FROM ${table}）や、列の名前（alias.${column}）が要る
        return null;
      } else if (COLUMN_BEFORE.matcher(before).find() || COMPARE_AFTER.matcher(rest).find()) {
        // 列や式が要る（WHERE ${column} = #{v} など）。1 にすると比べる相手が分からなくなるうえ、計画ごと消えることがある
        return null;
      } else if (ORDER_ITEM_BEFORE.matcher(before).find() && !AFTER_BY_OR_COMMA.matcher(before).find()) {
        g = "ASC";
      } else {
        g = "1";
      }
      if (guess != null && !guess.equals(g)) {
        return null;
      }
      guess = g;
    }
    if (SCHEMA.equals(guess)) {
      return tables.isEmpty() ? null : schemaOf.apply(tables);
    }
    return found ? guess : "1";
  }

  /**
   * 表をいちばん多く持つスキーマ（同じ数なら search_path にあるもの、名前の順）。どれも持たなければ null。
   * テナントごとにスキーマを分けている（tenant0001、tenant0002 …）なら、そのうちの 1 つになる。
   */
  static String schemaOf(Connection c, List<String> tables) {
    try (PreparedStatement ps = c.prepareStatement("SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
        + " WHERE c.relname = ANY (?) AND c.relkind IN ('r', 'v', 'm', 'p', 'f')"
        + " GROUP BY n.nspname ORDER BY count(DISTINCT c.relname) DESC, bool_or(n.nspname = ANY (current_schemas(false))) DESC, n.nspname"
        + " LIMIT 1")) {
      ps.setArray(1, c.createArrayOf("text", tables.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      return null;
    }
  }

  /** ${} の項目のうち、値が入っていない項目（パス） */
  static List<String> rawPaths(ParamNode node, Object actual) {
    List<String> out = new ArrayList<>();
    rawPaths(node, actual, "", out);
    return out;
  }

  @SuppressWarnings("unchecked")
  private static void rawPaths(ParamNode node, Object actual, String path, List<String> out) {
    for (ParamNode child : node.getProperties().values()) {
      String p = path.isEmpty() ? child.getName() : path + "." + child.getName();
      Object v = actual instanceof Map ? ((Map<String, Object>) actual).get(child.getName()) : null;
      if (child.getUsages().contains(ParamNode.Usage.RAW) && v == null) {
        out.add(p);
      }
      if (!child.getProperties().isEmpty()) {
        rawPaths(child, v, p, out);
      }
    }
  }

  // ---- 値の候補 ----

  /** 列の値の候補。多く出てくる順 */
  /**
   * @param scan 統計情報に「よく出る値」がないときに、表を読んで数えるか（読まなければ候補なしで返す。共有の DB に負担をかけないように、押したときだけ）
   */
  static Map<String, Object> values(String schema, String table, String column, boolean scan, QueryExecutor.ConnectionFactory connections)
      throws QueryExecutor.ExecutionException {
    Connection c;
    try {
      c = connections.open();
    } catch (SQLException | RuntimeException e) {
      throw new QueryExecutor.ExecutionException("接続できません\n" + Errors.describe(e), e);
    }
    try {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("SET TRANSACTION READ ONLY");
      }
      Map<String, Object> body = new LinkedHashMap<>();
      String type;
      double reltuples;
      boolean indexed;
      // 列から始まるインデックスがあるか（あれば、入力中の値の行を自動で探しても軽い）
      try (PreparedStatement ps = c.prepareStatement("SELECT format_type(a.atttypid, a.atttypmod), c.reltuples,"
          + " EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.oid AND i.indkey[0] = a.attnum)"
          + " FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = ?::name AND c.relname = ?::name AND a.attname = ?::name AND a.attnum > 0 AND NOT a.attisdropped")) {
        ps.setString(1, schema);
        ps.setString(2, table);
        ps.setString(3, column);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            throw new IllegalArgumentException("列がありません: " + schema + "." + table + "." + column);
          }
          type = rs.getString(1);
          reltuples = rs.getDouble(2);
          indexed = rs.getBoolean(3);
        }
      }
      body.put("type", type);
      body.put("indexed", indexed);
      body.put("estimatedRows", Math.max(0, Math.round(reltuples)));
      List<Map<String, Object>> values = new ArrayList<>();
      // まず統計情報の「よく出る値」（表を読まない）
      try (PreparedStatement ps = c.prepareStatement("SELECT most_common_vals::text::text[], most_common_freqs FROM pg_stats"
          + " WHERE schemaname = ?::name AND tablename = ?::name AND attname = ?::name")) {
        ps.setString(1, schema);
        ps.setString(2, table);
        ps.setString(3, column);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next() && rs.getArray(1) != null) {
            Object[] vals = (Object[]) rs.getArray(1).getArray();
            Object[] freqs = (Object[]) rs.getArray(2).getArray();
            for (int i = 0; i < vals.length && i < 30; i++) {
              Map<String, Object> v = new LinkedHashMap<>();
              v.put("value", vals[i] == null ? null : vals[i].toString());
              v.put("count", Math.round(((Number) freqs[i]).doubleValue() * Math.max(reltuples, 0)));
              values.add(v);
            }
          }
        }
      }
      String method = "stats";
      if (values.isEmpty() && !scan) {
        method = "none";
      } else if (values.isEmpty()) {
        // 統計情報に「よく出る値」がない（ほぼ一意の列など）: 表を読んで数える。大きな表は一部だけを読む
        String q = quote(schema) + "." + quote(table);
        String col = quote(column);
        boolean sample = reltuples > 200000;
        String from = sample ? q + " TABLESAMPLE SYSTEM (" + Math.max(0.01, Math.min(100, 200000 / reltuples * 100)) + ")" : q;
        method = sample ? "sample" : "scan";
        try (Statement s = c.createStatement()) {
          s.setQueryTimeout(10);
          try (ResultSet rs = s.executeQuery("SELECT v, count(*) FROM (SELECT " + col + "::text AS v FROM " + from
              + " WHERE " + col + " IS NOT NULL LIMIT 200000) x GROUP BY v ORDER BY 2 DESC, 1 LIMIT 30")) {
            while (rs.next()) {
              Map<String, Object> v = new LinkedHashMap<>();
              v.put("value", rs.getString(1));
              v.put("count", rs.getLong(2));
              values.add(v);
            }
          }
        }
      }
      body.put("method", method);
      body.put("values", values);
      c.rollback();
      return body;
    } catch (SQLException | RuntimeException e) {
      try {
        c.rollback();
      } catch (SQLException ignored) {
        // 読むだけ
      }
      throw new QueryExecutor.ExecutionException(Errors.describe(e), e);
    } finally {
      try {
        c.close();
      } catch (SQLException ignored) {
        // 閉じられなくても結果は変わらない
      }
    }
  }

  /** 行の表示で、1 つの値の長さの上限（長い JSON などを切る） */
  private static final int MAX_CELL = 200;

  /**
   * 候補の値ごとの行（値ごとに 1 行）と、入力中の値に当たる行。表のすべての列を返す。
   *
   * @param values 候補の値（文字列）
   * @param current 入力中の値（文字列。なければ null）
   */
  static Map<String, Object> rows(String schema, String table, String column, List<String> values, String current,
      QueryExecutor.ConnectionFactory connections) throws QueryExecutor.ExecutionException {
    Connection c;
    try {
      c = connections.open();
    } catch (SQLException | RuntimeException e) {
      throw new QueryExecutor.ExecutionException("接続できません\n" + Errors.describe(e), e);
    }
    try {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("SET TRANSACTION READ ONLY");
      }
      // 列の型（比べるときにその型にして、インデックスを使えるようにする）
      String type;
      try (PreparedStatement ps = c.prepareStatement("SELECT format_type(a.atttypid, a.atttypmod)"
          + " FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = ?::name AND c.relname = ?::name AND a.attname = ?::name AND a.attnum > 0 AND NOT a.attisdropped")) {
        ps.setString(1, schema);
        ps.setString(2, table);
        ps.setString(3, column);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            throw new IllegalArgumentException("列がありません: " + schema + "." + table + "." + column);
          }
          type = rs.getString(1);
        }
      }
      String q = quote(schema) + "." + quote(table);
      String col = quote(column);
      Map<String, Object> body = new LinkedHashMap<>();
      List<List<String>> candidateRows = new ArrayList<>();
      List<String> columns = new ArrayList<>();
      if (!values.isEmpty()) {
        // 値ごとに LIMIT 1 で取る（1 行見つかれば止まるので、行の多い値やインデックスのない列でも、その値の行をすべて読まない）
        try (PreparedStatement ps = c.prepareStatement("SELECT r.* FROM unnest((?::text[])::" + type + "[]) WITH ORDINALITY AS v(val, i)"
            + " CROSS JOIN LATERAL (SELECT * FROM " + q + " WHERE " + col + " = v.val LIMIT 1) r ORDER BY v.i")) {
          ps.setQueryTimeout(10);
          ps.setArray(1, c.createArrayOf("text", values.toArray()));
          read(ps, columns, candidateRows, values.size());
        }
      }
      List<List<String>> currentRows = new ArrayList<>();
      String currentError = null;
      if (current != null && !current.isEmpty()) {
        try (Statement s = c.createStatement()) {
          s.execute("SAVEPOINT current_rows");
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + q + " WHERE " + col + " = (?::text)::" + type + " LIMIT 5")) {
          ps.setQueryTimeout(10);
          ps.setString(1, current);
          read(ps, columns, currentRows, 5);
        } catch (SQLException e) {
          // 入力中の値が列の型として読めないなど
          currentError = e.getMessage();
          try (Statement s = c.createStatement()) {
            s.execute("ROLLBACK TO SAVEPOINT current_rows");
          }
        }
      }
      body.put("columns", columns);
      body.put("rows", candidateRows);
      body.put("currentRows", currentRows);
      body.put("currentError", currentError);
      c.rollback();
      return body;
    } catch (SQLException | RuntimeException e) {
      try {
        c.rollback();
      } catch (SQLException ignored) {
        // 読むだけ
      }
      throw new QueryExecutor.ExecutionException(Errors.describe(e), e);
    } finally {
      try {
        c.close();
      } catch (SQLException ignored) {
        // 閉じられなくても結果は変わらない
      }
    }
  }

  private static void read(PreparedStatement ps, List<String> columns, List<List<String>> out, int max) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      java.sql.ResultSetMetaData meta = rs.getMetaData();
      if (columns.isEmpty()) {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          columns.add(meta.getColumnLabel(i));
        }
      }
      while (rs.next() && out.size() < max) {
        List<String> row = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          String v = rs.getString(i);
          row.add(v != null && v.length() > MAX_CELL ? v.substring(0, MAX_CELL) + "…" : v);
        }
        out.add(row);
      }
    }
  }

  private static String quote(String ident) {
    return "\"" + ident.replace("\"", "\"\"") + "\"";
  }
}
