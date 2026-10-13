package mybatisrender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadErrorTest {

  private static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n";

  @Test
  void 読み込めないファイルやstatementがあっても残りは使える(@TempDir Path dir) throws Exception {
    Path good = write(dir, "Good.xml", HEADER
        + "<mapper namespace=\"good\"><select id=\"a\">SELECT 1</select></mapper>\n");
    Path broken = write(dir, "Broken.xml", HEADER
        + "<mapper namespace=\"broken\">\n"
        + "  <select id=\"a\">SELECT 1\n"
        + "</mapper>\n");
    Path missingInclude = write(dir, "MissingInclude.xml", HEADER
        + "<mapper namespace=\"inc\">\n"
        + "  <select id=\"ok\">SELECT 2</select>\n"
        + "  <select id=\"ng\">SELECT <include refid=\"nowhere.cols\"/> FROM t</select>\n"
        + "</mapper>\n");
    Path notFound = dir.resolve("NotFound.xml");

    SqlRenderer renderer = SqlRenderer.load(Arrays.asList(good, broken, missingInclude, notFound));

    assertEquals(Arrays.asList("good.a", "inc.ok"), renderer.statementIds());
    assertEquals("SELECT 1", renderer.render("good.a", null).literalSql.trim());

    List<LoadError> errors = renderer.getLoadErrors();
    assertEquals(3, errors.size(), errors.toString());
    // XML の構文エラーは元ファイルの行番号付き
    assertEquals(broken.toString(), errors.get(0).source);
    assertEquals(Integer.valueOf(5), errors.get(0).line);
    assertEquals(notFound.toString(), errors.get(1).source);
    // include 先がない statement は、全ファイル読み込み後に id 付きで報告する
    assertEquals("statement inc.ng", errors.get(2).source);
    assertTrue(errors.get(2).message.contains("nowhere.cols"), errors.get(2).message);
  }

  @Test
  void ディレクトリからmapperXMLだけを拾う(@TempDir Path dir) throws Exception {
    write(dir, "sub/A.xml", HEADER + "<mapper namespace=\"a\"><select id=\"x\">SELECT 1</select></mapper>\n");
    write(dir, "logback.xml", "<?xml version=\"1.0\"?>\n<configuration/>\n");
    write(dir, "B.txt", "<mapper namespace=\"b\"/>");

    MapperWorkspace ws = new MapperWorkspace(Collections.singletonList(dir));

    assertEquals(Collections.singletonList(dir.resolve("sub/A.xml")), ws.mapperFiles());
    assertEquals(Collections.singletonList("a.x"), ws.renderer().statementIds());
  }

  private static Path write(Path dir, String name, String content) throws Exception {
    Path p = dir.resolve(name);
    Files.createDirectories(p.getParent());
    Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    return p;
  }
}
