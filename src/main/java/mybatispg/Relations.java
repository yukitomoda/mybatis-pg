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
import java.util.Arrays;
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
 * 直さずに残る、サブクエリ・UNION の枝（"*SELECT* 1"）・WITH 句の列は、計画のその段の出力の列（VERBOSE の Output）から、
 * 対応が確実に分かるときだけ中の列に読み替える（{@link #derived}）。
 *
 * <p>どちらが源流（親）かは、一意かどうかで決める。片方だけが 1 列で一意（主キー・UNIQUE）なら、その側が親。
 * どちらも一意でなければ兄弟（同じ親を指している）として扱い、兄弟をたどって親を探す。
 *
 * <p>解析の結果は、プロジェクトと接続先の組ごとに 1 つの JSON に保存する（解析し直すときは、SQL が変わった statement だけを解析する）。
 * <pre>
 * {"version": 1, "analyzer": 解析の仕方の版, "project": "/path/to/project", "connectionId": "...", "updatedAt": ミリ秒,
 *  "statements": {"statement の id": {"hash": SQL の SHA-256,
 *                                    "joins": [{"a": "schema.table.column", "b": "...", "on": "Hash Cond", "expr": "c.user_id = u.id"}],
 *                                    "error": 解析できなかった理由（なければなし）}},
 *  "keys": {"schema.table.column": 一意の順位（1 列の主キー 2、1 列の UNIQUE 3、複数列なら列の数 × 2 + …。一意でなければなし）}}
 * </pre>
 */
final class Relations {

  /** 解析の仕方の版。変えたら、保存してある結果を使わずに解析し直す */
  static final int ANALYZER = 4;
  /**
   * 残した計画からの取り出し方（結合の組・パラメータと比べている列・出てくる表）の版。変えたら、ファイルを開いたときに、
   * 残した計画から取り出し直す（DB にはつながない）。親子の決め方など、保存したものから読むたびに組み立てるものは、版を変えなくてよい
   */
  static final int EXTRACTOR = 4;
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

  /**
   * 計画の中の、別の表（または同じ表の別の列）の列どうしを = で比べている組。[a, b, 計画の項目, 条件の式]。
   * 条件の式は、計画に書かれた別名のまま（c.user_id = u.id。SQL の中で結合している場所を探すのに使う）。同じ組が何か所にもあれば改行で区切る
   */
  static List<String[]> joins(String planJson) {
    JsonElement root = JsonParser.parseString(planJson);
    JsonObject top = root.isJsonArray() ? root.getAsJsonArray().get(0).getAsJsonObject() : root.getAsJsonObject();
    Map<String, String[]> aliases = new HashMap<>();
    List<String[]> conditions = new ArrayList<>();
    walk(top.getAsJsonObject("Plan"), aliases, conditions);
    Map<String, String> derived = derived(top.getAsJsonObject("Plan"));
    List<String[]> out = new ArrayList<>();
    Map<String, String[]> seen = new HashMap<>();
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
        if (temporal.contains(unquote(m.group(1)) + "." + unquote(m.group(2)))
            || temporal.contains(unquote(m.group(3)) + "." + unquote(m.group(4)))) {
          continue;
        }
        String a = resolve(aliases, derived, unquote(m.group(1)), unquote(m.group(2)), 0);
        String b = resolve(aliases, derived, unquote(m.group(3)), unquote(m.group(4)), 0);
        if (a == null || b == null) {
          // 式や集計の結果の列は、実際の表の列ではないので使わない
          continue;
        }
        if (a.equals(b)) {
          continue;
        }
        String pair = a.compareTo(b) < 0 ? a + "\u0000" + b : b + "\u0000" + a;
        String expr = m.group(0).replaceAll("\\s+", " ");
        String[] found = seen.get(pair);
        if (found == null) {
          found = new String[] {a, b, kc[0], expr};
          seen.put(pair, found);
          out.add(found);
        } else if (!Arrays.asList(found[3].split("\n")).contains(expr)) {
          found[3] = found[3] + "\n" + expr;
        }
      }
    }
    return out;
  }

  /** 列（別名.列）を、実際の表の列（schema.table.column）に直す。サブクエリ・WITH 句の列は中の列をたどる。直せなければ null */
  private static String resolve(Map<String, String[]> aliases, Map<String, String> derived, String alias, String column, int depth) {
    String[] t = aliases.get(alias);
    if (t != null) {
      return key(t[0], t[1], column);
    }
    String expr = derived.get(alias + "\u0000" + column);
    if (expr == null || depth > 8 || TEMPORAL_CAST.matcher(expr).find()) {
      return null;
    }
    Matcher m = WHOLE_COLUMN.matcher(bare(expr));
    return m.matches() ? resolve(aliases, derived, unquote(m.group(1)), unquote(m.group(2)), depth + 1) : null;
  }

  private static final Pattern WHOLE_COLUMN = Pattern.compile("(" + IDENT + ")\\.(" + IDENT + ")");

  /** 型の変換と、まわりの括弧を除く（((t.project_id)::bigint) → t.project_id） */
  private static String bare(String expr) {
    String e = CAST.matcher(expr.trim()).replaceAll("");
    String prev;
    do {
      prev = e;
      e = WRAPPED.matcher(e).replaceAll("$1").trim();
      if (e.startsWith("(") && e.endsWith(")") && e.indexOf('(', 1) < 0) {
        e = e.substring(1, e.length() - 1).trim();
      }
    } while (!e.equals(prev));
    return e;
  }

  /**
   * サブクエリ（Subquery Scan）と WITH 句（CTE Scan）の列の中身。別名\0列 → 中の段の出力の式。確実に対応が分かるときだけ入れる:
   * <ul>
   * <li>UNION の枝（Append の下）: 枝は列をすべて SQL の順で出すので、位置で対応させる</li>
   * <li>ほかのサブクエリ・WITH 句: 段の出力は外で使う列だけを、外で使う順に出すので、位置では対応しない。段の出力の列名の集まりが、
   * 中の段の出力（どれもただの列）の列名の集まりと同じとき（名前を変えた列がない）だけ、名前で対応させる</li>
   * </ul>
   * 同じ別名の段が複数あって中身が食い違うもの（UNION が 2 つあって、どちらも "*SELECT* 1" など）は使わない
   */
  private static Map<String, String> derived(JsonObject plan) {
    Map<String, JsonObject> ctes = new HashMap<>();
    List<JsonObject[]> scans = new ArrayList<>();
    collect(plan, null, ctes, scans);
    Map<String, String> out = new HashMap<>();
    Set<String> conflicts = new HashSet<>();
    for (JsonObject[] sp : scans) {
      JsonObject n = sp[0];
      JsonObject parent = sp[1];
      JsonObject inner;
      if (n.has("CTE Name")) {
        inner = ctes.get(n.get("CTE Name").getAsString());
      } else {
        JsonElement plans = n.get("Plans");
        inner = plans != null && plans.isJsonArray() && ((JsonArray) plans).size() > 0 ? ((JsonArray) plans).get(0).getAsJsonObject() : null;
      }
      List<String> own = outputs(n);
      List<String> src = inner == null ? new ArrayList<String>() : outputs(inner);
      String alias = n.get("Alias").getAsString();
      // 段の出力の列名（どれも、この別名の列）
      List<String> ownNames = new ArrayList<>();
      for (String o : own) {
        Matcher m = WHOLE_COLUMN.matcher(o.trim());
        ownNames.add(m.matches() && unquote(m.group(1)).equals(alias) ? unquote(m.group(2)) : null);
      }
      Map<String, String> mapped = new HashMap<>();
      String pt = parent == null ? null : str(parent, "Node Type");
      if (("Append".equals(pt) || "Merge Append".equals(pt)) && own.size() == src.size()) {
        for (int i = 0; i < own.size(); i++) {
          if (ownNames.get(i) != null) {
            mapped.put(ownNames.get(i), src.get(i));
          }
        }
      } else {
        // 中の段の出力の列名（ただの列でないものがあれば、名前では対応させない）
        List<String> srcNames = new ArrayList<>();
        for (String x : src) {
          Matcher sm = WHOLE_COLUMN.matcher(bare(x));
          srcNames.add(sm.matches() ? unquote(sm.group(2)) : null);
        }
        List<String> a = new ArrayList<>(ownNames);
        List<String> b = new ArrayList<>(srcNames);
        if (!a.contains(null) && !b.contains(null) && new HashSet<>(b).size() == b.size()) {
          java.util.Collections.sort(a);
          java.util.Collections.sort(b);
          if (a.equals(b)) {
            for (int i = 0; i < src.size(); i++) {
              mapped.put(srcNames.get(i), src.get(i));
            }
          }
        }
      }
      for (Map.Entry<String, String> e : mapped.entrySet()) {
        String k = alias + "\u0000" + e.getKey();
        String old = out.put(k, e.getValue());
        if (old != null && !old.equals(e.getValue())) {
          conflicts.add(k);
        }
      }
    }
    for (String k : conflicts) {
      out.remove(k);
    }
    return out;
  }

  /** CTE の計画と、サブクエリ・WITH 句の段（[段, 親の段]）を集める */
  private static void collect(JsonObject node, JsonObject parent, Map<String, JsonObject> ctes, List<JsonObject[]> scans) {
    if (node.has("Subplan Name") && node.get("Subplan Name").getAsString().startsWith("CTE ")) {
      ctes.put(node.get("Subplan Name").getAsString().substring(4), node);
    }
    if (node.has("Alias") && !node.has("Relation Name")
        && ("Subquery Scan".equals(str(node, "Node Type")) || "CTE Scan".equals(str(node, "Node Type")))) {
      scans.add(new JsonObject[] {node, parent});
    }
    JsonElement plans = node.get("Plans");
    if (plans != null && plans.isJsonArray()) {
      for (JsonElement child : (JsonArray) plans) {
        collect(child.getAsJsonObject(), node, ctes, scans);
      }
    }
  }

  private static String str(JsonObject node, String key) {
    return node.has(key) && node.get(key).isJsonPrimitive() ? node.get(key).getAsString() : null;
  }

  private static List<String> outputs(JsonObject node) {
    List<String> out = new ArrayList<>();
    JsonElement o = node.get("Output");
    if (o != null && o.isJsonArray()) {
      for (JsonElement e : (JsonArray) o) {
        out.add(e.getAsString());
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
    /** 計画を残したか（true: 残した、false: 計画を作れない文か、作れなかった、null: 前の版の結果で、残していない） */
    final Boolean planned;
    /** 計画に出てくる表（schema.table） */
    final List<String> tables;

    Entry(String hash, List<String[]> joins, String error) {
      this(hash, joins, error, new LinkedHashMap<String, List<String>>(), false, new ArrayList<String>());
    }

    Entry(String hash, List<String[]> joins, String error, Map<String, List<String>> params) {
      this(hash, joins, error, params, false, new ArrayList<String>());
    }

    Entry(String hash, List<String[]> joins, String error, Map<String, List<String>> params, Boolean planned, List<String> tables) {
      this.hash = hash;
      this.joins = joins;
      this.error = error;
      this.params = params;
      this.planned = planned;
      this.tables = tables;
    }
  }

  /** 残した計画（DB から取った元のもの）。取り出し方を変えたときに、ここから取り出し直す */
  static final class SavedPlan {
    final String plan;
    /** パラメータに入れた目印（番号順。目印のないものは null） */
    final List<String> markers;
    /** パラメータの項目（番号順） */
    final List<String> properties;

    SavedPlan(String plan, List<String> markers, List<String> properties) {
      this.plan = plan;
      this.markers = markers;
      this.properties = properties;
    }
  }

  /** 残した計画から、結合の組・パラメータと比べている列・出てくる表を取り出す */
  static Entry extract(String hash, SavedPlan p) {
    // パラメータと比べている列（目印の値で探す。foreach の要素は、名前が statement ごとの仮のものなので記録しない）
    Map<String, List<String>> params = new LinkedHashMap<>();
    for (Map.Entry<Integer, List<ParamSources.Source>> pe : ParamSources.fromPlan(p.plan, p.markers).entrySet()) {
      String property = p.properties.get(pe.getKey() - 1);
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
    return new Entry(hash, joins(p.plan), null, params, true, tables(p.plan));
  }

  /** 計画に出てくる表（schema.table） */
  static List<String> tables(String planJson) {
    JsonElement root = JsonParser.parseString(planJson);
    JsonObject top = root.isJsonArray() ? root.getAsJsonArray().get(0).getAsJsonObject() : root.getAsJsonObject();
    Map<String, String[]> aliases = new HashMap<>();
    walk(top.getAsJsonObject("Plan"), aliases, new ArrayList<String[]>());
    Set<String> out = new java.util.TreeSet<>();
    for (String[] r : aliases.values()) {
      out.add(r[0] + "." + r[1]);
    }
    return new ArrayList<>(out);
  }

  /** 表の主キーか UNIQUE の列 */
  static final class TableKey {
    final boolean primary;
    final List<String> columns;

    TableKey(boolean primary, List<String> columns) {
      this.primary = primary;
      this.columns = columns;
    }

    String describe() {
      return (primary ? "主キー" : "UNIQUE") + "（" + String.join(", ", columns) + "）";
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
    /** 表（schema.table）ごとの、主キーと UNIQUE（条件付き・式のものは除く）の列 */
    final Map<String, List<TableKey>> tableKeys = new TreeMap<>();
    long updatedAt;
    /** 最後の解析を最後まで終えたか（一時停止したら false） */
    boolean complete;
    /** 保存した結果の取り出し方の版 */
    int extractor;
    /** 計画に出てこなくても主キー・UNIQUE を読む表（手で追加したリレーションの表。保存しない） */
    Set<String> extraTables = new LinkedHashSet<>();

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
        // 取り出し方が変わっていれば、残した計画から取り出し直す（DB にはつながない）
        if (!s.statements.isEmpty() && s.extractor != EXTRACTOR) {
          s.reextract();
          s.save();
        }
      }
      return s;
    }

    /** 残した計画のディレクトリ（null なら残さない） */
    private Path plansDir() {
      if (file == null) {
        return null;
      }
      String name = file.getFileName().toString();
      return file.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".plans");
    }

    private Path planFile(String statementId) {
      Path dir = plansDir();
      return dir == null ? null : dir.resolve(hash(statementId).substring(0, 24) + ".json.gz");
    }

    /** 計画を残す（DB から取った元のものを、加工せずに） */
    void savePlan(String statementId, SavedPlan p) throws IOException {
      Path f = planFile(statementId);
      if (f == null) {
        memoryPlans.put(statementId, p);
        return;
      }
      Files.createDirectories(f.getParent());
      JsonObject o = new JsonObject();
      o.addProperty("plan", p.plan);
      o.add("markers", Json.GSON.toJsonTree(p.markers));
      o.add("properties", Json.GSON.toJsonTree(p.properties));
      try (Writer out = new java.io.OutputStreamWriter(new java.util.zip.GZIPOutputStream(Files.newOutputStream(f)), StandardCharsets.UTF_8)) {
        Json.GSON.toJson(o, out);
      }
    }

    /** 残した計画（なければ null） */
    SavedPlan loadPlan(String statementId) {
      Path f = planFile(statementId);
      if (f == null) {
        return memoryPlans.get(statementId);
      }
      if (!Files.isRegularFile(f)) {
        return null;
      }
      try (Reader in = new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8)) {
        JsonObject o = JsonParser.parseReader(in).getAsJsonObject();
        List<String> markers = new ArrayList<>();
        for (JsonElement m : o.getAsJsonArray("markers")) {
          markers.add(m.isJsonNull() ? null : m.getAsString());
        }
        List<String> properties = new ArrayList<>();
        for (JsonElement m : o.getAsJsonArray("properties")) {
          properties.add(m.getAsString());
        }
        return new SavedPlan(o.get("plan").getAsString(), markers, properties);
      } catch (IOException | RuntimeException e) {
        return null;
      }
    }

    void deletePlan(String statementId) {
      Path f = planFile(statementId);
      memoryPlans.remove(statementId);
      if (f != null) {
        try {
          Files.deleteIfExists(f);
        } catch (IOException ignored) {
          // 残っていても、使わない
        }
      }
    }

    /** ファイルに残さないとき（テスト用）の、残した計画 */
    private final Map<String, SavedPlan> memoryPlans = new HashMap<>();

    /** 残した計画から、結合の組などを取り出し直す（DB にはつながない）。計画を残していないものは、そのまま */
    synchronized void reextract() {
      for (Map.Entry<String, Entry> e : statements.entrySet()) {
        if (!Boolean.TRUE.equals(e.getValue().planned)) {
          continue;
        }
        SavedPlan p = loadPlan(e.getKey());
        if (p != null) {
          e.setValue(extract(e.getValue().hash, p));
        }
      }
      extractor = EXTRACTOR;
      deriveKeys(this);
    }

    private void read(JsonObject o) {
      if (!o.has("analyzer") || o.get("analyzer").getAsInt() != ANALYZER) {
        // 解析の仕方が変わったので、前の結果は使わない
        return;
      }
      updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsLong() : 0;
      extractor = o.has("extractor") ? o.get("extractor").getAsInt() : 0;
      complete = !o.has("complete") || o.get("complete").getAsBoolean();
      for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("statements").entrySet()) {
        JsonObject st = e.getValue().getAsJsonObject();
        List<String[]> joins = new ArrayList<>();
        for (JsonElement j : st.getAsJsonArray("joins")) {
          JsonObject jo = j.getAsJsonObject();
          joins.add(new String[] {jo.get("a").getAsString(), jo.get("b").getAsString(), jo.get("on").getAsString(),
              jo.has("expr") ? jo.get("expr").getAsString() : ""});
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
        List<String> tables = new ArrayList<>();
        if (st.has("tables")) {
          for (JsonElement t : st.getAsJsonArray("tables")) {
            tables.add(t.getAsString());
          }
        }
        Boolean planned = st.has("planned") ? st.get("planned").getAsBoolean() : null;
        statements.put(e.getKey(), new Entry(st.get("hash").getAsString(), joins, st.has("error") ? st.get("error").getAsString() : null,
            params, planned, tables));
      }
      if (o.has("keys")) {
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("keys").entrySet()) {
          keys.put(e.getKey(), e.getValue().getAsInt());
        }
      }
      if (o.has("tableKeys")) {
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("tableKeys").entrySet()) {
          List<TableKey> list = new ArrayList<>();
          for (JsonElement k : e.getValue().getAsJsonArray()) {
            JsonObject ko = k.getAsJsonObject();
            List<String> cols = new ArrayList<>();
            for (JsonElement c : ko.getAsJsonArray("columns")) {
              cols.add(c.getAsString());
            }
            list.add(new TableKey(ko.get("primary").getAsBoolean(), cols));
          }
          tableKeys.put(e.getKey(), list);
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
      o.addProperty("extractor", extractor);
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
          if (j.length > 3 && !j[3].isEmpty()) {
            jo.addProperty("expr", j[3]);
          }
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
        if (e.getValue().planned != null) {
          st.addProperty("planned", e.getValue().planned);
        }
        st.add("tables", Json.GSON.toJsonTree(e.getValue().tables));
        sts.add(e.getKey(), st);
      }
      o.add("statements", sts);
      JsonObject k = new JsonObject();
      for (Map.Entry<String, Integer> e : keys.entrySet()) {
        k.addProperty(e.getKey(), e.getValue());
      }
      o.add("keys", k);
      JsonObject tk = new JsonObject();
      for (Map.Entry<String, List<TableKey>> e : tableKeys.entrySet()) {
        JsonArray list = new JsonArray();
        for (TableKey key : e.getValue()) {
          JsonObject ko = new JsonObject();
          ko.addProperty("primary", key.primary);
          JsonArray cols = new JsonArray();
          for (String c : key.columns) {
            cols.add(c);
          }
          ko.add("columns", cols);
          list.add(ko);
        }
        tk.add(e.getKey(), list);
      }
      o.add("tableKeys", tk);
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
    /** 終えた時間（終えていなければ 0） */
    volatile long finishedAt;
    /**
     * フェーズ。prepare: SQL を組み立てて、前から変わったものを数える、plan: 変わったものの計画を取る、
     * finish: 主キー・UNIQUE を読み、保存し、画面に返す結果をまとめる
     */
    volatile String phase = "prepare";
    volatile long phaseStartedAt = startedAt;
    /** 準備で組み立てた数（total のうち） */
    volatile int prepared;
    /** 計画を取る statement の数（前から変わったもの） */
    volatile int toAnalyze;
    /** 仕上げの今の作業 */
    volatile String step;

    void phase(String p) {
      phase = p;
      phaseStartedAt = System.currentTimeMillis();
    }
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
    for (String id : new ArrayList<>(store.statements.keySet())) {
      if (!alive.contains(id)) {
        store.statements.remove(id);
        store.deletePlan(id);
      }
    }
    store.extractor = EXTRACTOR;
    int sinceSave = 0;
    boolean stopped = false;
    // ${schema}.表 の表を持つスキーマ（同じ表の組は、何度も聞かない）
    Map<List<String>, String> schemas = new HashMap<>();
    // if・when の条件をすべて true にした renderer（要るときに 1 回だけ作る）
    SqlRenderer[] allTrue = {null};
    // 往復を減らすため、トランザクションなどを件をまたいで使い回す
    ParamSources.Session ses = new ParamSources.Session(c);
    try {
      // 準備: SQL を組み立てて、前から変わったもの（計画を取るもの）を数える
      List<Object[]> pending = new ArrayList<>();
      for (String id : ids) {
        if (progress.stop) {
          stopped = true;
          DbLog.event("relations", "一時停止（準備 " + progress.prepared + " / " + progress.total + "）");
          break;
        }
        progress.current = id;
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
        progress.prepared++;
        // 計画を残していない前の版の結果（planned が null）は、解析し直して計画を残す
        if (full != null && old != null && old.hash.equals(h) && (old.error == null || resume) && old.params != null && old.planned != null
            && boundProperties(full).containsAll(old.params.keySet())) {
          progress.done++;
          continue;
        }
        pending.add(new Object[] {id, schema, collections, guess, full, renderError, h});
      }
      progress.toAnalyze = pending.size();
      DbLog.event("relations", "計画を取る statement " + pending.size() + " 件");
      progress.phase("plan");
      for (Object[] pe : stopped ? new ArrayList<Object[]>() : pending) {
        if (progress.stop) {
          stopped = true;
          DbLog.event("relations", "一時停止（" + progress.analyzed + " / " + progress.toAnalyze + "）");
          break;
        }
        String id = (String) pe[0];
        ParamNode schema = (ParamNode) pe[1];
        @SuppressWarnings("unchecked")
        java.util.Set<String> collections = (java.util.Set<String>) pe[2];
        @SuppressWarnings("unchecked")
        java.util.function.Function<String, String> guess = (java.util.function.Function<String, String>) pe[3];
        SqlRenderer.Result full = (SqlRenderer.Result) pe[4];
        RuntimeException renderError = (RuntimeException) pe[5];
        String h = (String) pe[6];
        progress.current = id;
        DbLog.event("relations", "statement " + id);
        Entry entry;
        long t0 = System.nanoTime();
        entry = full == null ? null : explainEntry(ses, store, id, full, h, progress);
        if (entry == null || entry.error != null) {
          // if・when の条件の式を仮の値で評価できない（数と文字の取り違えなど）か、組み立てた SQL が壊れるときは、
          // 条件をすべて true にした renderer で組み立て直す
          try {
            if (allTrue[0] == null) {
              DbLog.event("relations", "if・when の条件をすべて true にした mapper を読み込む");
              progress.current = "if・when の条件をすべて通す mapper を読み込む";
              allTrue[0] = renderer.withAllConditionsTrue();
            }
            if (allTrue[0].hasStatement(id)) {
              SqlRenderer.Result t = allTrue[0].render(id, ParamSources.probe(schema, parameters, guess, collections::contains, false));
              if (full == null || !t.numberedSql.equals(full.numberedSql)) {
                Entry second = explainEntry(ses, store, id, t, h, progress);
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
              Entry second = explainEntry(ses, store, id, min, h, progress);
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
          progress.current = "途中の結果を保存";
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
    progress.phase("finish");
    progress.current = null;
    progress.step = "主キー・UNIQUE を読む";
    updateKeys(store, c);
    progress.step = "保存";
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
  private static Entry explainEntry(ParamSources.Session ses, Store store, String id, SqlRenderer.Result r, String hash, Progress progress) {
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
      // 計画を残し（取り出し方を変えたときに、DB にはつながずに取り出し直すため）、そこから取り出す
      List<String> properties = new ArrayList<>();
      for (SqlRenderer.Binding b : r.bindings) {
        properties.add(b.property);
      }
      SavedPlan saved = new SavedPlan(e.plan, e.markers, properties);
      try {
        store.savePlan(id, saved);
      } catch (IOException ex) {
        // 残せなくても、解析の結果は使う
      }
      return extract(hash, saved);
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
  static void updateKeys(Store store, Connection c) {
    // 計画に出てくる表と、結合に出てくる表、手で追加したリレーションの表の、主キー・UNIQUE の列を読む（列が一意かどうかも、ここから求める）
    Set<String> keyTables = new LinkedHashSet<>(store.extraTables);
    for (Entry e : store.statements.values()) {
      keyTables.addAll(e.tables);
      for (String[] j : e.joins) {
        keyTables.add(j[0].substring(0, j[0].lastIndexOf('.')));
        keyTables.add(j[1].substring(0, j[1].lastIndexOf('.')));
      }
    }
    List<String> ts = new ArrayList<>();
    List<String> tn = new ArrayList<>();
    for (String t : keyTables) {
      ts.add(t.substring(0, t.indexOf('.')));
      tn.add(t.substring(t.indexOf('.') + 1));
    }
    Map<String, List<TableKey>> tableKeys = new TreeMap<>();
    try (PreparedStatement ps = c.prepareStatement("SELECT n.nspname, c.relname, i.indisprimary,"
        + " ARRAY(SELECT a.attname::text FROM unnest(i.indkey[0:i.indnkeyatts - 1]) WITH ORDINALITY AS k(attnum, ord)"
        + " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum ORDER BY k.ord)"
        + " FROM unnest(?::text[], ?::text[]) AS x(s, t)"
        + " JOIN pg_namespace n ON n.nspname = x.s JOIN pg_class c ON c.relnamespace = n.oid AND c.relname = x.t"
        + " JOIN pg_index i ON i.indrelid = c.oid AND i.indisunique AND i.indpred IS NULL AND i.indexprs IS NULL"
        + " ORDER BY n.nspname, c.relname, NOT i.indisprimary, i.indnkeyatts")) {
      ps.setArray(1, c.createArrayOf("text", ts.toArray()));
      ps.setArray(2, c.createArrayOf("text", tn.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          List<String> kc = new ArrayList<>();
          for (Object x : (Object[]) rs.getArray(4).getArray()) {
            kc.add(String.valueOf(x));
          }
          tableKeys.computeIfAbsent(rs.getString(1) + "." + rs.getString(2), k -> new ArrayList<>()).add(new TableKey(rs.getBoolean(3), kc));
        }
      }
    } catch (SQLException e) {
      return;
    } finally {
      try {
        c.rollback();
      } catch (SQLException ignored) {
        // 読むだけ
      }
    }
    store.tableKeys.clear();
    store.tableKeys.putAll(tableKeys);
    deriveKeys(store);
  }

  /**
   * 列ごとの一意の順位を、表の鍵から求める（1 列の主キー 2、1 列の UNIQUE 3、n 列なら n × 2、UNIQUE は + 1。列を含む鍵のうち最小）。
   * 表の鍵を読んでいない前の版の結果では、読んであった順位をそのまま使う
   */
  static void deriveKeys(Store store) {
    if (store.tableKeys.isEmpty()) {
      return;
    }
    Map<String, Integer> keys = new TreeMap<>();
    for (Map.Entry<String, List<TableKey>> e : store.tableKeys.entrySet()) {
      for (TableKey k : e.getValue()) {
        int rank = k.columns.size() * 2 + (k.primary ? 0 : 1);
        for (String col : k.columns) {
          keys.merge(e.getKey() + "." + col, rank, Math::min);
        }
      }
    }
    store.keys.clear();
    store.keys.putAll(keys);
  }

  // ---- 集計 ----

  /** 2 つの列のつながり */
  static final class Link {
    String a;
    String b;
    /** parent: b が親、child: a が親（b が子）、one-to-one: どちらも一意、sibling: どちらも一意でない */
    String kind;
    /** 複数の列でできた鍵で親子（1 対 1）と決めたとき、親の表と鍵（schema.table の主キー（a, b））。そうでなければ null */
    String compositeKey;
    final Set<String> statements = new LinkedHashSet<>();
    /** 手で追加したリレーション（向きは、解析で拾ったものと同じく一意かどうかと表の関係で決める） */
    boolean manual;
    /** 手で決めた表の関係で、向きと種類を決めた */
    boolean byTable;
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
   * （同じ列の組を推し量っていれば、それに印を付ける）。手で決めた表の関係があれば、その 2 つの表の間のつながりの向きと種類をそれにする。
   */
  static List<Link> links(Store store, ManualRelations manual) {
    List<Link> out = store == null ? new ArrayList<>() : links(store);
    if (manual == null) {
      return out;
    }
    java.util.function.Predicate<String> isUnique = col -> store != null && unique(store, col);
    synchronized (manual) {
      for (Link l : out) {
        l.disabled = manual.isDisabled(l.a, l.b);
      }
      for (ManualRelations.Relation r : manual.relations) {
        Link found = null;
        for (Link l : out) {
          if (l.a.equals(r.a) && l.b.equals(r.b) || l.a.equals(r.b) && l.b.equals(r.a)) {
            found = l;
          }
        }
        if (found == null) {
          String[] o = orient(isUnique, null, r.a, r.b);
          found = new Link(o[0], o[1], o[2]);
          out.add(found);
        }
        found.manual = true;
        found.disabled = false;
      }
      for (Link l : out) {
        String ta = tableOfColumn(l.a);
        String tb = tableOfColumn(l.b);
        if (ta.equals(tb) || manual.tableKind(ta, tb) == null) {
          continue;
        }
        String[] o = orient(isUnique, manual, l.a, l.b);
        l.a = o[0];
        l.b = o[1];
        l.kind = o[2];
        l.compositeKey = null;
        l.byTable = true;
      }
    }
    return out;
  }

  /**
   * 列の組の向きと種類。[a, b, kind]（parent なら b が親。それ以外は名前の順）。手で決めた表の関係（親子・1 対 1・兄弟）があればそれ、
   * なければ一意かどうかで決める（片方だけが一意ならその側が親、どちらも一意なら 1 対 1、どちらも一意でなければ兄弟）
   */
  static String[] orient(java.util.function.Predicate<String> isUnique, ManualRelations manual, String a, String b) {
    String ta = tableOfColumn(a);
    String tb = tableOfColumn(b);
    String[] t = manual == null || ta.equals(tb) ? null : manual.tableKind(ta, tb);
    String kind;
    if (t != null) {
      if (t[2].equals("parent")) {
        return t[0].equals(ta) ? new String[] {a, b, "parent"} : new String[] {b, a, "parent"};
      }
      kind = t[2];
    } else {
      boolean ua = isUnique.test(a);
      boolean ub = isUnique.test(b);
      if (ua != ub) {
        return ub ? new String[] {a, b, "parent"} : new String[] {b, a, "parent"};
      }
      kind = ua ? "one-to-one" : "sibling";
    }
    return a.compareTo(b) <= 0 ? new String[] {a, b, kind} : new String[] {b, a, kind};
  }

  /**
   * 列に手でつないだ列のうち、親になるもの（向きは {@link #orient} で決める）。テナントごとのスキーマに合わせる
   */
  static java.util.function.Function<String, List<String>> manualParents(java.util.function.Predicate<String> isUnique,
      ManualRelations manual) {
    if (manual == null) {
      return col -> new ArrayList<String>();
    }
    return col -> {
      List<String> out = new ArrayList<>();
      for (String other : manual.pairsOf(col)) {
        String[] o = orient(isUnique, manual, col, other);
        if (o[2].equals("parent") && o[0].equals(col)) {
          out.add(other);
        }
      }
      return out;
    };
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
    List<Link> links = new ArrayList<>(out.values());
    applyCompositeKeys(store, links);
    return links;
  }

  /**
   * 複数の列でできた鍵で、兄弟になっていた列の組を親子に直す。1 つの statement の中で、表 P の主キー（か UNIQUE）のすべての列が
   * 表 C の列と = で結ばれていれば、P を親、C を子とする（comment.(categoryId, postId) → post の主キー (categoryId, postId)）。
   * C の側の結ばれた列が C の鍵そのものなら、1 対 1 とする。
   */
  static void applyCompositeKeys(Store store, List<Link> links) {
    Map<String, Link> byPair = new HashMap<>();
    for (Link l : links) {
      byPair.put(pairKey(l.a, l.b), l);
    }
    for (Map.Entry<String, Entry> e : store.statements.entrySet()) {
      // 表の組ごとの、この statement で = で結ばれた列の組
      Map<String, List<String[]>> byTables = new LinkedHashMap<>();
      for (String[] j : e.getValue().joins) {
        String ta = tableOfColumn(j[0]);
        String tb = tableOfColumn(j[1]);
        if (ta.equals(tb)) {
          continue;
        }
        byTables.computeIfAbsent(ta.compareTo(tb) < 0 ? ta + "\u0000" + tb : tb + "\u0000" + ta, k -> new ArrayList<>()).add(j);
      }
      for (Map.Entry<String, List<String[]>> g : byTables.entrySet()) {
        String[] t = g.getKey().split("\u0000");
        for (int d = 0; d < 2; d++) {
          String parent = t[d];
          String child = t[1 - d];
          for (TableKey key : store.tableKeys.getOrDefault(parent, new ArrayList<TableKey>())) {
            if (key.columns.size() < 2) {
              continue;
            }
            // 親の鍵の列ごとに、結ばれた子の列
            List<String[]> pairs = new ArrayList<>();
            for (String kc : key.columns) {
              String pcol = parent + "." + kc;
              String ccol = null;
              for (String[] j : g.getValue()) {
                if (j[0].equals(pcol) && tableOfColumn(j[1]).equals(child)) {
                  ccol = j[1];
                } else if (j[1].equals(pcol) && tableOfColumn(j[0]).equals(child)) {
                  ccol = j[0];
                }
              }
              if (ccol == null) {
                break;
              }
              pairs.add(new String[] {ccol, pcol});
            }
            if (pairs.size() < key.columns.size()) {
              continue;
            }
            // 子の側の結ばれた列が、子の鍵そのものなら 1 対 1
            Set<String> childCols = new HashSet<>();
            for (String[] p : pairs) {
              childCols.add(p[0].substring(p[0].lastIndexOf('.') + 1));
            }
            boolean oneToOne = false;
            for (TableKey ck : store.tableKeys.getOrDefault(child, new ArrayList<TableKey>())) {
              if (new HashSet<>(ck.columns).equals(childCols)) {
                oneToOne = true;
              }
            }
            for (String[] p : pairs) {
              Link l = byPair.get(pairKey(p[0], p[1]));
              // 1 列の鍵で決めた親子・1 対 1 はそのまま。兄弟だったものだけを直す
              if (l == null || !l.kind.equals("sibling")) {
                continue;
              }
              l.a = p[0];
              l.b = p[1];
              l.kind = oneToOne ? "one-to-one" : "parent";
              l.compositeKey = parent + " の" + key.describe();
            }
            break;
          }
        }
      }
    }
  }

  private static String pairKey(String a, String b) {
    return a.compareTo(b) < 0 ? a + "\u0000" + b : b + "\u0000" + a;
  }

  private static String tableOfColumn(String col) {
    return col.substring(0, col.lastIndexOf('.'));
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
      return Relations.origins(unique::contains, links, column, manualParents(unique::contains, manual));
    }

    /** 比べている列から、源流と判断する列（たどった源流、なければ一意の列。どちらもなければ null） */
    String decided(List<String> compared) {
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
   * @param manualParents 列に手で追加したつながりで決まった親。あれば、その列の源流は手で設定したものだけにする（解析で推し量ったものより優先する）。
   *     兄弟をたどった先の列に、手で追加したつながりで決まった親があれば、それも源流にする
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
        // 無効にしたつながりは使わない。手で追加した親子は manualParents で扱う
        if (l.disabled || l.manual && l.kind.equals("parent")) {
          continue;
        }
        String other = l.a.equals(col) ? l.b : l.a;
        int c = Math.min(count, l.statements.size());
        List<String> next = new ArrayList<>(path);
        next.add(col);
        if (l.kind.equals("sibling") && next.size() < MAX_HOPS && !visited.contains(other) && !manualParents.apply(other).isEmpty()) {
          // 兄弟に、手で追加したつながりで決まった親がある
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
          // 親が 1 列で一意でなければ（複数の列でできた鍵の親）、さらに親をたどる（祖先まで）
          for (Origin o : climb(isUnique, links, other, next, c, manualParents, 0)) {
            if (found.add(o.column)) {
              out.add(o);
            }
          }
        } else if (l.kind.equals("sibling") && next.size() < MAX_HOPS && visited.add(other)) {
          queue.add(new Object[] {other, next, c});
        }
      }
    }
    return out;
  }

  /** 祖先をたどる段数の上限 */
  private static final int MAX_ANCESTORS = 4;

  /**
   * 親 column から、さらに親をたどる。column が 1 列で一意か、それ以上の親がなければ column が源流。
   * 手で追加したつながりで決まった親があれば、それを源流にする。
   *
   * @param path column の手前までにたどった列
   */
  private static List<Origin> climb(java.util.function.Predicate<String> isUnique, List<Link> links, String column, List<String> path,
      int count, java.util.function.Function<String, List<String>> manualParents, int depth) {
    List<Origin> out = new ArrayList<>();
    List<String> via = new ArrayList<>(path);
    via.add(column);
    List<String> manual = manualParents.apply(column);
    if (!manual.isEmpty() && !isUnique.test(column)) {
      for (String p : manual) {
        out.add(new Origin(p, via, count, true));
      }
      return out;
    }
    if (isUnique.test(column) || depth >= MAX_ANCESTORS) {
      out.add(new Origin(column, path, count));
      return out;
    }
    for (Link l : links) {
      // 手で追加した親子は manualParents で扱う
      if (!l.disabled && !l.manual && l.kind.equals("parent") && l.a.equals(column) && !path.contains(l.b)) {
        out.addAll(climb(isUnique, links, l.b, via, Math.min(count, l.statements.size()), manualParents, depth + 1));
      }
    }
    if (out.isEmpty()) {
      out.add(new Origin(column, path, count));
    }
    return out;
  }
}
