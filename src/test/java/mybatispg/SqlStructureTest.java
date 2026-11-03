package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlStructureTest {

  private static List<String> spans(SqlRenderer r, String id, Map<String, Object> params) {
    SqlRenderer.Result res = r.render(id, params);
    List<String> out = new ArrayList<>();
    for (SqlStructure.Span s : r.structure(id, params, res)) {
      out.add(s.kind + "[" + res.literalSql.substring(s.start, s.end).replaceAll("\\s+", " ") + "]");
    }
    return out;
  }

  @Test
  void ifとforeachから出た範囲を_実際のSQLの位置で返す(@TempDir Path dir) throws Exception {
    Path mapper = dir.resolve("M.xml");
    Files.write(mapper, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
        + "<mapper namespace=\"m\">\n"
        + "  <select id=\"find\" resultType=\"map\">SELECT * FROM t\n"
        + "    <where>\n"
        + "      <if test=\"a != null\">AND a = #{a}</if>\n"
        + "      <if test=\"b != null\">AND b = #{b}<if test=\"c != null\"> AND c = #{c}</if></if>\n"
        + "      <if test=\"ids != null\">AND id IN <foreach collection=\"ids\" item=\"i\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></if>\n"
        + "    </where>\n"
        + "  </select>\n"
        + "  <update id=\"up\">UPDATE t\n"
        + "    <set>\n"
        + "      <if test=\"a != null\">a = #{a},</if>\n"
        + "      <if test=\"b != null\">b = #{b},</if>\n"
        + "    </set>\n"
        + "    WHERE id = #{id}\n"
        + "  </update>\n"
        + "</mapper>\n").getBytes(StandardCharsets.UTF_8));
    SqlRenderer r = SqlRenderer.load(Collections.singletonList(mapper));

    Map<String, Object> p = new LinkedHashMap<>();
    p.put("a", 1);
    p.put("b", 2);
    p.put("c", 3);
    p.put("ids", Arrays.asList(10, 20));
    // 先頭の AND は <where> が取り除く（範囲は if の中身の残り）。入れ子の if、foreach の全体と 1 回ごと
    assertEquals(Arrays.asList("if[a = 1]", "if[AND b = 2 AND c = 3]", "if[AND c = 3]", "if[AND id IN (10,20)]", "foreach[(10,20)]",
        "item[10]", "item[20]"), spans(r, "m.find", p));
    // 最初の if が false でも、2 つ目の先頭の AND を取り除く
    p.put("a", null);
    assertEquals("if[b = 2 AND c = 3]", spans(r, "m.find", p).get(0));

    Map<String, Object> u = new LinkedHashMap<>();
    u.put("a", 1);
    u.put("b", 2);
    u.put("id", 9);
    // 末尾の , は <set> が取り除く
    assertEquals(Arrays.asList("if[a = 1,]", "if[b = 2]"), spans(r, "m.up", u));
  }

  @Test
  void 空白を除くと範囲が同じでも_入れ子の深さで内と外を決める(@TempDir Path dir) throws Exception {
    Path mapper = dir.resolve("N.xml");
    Files.write(mapper, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
        + "<mapper namespace=\"n\">\n"
        + "  <select id=\"even\" resultType=\"map\">SELECT 1 WHERE 0 IN (0\n"
        + "    <foreach item=\"i\" collection=\"list\">\n"
        + "      <if test=\"i % 2 == 0\">\n"
        + "        , ${i}\n"
        + "      </if>\n"
        + "    </foreach>)\n"
        + "  </select>\n"
        + "</mapper>\n").getBytes(StandardCharsets.UTF_8));
    SqlRenderer r = SqlRenderer.load(Collections.singletonList(mapper));
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("list", Arrays.asList(1, 2, 3, 4));
    SqlRenderer.Result res = r.render("n.even", p);
    List<String> got = new ArrayList<>();
    for (SqlStructure.Span s : r.structure("n.even", p, res)) {
      got.add(s.kind + s.depth + "[" + res.literalSql.substring(s.start, s.end).replaceAll("\\s+", " ").trim() + "]");
    }
    // foreach の 1 回分（item）が外、if が内（同じ範囲でも、深さの小さいものを先＝外側に）
    assertEquals(Arrays.asList("foreach0[, 2 , 4]", "item1[, 2]", "if2[, 2]", "item1[, 4]", "if2[, 4]"), got);
  }
}
