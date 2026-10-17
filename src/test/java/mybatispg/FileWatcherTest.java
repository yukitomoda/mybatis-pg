package mybatispg;

import static mybatispg.MapperLocationsTest.mapper;
import static mybatispg.MapperLocationsTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWatcherTest {

  private static final String RES = "src/main/resources/";
  private static final long TIMEOUT = 10_000;

  @Test
  void mapperや設定の変更を検知して読み込み直す(@TempDir Path project) throws Exception {
    write(project, RES + "application.yml", "mybatis:\n  mapper-locations: classpath:mapper/**/*.xml\n");
    mapper(project, RES + "mapper/A.xml", "a");
    MapperWorkspace ws = MapperWorkspace.ofProject(project);
    assertEquals(Collections.singletonList("a.s"), ws.renderer().statementIds());

    try (FileWatcher watcher = FileWatcher.start(ws.watchRoots(), ws::reload)) {
      // 既存ファイルの変更
      long g = ws.generation();
      mapper(project, RES + "mapper/A.xml", "a2");
      assertTrue(ws.awaitChange(g, TIMEOUT) > g);
      assertEquals(Collections.singletonList("a2.s"), ws.renderer().statementIds());

      // 新しいディレクトリとその中のファイル（ディレクトリ作成後に置かれたものも拾う）
      g = ws.generation();
      Files.createDirectories(project.resolve(RES + "mapper/sub"));
      Thread.sleep(500);
      mapper(project, RES + "mapper/sub/B.xml", "b");
      waitFor(ws, g, Arrays.asList("a2.s", "b.s"));

      // 削除
      g = ws.generation();
      Files.delete(project.resolve(RES + "mapper/A.xml"));
      waitFor(ws, g, Collections.singletonList("b.s"));

      // 設定ファイルの変更で探索範囲が変わる
      g = ws.generation();
      write(project, RES + "application.yml", "mybatis:\n  mapper-locations: classpath:mapper/*.xml\n");
      waitFor(ws, g, Collections.<String>emptyList());
    }
  }

  @Test
  void JARを作り直したら取り出し直す(@TempDir Path dir) throws Exception {
    Path jar = dir.resolve("app.jar");
    Files.write(jar, JarMappersTest.zip("mapper/A.xml", JarMappersTest.mapper("a", "<select id=\"s\">SELECT 1</select>")));
    // JAR と同じディレクトリのほかのファイルの変更では読み込み直さない
    Files.createDirectories(dir.resolve("classes"));
    MapperWorkspace ws = MapperWorkspace.ofJar(jar);
    assertEquals(Collections.singletonList("a.s"), ws.renderer().statementIds());
    try (FileWatcher watcher = FileWatcher.startFile(ws.jar(), ws::reload)) {
      long g = ws.generation();
      write(dir, "classes/Other.xml", "<x/>");
      assertEquals(g, ws.awaitChange(g, 1500));

      Files.write(jar, JarMappersTest.zip("mapper/A.xml", JarMappersTest.mapper("a", "<select id=\"s\">SELECT 1</select><select id=\"t\">SELECT 2</select>")));
      Files.setLastModifiedTime(jar, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
      waitFor(ws, g, Arrays.asList("a.s", "a.t"));
    }
  }

  /** 1 回の保存で複数回読み込み直されることがあるため、期待した状態になるまで待つ */
  private static void waitFor(MapperWorkspace ws, long since, java.util.List<String> expected) throws Exception {
    long deadline = System.currentTimeMillis() + TIMEOUT;
    long g = since;
    while (!expected.equals(ws.renderer().statementIds()) && System.currentTimeMillis() < deadline) {
      g = ws.awaitChange(g, deadline - System.currentTimeMillis());
    }
    assertEquals(expected, ws.renderer().statementIds());
  }
}
