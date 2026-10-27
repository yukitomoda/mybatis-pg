package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelationsTest {

  private static final String URL = System.getenv("MYBATIS_PG_TEST_URL");

  @Test
  void 計画の結合の条件から列の組を取り出す() {
    String plan = "[{\"Plan\": {\"Node Type\": \"Hash Join\", \"Hash Cond\": \"((h.user_id)::bigint = u.id)\","
        + " \"Join Filter\": \"(h.post_id = p.id)\", \"Filter\": \"(h.x = $1)\", \"Plans\": ["
        + " {\"Node Type\": \"Seq Scan\", \"Relation Name\": \"post_histories\", \"Schema\": \"app\", \"Alias\": \"h\"},"
        + " {\"Node Type\": \"Seq Scan\", \"Relation Name\": \"users\", \"Schema\": \"app\", \"Alias\": \"u\"},"
        + " {\"Node Type\": \"Index Scan\", \"Relation Name\": \"posts\", \"Schema\": \"app\", \"Alias\": \"p\","
        + "  \"Index Cond\": \"(p.id = h.post_id)\"},"
        + " {\"Node Type\": \"CTE Scan\", \"CTE Name\": \"t\", \"Alias\": \"t\", \"Filter\": \"(t.id = u.id)\"},"
        + " {\"Node Type\": \"Seq Scan\", \"Relation Name\": \"logs\", \"Schema\": \"app\", \"Alias\": \"l\","
        + "  \"Filter\": \"(((l.at)::date = (h.at)::date) AND ((l.at)::timestamp with time zone > u.created))\"}]}}]";
    List<String> got = new ArrayList<>();
    for (String[] j : Relations.joins(plan)) {
      got.add(j[0] + "=" + j[1] + " " + j[2]);
    }
    // 型の変換は除く。同じ組は 1 つ。CTE の列は実際の表ではないので使わない。パラメータとの比較、日付に直した比較は使わない
    assertEquals(Arrays.asList("app.post_histories.user_id=app.users.id Hash Cond", "app.post_histories.post_id=app.posts.id Join Filter"),
        got);
  }

  @Test
  void 外部キーがなくてもSQLから源流を探し_結果を保存する(@TempDir Path dir) throws Exception {
    assumeTrue(URL != null, "MYBATIS_PG_TEST_URL がないため省略");
    try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
      s.execute("DROP SCHEMA IF EXISTS reltest CASCADE");
      s.execute("CREATE SCHEMA reltest");
      // 外部キーは張らない
      s.execute("CREATE TABLE reltest.users (id bigint PRIMARY KEY, name text)");
      s.execute("CREATE TABLE reltest.posts (id bigint PRIMARY KEY, user_id bigint, title text)");
      s.execute("CREATE TABLE reltest.post_histories (id bigint PRIMARY KEY, post_id bigint, user_id bigint, at timestamp)");
    }
    Path mapper = dir.resolve("RelMapper.xml");
    Files.write(mapper, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
        + "<mapper namespace=\"rel\">\n"
        // 投稿と利用者
        + "  <select id=\"posts\" resultType=\"map\">SELECT p.title, u.name FROM reltest.posts p JOIN reltest.users u ON u.id = p.user_id"
        + "   WHERE p.id = #{id}</select>\n"
        // 投稿履歴と投稿は user_id どうし（どちらも一意でない）。if の中でも拾う
        + "  <select id=\"histories\" resultType=\"map\">SELECT h.* FROM reltest.post_histories h"
        + "   <where><if test=\"withPosts\">EXISTS (SELECT 1 FROM reltest.posts p WHERE p.user_id = h.user_id AND p.id = h.post_id)</if>"
        + "   AND h.user_id = #{userId}</where></select>\n"
        // スキーマはパラメータ（${schema}）。値がなければ、表を持つスキーマ（reltest。search_path にない）を DB から探す。
        // #{postId} IS NULL は型を決められないので、そのパラメータにだけ値の型を書く（#{from}::date は date のまま）
        + "  <select id=\"bySchema\" resultType=\"map\">SELECT h.* FROM ${schema}.post_histories h JOIN ${schema}.users u ON u.id = h.user_id"
        + "   WHERE (#{postId} IS NULL OR h.post_id = #{postId}) AND h.at &gt;= #{from}::date</select>\n"
        + "</mapper>\n").getBytes(StandardCharsets.UTF_8));
    SqlRenderer renderer = SqlRenderer.load(Collections.singletonList(mapper));
    Relations.Store store = Relations.Store.open(dir.resolve("relations"), "proj", "conn");
    // 一時停止を頼まれていれば、解析せずに止まり、途中までとして保存する
    Relations.Progress paused = new Relations.Progress();
    paused.stop = true;
    try (Connection c = DriverManager.getConnection(URL)) {
      Relations.analyze(renderer, Arrays.asList("rel.posts", "rel.histories"), null, c, store, paused);
    }
    assertEquals(0, paused.analyzed);
    assertEquals(false, Relations.Store.open(dir.resolve("relations"), "proj", "conn").complete);
    Relations.Progress progress = new Relations.Progress();
    try (Connection c = DriverManager.getConnection(URL)) {
      Relations.analyze(renderer, Arrays.asList("rel.posts", "rel.histories"), null, c, store, progress);
    }
    assertEquals(2, progress.analyzed);
    assertTrue(store.complete);
    // パラメータと比べている列も記録する（posts の #{id} は posts.id、histories の #{userId} は post_histories.user_id）
    assertEquals(Collections.singletonList("reltest.posts.id"), store.statements.get("rel.posts").params.get("id"));
    assertEquals(Collections.singletonList("reltest.post_histories.user_id"), store.statements.get("rel.histories").params.get("userId"));
    assertTrue(store.statements.values().stream().allMatch(e -> e.error == null), store.statements.toString());

    List<Relations.Link> links = Relations.links(store);
    // 投稿.user_id → 利用者.id（親）、投稿履歴.post_id → 投稿.id（親）、投稿履歴.user_id ─ 投稿.user_id（兄弟）
    List<String> got = new ArrayList<>();
    for (Relations.Link l : links) {
      got.add(l.a.replace("reltest.", "") + " " + l.kind + " " + l.b.replace("reltest.", ""));
    }
    assertTrue(got.contains("posts.user_id parent users.id"), got.toString());
    assertTrue(got.contains("post_histories.post_id parent posts.id"), got.toString());
    assertTrue(got.contains("post_histories.user_id sibling posts.user_id"), got.toString());

    // 投稿履歴.user_id の源流は、兄弟（投稿.user_id）をたどって 利用者.id
    List<Relations.Origin> origins = Relations.origins(store, links, "reltest.post_histories.user_id");
    assertEquals("reltest.users.id", origins.get(0).column);
    assertEquals(Arrays.asList("reltest.post_histories.user_id", "reltest.posts.user_id"), origins.get(0).path);

    // 保存した結果を読み直せる。SQL が変わっていなければ、解析し直さない
    Relations.Store again = Relations.Store.open(dir.resolve("relations"), "proj", "conn");
    assertEquals(store.statements.keySet(), again.statements.keySet());
    Relations.Progress p2 = new Relations.Progress();
    try (Connection c = DriverManager.getConnection(URL)) {
      Relations.analyze(renderer, Arrays.asList("rel.posts", "rel.histories"), null, c, again, p2);
    }
    assertEquals(0, p2.analyzed);
    assertEquals(got.size(), Relations.links(again).size());

    // パラメータの名前だけを書き換えても（$1 にした SQL は同じ）、気づいて解析し直す
    Path renamed = dir.resolve("RelMapper2.xml");
    Files.write(renamed, new String(Files.readAllBytes(mapper), StandardCharsets.UTF_8).replace("#{userId}", "#{uid}")
        .getBytes(StandardCharsets.UTF_8));
    SqlRenderer renamedRenderer = SqlRenderer.load(Collections.singletonList(renamed));
    Relations.Progress pr = new Relations.Progress();
    try (Connection c = DriverManager.getConnection(URL)) {
      Relations.analyze(renamedRenderer, Arrays.asList("rel.posts", "rel.histories"), null, c, again, pr);
    }
    assertEquals(1, pr.analyzed);
    assertTrue(again.statements.get("rel.histories").params.containsKey("uid"), again.statements.get("rel.histories").params.toString());
    // 元に戻す（以降の確かめのため）
    try (Connection c = DriverManager.getConnection(URL)) {
      Relations.analyze(renderer, Arrays.asList("rel.posts", "rel.histories"), null, c, again, new Relations.Progress());
    }

    // statement が増えたら、増えたものだけを解析する
    Relations.Progress p3 = new Relations.Progress();
    try (Connection c = DriverManager.getConnection(URL)) {
      Relations.analyze(renderer, Arrays.asList("rel.posts", "rel.histories", "rel.bySchema"), null, c, again, p3);
    }
    assertEquals(1, p3.analyzed);
    assertEquals(null, again.statements.get("rel.bySchema").error);
    List<String> got3 = new ArrayList<>();
    for (Relations.Link l : Relations.links(again)) {
      got3.add(l.a.replace("reltest.", "") + " " + l.kind + " " + l.b.replace("reltest.", ""));
    }
    // ${schema} の statement からも（post_histories.user_id → users.id）
    assertTrue(got3.contains("post_histories.user_id parent users.id"), got3.toString());

    // 値を渡せば、その値でスキーマを決める（ない項目は仮の値）
    Relations.Store other = Relations.Store.open(null, "proj", "conn2");
    java.util.Map<String, Object> params = new java.util.HashMap<>();
    params.put("schema", "nosuchschema");
    try (Connection c = DriverManager.getConnection(URL)) {
      Relations.analyze(renderer, Arrays.asList("rel.bySchema"), params, c, other, new Relations.Progress());
    }
    assertTrue(String.valueOf(other.statements.get("rel.bySchema").error).contains("nosuchschema"),
        String.valueOf(other.statements.get("rel.bySchema").error));
    try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
      s.execute("DROP SCHEMA reltest CASCADE");
    }
  }

  @Test
  void 失敗する文が混じっても_トランザクションを区切り直しても_ほかの結果は変わらない(@TempDir Path dir) throws Exception {
    assumeTrue(URL != null, "MYBATIS_PG_TEST_URL がないため省略");
    try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
      s.execute("DROP SCHEMA IF EXISTS reltest2 CASCADE");
      s.execute("CREATE SCHEMA reltest2");
      s.execute("CREATE TABLE reltest2.users (id bigint PRIMARY KEY)");
      s.execute("CREATE TABLE reltest2.posts (id bigint PRIMARY KEY, user_id bigint)");
    }
    // 120 件（トランザクションを 50 件ごとに区切り直す）。3 件に 1 件は、ない表を読んで失敗する。
    // 型を決められないパラメータ（#{x} IS NULL）で作り直す件も混ぜる
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
        + "<mapper namespace=\"many\">\n");
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 120; i++) {
      String body = i % 3 == 1 ? "SELECT * FROM reltest2.nosuch WHERE id = #{id}"
          : "SELECT p.id FROM reltest2.posts p JOIN reltest2.users u ON u.id = p.user_id WHERE (#{x} IS NULL OR p.id = #{x}) AND u.id &gt; " + i;
      xml.append("  <select id=\"s").append(i).append("\" resultType=\"map\">").append(body).append("</select>\n");
      ids.add("many.s" + i);
    }
    xml.append("</mapper>\n");
    Path mapper = dir.resolve("ManyMapper.xml");
    Files.write(mapper, xml.toString().getBytes(StandardCharsets.UTF_8));
    SqlRenderer renderer = SqlRenderer.load(Collections.singletonList(mapper));
    Relations.Store store = Relations.Store.open(null, "proj", "conn");
    Relations.Progress progress = new Relations.Progress();
    try (Connection c = DriverManager.getConnection(URL)) {
      Relations.analyze(renderer, ids, null, c, store, progress);
      // 解析の後に、接続に PREPARE が残っていない
      c.setAutoCommit(true);
      try (Statement s = c.createStatement(); java.sql.ResultSet rs = s.executeQuery(
          "SELECT count(*) FROM pg_prepared_statements WHERE name LIKE 'mybatis_pg_infer%'")) {
        rs.next();
        assertEquals(0, rs.getInt(1));
      }
    }
    assertEquals(120, progress.analyzed);
    for (int i = 0; i < 120; i++) {
      Relations.Entry e = store.statements.get("many.s" + i);
      if (i % 3 == 1) {
        assertTrue(e.error != null && e.error.contains("nosuch"), i + ": " + e.error);
      } else {
        assertEquals(null, e.error, "many.s" + i);
        assertEquals(1, e.joins.size(), "many.s" + i);
        assertEquals("reltest2.posts.user_id", e.joins.get(0)[0].compareTo(e.joins.get(0)[1]) < 0 ? e.joins.get(0)[0] : e.joins.get(0)[1]);
      }
    }
    assertTrue(progress.planMillis > 0);
    try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
      s.execute("DROP SCHEMA reltest2 CASCADE");
    }
  }

  @Test
  void 仮の値で評価できない条件や壊れるSQLも_できるだけ解析する(@TempDir Path dir) throws Exception {
    assumeTrue(URL != null, "MYBATIS_PG_TEST_URL がないため省略");
    try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
      s.execute("DROP SCHEMA IF EXISTS reltest3 CASCADE");
      s.execute("CREATE SCHEMA reltest3");
      s.execute("CREATE TABLE reltest3.users (id bigint PRIMARY KEY, name text)");
      s.execute("CREATE TABLE reltest3.posts (id bigint PRIMARY KEY, user_id bigint, at time)");
    }
    String join = "SELECT p.id FROM reltest3.posts p JOIN reltest3.users u ON u.id = p.user_id ";
    String[][] statements = {
      // time の列と比べる（前は仮の値の "1" を time にできなかった）
      {"time", join + "WHERE p.at = #{at}"},
      // 文字と数の取り違え（前は "x" > 0 で For input string: "x"）
      {"number", join + "<where><if test=\"code != null and code != '' and code &gt; 0\">p.id = #{code}</if></where>"},
      // .size() を呼ぶ項目（前は 1.size() で失敗）
      {"size", join + "<where><if test=\"names != null and names.size() &gt; 0\">u.name IS NOT NULL</if></where>"},
      // 仮の値で評価できない条件の中の結合（条件をすべて true にして組み立てる）
      {"allTrue", "SELECT p.id FROM reltest3.posts p <if test=\"mode.name() == 'X'\">JOIN reltest3.users u ON u.id = p.user_id</if>"},
      // 両立しない分岐がそろうと壊れる（if を通さない形で、外側の結合を拾う）
      {"minimal", join + "<if test=\"a != null\">WHERE p.id = 1</if> <if test=\"b != null\">WHERE p.id = 2</if>"},
      // CREATE などは解析しない（エラーにしない）
      {"ddl", "CREATE TEMP TABLE reltest3_tmp (id int)"},
    };
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
        + "<mapper namespace=\"hard\">\n");
    List<String> ids = new ArrayList<>();
    for (String[] st : statements) {
      String tag = st[0].equals("ddl") ? "update" : "select";
      xml.append("  <").append(tag).append(" id=\"").append(st[0]).append("\"").append(tag.equals("select") ? " resultType=\"map\"" : "")
          .append(">").append(st[1]).append("</").append(tag).append(">\n");
      ids.add("hard." + st[0]);
    }
    xml.append("</mapper>\n");
    Path mapper = dir.resolve("HardMapper.xml");
    Files.write(mapper, xml.toString().getBytes(StandardCharsets.UTF_8));
    SqlRenderer renderer = SqlRenderer.load(Collections.singletonList(mapper));
    Relations.Store store = Relations.Store.open(null, "proj", "conn");
    try (Connection c = DriverManager.getConnection(URL)) {
      Relations.analyze(renderer, ids, null, c, store, new Relations.Progress());
    }
    for (String[] st : statements) {
      Relations.Entry e = store.statements.get("hard." + st[0]);
      assertEquals(null, e.error, st[0]);
      if (!st[0].equals("ddl")) {
        assertEquals(1, e.joins.size(), st[0]);
      }
    }
    try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
      s.execute("DROP SCHEMA reltest3 CASCADE");
    }
  }

  @Test
  void 値の候補は_リレーションの源流の列を先に_比べている列を後に出す() throws Exception {
    Relations.Store store = Relations.Store.open(null, "proj", "conn");
    List<String[]> joins1 = new ArrayList<>();
    joins1.add(new String[] {"app.posts.user_id", "app.users.id", "Hash Cond"});
    joins1.add(new String[] {"app.posts.user_id", "app.users.id", "Hash Cond"});
    store.statements.put("m.a", new Relations.Entry("h1", joins1, null));
    store.statements.put("m.b", new Relations.Entry("h2", Collections.singletonList(new String[] {"app.posts.user_id", "app.users.id", "Hash Cond"}), null));
    store.statements.put("m.c", new Relations.Entry("h3",
        Collections.singletonList(new String[] {"app.post_histories.user_id", "app.posts.user_id", "Hash Cond"}), null));
    store.keys.put("app.users.id", 2);
    store.keys.put("app.posts.id", 2);
    Relations.Graph graph = new Relations.Graph(store, Relations.links(store));

    java.util.Map<String, List<ParamSources.Source>> byProperty = new java.util.LinkedHashMap<>();
    byProperty.put("userId", new ArrayList<>(Collections.singletonList(
        new ParamSources.Source("app", "post_histories", "user_id", "(h.user_id = $1)", true))));
    // 比べている列がそれ自体一意なら、源流は足さない
    byProperty.put("id", new ArrayList<>(Collections.singletonList(new ParamSources.Source("app", "users", "id", "(u.id = $2)", true))));
    java.util.Map<String, List<ParamSources.Source>> got = Relations.candidates(byProperty, graph);

    List<ParamSources.Source> user = got.get("userId");
    assertEquals(2, user.size());
    assertEquals("users.id", user.get(0).table + "." + user.get(0).column);
    assertEquals("origin", user.get(0).role);
    assertEquals(Arrays.asList("app.post_histories.user_id", "app.posts.user_id"), user.get(0).via);
    assertEquals("post_histories.user_id → posts.user_id → users.id", user.get(0).condition);
    // 根拠の SQL は、たどったつながりのうち少ないほう（兄弟は 1 件）
    assertEquals(1, user.get(0).count);
    assertEquals("compared", user.get(1).role);
    assertEquals("post_histories", user.get(1).table);
    assertEquals(1, got.get("id").size());

    // 源流が比べている列でもあれば、その列を先に（比べている列のまま）
    java.util.Map<String, List<ParamSources.Source>> both = new java.util.LinkedHashMap<>();
    both.put("userId", new ArrayList<>(Arrays.asList(new ParamSources.Source("app", "posts", "user_id", "(p.user_id = $1)", true),
        new ParamSources.Source("app", "users", "id", "(u.id = $1)", true))));
    List<ParamSources.Source> b = Relations.candidates(both, graph).get("userId");
    assertEquals(2, b.size());
    assertEquals("users.id compared", b.get(0).table + "." + b.get(0).column + " " + b.get(0).role);
    assertEquals("posts.user_id", b.get(1).table + "." + b.get(1).column);

    // リレーションを解析していなければ、比べている列だけ
    assertEquals(1, Relations.candidates(byProperty, null).get("userId").size());
  }

  @Test
  void 値の候補に_同じ名前のパラメータが源流と判断した列を最後に足す() throws Exception {
    Relations.Store store = Relations.Store.open(null, "proj", "conn");
    store.keys.put("app.users.id", 2);
    // 2 つの statement で、customerId は orders.customer_id と比べられ、その源流は users.id
    java.util.Map<String, List<String>> p1 = new java.util.LinkedHashMap<>();
    p1.put("cond.customerId", Collections.singletonList("app.orders.customer_id"));
    store.statements.put("m.a", new Relations.Entry("h1",
        Collections.singletonList(new String[] {"app.orders.customer_id", "app.users.id", "Hash Cond"}), null, p1));
    java.util.Map<String, List<String>> p2 = new java.util.LinkedHashMap<>();
    p2.put("customerId", Collections.singletonList("app.users.id"));
    // 短い名前（id）は使わない
    p2.put("id", Collections.singletonList("app.users.id"));
    store.statements.put("m.b", new Relations.Entry("h2", new ArrayList<>(), null, p2));
    Relations.Graph graph = new Relations.Graph(store, Relations.links(store));

    java.util.Map<String, List<ParamSources.Source>> byProperty = new java.util.LinkedHashMap<>();
    // 比べている列を推論できなかったパラメータ
    byProperty.put("customerId", new ArrayList<>());
    // 比べている列はあるが、源流がない
    byProperty.put("x.customerId", new ArrayList<>(Collections.singletonList(new ParamSources.Source("app", "logs", "cid", "(l.cid = $1)", true))));
    byProperty.put("id", new ArrayList<>());
    java.util.Map<String, List<ParamSources.Source>> got = Relations.candidates(byProperty, graph);

    List<ParamSources.Source> a = got.get("customerId");
    assertEquals(1, a.size());
    assertEquals("users.id name 2", a.get(0).table + "." + a.get(0).column + " " + a.get(0).role + " " + a.get(0).count);
    List<ParamSources.Source> b = got.get("x.customerId");
    assertEquals(2, b.size());
    assertEquals("compared", b.get(0).role);
    assertEquals("users.id name", b.get(1).table + "." + b.get(1).column + " " + b.get(1).role);
    assertTrue(got.get("id").isEmpty());
  }
}
