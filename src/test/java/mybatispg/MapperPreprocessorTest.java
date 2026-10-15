package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXParseException;

class MapperPreprocessorTest {

  private static final Path DIR = Paths.get("src/test/resources/classref");

  @Test
  void 前処理なしではクラス参照のせいで読み込めない() throws Exception {
    Configuration configuration = new Configuration();
    try (InputStream in = Files.newInputStream(DIR.resolve("OrderMapper.xml"))) {
      XMLMapperBuilder builder = new XMLMapperBuilder(in, configuration, "OrderMapper.xml", configuration.getSqlFragments());
      assertThrows(RuntimeException.class, builder::parse);
    }
  }

  @Test
  void 前処理するとクラスなしで読み込んで展開できる() throws Exception {
    SqlRenderer renderer = SqlRenderer.load(Arrays.asList(DIR.resolve("OrderMapper.xml"), DIR.resolve("ResultMapOnly.xml")));
    assertEquals(java.util.Collections.emptyList(), renderer.getLoadErrors());

    assertEquals(Arrays.asList("sample.OrderMapper.findById", "sample.OrderMapper.insert",
        "sample.OrderMapper.insert!selectKey", "sample.OrderMapper.search"), renderer.statementIds());

    Map<String, Object> id = new HashMap<>();
    id.put("id", 7);
    assertEquals("SELECT * FROM orders WHERE id = 7 AND amount < 1000 AND memo = '#{not a param}'",
        normalize(renderer.render("sample.OrderMapper.findById", id).literalSql));

    Map<String, Object> cond = new HashMap<>();
    cond.put("status", "OPEN");
    cond.put("from", "2026-10-01");
    assertEquals("SELECT * FROM orders WHERE status = 'OPEN' AND ordered_at >= '2026-10-01'",
        normalize(renderer.render("sample.OrderMapper.search", cond).literalSql));

    Map<String, Object> order = new HashMap<>();
    order.put("id", 1);
    order.put("status", "NEW");
    assertEquals("INSERT INTO orders (id, status) VALUES (1, 'NEW')",
        normalize(renderer.render("sample.OrderMapper.insert", order).literalSql));
    assertEquals("SELECT nextval('order_seq')",
        normalize(renderer.render("sample.OrderMapper.insert!selectKey", order).literalSql));
  }

  @Test
  void パラメータのクラス参照オプションだけを除く() {
    assertEquals("a #{id, jdbcType=INTEGER} b",
        MapperPreprocessor.stripParameterClassOptions("a #{id, javaType=X, typeHandler=Y, jdbcType=INTEGER} b"));
    assertEquals("#{(a, b):VARCHAR,mode=IN}",
        MapperPreprocessor.stripParameterClassOptions("#{(a, b):VARCHAR,javaType=X,mode=IN}"));
    // エスケープされたトークンと閉じていないトークンはそのまま残す
    assertEquals("\\#{x, javaType=X} #{y} #{z, javaType=Z",
        MapperPreprocessor.stripParameterClassOptions("\\#{x, javaType=X} #{y,typeHandler=H} #{z, javaType=Z"));
    // ${} は対象外
    assertEquals("${x, javaType=X}", MapperPreprocessor.stripParameterClassOptions("${x, javaType=X}"));
  }

  @Test
  void XMLの構文エラーは元ファイルの行番号で報告する() {
    String xml = "<?xml version=\"1.0\"?>\n<mapper namespace=\"x\">\n  <select id=\"a\">\n</mapper>\n";
    SAXParseException e = assertThrows(SAXParseException.class,
        () -> MapperPreprocessor.preprocess(new java.io.ByteArrayInputStream(xml.getBytes("UTF-8")), "broken.xml"));
    assertEquals(4, e.getLineNumber());
    assertTrue(e.getSystemId().endsWith("broken.xml"));
  }

  private static String normalize(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }
}
