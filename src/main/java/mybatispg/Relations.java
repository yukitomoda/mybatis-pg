package mybatispg;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * リレーション（試作）。外部キーがなくても、statement の結合の条件（a.x = b.y）を集めて、テーブルどうしのつながりを推し量る。
 *
 * <p>statement を「if をすべて通す仮の値」で組み立てて EXPLAIN（VERBOSE、ANALYZE なし）し、計画の結合の条件
 * （Hash Cond・Merge Cond・Join Filter・Index Cond など）から、別の表の列どうしを = で比べている組を取り出す。
 * 別名・サブクエリ・ビューは PostgreSQL が実際の表に直すので、SQL を構文解析しない。
 *
 * <p>どちらが源流（親）かは、一意かどうかで決める。片方だけが 1 列で一意（主キー・UNIQUE）なら、その側が親。
 * どちらも一意でなければ兄弟（同じ親を指している）として扱い、兄弟をたどって親を探す。
 *
 * <p>解析の結果は、プロジェクトと接続先の組ごとに 1 つの JSON に保存する（解析し直すときは、SQL が変わった statement だけを解析する）。
 * <pre>
 * {"version": 1, "analyzer": 解析の仕方の版, "project": "/path/to/project", "connectionId": "...", "updatedAt": ミリ秒,
 *  "statements": {"statement の id": {"hash": SQL の SHA-256, "joins": [{"a": "schema.table.column", "b": "...", "on": "Hash Cond"}],
 *                                    "error": 解析できなかった理由（なければなし）}},
 *  "keys": {"schema.table.column": 一意の順位（1 列の主キー 2、1 列の UNIQUE 3、複数列なら列の数 × 2 + …。一意でなければなし）}}
 * </pre>
 */
final class Relations {

  /** 解析の仕方の版。変えたら、保存してある結果を使わずに解析し直す */
  static final int ANALYZER = 4;
  /** 結合の条件を探す計画の項目 */
  private static final String[] JOIN_KEYS = {"Hash Cond", "Merge Cond", "Join Filter", "Index Cond", "Recheck Cond", "Filter"};
  private static final String IDENT = "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)";
  /** alias.column = alias.column（型の変換と、列を囲む括弧は先に除く） */
  private static final Pattern EQUALS = Pattern.compile(
      "(?<![\\w.\"$])(" + IDENT + ")\\.(" + IDENT + ")\\s*=\\s*(" + IDENT + ")\\.(" + IDENT + ")(?![\\w.\"$(])");
  private static final Pattern CAST = Pattern.compile("::(?:\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_ ]*)(?:\\(\\d+(?:,\\d+)?\\))?(?:\\[\\])?");
  /** 日付・時刻に直している列（o.ordered_at::date = h.created_at::date は値の対応ではないので使わない） */
  private static final Pattern TEMPORAL_CAST = Pattern.compile(
      "(" + IDENT + ")\\.(" + IDENT + ")\\)?::(?:date|time(?:stamp)?(?: with(?:out)? time zone)?)\\b");
  private static final Pattern WRAPPED = Pattern.compile("\\((" + IDENT + "\\." + IDENT + ")\\)");
  /** 1 列で一意（主キー・UNIQUE）とみなす順位の上限 */
  private static final int SINGLE_UNIQUE = 3;
  /** 源流を探すとき、兄弟をたどる段数の上限 */
  private static final int MAX_HOPS = 3;

  private Relations() {
  }

  /** 列（schema.table.column） */
  static String key(String schema, String table, String column) {
    return schema + "." + table + "." + column;
  }

  /** schema.table.column を分ける（表や列の名前に . が入ることは考えない） */
  static String[] split(String key) {
    int a = key.indexOf('.');
    int b = key.indexOf('.', a + 1);
    return new String[] {key.substring(0, a), key.substring(a + 1, b), key.substring(b + 1)};
  }

  // ---- 計画から結合の条件を取り出す ----

