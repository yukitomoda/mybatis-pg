package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

  @TempDir
  Path home;

  private static String read(Path p) throws Exception {
    return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
  }

  @Test
  void 改名前の設定のうち新しい場所にないものだけを引き継ぐ() throws Exception {
    Path oldDir = Files.createDirectories(home.resolve(".mybatis-xml-editor"));
    Path newDir = home.resolve(".mybatis-pg");
    Files.write(oldDir.resolve("connections.json"), "old-connections".getBytes(StandardCharsets.UTF_8));
    Files.write(oldDir.resolve("globals.json"), "old-globals".getBytes(StandardCharsets.UTF_8));
    Files.createDirectories(newDir);
    Files.write(newDir.resolve("globals.json"), "new-globals".getBytes(StandardCharsets.UTF_8));

    Main.migrateConfig(oldDir, newDir);

    assertEquals("old-connections", read(newDir.resolve("connections.json")));
    assertEquals("new-globals", read(newDir.resolve("globals.json")));
    // 古い場所のファイルは残す
    assertTrue(Files.exists(oldDir.resolve("connections.json")));
  }

  @Test
  void 改名前の設定がなければ何もしない() throws Exception {
    Main.migrateConfig(home.resolve(".mybatis-xml-editor"), home.resolve(".mybatis-pg"));
    assertTrue(!Files.exists(home.resolve(".mybatis-pg")));
  }

  @Test
  void ポートを指定しなければ空いているポートを順に使う() throws Exception {
    MapperWorkspace ws = MapperWorkspace.ofPaths(java.util.Collections.singletonList(java.nio.file.Paths.get("src/test/resources/mappers")));
    ToolServer a = Main.startServer(ws, ConnectionStore.inMemory(), GlobalStore.inMemory(), null, "t");
    ToolServer b = Main.startServer(ws, ConnectionStore.inMemory(), GlobalStore.inMemory(), null, "t");
    try {
      assertTrue(a.getPort() >= 7171 && a.getPort() < 7271, String.valueOf(a.getPort()));
      assertTrue(b.getPort() > a.getPort(), a.getPort() + " " + b.getPort());
    } finally {
      a.stop();
      b.stop();
    }
  }

  @Test
  void 公開URLのポートを置き換える() {
    assertEquals("https://ide.example.com/proxy/7172/", Main.resolvePort("https://ide.example.com/proxy/{port}/", 7172));
    assertEquals("https://ide.example.com/proxy/7172/", Main.resolvePort("https://ide.example.com/proxy/{{port}}/", 7172));
  }
}
