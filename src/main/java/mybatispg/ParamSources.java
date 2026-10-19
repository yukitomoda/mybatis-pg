package mybatispg;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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

    Source(String schema, String table, String column, String condition, boolean direct) {
      this.schema = schema;
      this.table = table;
      this.column = column;
      this.condition = condition;
      this.direct = direct;
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
    Map<Integer, List<Source>> out = new LinkedHashMap<>();
    if (count == 0) {
      return out;
    }
    Connection c;
    try {
      c = connections.open();
    } catch (SQLException | RuntimeException e) {
      throw new QueryExecutor.ExecutionException("接続できません\n" + Errors.describe(e), e);
    }
    try {
      c.setAutoCommit(false);
      String plan;
      List<String> markers = new ArrayList<>();
      try (Statement s = c.createStatement()) {
        s.execute("SET TRANSACTION READ ONLY");
        s.setQueryTimeout(30);
        // 型は書かず、PostgreSQL に推し量らせる（比べる列の型になる）。決められなければ（$1 IS NULL など）、値から決めた型で作り直す
        try {
          s.execute("SAVEPOINT infer");
          s.execute("PREPARE " + PREPARED + " AS " + numberedSql);
        } catch (SQLException e) {
          s.execute("ROLLBACK TO SAVEPOINT infer");
          // 型を決められない（unknown のまま）パラメータは text にする
          List<String> explicit = new ArrayList<>();
          for (String t : fallbackTypes) {
            explicit.add("unknown".equals(t) ? "text" : t);
          }
          s.execute("PREPARE " + PREPARED + "(" + String.join(", ", explicit) + ") AS " + numberedSql);
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
          try (ResultSet rs = s.executeQuery("EXPLAIN (VERBOSE, FORMAT JSON) EXECUTE " + PREPARED + "(" + String.join(", ", literals) + ")")) {
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
      return fromPlan(plan, markers);
    } catch (SQLException | RuntimeException e) {
      try {
        c.rollback();
      } catch (SQLException ignored) {
        // 読むだけなので、ロールバックできなくても変更は残らない
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
    return probe(node, actual, "", rawGuess);
  }

  @SuppressWarnings("unchecked")
  private static Object probe(ParamNode node, Object actual, String path, java.util.function.Function<String, String> rawGuess) {
    if (node.getUsages().contains(ParamNode.Usage.ITERATE)) {
      if (actual instanceof java.util.Collection && !((java.util.Collection<?>) actual).isEmpty()) {
        return actual;
      }
      List<Object> list = new ArrayList<>();
      list.add(node.getElement() == null ? 1 : probe(node.getElement(), null, path + "[0]", rawGuess));
      return list;
    }
    if (!node.getProperties().isEmpty()) {
      Map<String, Object> out = new LinkedHashMap<>();
      if (actual instanceof Map) {
        out.putAll((Map<String, Object>) actual);
      }
      for (ParamNode child : node.getProperties().values()) {
        Object v = probe(child, out.get(child.getName()), path.isEmpty() ? child.getName() : path + "." + child.getName(), rawGuess);
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
    if (node.getTypeHints().contains(ParamNode.TypeHint.BOOLEAN)) {
      return Boolean.TRUE;
    }
    if (node.getTypeHints().contains(ParamNode.TypeHint.TIMESTAMP)) {
      return java.sql.Timestamp.valueOf("2000-01-01 00:00:00");
    }
    if (node.getTypeHints().contains(ParamNode.TypeHint.DATE)) {
      return java.sql.Date.valueOf("2000-01-01");
    }
    if (node.getTypeHints().contains(ParamNode.TypeHint.STRING)) {
      return "x";
    }
    // 型が分からなければ数にする（!= null、> 0、!= '' の判定を通る）
    return 1;
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
        // スキーマの名前（FROM ${schema}.orders など）
        g = "public";
      } else if (TABLE_BEFORE.matcher(before).find()) {
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
    return found ? guess : "1";
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
  static Map<String, Object> values(String schema, String table, String column, QueryExecutor.ConnectionFactory connections)
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
      try (PreparedStatement ps = c.prepareStatement("SELECT format_type(a.atttypid, a.atttypmod), c.reltuples"
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
        }
      }
      body.put("type", type);
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
      if (values.isEmpty()) {
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
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT ON (" + col + ") * FROM " + q + " WHERE " + col
            + " = ANY ((?::text[])::" + type + "[]) ORDER BY " + col)) {
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