  /** 計画の中の、別の表（または同じ表の別の列）の列どうしを = で比べている組。[a, b, 計画の項目] */
  static List<String[]> joins(String planJson) {
    JsonElement root = JsonParser.parseString(planJson);
    JsonObject top = root.isJsonArray() ? root.getAsJsonArray().get(0).getAsJsonObject() : root.getAsJsonObject();
    Map<String, String[]> aliases = new HashMap<>();
    List<String[]> conditions = new ArrayList<>();
    walk(top.getAsJsonObject("Plan"), aliases, conditions);
    List<String[]> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String[] kc : conditions) {
      Set<String> temporal = new HashSet<>();
      Matcher tm = TEMPORAL_CAST.matcher(kc[1]);
      while (tm.find()) {
        temporal.add(unquote(tm.group(1)) + "." + unquote(tm.group(2)));
      }
      String cond = CAST.matcher(kc[1]).replaceAll("");
      // ((o.customer_id) = c.id) のように列を括弧で囲むことがある
      String prev;
      do {
        prev = cond;
        cond = WRAPPED.matcher(cond).replaceAll("$1");
      } while (!cond.equals(prev));
      Matcher m = EQUALS.matcher(cond);
      while (m.find()) {
        String[] l = aliases.get(unquote(m.group(1)));
        String[] r = aliases.get(unquote(m.group(3)));
        if (temporal.contains(unquote(m.group(1)) + "." + unquote(m.group(2)))
            || temporal.contains(unquote(m.group(3)) + "." + unquote(m.group(4)))) {
          continue;
        }
        if (l == null || r == null) {
          // CTE やサブクエリの結果の列は、実際の表の列ではないので使わない
          continue;
        }
        String a = key(l[0], l[1], unquote(m.group(2)));
        String b = key(r[0], r[1], unquote(m.group(4)));
        if (a.equals(b)) {
          continue;
        }
        String pair = a.compareTo(b) < 0 ? a + "\u0000" + b : b + "\u0000" + a;
        if (seen.add(pair)) {
          out.add(new String[] {a, b, kc[0]});
        }
      }
    }
    return out;
  }

  private static String unquote(String ident) {
    return ident.startsWith("\"") ? ident.substring(1, ident.length() - 1).replace("\"\"", "\"") : ident;
  }

  private static void walk(JsonObject node, Map<String, String[]> aliases, List<String[]> conditions) {
    if (node.has("Relation Name") && node.has("Alias")) {
      aliases.putIfAbsent(node.get("Alias").getAsString(),
          new String[] {node.has("Schema") ? node.get("Schema").getAsString() : "public", node.get("Relation Name").getAsString()});
    }
    for (String k : JOIN_KEYS) {
      if (node.has(k) && node.get(k).isJsonPrimitive()) {
        conditions.add(new String[] {k, node.get(k).getAsString()});
      }
    }
    JsonElement plans = node.get("Plans");
    if (plans != null && plans.isJsonArray()) {
      for (JsonElement child : (JsonArray) plans) {
        walk(child.getAsJsonObject(), aliases, conditions);
      }
    }
  }

  // ---- 保存 ----

  /** 1 つの statement の解析の結果 */
  static final class Entry {
    final String hash;
    final List<String[]> joins;
    final String error;
    /**
     * パラメータ（項目のパス。cond.customerId など）ごとの、比べている列（schema.table.column）。
     * パラメータの名前が同じなら同じものを指すとみなして、値の候補に使う。前の版の結果で、記録していなければ null（解析し直す）
     */
    final Map<String, List<String>> params;

    Entry(String hash, List<String[]> joins, String error) {
      this(hash, joins, error, new LinkedHashMap<String, List<String>>());
    }

    Entry(String hash, List<String[]> joins, String error, Map<String, List<String>> params) {
      this.hash = hash;
      this.joins = joins;
      this.error = error;
      this.params = params;
    }
  }

  /** プロジェクトと接続先の組ごとの、解析の結果 */
  static final class Store {
    /** null ならファイルに保存しない（テスト用） */
    private final Path file;
    private final String project;
    private final String connectionId;
    final Map<String, Entry> statements = new TreeMap<>();
    final Map<String, Integer> keys = new TreeMap<>();
    long updatedAt;
    /** 最後の解析を最後まで終えたか（一時停止したら false） */
    boolean complete;

    private Store(Path file, String project, String connectionId) {
      this.file = file;
      this.project = project;
      this.connectionId = connectionId;
    }

    /**
     * @param root 保存先のディレクトリ（~/.mybatis-pg/relations）。null なら保存しない
     */
    static Store open(Path root, String project, String connectionId) throws IOException {
      Path file = root == null ? null
          : root.resolve(hash(project).substring(0, 16)).resolve(hash(connectionId).substring(0, 16) + ".json");
      Store s = new Store(file, project, connectionId);
      if (file != null && Files.isRegularFile(file)) {
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
          s.read(JsonParser.parseReader(in).getAsJsonObject());
        } catch (JsonParseException | IllegalStateException | NullPointerException e) {
          // 壊れていれば、解析し直す
          s.statements.clear();
          s.keys.clear();
        }
      }
      return s;
    }

    private void read(JsonObject o) {
      if (!o.has("analyzer") || o.get("analyzer").getAsInt() != ANALYZER) {
        // 解析の仕方が変わったので、前の結果は使わない
        return;
      }
      updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsLong() : 0;
      complete = !o.has("complete") || o.get("complete").getAsBoolean();
      for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("statements").entrySet()) {
        JsonObject st = e.getValue().getAsJsonObject();
        List<String[]> joins = new ArrayList<>();
        for (JsonElement j : st.getAsJsonArray("joins")) {
          JsonObject jo = j.getAsJsonObject();
          joins.add(new String[] {jo.get("a").getAsString(), jo.get("b").getAsString(), jo.get("on").getAsString()});
        }
        Map<String, List<String>> params = null;
        if (st.has("params")) {
          params = new LinkedHashMap<>();
          for (Map.Entry<String, JsonElement> pe : st.getAsJsonObject("params").entrySet()) {
            List<String> cols = new ArrayList<>();
            for (JsonElement c : pe.getValue().getAsJsonArray()) {
              cols.add(c.getAsString());
            }
            params.put(pe.getKey(), cols);
          }
        }
        statements.put(e.getKey(), new Entry(st.get("hash").getAsString(), joins, st.has("error") ? st.get("error").getAsString() : null,
            params));
      }
      if (o.has("keys")) {
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("keys").entrySet()) {
          keys.put(e.getKey(), e.getValue().getAsInt());
        }
      }
    }

    synchronized void save() throws IOException {
      updatedAt = System.currentTimeMillis();
      if (file == null) {
        return;
      }
      JsonObject o = new JsonObject();
      o.addProperty("version", 1);
      o.addProperty("analyzer", ANALYZER);
      o.addProperty("project", project);
      o.addProperty("connectionId", connectionId);
      o.addProperty("updatedAt", updatedAt);
      o.addProperty("complete", complete);
      JsonObject sts = new JsonObject();
      for (Map.Entry<String, Entry> e : statements.entrySet()) {
        JsonObject st = new JsonObject();
        st.addProperty("hash", e.getValue().hash);
        JsonArray joins = new JsonArray();
        for (String[] j : e.getValue().joins) {
          JsonObject jo = new JsonObject();
          jo.addProperty("a", j[0]);
          jo.addProperty("b", j[1]);
          jo.addProperty("on", j[2]);
          joins.add(jo);
        }
        st.add("joins", joins);
        if (e.getValue().params != null) {
          JsonObject ps = new JsonObject();
          for (Map.Entry<String, List<String>> pe : e.getValue().params.entrySet()) {
            JsonArray cols = new JsonArray();
            for (String c : pe.getValue()) {
              cols.add(c);
            }
            ps.add(pe.getKey(), cols);
          }
          st.add("params", ps);
        }
        if (e.getValue().error != null) {
          st.addProperty("error", e.getValue().error);
        }
        sts.add(e.getKey(), st);
      }
      o.add("statements", sts);
      JsonObject k = new JsonObject();
      for (Map.Entry<String, Integer> e : keys.entrySet()) {
        k.addProperty(e.getKey(), e.getValue());
      }
      o.add("keys", k);
      Files.createDirectories(file.getParent());
      Path tmp = Files.createTempFile(file.getParent(), "relations", ".tmp");
      try {
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
          new GsonBuilder().disableHtmlEscaping().create().toJson(o, out);
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

  static String hash(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder b = new StringBuilder();
      for (byte x : d) {
        b.append(String.format("%02x", x));
      }
      return b.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- 解析 ----

  /** 解析の進み具合 */
  static final class Progress {
    volatile int total;
    volatile int done;
    volatile int analyzed;
    volatile String current;
    /** 一時停止を頼まれた */
    volatile boolean stop;
    /** 解析した statement の、計画を取るのにかかった時間の合計（ミリ秒。DB との往復を含む） */
    volatile double millis;
    /** そのうち、PostgreSQL が計画を作るのにかかった時間の合計（ミリ秒） */
    volatile double planMillis;
    final long startedAt = System.currentTimeMillis();
  }

  /**
   * statement を解析して保存する。SQL が前と同じ statement は解析しない。なくなった statement は消す。
   * 一時停止を頼まれたら、そこまでの結果を保存して止まる（次は続きから。前の解析を最後まで終えていなければ、解析できなかった statement も飛ばす）。
   *
   * @param ids 解析する statement
   * @param parameters すべての statement に入れる値（スキーマを決める ${schema} など）。ない項目は仮の値で埋める。なければ null
   */
  static void analyze(SqlRenderer renderer, List<String> ids, Object parameters, Connection c, Store store, Progress progress)
      throws IOException {
    progress.total = ids.size();
    DbLog.event("relations", "解析を始める（statement " + ids.size() + " 件、前の結果 " + store.statements.size() + " 件）");
    // 続きからのときは、解析できなかったものも飛ばす（最後まで終えた後の解析し直しでは、もう一度試す）
    boolean resume = !store.complete && !store.statements.isEmpty();
    Set<String> alive = new HashSet<>(ids);
    store.statements.keySet().removeIf(id -> !alive.contains(id));
    int sinceSave = 0;
    boolean stopped = false;
    // ${schema}.表 の表を持つスキーマ（同じ表の組は、何度も聞かない）
    Map<List<String>, String> schemas = new HashMap<>();
    // if・when の条件をすべて true にした renderer（要るときに 1 回だけ作る）
    SqlRenderer[] allTrue = {null};
    // 往復を減らすため、トランザクションなどを件をまたいで使い回す
    ParamSources.Session ses = new ParamSources.Session(c);
    try {
      for (String id : ids) {
        if (progress.stop) {
          stopped = true;
          DbLog.event("relations", "一時停止（" + progress.done + " / " + progress.total + "）");
          break;
        }
        progress.current = id;
        DbLog.event("relations", "statement " + id);
        Entry entry;
        ParamNode schema = renderer.parameters(id).root;
        String script = renderer.scriptText(id);
        java.util.Set<String> collections = ParamSources.collectionPaths(script);
        java.util.function.Function<String, String> guess = path -> ParamSources.rawGuess(script, path,
            tables -> schemaOf(ses, tables, schemas));
        SqlRenderer.Result full = null;
        RuntimeException renderError = null;
        try {
          full = renderer.render(id, ParamSources.probe(schema, parameters, guess, collections::contains, false));
        } catch (RuntimeException e) {
          renderError = e;
        }
        String h = full == null ? "" : hash(full.numberedSql);
        Entry old = store.statements.get(id);
        if (full != null && old != null && old.hash.equals(h) && (old.error == null || resume) && old.params != null
            && boundProperties(full).containsAll(old.params.keySet())) {
          progress.done++;
          continue;
        }
        long t0 = System.nanoTime();
        entry = full == null ? null : explainEntry(ses, full, h, progress);
        if (entry == null || entry.error != null) {
          // if・when の条件の式を仮の値で評価できない（数と文字の取り違えなど）か、組み立てた SQL が壊れるときは、
          // 条件をすべて true にした renderer で組み立て直す
          try {
            if (allTrue[0] == null) {
              DbLog.event("relations", "if・when の条件をすべて true にした mapper を読み込む");
              allTrue[0] = renderer.withAllConditionsTrue();
            }
            if (allTrue[0].hasStatement(id)) {
              SqlRenderer.Result t = allTrue[0].render(id, ParamSources.probe(schema, parameters, guess, collections::contains, false));
              if (full == null || !t.numberedSql.equals(full.numberedSql)) {
                Entry second = explainEntry(ses, t, h, progress);
                if (second.error == null || entry == null) {
                  entry = second;
                }
              }
            }
          } catch (RuntimeException ignored) {
            // 次の形で試す
          }
        }
        if (entry == null || entry.error != null) {
          // if をすべて通すと壊れる SQL がある（両立しない分岐がそろうなど）。if を通さない形（今の値・${}・リストだけ）でも試し、
          // 外側の結合だけでも拾う
          try {
            SqlRenderer.Result min = renderer.render(id, ParamSources.probe(schema, parameters, guess, collections::contains, true));
            if (full == null || !min.numberedSql.equals(full.numberedSql)) {
              Entry second = explainEntry(ses, min, h, progress);
              if (second.error == null) {
                entry = second;
              }
            }
          } catch (RuntimeException ignored) {
            // 最初のエラーを残す
          }
          if (entry == null) {
            entry = new Entry("", new ArrayList<>(), "SQL を組み立てられません: " + Errors.describe(renderError));
          }
        }
        progress.millis += (System.nanoTime() - t0) / 1_000_000.0;
        store.statements.put(id, entry);
        progress.analyzed++;
        progress.done++;
        // 途中で止まっても、そこまでの結果が残るように、ときどき保存する
        if (++sinceSave >= 50) {
          DbLog.event("relations", "途中の結果を保存する（" + progress.done + " / " + progress.total + "）");
          store.complete = false;
          endQuietly(ses);
          updateKeys(store, c);
          store.save();
          DbLog.event("relations", "途中の結果を保存した");
          sinceSave = 0;
        }
      }
      store.complete = !stopped;
    } finally {
      ses.close();
    }
    updateKeys(store, c);
    store.save();
    DbLog.event("relations", (stopped ? "止めた" : "終えた") + "（解析 " + progress.analyzed + " 件、" + progress.done + " / " + progress.total + "）");
  }

  /**
   * SQL に出ているパラメータ（項目のパス）。#{customerId} を #{custId} に変えても、$1 にした SQL は同じなので、
   * 前に記録したパラメータが今もあるかで、名前の書き換えに気づく
   */
  private static Set<String> boundProperties(SqlRenderer.Result r) {
    Set<String> out = new HashSet<>();
    for (SqlRenderer.Binding b : r.bindings) {
      out.add(b.property);
    }
    return out;
  }

  /** 計画を作れる文（CREATE・DROP などは PREPARE できないので、解析しない） */
  private static final Pattern PLANNABLE = Pattern.compile(
      "^(?:\\s|--[^\\n]*\\n|/\\*.*?\\*/|\\()*(select|with|insert|update|delete|values|table)\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** 1 つの SQL の計画を作り、結合の組を取り出す。CREATE などは、結合なし・エラーなしとして飛ばす */
  private static Entry explainEntry(ParamSources.Session ses, SqlRenderer.Result r, String hash, Progress progress) {
    if (!PLANNABLE.matcher(r.numberedSql).find()) {
      return new Entry(hash, new ArrayList<>(), null);
    }
    List<String> fallbackTypes = new ArrayList<>();
    List<String> values = new ArrayList<>();
    for (SqlRenderer.Binding b : r.bindings) {
      fallbackTypes.add(QueryExecutor.pgType(b.value, true));
      values.add(b.value == null ? null : b.value instanceof org.postgresql.util.PGobject
          ? ((org.postgresql.util.PGobject) b.value).getValue() : b.value.toString());
    }
    try {
      ParamSources.Explained e = ParamSources.explainForJoins(ses, r.numberedSql, r.bindings.size(), fallbackTypes, values);
      progress.planMillis += e.planMillis;
      // パラメータと比べている列（目印の値で探す。foreach の要素は、名前が statement ごとの仮のものなので記録しない）
      Map<String, List<String>> params = new LinkedHashMap<>();
      for (Map.Entry<Integer, List<ParamSources.Source>> pe : ParamSources.fromPlan(e.plan, e.markers).entrySet()) {
        String property = r.bindings.get(pe.getKey() - 1).property;
        if (property.startsWith("__frch_")) {
          continue;
        }
        List<String> cols = params.computeIfAbsent(property, k -> new ArrayList<>());
        for (ParamSources.Source src : pe.getValue()) {
          String col = key(src.schema, src.table, src.column);
          if (!cols.contains(col)) {
            cols.add(col);
          }
        }
      }
      return new Entry(hash, joins(e.plan), null, params);
    } catch (QueryExecutor.ExecutionException e) {
      return new Entry(hash, new ArrayList<>(), e.getMessage());
    }
  }

  private static void endQuietly(ParamSources.Session ses) {
    try {
      ses.end();
    } catch (SQLException ignored) {
      // 次の件で開き直す
    }
  }

  private static String schemaOf(ParamSources.Session ses, List<String> tables, Map<List<String>, String> cache) {
    List<String> key = new ArrayList<>(new java.util.TreeSet<>(tables));
    if (!cache.containsKey(key)) {
      // 使い回しているトランザクションを閉じてから聞き、閉じる（次の件で、読み取り専用で開き直す）
      endQuietly(ses);
      cache.put(key, ParamSources.schemaOf(ses.c, key));
      try {
        ses.c.rollback();
      } catch (SQLException ignored) {
        // 読むだけ
      }
    }
    return cache.get(key);
  }

  /** 結合に出てくる列が、一意かどうか（順位）を読み直す */
  private static void updateKeys(Store store, Connection c) {
    Set<String> cols = new LinkedHashSet<>();
    for (Entry e : store.statements.values()) {
      for (String[] j : e.joins) {
        cols.add(j[0]);
        cols.add(j[1]);
      }
    }
    // すべての列を 1 回で聞く（列ごとに聞くと、列の数だけ DB と往復する）
    List<String> schemas = new ArrayList<>();
    List<String> tables = new ArrayList<>();
    List<String> columns = new ArrayList<>();
    for (String col : cols) {
      String[] p = split(col);
      schemas.add(p[0]);
      tables.add(p[1]);
      columns.add(p[2]);
    }
    Map<String, Integer> keys = new TreeMap<>();
    try (PreparedStatement ps = c.prepareStatement("SELECT x.s, x.t, x.c,"
        + " min(i.indnkeyatts * 2 + CASE WHEN i.indisprimary THEN 0 ELSE 1 END)"
        + " FROM unnest(?::text[], ?::text[], ?::text[]) AS x(s, t, c)"
        + " JOIN pg_namespace n ON n.nspname = x.s JOIN pg_class c ON c.relnamespace = n.oid AND c.relname = x.t"
        + " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = x.c"
        + " JOIN pg_index i ON i.indrelid = c.oid AND i.indisunique AND i.indpred IS NULL AND i.indexprs IS NULL"
        + " AND a.attnum = ANY (i.indkey[0:i.indnkeyatts - 1])"
        + " GROUP BY x.s, x.t, x.c")) {
      ps.setArray(1, c.createArrayOf("text", schemas.toArray()));
      ps.setArray(2, c.createArrayOf("text", tables.toArray()));
      ps.setArray(3, c.createArrayOf("text", columns.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          keys.put(key(rs.getString(1), rs.getString(2), rs.getString(3)), rs.getInt(4));
        }
      }
    } catch (SQLException e) {
      // 一意かどうかが読めなければ、前に読んだものを使う
      return;
    } finally {
      try {
        c.rollback();
      } catch (SQLException ignored) {
        // 読むだけ
      }
    }
    store.keys.clear();
    store.keys.putAll(keys);
  }

  // ---- 集計 ----

  /** 2 つの列のつながり */
  static final class Link {
    final String a;
    final String b;
    /** parent: b が親、child: a が親（b が子）、one-to-one: どちらも一意、sibling: どちらも一意でない */
    final String kind;
    final Set<String> statements = new LinkedHashSet<>();
    /** 手で追加したリレーション（a が子、b が親） */
    boolean manual;
    /** 手で無効にした（源流を探すときに使わない） */
    boolean disabled;

    Link(String a, String b, String kind) {
      this.a = a;
      this.b = b;
      this.kind = kind;
    }
  }

  static boolean unique(Store store, String col) {
    Integer r = store.keys.get(col);
    return r != null && r <= SINGLE_UNIQUE;
  }

  /**
   * 解析で推し量ったつながりに、手で設定したものを重ねる。無効にしたつながりには印を付け、手で追加したリレーションを足す
   * （同じ列の組を推し量っていれば、根拠の statement を引き継いで、手で追加したものに置き換える）。
   */
  static List<Link> links(Store store, ManualRelations manual) {
    List<Link> out = store == null ? new ArrayList<>() : links(store);
    if (manual == null) {
      return out;
    }
    synchronized (manual) {
      for (Link l : out) {
        l.disabled = manual.isDisabled(l.a, l.b);
      }
      for (ManualRelations.Relation r : manual.relations) {
        Link m = new Link(r.child, r.parent, "parent");
        m.manual = true;
        java.util.Iterator<Link> it = out.iterator();
        while (it.hasNext()) {
          Link l = it.next();
          if (l.a.equals(r.child) && l.b.equals(r.parent) || l.a.equals(r.parent) && l.b.equals(r.child)) {
            m.statements.addAll(l.statements);
            it.remove();
          }
        }
        out.add(m);
      }
    }
    return out;
  }

  /** 列の組ごとにまとめる。a が子（または名前の順で前）になるように向きをそろえる */
  static List<Link> links(Store store) {
    Map<String, Link> out = new LinkedHashMap<>();
    for (Map.Entry<String, Entry> e : store.statements.entrySet()) {
      for (String[] j : e.getValue().joins) {
        String a = j[0];
        String b = j[1];
        boolean ua = unique(store, a);
        boolean ub = unique(store, b);
        String kind;
        if (ua && ub) {
          kind = "one-to-one";
        } else if (ua || ub) {
          kind = "parent";
          if (ua) {
            String t = a;
            a = b;
            b = t;
          }
        } else {
          kind = "sibling";
        }
        if (!kind.equals("parent") && a.compareTo(b) > 0) {
          String t = a;
          a = b;
          b = t;
        }
        String k = a + "\u0000" + b;
        Link l = out.get(k);
        if (l == null) {
          l = new Link(a, b, kind);
          out.put(k, l);
        }
        l.statements.add(e.getKey());
      }
    }
    return new ArrayList<>(out.values());
  }

  /** 同じものを指すとみなすパラメータの名前の、最短の長さ（id や no のような短い名前は、別のものを指すことが多い） */
  static final int MIN_NAME = 3;

  /** パラメータの名前（cond.customerId なら customerId） */
  static String paramName(String property) {
    return property.substring(property.lastIndexOf('.') + 1);
  }

  /** 列と列のリレーション（源流を探すため、列ごとのつながりを引けるようにしたもの） */
  static final class Graph {
    /** 1 列で一意の列（作ったときの写し。解析中に変わっても影響しない） */
    private final Set<String> unique = new HashSet<>();
    private final List<Link> links;

    /** パラメータの名前ごとの、ほかの statement で源流と判断した列と、その statement の数 */
    private final Map<String, Map<String, Integer>> byName = new HashMap<>();

    /** 手で設定したリレーション（なければ null） */
    private final ManualRelations manual;

    /** store のロックを持って呼ぶ */
    Graph(Store store, List<Link> links) {
      this(store, links, null);
    }

    /**
     * store のロックを持って呼ぶ。
     *
     * @param links {@link Relations#links(Store, ManualRelations)} で、手で設定したものを入れたもの
     */
    Graph(Store store, List<Link> links, ManualRelations manual) {
      this.manual = manual;
      for (String col : store.keys.keySet()) {
        if (Relations.unique(store, col)) {
          unique.add(col);
        }
      }
      this.links = links;
      for (Entry e : store.statements.values()) {
        if (e.params == null) {
          continue;
        }
        for (Map.Entry<String, List<String>> pe : e.params.entrySet()) {
          String name = paramName(pe.getKey());
          String top = name.length() >= MIN_NAME ? decided(pe.getValue()) : null;
          if (top != null) {
            byName.computeIfAbsent(name, k -> new HashMap<>()).merge(top, 1, Integer::sum);
          }
        }
      }
    }

    List<Origin> origins(String column) {
      return Relations.origins(unique::contains, links, column,
          manual == null ? col -> new ArrayList<String>() : manual::parentsOf);
    }

    /** 比べている列から、源流と判断する列（たどった源流、なければ一意の列。どちらもなければ null） */
    private String decided(List<String> compared) {
      Origin best = null;
      for (String col : compared) {
        for (Origin o : origins(col)) {
          if (best == null || o.path.size() < best.path.size() || o.path.size() == best.path.size() && o.count > best.count) {
            best = o;
          }
        }
      }
      if (best != null) {
        return best.column;
      }
      for (String col : compared) {
        if (unique.contains(col)) {
          return col;
        }
      }
      // 一意でない列（tenant_id など）は源流とみなさない（ほかの表の同じ名前の列が、候補にずらっと並ぶため）
      return null;
    }

    /** 同じ名前のパラメータが、ほかの statement で源流と判断した列（statement の多い順） */
    List<Map.Entry<String, Integer>> sameName(String property) {
      Map<String, Integer> m = byName.get(paramName(property));
      List<Map.Entry<String, Integer>> out = new ArrayList<>(m == null ? new HashMap<String, Integer>().entrySet() : m.entrySet());
      out.sort((x, y) -> y.getValue() - x.getValue());
      return out;
    }
  }

  /**
   * パラメータごとの、値の候補を出す列。比べている列からリレーションをたどった源流の列（近いもの、根拠の SQL の多いものから）、
   * 比べている列の順にする（投稿履歴.user_id と比べているなら、利用者.id を先に）。
   */
  static Map<String, List<ParamSources.Source>> candidates(Map<String, List<ParamSources.Source>> byProperty,
      Relations.Graph graph) {
    Map<String, List<ParamSources.Source>> out = new LinkedHashMap<>();
    for (Map.Entry<String, List<ParamSources.Source>> e : byProperty.entrySet()) {
      List<ParamSources.Source> origins = new ArrayList<>();
      List<Relations.Origin> found = new ArrayList<>();
      if (graph != null) {
        for (ParamSources.Source src : e.getValue()) {
          for (Relations.Origin o : graph.origins(Relations.key(src.schema, src.table, src.column))) {
            found.add(o);
          }
        }
      }
      // 近いもの（たどった列が少ない）、根拠の SQL が多いものから
      found.sort((x, y) -> x.path.size() != y.path.size() ? x.path.size() - y.path.size() : y.count - x.count);
      for (Relations.Origin o : found) {
        String[] p = Relations.split(o.column);
        ParamSources.Source src = new ParamSources.Source(p[0], p[1], p[2], shortPath(withLast(o.path, o.column)), true, "origin",
            o.path, o.count, o.manual);
        if (origins.stream().anyMatch(src::same)) {
          continue;
        }
        // 源流が比べている列でもある（#{categoryId} を products.category_id とも categories.id とも比べている）なら、
        // 比べている列として先に出す
        ParamSources.Source compared = e.getValue().stream().filter(src::same).findFirst().orElse(null);
        origins.add(compared != null ? compared : src);
      }
      List<ParamSources.Source> list = new ArrayList<>(origins);
      for (ParamSources.Source src : e.getValue()) {
        if (list.stream().noneMatch(src::same)) {
          list.add(src);
        }
      }
      // 名前が同じ（3 文字以上で完全一致）パラメータが、ほかの statement で源流と判断した列（同じものを指しているとみなす。順位は最も低い）
      if (graph != null && !e.getKey().startsWith("__frch_")) {
        String name = paramName(e.getKey());
        for (Map.Entry<String, Integer> ne : graph.sameName(e.getKey())) {
          String[] p = split(ne.getKey());
          ParamSources.Source src = new ParamSources.Source(p[0], p[1], p[2], "同じ名前のパラメータ（" + name + "）から", true, "name",
              new ArrayList<String>(), ne.getValue());
          if (list.stream().noneMatch(src::same)) {
            list.add(src);
          }
        }
      }
      out.put(e.getKey(), list);
    }
    return out;
  }

  private static List<String> withLast(List<String> path, String last) {
    List<String> out = new ArrayList<>(path);
    out.add(last);
    return out;
  }

  /** schema.table.column の並びを、表.列 → 表.列 の形にする */
  static String shortPath(List<String> cols) {
    List<String> out = new ArrayList<>();
    for (String c : cols) {
      String[] p = Relations.split(c);
      out.add(p[1] + "." + p[2]);
    }
    return String.join(" → ", out);
  }
  /** 源流の候補 */
  static final class Origin {
    final String column;
    /** たどった列（最初の列から源流の手前まで） */
    final List<String> path;
    /** 根拠の statement の数（たどったつながりのうち、最も少ないもの） */
    final int count;
    /** 手で設定したリレーションで見つけた */
    final boolean manual;

    Origin(String column, List<String> path, int count) {
      this(column, path, count, false);
    }

    Origin(String column, List<String> path, int count, boolean manual) {
      this.column = column;
      this.path = path;
      this.count = count;
      this.manual = manual;
    }
  }

  /**
   * 列の源流（一意の列）を探す。直接の親、兄弟の親の順に、近いものから。列そのものが一意なら、なし。
   * 兄弟は MAX_HOPS 段までたどる（遠くまでたどると、関係のない表につながりやすい）。
   */
  static List<Origin> origins(Store store, List<Link> links, String column) {
    return origins(col -> unique(store, col), links, column);
  }

  static List<Origin> origins(java.util.function.Predicate<String> isUnique, List<Link> links, String column) {
    return origins(isUnique, links, column, col -> new ArrayList<String>());
  }

  /**
   * @param manualParents 列に手で設定した親。あれば、その列の源流は手で設定したものだけにする（解析で推し量ったものより優先する）。
   *     兄弟をたどった先の列に手で設定した親があれば、それも源流にする
   */
  static List<Origin> origins(java.util.function.Predicate<String> isUnique, List<Link> links, String column,
      java.util.function.Function<String, List<String>> manualParents) {
    List<Origin> out = new ArrayList<>();
    List<String> mine = manualParents.apply(column);
    if (!mine.isEmpty()) {
      for (String p : mine) {
        out.add(new Origin(p, new ArrayList<>(Collections.singletonList(column)), 0, true));
      }
      return out;
    }
    if (isUnique.test(column)) {
      return out;
    }
    Map<String, List<Link>> byColumn = new HashMap<>();
    for (Link l : links) {
      byColumn.computeIfAbsent(l.a, k -> new ArrayList<>()).add(l);
      byColumn.computeIfAbsent(l.b, k -> new ArrayList<>()).add(l);
    }
    Set<String> visited = new HashSet<>();
    visited.add(column);
    Set<String> found = new HashSet<>();
    Deque<Object[]> queue = new ArrayDeque<>();
    queue.add(new Object[] {column, new ArrayList<String>(), Integer.MAX_VALUE});
    while (!queue.isEmpty()) {
      Object[] cur = queue.poll();
      String col = (String) cur[0];
      @SuppressWarnings("unchecked")
      List<String> path = (List<String>) cur[1];
      int count = (Integer) cur[2];
      List<Link> ls = byColumn.getOrDefault(col, new ArrayList<>());
      // 同じ段では、根拠の多いつながりから
      ls.sort((x, y) -> y.statements.size() - x.statements.size());
      for (Link l : ls) {
        // 無効にしたつながりは使わない。手で追加したものは manualParents で扱う
        if (l.disabled || l.manual) {
          continue;
        }
        String other = l.a.equals(col) ? l.b : l.a;
        int c = Math.min(count, l.statements.size());
        List<String> next = new ArrayList<>(path);
        next.add(col);
        if (l.kind.equals("sibling") && next.size() < MAX_HOPS && !visited.contains(other) && !manualParents.apply(other).isEmpty()) {
          // 兄弟に手で設定した親がある
          visited.add(other);
          List<String> via = new ArrayList<>(next);
          via.add(other);
          for (String p : manualParents.apply(other)) {
            if (found.add(p)) {
              out.add(new Origin(p, via, c, true));
            }
          }
          continue;
        }
        if (l.kind.equals("parent") && l.a.equals(col)) {
          if (found.add(other)) {
            out.add(new Origin(other, next, c));
          }
        } else if (l.kind.equals("sibling") && next.size() < MAX_HOPS && visited.add(other)) {
          queue.add(new Object[] {other, next, c});
        }
      }
    }
    return out;
  }
}
