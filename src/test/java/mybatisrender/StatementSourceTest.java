package mybatisrender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatementSourceTest {

  @TempDir
  Path dir;

  private Path write(String xml) throws Exception {
    Path f = dir.resolve("M.xml");
    Files.write(f, xml.getBytes(StandardCharsets.UTF_8));
    return f;
  }

  private static final String MAPPER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
      + "<mapper namespace=\"m\">\n"
      + "  <!-- <select id=\"find\"> コメントの中は無視する -->\n"
      + "  <sql id=\"find\">x</sql>\n"
      + "  <select id=\"find\" resultType=\"map\">\n"
      + "    SELECT * FROM t\n"
      + "    <where>\n"
      + "      <if test=\"a != null\">a = #{a}</if>\n"
      + "    </where>\n"
      + "  </select>\n"
      + "  <insert id=\"add\">\n"
      + "    <selectKey keyProperty=\"id\" resultType=\"long\" order=\"BEFORE\">SELECT 1</selectKey>\n"
      + "    <![CDATA[ INSERT INTO t VALUES (#{id}) -- </insert> ]]>\n"
      + "  </insert>\n"
      + "  <delete id=\"empty\"/>\n"
      + "</mapper>\n";

  @Test
  void 書かれたままの要素を字下げを除いて返す() throws Exception {
    StatementSource.Snippet s = StatementSource.find(write(MAPPER), "find");
    assertEquals(6, s.line);
    assertEquals("<select id=\"find\" resultType=\"map\">\n"
        + "  SELECT * FROM t\n"
        + "  <where>\n"
        + "    <if test=\"a != null\">a = #{a}</if>\n"
        + "  </where>\n"
        + "</select>", s.xml);
  }

  @Test
  void CDATAの中の終了タグに惑わされない() throws Exception {
    StatementSource.Snippet s = StatementSource.find(write(MAPPER), "add");
    assertEquals(12, s.line);
    assertEquals(true, s.xml.endsWith("]]>\n</insert>"), s.xml);
    // selectKey は元の statement を返す
    assertEquals(s.xml, StatementSource.find(write(MAPPER), "add!selectKey").xml);
  }

  @Test
  void 空要素と見つからない場合() throws Exception {
    assertEquals("<delete id=\"empty\"/>", StatementSource.find(write(MAPPER), "empty").xml);
    assertNull(StatementSource.find(write(MAPPER), "nothing"));
    assertNull(StatementSource.find(write("<mapper><select id=\"x\">"), "x"));
  }
}
