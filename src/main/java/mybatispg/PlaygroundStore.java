package mybatispg;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * プレイグラウンド: このアプリだけで使う mapper XML。プロジェクトのファイルとは別に、プロジェクトごとのディレクトリに「名前.xml」で置く。
 * 画面では「playground:名前」というパスで扱う。
 */
public final class PlaygroundStore {

  /** 画面のパスの頭 */
  public static final String PREFIX = "playground:";

  /** ファイル名に使えない文字・制御文字を除く。先頭の . と空白は使わない */
  private static final Pattern NAME = Pattern.compile("[^\\s.\\\\/:*?\"<>|\\u0000-\\u001f][^\\\\/:*?\"<>|\\u0000-\\u001f]{0,99}");

  /** null ならどこにも保存しない（テスト用に一時ディレクトリを渡す） */
  private final Path dir;

  private PlaygroundStore(Path dir) {
    this.dir = dir;
  }

  /**
   * @param root プレイグラウンドを置くディレクトリ（~/.mybatis-pg/playground）
   * @param projectKey プロジェクトを区別する文字列（基準ディレクトリの絶対パス）
   */
  public static PlaygroundStore open(Path root, String projectKey) {
    return new PlaygroundStore(root.resolve(hash(projectKey).substring(0, 16)));
  }

  /** ディレクトリをそのまま使う（テスト用） */
  public static PlaygroundStore at(Path dir) {
    return new PlaygroundStore(dir);
  }

  /** 名前の順 */
  public synchronized List<String> names() throws IOException {
    List<String> out = new ArrayList<>();
    if (dir != null && Files.isDirectory(dir)) {
      try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.xml")) {
        for (Path p : files) {
          String f = p.getFileName().toString();
          out.add(f.substring(0, f.length() - ".xml".length()));
        }
      }
    }
    Collections.sort(out);
    return out;
  }

  /** 画面のパス（playground:名前）のファイル。プレイグラウンドのパスでないか、ないときは null */
  public Path file(String shown) {
    if (dir == null || shown == null || !shown.startsWith(PREFIX)) {
      return null;
    }
    String name = shown.substring(PREFIX.length());
    if (!NAME.matcher(name).matches()) {
      return null;
    }
    Path p = dir.resolve(name + ".xml");
    return Files.isRegularFile(p) ? p : null;
  }

  /** 作る。中身はひな形（namespace は playground.名前） */
  public synchronized void create(String name) throws IOException {
    name = validName(name);
    Path p = pathOf(name);
    if (Files.exists(p)) {
      throw new IllegalArgumentException("同じ名前のプレイグラウンドがあります: " + name);
    }
    Files.createDirectories(dir);
    String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
        + "<mapper namespace=\"playground." + escape(name) + "\">\n"
        + "  <select id=\"example\" resultType=\"map\">\n"
        + "    SELECT 1\n"
        + "  </select>\n"
        + "</mapper>\n";
    Files.write(p, xml.getBytes(StandardCharsets.UTF_8));
  }

  public synchronized void rename(String name, String newName) throws IOException {
    Path from = pathOf(validName(name));
    Path to = pathOf(validName(newName));
    if (!Files.exists(from)) {
      throw new IllegalArgumentException("プレイグラウンドがありません: " + name);
    }
    if (Files.exists(to)) {
      throw new IllegalArgumentException("同じ名前のプレイグラウンドがあります: " + newName);
    }
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
  }

  public synchronized void delete(String name) throws IOException {
    Files.deleteIfExists(pathOf(validName(name)));
  }

  private Path pathOf(String name) {
    if (dir == null) {
      throw new IllegalStateException("プレイグラウンドの保存先がありません");
    }
    return dir.resolve(name + ".xml");
  }

  private static String validName(String name) {
    String trimmed = name == null ? "" : name.trim();
    if (!NAME.matcher(trimmed).matches()) {
      throw new IllegalArgumentException("名前には 1〜100 文字で、\\ / : * ? \" < > | 以外の文字を使ってください（先頭に . は使えません）");
    }
    return trimmed;
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
  }

  private static String hash(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : d) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
