package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class PasswordVaultTest {

  @TempDir
  Path dir;

  @Test
  void マスターパスワードで暗号化して保存し_開くと読める() throws Exception {
    Path file = dir.resolve("secrets.json");
    PasswordVault v = PasswordVault.load(file);
    assertFalse(v.exists());
    assertThrows(IllegalArgumentException.class, () -> v.setup("short".toCharArray()));
    v.setup("correct horse".toCharArray());
    assertTrue(v.isUnlocked());
    v.put("conn-a", "pg-secret-あ");
    v.put("conn-b", "other");

    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    assertFalse(text.contains("pg-secret"), text);
    assertFalse(text.contains("correct horse"), text);
    assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

    PasswordVault reopened = PasswordVault.load(file);
    assertTrue(reopened.exists());
    assertFalse(reopened.isUnlocked());
    assertTrue(reopened.has("conn-a"));
    assertThrows(IllegalStateException.class, () -> reopened.put("x", "y"));
    assertThrows(IllegalArgumentException.class, () -> reopened.unlock("wrong password".toCharArray()));
    assertFalse(reopened.isUnlocked());
    assertEquals("pg-secret-あ", reopened.unlock("correct horse".toCharArray()).get("conn-a"));
    reopened.remove("conn-b");
    assertEquals(Collections.singletonList("conn-a"), reopened.savedIds());
  }

  @Test
  void 別の接続先へ付け替えたものは読めない() throws Exception {
    Path file = dir.resolve("secrets.json");
    PasswordVault v = PasswordVault.load(file);
    v.setup("correct horse".toCharArray());
    v.put("conn-a", "secret-a");
    JsonObject data = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
    JsonObject passwords = data.getAsJsonObject("passwords");
    passwords.add("conn-b", passwords.get("conn-a"));
    Files.write(file, data.toString().getBytes(StandardCharsets.UTF_8));
    assertEquals(Collections.singletonMap("conn-a", "secret-a"), PasswordVault.load(file).unlock("correct horse".toCharArray()));
  }

  @Test
  void マスターパスワードを変える_忘れたら消す() throws Exception {
    Path file = dir.resolve("secrets.json");
    PasswordVault v = PasswordVault.load(file);
    v.setup("correct horse".toCharArray());
    v.put("conn-a", "secret-a");
    assertThrows(IllegalArgumentException.class, () -> v.change("wrong password".toCharArray(), "battery staple".toCharArray()));
    v.change("correct horse".toCharArray(), "battery staple".toCharArray());
    PasswordVault reopened = PasswordVault.load(file);
    assertThrows(IllegalArgumentException.class, () -> reopened.unlock("correct horse".toCharArray()));
    assertEquals("secret-a", reopened.unlock("battery staple".toCharArray()).get("conn-a"));
    reopened.reset();
    assertFalse(Files.exists(file));
    assertFalse(reopened.exists());
    assertFalse(reopened.isUnlocked());
  }
}
