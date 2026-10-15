package mybatispg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 対象の mapper XML 一式と、それを読み込んだ SqlRenderer を保持する。
 * ファイルが変わったら reload() で読み込み直し、世代番号を進める。
 */
public final class MapperWorkspace {

  private final Supplier<List<Path>> files;
  private final List<Path> watchRoots;
  /** 画面でファイルを相対パスで見せるときの基準 */
  private final Path baseDir;
  private volatile SqlRenderer renderer;
  /** reload() のたびに増える。画面はこれを見て再取得する */
  private long generation;

  private MapperWorkspace(Supplier<List<Path>> files, List<Path> watchRoots, Path baseDir) {
    this.files = files;
    this.watchRoots = Collections.unmodifiableList(new ArrayList<>(watchRoots));
    this.baseDir = baseDir;
    reload();
  }

  /** mapper XML のファイル、またはそれを含むディレクトリを直接指定する */
  public static MapperWorkspace ofPaths(List<Path> sources) {
    final List<Path> fixed = new ArrayList<>(sources);
    Set<Path> roots = new LinkedHashSet<>();
    for (Path source : fixed) {
      roots.add(Files.isDirectory(source) ? source : source.toAbsolutePath().getParent());
    }
    roots.remove(null);
    return new MapperWorkspace(() -> {
      List<Path> result = new ArrayList<>();
      for (Path source : fixed) {
        if (Files.isDirectory(source)) {
          result.addAll(MapperLocations.mapperXmlUnder(source));
        } else {
          result.add(source);
        }
      }
      return result;
    }, new ArrayList<>(roots), commonAncestor(roots));
  }

  /** Spring Boot プロジェクトの設定（mybatis.mapper-locations）から探す。設定は読み込み直すたびに読む */
  public static MapperWorkspace ofProject(Path projectDir) {
    return new MapperWorkspace(() -> MapperLocations.of(projectDir).resolve(),
        MapperLocations.of(projectDir).watchRoots(), projectDir.toAbsolutePath().normalize());
  }

  /** すべてのパスを含む最も深いディレクトリ。共通部分がなければ null */
  private static Path commonAncestor(Iterable<Path> paths) {
    Path common = null;
    for (Path p : paths) {
      Path abs = p.toAbsolutePath().normalize();
      if (common == null) {
        common = abs;
      } else {
        while (common != null && !abs.startsWith(common)) {
          common = common.getParent();
        }
      }
    }
    return common;
  }

  public void reload() {
    List<Path> mapperFiles;
    List<LoadError> errors = new ArrayList<>();
    try {
      mapperFiles = files.get();
    } catch (RuntimeException e) {
      mapperFiles = Collections.emptyList();
      errors.add(new LoadError("mapper の探索", null, null, Errors.describe(e)));
    }
    SqlRenderer loaded = SqlRenderer.load(mapperFiles, errors);
    synchronized (this) {
      renderer = loaded;
      generation++;
      notifyAll();
    }
  }

  public SqlRenderer renderer() {
    return renderer;
  }

  /** 画面でファイルを相対パスで見せるときの基準。null なら絶対パスで見せる */
  public Path baseDir() {
    return baseDir;
  }

  /** 変更を監視するディレクトリ */
  public List<Path> watchRoots() {
    return watchRoots;
  }

  List<Path> mapperFiles() {
    return files.get();
  }

  public synchronized long generation() {
    return generation;
  }

  /** 世代番号が since より進むか、タイムアウトするまで待ち、その時点の世代番号を返す */
  public synchronized long awaitChange(long since, long timeoutMillis) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (generation <= since) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        break;
      }
      wait(remaining);
    }
    return generation;
  }
}
