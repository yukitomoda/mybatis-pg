package mybatisrender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionStoreTest {

  @TempDir
  Path dir;

  private static ConnectionStore.Profile profile(String name) {
    ConnectionStore.Profile p = new ConnectionStore.Profile();
    p.name = name;
    p.host = "db.example.com";
    p.port = 5433;
    p.database = "app";
    p.user = "app_user";
    p.params = "stringtype=unspecified";
    return p;
  }

  @Test
  void パスワードはファイルに保存しない() throws Exception {
    Path file = dir.resolve("conf/connections.json");
    ConnectionStore store = ConnectionStore.load(file);
    ConnectionStore.Profile saved = store.save(profile("開発"), "secret");
    assertTrue(store.hasPassword(saved.id));

    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    assertTrue(text.contains("\"開発\""), text);
    assertFalse(text.contains("secret"), text);
    if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    // 読み込み直すと設定は残り、パスワードは消える
    ConnectionStore reloaded = ConnectionStore.load(file);
    assertEquals("db.example.com", reloaded.get(saved.id).host);
    assertEquals(5433, reloaded.get(saved.id).port);
    assertFalse(reloaded.hasPassword(saved.id));
  }

  @Test
  void パスワードはnullなら維持し空文字なら消す() throws Exception {
    ConnectionStore store = ConnectionStore.inMemory();
    ConnectionStore.Profile saved = store.save(profile("開発"), "secret");
    saved.name = "開発2";
    store.save(saved, null);
    assertTrue(store.hasPassword(saved.id));
    assertEquals("開発2", store.get(saved.id).name);
    store.save(saved, "");
    assertFalse(store.hasPassword(saved.id));
  }

  @Test
  void 削除() throws Exception {
    ConnectionStore store = ConnectionStore.inMemory();
    ConnectionStore.Profile saved = store.save(profile("開発"), "secret");
    assertTrue(store.delete(saved.id));
    assertNull(store.get(saved.id));
    assertFalse(store.hasPassword(saved.id));
    assertFalse(store.delete(saved.id));
  }

  @Test
  void 入力の検証() {
    ConnectionStore store = ConnectionStore.inMemory();
    ConnectionStore.Profile noName = profile(" ");
    assertThrows(IllegalArgumentException.class, () -> store.save(noName, null));
    ConnectionStore.Profile badPort = profile("x");
    badPort.port = 70000;
    assertThrows(IllegalArgumentException.class, () -> store.save(badPort, null));
    ConnectionStore.Profile badParams = profile("x");
    badParams.params = "?sslmode=require";
    assertThrows(IllegalArgumentException.class, () -> store.save(badParams, null));
    ConnectionStore.Profile unknownId = profile("x");
    unknownId.id = "no-such-id";
    assertThrows(IllegalArgumentException.class, () -> store.save(unknownId, null));
  }

  @Test
  void JDBCのURL() {
    ConnectionStore.Profile p = profile("x");
    assertEquals("jdbc:postgresql://db.example.com:5433/app?stringtype=unspecified", ConnectionStore.jdbcUrl(p));
    p.host = "::1";
    p.params = "";
    assertEquals("jdbc:postgresql://[::1]:5433/app", ConnectionStore.jdbcUrl(p));
  }
}
