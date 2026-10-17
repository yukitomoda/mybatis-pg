package mybatispg;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * 接続先のパスワードを、マスターパスワードで暗号化して保存する。
 *
 * <p>鍵はマスターパスワードから PBKDF2（HMAC-SHA256）で作り、ファイルには保存しない。サーバを起動するたびにマスターパスワードを入れて開く。
 * パスワードは AES-GCM で 1 つずつ暗号化し、接続先の id を認証付きデータにする（別の接続先へ付け替えられないように）。
 * <pre>
 * {"version": 1, "kdf": "PBKDF2WithHmacSHA256", "iterations": 600000, "salt": Base64,
 *  "check": {"iv", "data"},             // マスターパスワードが正しいかを確かめるための値
 *  "passwords": {"接続先の id": {"iv", "data"}}}
 * </pre>
 */
public final class PasswordVault {

  private static final int ITERATIONS = 600_000;
  private static final int KEY_BITS = 256;
  private static final int SALT_BYTES = 16;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final String CHECK_AAD = "mybatis-pg:check";
  private static final String CHECK_VALUE = "mybatis-pg";
  /** マスターパスワードの最低の長さ */
  public static final int MIN_LENGTH = 8;

  private final SecureRandom random = new SecureRandom();
  /** null ならファイルに保存しない（テスト用） */
  private final Path file;
  /** ファイルの内容。まだ作っていなければ null */
  private JsonObject data;
  /** 開いている間だけ持つ鍵 */
  private SecretKey key;

  private PasswordVault(Path file, JsonObject data) {
    this.file = file;
    this.data = data;
  }

