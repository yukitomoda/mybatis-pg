package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarMappersTest {

  @TempDir
  Path dir;

  static String mapper(String namespace, String body) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
        + "<mapper namespace=\"" + namespace + "\">" + body + "</mapper>\n";
  }

  static byte[] zip(String... nameAndContent) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (int i = 0; i < nameAndContent.length; i += 2) {
        zip.putNextEntry(new ZipEntry(nameAndContent[i]));
        zip.write(nameAndContent[i + 1].getBytes(StandardCharsets.ISO_8859_1));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  /** Spring Boot の形の JAR: アプリの mapper と、中の JAR の mapper（別の mapper の sql 断片を include する） */
  static Path springBootJar(Path dir) throws Exception {
    byte[] lib = zip("mapper/CommonMapper.xml", mapper("lib.Common", "<sql id=\"cols\">id, name</sql>"),
        "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
    ByteArrayOutputStream app = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(app)) {
      zip.putNextEntry(new ZipEntry("BOOT-INF/classes/mapper/UserMapper.xml"));
      zip.write(mapper("app.User", "<select id=\"find\">SELECT <include refid=\"lib.Common.cols\"/> FROM users WHERE id = #{id}</select>")
          .getBytes(StandardCharsets.UTF_8));
      zip.putNextEntry(new ZipEntry("BOOT-INF/classes/logback.xml"));
      zip.write("<configuration/>".getBytes(StandardCharsets.UTF_8));
      zip.putNextEntry(new ZipEntry("BOOT-INF/lib/core.jar"));
      zip.write(lib);
      zip.putNextEntry(new ZipEntry("../evil.xml"));
      zip.write(mapper("evil", "").getBytes(StandardCharsets.UTF_8));
    }
    Path jar = dir.resolve("app.jar");
    Files.write(jar, app.toByteArray());
    return jar;
  }

  @Test
  void 中のJARも含めてmapperXMLだけを取り出す() throws Exception {
    Path jar = springBootJar(dir);
    Path out = dir.resolve("out");
    assertEquals(2, JarMappers.extract(jar, out));
    assertTrue(Files.isRegularFile(out.resolve("BOOT-INF/classes/mapper/UserMapper.xml")));
    assertTrue(Files.isRegularFile(out.resolve("BOOT-INF/lib/core.jar/mapper/CommonMapper.xml")));
    assertFalse(Files.exists(out.resolve("BOOT-INF/classes/logback.xml")));
    assertFalse(Files.exists(dir.resolve("evil.xml")));
  }

  @Test
  void JARから読み込み_中のJARのsql断片をincludeできる() throws Exception {
    MapperWorkspace ws = MapperWorkspace.ofJar(springBootJar(dir));
    assertTrue(ws.isReadOnly());
    assertEquals(dir.resolve("app.jar").toAbsolutePath().toString(), ws.projectKey());
    assertEquals(Arrays.asList("app.User.find"), ws.renderer().statementIds());
    assertEquals(0, ws.renderer().getLoadErrors().size(), ws.renderer().getLoadErrors().toString());
    assertTrue(ws.renderer().render("app.User.find", java.util.Collections.singletonMap("id", 1)).literalSql.contains("id, name"));
    assertTrue(ws.renderer().statementSource("app.User.find").replace('\\', '/').endsWith("BOOT-INF/classes/mapper/UserMapper.xml"));
  }
}
