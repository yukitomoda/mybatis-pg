package mybatisrender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapperLocationsTest {

  private static final String RES = "src/main/resources/";

  @Test
  void ymlのmapperLocationsからファイルを探す(@TempDir Path project) throws Exception {
    write(project, RES + "application.yml", ""
        + "spring:\n"
        + "  datasource:\n"
        + "    url: jdbc:postgresql://localhost/app\n"
        + "mybatis:\n"
        + "  mapper-locations: classpath*:mapper/**/*.xml\n");
    Path a = mapper(project, RES + "mapper/A.xml", "a");
    Path b = mapper(project, RES + "mapper/sub/B.xml", "b");
    mapper(project, RES + "other/C.xml", "c");

    MapperLocations loc = MapperLocations.of(project);

    assertEquals(Collections.singletonList("classpath*:mapper/**/*.xml"), loc.getPatterns());
    assertEquals(Arrays.asList(a, b), loc.resolve());
  }

  @Test
  void リスト形式とプロファイル別の文書とキャメルケースのキー(@TempDir Path project) throws Exception {
    write(project, RES + "application.yml", ""
        + "mybatis:\n"
        + "  mapperLocations:\n"
        + "    - classpath:mapper/*.xml\n"
        + "---\n"
        + "spring:\n"
        + "  profiles: dev\n"
        + "mybatis.mapper-locations: classpath:dev/Dev.xml\n");
    Path a = mapper(project, RES + "mapper/A.xml", "a");
    mapper(project, RES + "mapper/sub/B.xml", "b");
    Path dev = mapper(project, RES + "dev/Dev.xml", "dev");

    MapperLocations loc = MapperLocations.of(project);

    assertEquals(Arrays.asList("classpath:mapper/*.xml", "classpath:dev/Dev.xml"), loc.getPatterns());
    assertEquals(Arrays.asList(a, dev), loc.resolve());
  }

  @Test
  void propertiesのカンマ区切りとsrc_main_javaに置いたmapper(@TempDir Path project) throws Exception {
    write(project, RES + "application.properties", "mybatis.mapper-locations=classpath:mapper/*.xml, classpath:com/example/**/*Mapper.xml\n");
    Path a = mapper(project, RES + "mapper/A.xml", "a");
    Path j = mapper(project, "src/main/java/com/example/user/UserMapper.xml", "user");

    assertEquals(Arrays.asList(a, j), MapperLocations.of(project).resolve());
  }

  @Test
  void 設定がなければ配下のmapperXMLをすべて対象にする(@TempDir Path project) throws Exception {
    write(project, RES + "application.yml", "server:\n  port: 8080\n");
    Path a = mapper(project, RES + "x/A.xml", "a");
    write(project, RES + "logback.xml", "<configuration/>\n");

    MapperLocations loc = MapperLocations.of(project);

    assertNull(loc.getConfigFile());
    assertEquals(Collections.singletonList(a), loc.resolve());
  }

  @Test
  void 設定ファイルを読めなければ読み込みエラーとして報告する(@TempDir Path project) throws Exception {
    write(project, RES + "application.yml", "mybatis:\n  mapper-locations: [unclosed\n");

    MapperWorkspace ws = MapperWorkspace.ofProject(project);

    List<LoadError> errors = ws.renderer().getLoadErrors();
    assertEquals(1, errors.size());
    assertEquals("mapper の探索", errors.get(0).source);
    assertTrue(errors.get(0).message.contains("application.yml"), errors.get(0).message);
    assertEquals(Collections.singletonList(project.resolve("src/main/resources")), ws.watchRoots());
  }

  @Test
  void Ant形式のパターン() {
    assertTrue(MapperLocations.antToRegex("**/*.xml").matcher("A.xml").matches());
    assertTrue(MapperLocations.antToRegex("**/*.xml").matcher("x/y/A.xml").matches());
    assertFalse(MapperLocations.antToRegex("*.xml").matcher("x/A.xml").matches());
    assertTrue(MapperLocations.antToRegex("*Mapper.xml").matcher("UserMapper.xml").matches());
    assertFalse(MapperLocations.antToRegex("*Mapper.xml").matcher("UserMapper.xml.bak").matches());
    assertEquals("mapper/", MapperLocations.fixedPrefix("mapper/**/*.xml"));
    assertEquals("", MapperLocations.fixedPrefix("*.xml"));
    assertEquals("mapper/A.xml", MapperLocations.fixedPrefix("mapper/A.xml"));
  }

  static Path mapper(Path project, String path, String namespace) throws Exception {
    return write(project, path, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
        + "<mapper namespace=\"" + namespace + "\"><select id=\"s\">SELECT 1</select></mapper>\n");
  }

  static Path write(Path project, String path, String content) throws Exception {
    Path p = project.resolve(path);
    Files.createDirectories(p.getParent());
    Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    return p;
  }
}