  public static PasswordVault load(Path file) throws IOException {
    JsonObject data = null;
    if (Files.exists(file)) {
      try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        data = JsonParser.parseReader(in).getAsJsonObject();
      } catch (JsonParseException | IllegalStateException e) {
        throw new IOException("パスワードの保存先のファイルを読み込めません: " + file + "\n" + e.getMessage(), e);
      }
    }
    return new PasswordVault(file, data);
  }

  /** ファイルに保存しない（テスト用） */
  public static PasswordVault inMemory() {
    return new PasswordVault(null, null);
  }

  /** マスターパスワードを設定済みか */
  public synchronized boolean exists() {
    return data != null;
  }

  public synchronized boolean isUnlocked() {
    return key != null;
  }

  /** パスワードを保存している接続先（開いていなくても分かる） */
  public synchronized List<String> savedIds() {
    List<String> out = new ArrayList<>();
    if (data != null) {
      out.addAll(data.getAsJsonObject("passwords").keySet());
    }
    return out;
  }

  public synchronized boolean has(String id) {
    return data != null && data.getAsJsonObject("passwords").has(id);
  }

  /** マスターパスワードを設定して開く */
  public synchronized void setup(char[] master) throws IOException {
    if (data != null) {
      throw new IllegalStateException("マスターパスワードは設定済みです");
    }
    create(master, new LinkedHashMap<String, String>());
  }

  /** 新しい塩と鍵で作り、パスワードを暗号化して入れ、1 回で書く */
  private void create(char[] master, Map<String, String> passwords) throws IOException {
    validMaster(master);
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);
    SecretKey k = derive(master, salt, ITERATIONS);
    JsonObject d = new JsonObject();
    d.addProperty("version", 1);
    d.addProperty("kdf", "PBKDF2WithHmacSHA256");
    d.addProperty("iterations", ITERATIONS);
    d.addProperty("salt", Base64.getEncoder().encodeToString(salt));
    d.add("check", encrypt(k, CHECK_VALUE, CHECK_AAD));
    JsonObject p = new JsonObject();
    for (Map.Entry<String, String> e : passwords.entrySet()) {
      p.add(e.getKey(), encrypt(k, e.getValue(), e.getKey()));
    }
    d.add("passwords", p);
    JsonObject old = data;
    data = d;
    try {
      write();
    } catch (IOException e) {
      data = old;
      throw e;
    }
    key = k;
  }

  /**
   * 開く。開いたら、保存しているパスワードを返す（接続先の id → パスワード）。
   *
   * @throws IllegalArgumentException マスターパスワードが違う
   */
  public synchronized Map<String, String> unlock(char[] master) {
    if (data == null) {
      throw new IllegalStateException("マスターパスワードがまだ設定されていません");
    }
    SecretKey k = derive(master, Base64.getDecoder().decode(data.get("salt").getAsString()), data.get("iterations").getAsInt());
    if (!CHECK_VALUE.equals(decryptOrNull(k, data.getAsJsonObject("check"), CHECK_AAD))) {
      throw new IllegalArgumentException("マスターパスワードが違います");
    }
    key = k;
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, com.google.gson.JsonElement> e : data.getAsJsonObject("passwords").entrySet()) {
      String p = decryptOrNull(k, e.getValue().getAsJsonObject(), e.getKey());
      if (p != null) {
        out.put(e.getKey(), p);
      }
    }
    return out;
  }

  /** 閉じる（鍵を忘れる） */
  public synchronized void lock() {
    key = null;
  }

  /** パスワードを保存する。開いていなければ保存できない */
  public synchronized void put(String id, String password) throws IOException {
    requireUnlocked();
    data.getAsJsonObject("passwords").add(id, encrypt(key, password, id));
    write();
  }

  public synchronized void remove(String id) throws IOException {
    if (data != null && data.getAsJsonObject("passwords").remove(id) != null) {
      write();
    }
  }

  /** マスターパスワードを変える。保存しているパスワードは新しい鍵で暗号化し直す */
  public synchronized void change(char[] current, char[] next) throws IOException {
    validMaster(next);
    create(next, unlock(current));
  }

  /** マスターパスワードを忘れたときに、保存しているパスワードごと消す */
  public synchronized void reset() throws IOException {
    data = null;
    key = null;
    if (file != null) {
      Files.deleteIfExists(file);
    }
  }

  private void requireUnlocked() {
    if (key == null) {
      throw new IllegalStateException("パスワードを保存するには、マスターパスワードを入れてください");
    }
  }

  private static void validMaster(char[] master) {
    if (master == null || master.length < MIN_LENGTH) {
      throw new IllegalArgumentException("マスターパスワードは " + MIN_LENGTH + " 文字以上にしてください");
    }
  }

  private static SecretKey derive(char[] master, byte[] salt, int iterations) {
    try {
      PBEKeySpec spec = new PBEKeySpec(master, salt, iterations, KEY_BITS);
      try {
        byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        return new SecretKeySpec(k, "AES");
      } finally {
        spec.clearPassword();
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonObject encrypt(SecretKey k, String text, String aad) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
      c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      byte[] out = c.doFinal(text.getBytes(StandardCharsets.UTF_8));
      JsonObject o = new JsonObject();
      o.addProperty("iv", Base64.getEncoder().encodeToString(iv));
      o.addProperty("data", Base64.getEncoder().encodeToString(out));
      return o;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 復号する。鍵が違う・改ざんされているときは null */
  private static String decryptOrNull(SecretKey k, JsonObject o, String aad) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(o.get("iv").getAsString())));
      c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      return new String(c.doFinal(Base64.getDecoder().decode(o.get("data").getAsString())), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | RuntimeException e) {
      // 鍵が違う（AEADBadTagException）・中身が壊れている
      return null;
    }
  }

  /** 一時ファイルに書いてから置き換える。自分だけが読めるようにする */
  private void write() throws IOException {
    if (file == null) {
      return;
    }
    Path dir = file.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    Path tmp = Files.createTempFile(dir, "secrets", ".tmp");
    try {
      try {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException e) {
        // Windows など POSIX の権限がない環境では設定しない
      }
      try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        new GsonBuilder().setPrettyPrinting().create().toJson(data, out);
      }
      try {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
