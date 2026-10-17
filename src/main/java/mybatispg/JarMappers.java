package mybatispg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * JAR（war）の中の mapper XML を、ディレクトリに取り出す。
 * 中の JAR（Spring Boot の BOOT-INF/lib/*.jar、war の WEB-INF/lib/*.jar など）の mapper XML も取り出す。
 *
 * <p>取り出した先のパスは JAR の中のパスと同じにする。中の JAR のものは、その JAR の名前のディレクトリの下に置く
 * （例: BOOT-INF/lib/core.jar/mapper/OrderMapper.xml）。
 */
final class JarMappers {

  private static final Pattern MAPPER_ROOT = Pattern.compile("<mapper[\\s>]");
  /** 中の JAR をたどる深さ */
  private static final int MAX_DEPTH = 2;

  private JarMappers() {
  }

  /**
   * dest を空にしてから取り出す。
   *
   * @return 取り出した mapper XML の数
   */
  static int extract(Path jar, Path dest) throws IOException {
    clear(dest);
    Files.createDirectories(dest);
    try (InputStream in = Files.newInputStream(jar)) {
      return extract(in, dest, dest, 0);
    }
  }

  private static int extract(InputStream jar, Path root, Path dest, int depth) throws IOException {
    int count = 0;
    ZipInputStream zip = new ZipInputStream(jar);
    ZipEntry entry;
    while ((entry = zip.getNextEntry()) != null) {
      String name = entry.getName();
      if (entry.isDirectory() || name.contains("..")) {
        continue;
      }
      if (name.endsWith(".xml")) {
        byte[] bytes = readAll(zip);
        if (isMapper(bytes)) {
          Path out = dest.resolve(name).normalize();
          if (out.startsWith(root)) {
            Files.createDirectories(out.getParent());
            Files.write(out, bytes);
            count++;
          }
        }
      } else if ((name.endsWith(".jar") || name.endsWith(".war")) && depth < MAX_DEPTH) {
        // 中の JAR は閉じずに読み進める（外の ZipInputStream を閉じないように包む）
        count += extract(new NonClosing(zip), root, dest.resolve(name), depth + 1);
      }
    }
    return count;
  }

  private static boolean isMapper(byte[] bytes) {
    int n = Math.min(bytes.length, 4096);
    return MAPPER_ROOT.matcher(new String(bytes, 0, n, StandardCharsets.ISO_8859_1)).find();
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) {
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  /** ディレクトリを中身ごと消す */
  static void clear(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
        Files.deleteIfExists(p);
      }
    }
  }

  /** close() で外のストリームを閉じない */
  private static final class NonClosing extends java.io.FilterInputStream {
    NonClosing(InputStream in) {
      super(in);
    }

    @Override
    public void close() {
      // 外の ZipInputStream の、今のエントリーの読み込みを続けるため閉じない
    }
  }
}
