package mybatisrender;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 対象の mapper XML 一式と、それを読み込んだ SqlRenderer を保持する。
 * ファイルが変わったら reload() で読み込み直す。
 */
public final class MapperWorkspace {

  private static final Pattern MAPPER_ROOT = Pattern.compile("<mapper[\\s>]");

  private final List<Path> sources;
  private volatile SqlRenderer renderer;

  /** @param sources mapper XML のファイル、またはそれを含むディレクトリ */
  public MapperWorkspace(List<Path> sources) {
    this.sources = new ArrayList<>(sources);
    reload();
  }

  public synchronized void reload() {
    renderer = SqlRenderer.load(mapperFiles());
  }

  public SqlRenderer renderer() {
    return renderer;
  }

  /** ディレクトリは配下の *.xml のうち mapper XML だけを拾う。ファイルの指定はそのまま使う */
  List<Path> mapperFiles() {
    List<Path> files = new ArrayList<>();
    for (Path source : sources) {
      if (Files.isDirectory(source)) {
        try (Stream<Path> walk = Files.walk(source)) {
          List<Path> found = walk
              .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".xml"))
              .filter(MapperWorkspace::isMapperXml)
              .sorted()
              .collect(Collectors.toList());
          files.addAll(found);
        } catch (IOException e) {
          throw new IllegalStateException(source + " を走査できません", e);
        }
      } else {
        files.add(source);
      }
    }
    return Collections.unmodifiableList(files);
  }

  /** 先頭部分にルート要素 mapper があれば mapper XML とみなす */
  private static boolean isMapperXml(Path file) {
    byte[] head = new byte[4096];
    try (InputStream in = Files.newInputStream(file)) {
      int n = in.read(head);
      return n > 0 && MAPPER_ROOT.matcher(new String(head, 0, n, StandardCharsets.UTF_8)).find();
    } catch (IOException e) {
      return false;
    }
  }
}
