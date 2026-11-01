package mybatispg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 対象の mapper XML 一式と、それを読み込んだ SqlRenderer を保持する。
 * ファイルが変わったら reload() で読み込み直し、世代番号を進める。
 *
 * <p>ワークスペースでは、複数のソース（プロジェクト・JAR・mapper のファイル）の mapper XML をまとめて 1 つに読み込む。
 * ソースが 2 つ以上あるときは、画面ではファイルを「ソースの名前/ソースの中のパス」で見せる。
 */
public final class MapperWorkspace {

  /** 1 つのソース（読み込んだもの） */
  static final class Part {
    /** 画面に出す名前 */
    final String name;
    /** project・jar・files */
    final String type;
    final Supplier<List<Path>> files;
    final List<Path> watchRoots;
    /** 画面でファイルを相対パスで見せるときの基準 */
    final Path baseDir;
    /** ファイルに保存できない（JAR から取り出したものなど） */
    final boolean readOnly;
    /** JAR から読み込んでいるときの JAR（見張る対象） */
    final Path jar;
    /** プロジェクトのディレクトリ（application.yml のデータソースを接続先にする）。プロジェクトでなければ null */
    final Path projectDir;
    /** ソースの名前を付けて見せるときの基準（プロジェクトなら mapper を探す src/main/resources など。ほかは baseDir） */
    final Path displayBase;

    Part(String name, String type, Supplier<List<Path>> files, List<Path> watchRoots, Path baseDir, boolean readOnly, Path jar,
        Path projectDir) {
      this.name = name;
      this.type = type;
      this.files = files;
      this.watchRoots = Collections.unmodifiableList(new ArrayList<>(watchRoots));
      this.baseDir = baseDir;
      this.readOnly = readOnly;
      this.jar = jar;
      this.projectDir = projectDir;
      Path common = projectDir != null ? commonAncestor(watchRoots) : null;
      this.displayBase = common != null && common.startsWith(baseDir) ? common : baseDir;
    }
  }

  /** MyBatis の、statement の名前が重なったときのエラー（3.4: "value for 名前"、3.5: "key 名前. please check …"） */
  private static final java.util.regex.Pattern DUPLICATE = java.util.regex.Pattern.compile("collection already contains (?:value for|key) (\\S+)");

  private volatile List<Part> parts;
  /** ファイルを「ソースの名前/相対パス」で見せる（定義したソースが 2 つ以上。有効・無効で見せ方が変わらないように） */
  private volatile boolean prefixed;
  /** 下書き・共通パラメータ・プレイグラウンドなどを分けるための、プロジェクト（ワークスペース）を区別する文字列 */
  private volatile String projectKey;
  private volatile SqlRenderer renderer;
  /** reload() のたびに増える。画面はこれを見て再取得する */
  private long generation;
  /** 見張っているもの（watch() で始め、ソースを差し替えたら見張り直す） */
  private final List<FileWatcher> watchers = new ArrayList<>();
  /** 読み込み直したときに呼ぶ（起動したときの画面に、件数を出すなど） */
  private volatile Runnable onReload = () -> { };

  private MapperWorkspace(String projectKey, List<Part> parts, boolean prefixed) {
    this.projectKey = projectKey;
    this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
    this.prefixed = prefixed;
    reload();
  }

  /**
   * JAR（war）の中の mapper XML を使う。一時ディレクトリに取り出して読み込み、JAR が変わったら取り出し直す。
   * 画面では JAR の中のパスで見せる。ファイルには保存できない。
   */
  public static MapperWorkspace ofJar(Path jar) throws IOException {
    Part p = jarPart(defaultName(jar), jar);
    return new MapperWorkspace(p.jar.toString(), Collections.singletonList(p), false);
  }

  /** mapper XML のファイル、またはそれを含むディレクトリを直接指定する */
  public static MapperWorkspace ofPaths(List<Path> sources) {
    Part p = pathsPart("mapper", sources);
    return new MapperWorkspace(p.baseDir == null ? "" : p.baseDir.toString(), Collections.singletonList(p), false);
  }

  /** Spring Boot プロジェクトの設定（mybatis.mapper-locations）から探す。設定は読み込み直すたびに読む */
  public static MapperWorkspace ofProject(Path projectDir) {
    Part p = projectPart(defaultName(projectDir), projectDir);
    return new MapperWorkspace(p.baseDir.toString(), Collections.singletonList(p), false);
  }

  /** ワークスペースの定義から作る（有効なソースだけを読む） */
  static MapperWorkspace of(Workspaces.Def def) throws IOException {
    return new MapperWorkspace(def.key, parts(def), def.sources.size() > 1);
  }

  /** ワークスペースを差し替える（別のワークスペースを開いた、ソースを変えた）。読み込み直して、見張り直す */
  void configure(Workspaces.Def def) throws IOException {
    List<Part> next = parts(def);
    synchronized (this) {
      projectKey = def.key;
      parts = Collections.unmodifiableList(next);
      prefixed = def.sources.size() > 1;
    }
    reload();
    boolean watching;
    synchronized (watchers) {
      watching = !watchers.isEmpty();
    }
    if (watching) {
      watch();
    }
  }

  static List<Part> parts(Workspaces.Def def) throws IOException {
    List<Part> out = new ArrayList<>();
    for (Workspaces.Source s : def.sources) {
      if (!s.enabled || Workspaces.check(s) != null) {
        continue;
      }
      Path p = Paths.get(s.path).toAbsolutePath().normalize();
      if (Workspaces.JAR.equals(s.type)) {
        out.add(jarPart(s.name, p));
      } else if (Workspaces.PROJECT.equals(s.type)) {
        out.add(projectPart(s.name, p));
      } else {
        out.add(pathsPart(s.name, Collections.singletonList(p)));
      }
    }
    return out;
  }

  private static String defaultName(Path p) {
    Path n = p.getFileName();
    return n == null ? p.toString() : n.toString();
  }

  private static Part jarPart(String name, Path jar) throws IOException {
    final Path abs = jar.toAbsolutePath().normalize();
    final Path dir = Files.createTempDirectory("mybatis-pg-jar");
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        JarMappers.clear(dir);
      } catch (IOException e) {
        // 一時ディレクトリなので、消せなくても困らない
      }
    }));
    final long[] extractedAt = {Long.MIN_VALUE};
    Supplier<List<Path>> files = () -> {
      synchronized (extractedAt) {
        try {
          long modified = Files.getLastModifiedTime(abs).toMillis();
          if (modified != extractedAt[0]) {
            JarMappers.extract(abs, dir);
            extractedAt[0] = modified;
          }
        } catch (IOException e) {
          throw new IllegalStateException(abs + " から mapper XML を取り出せません", e);
        }
        return MapperLocations.mapperXmlUnder(dir);
      }
    };
    // JAR のディレクトリをまるごとは見張らない（JAR だけを見張る）
    return new Part(name, Workspaces.JAR, files, Collections.<Path>emptyList(), dir, true, abs, null);
  }

  private static Part pathsPart(String name, List<Path> sources) {
    final List<Path> fixed = new ArrayList<>(sources);
    Set<Path> roots = new LinkedHashSet<>();
    for (Path source : fixed) {
      roots.add(Files.isDirectory(source) ? source : source.toAbsolutePath().getParent());
    }
    roots.remove(null);
    return new Part(name, Workspaces.FILES, () -> {
      List<Path> result = new ArrayList<>();
      for (Path source : fixed) {
        if (Files.isDirectory(source)) {
          result.addAll(MapperLocations.mapperXmlUnder(source));
        } else {
          result.add(source);
        }
      }
      return result;
    }, new ArrayList<>(roots), commonAncestor(roots), false, null, null);
  }

  private static Part projectPart(String name, Path projectDir) {
    Path abs = projectDir.toAbsolutePath().normalize();
    return new Part(name, Workspaces.PROJECT, () -> MapperLocations.of(abs).resolve(), MapperLocations.of(abs).watchRoots(), abs, false,
        null, abs);
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
    List<Path> mapperFiles = new ArrayList<>();
    List<LoadError> errors = new ArrayList<>();
    List<Part> ps = parts;
    for (Part p : ps) {
      try {
        mapperFiles.addAll(p.files.get());
      } catch (RuntimeException e) {
        errors.add(new LoadError(ps.size() > 1 ? "mapper の探索（" + p.name + "）" : "mapper の探索", null, null, Errors.describe(e)));
      }
    }
    SqlRenderer loaded = SqlRenderer.load(mapperFiles, errors);
    if (prefixed || ps.size() > 1) {
      // ソースどうしで同じ namespace の mapper があると、後から読んだファイルは読み込めない。どれと重なったかを、画面のパスで書く
      loaded.rewriteLoadErrors(e -> {
        java.util.regex.Matcher m = DUPLICATE.matcher(e.message == null ? "" : e.message);
        if (!m.find()) {
          return e;
        }
        String id = m.group(1).replaceAll("\\.$", "");
        String other = loaded.hasStatement(id) ? displayPath(loaded.statementSource(id)) : null;
        return new LoadError(e.source == null ? null : displayPath(e.source), e.line, e.column, "statement の名前 " + id + " が"
            + (other == null ? "ほかのファイル" : "「" + other + "」") + "と重なるため、このファイルは読み込んでいません（ワークスペースのソースどうしで、同じ namespace の mapper があります）");
      });
    }
    synchronized (this) {
      renderer = loaded;
      generation++;
      notifyAll();
    }
    onReload.run();
  }

  /** 読み込み直したときに呼ぶものを決める */
  void onReload(Runnable r) {
    this.onReload = r;
  }

  /** ファイルの変更を見張る（前の見張りはやめる）。変わったら読み込み直す */
  void watch() throws IOException {
    synchronized (watchers) {
      for (FileWatcher w : watchers) {
        w.close();
      }
      watchers.clear();
      List<Path> roots = new ArrayList<>();
      for (Part p : parts) {
        if (p.jar != null) {
          watchers.add(FileWatcher.startFile(p.jar, this::reload));
        }
        roots.addAll(p.watchRoots);
      }
      if (!roots.isEmpty()) {
        watchers.add(FileWatcher.start(roots, this::reload));
      }
    }
  }

  /** 見張っているもの（画面に出す） */
  List<Path> watched() {
    List<Path> out = new ArrayList<>();
    for (Part p : parts) {
      if (p.jar != null) {
        out.add(p.jar);
      }
      out.addAll(p.watchRoots);
    }
    return out;
  }

  public SqlRenderer renderer() {
    return renderer;
  }

  /** 画面でファイルを相対パスで見せるときの基準。ソースの名前を付けて見せるときや、ソースが 1 つでなければ null */
  public Path baseDir() {
    List<Part> ps = parts;
    return !prefixed && ps.size() == 1 ? ps.get(0).baseDir : null;
  }

  /**
   * 画面に出すファイルのパス（/ 区切り）。ソースを 1 つしか定義していなければその基準からの相対パス、2 つ以上なら「ソースの名前/相対パス」。
   * どのソースの中でもなければ、絶対パスのまま
   */
  public String displayPath(String resource) {
    Path file;
    try {
      file = Paths.get(resource).toAbsolutePath().normalize();
    } catch (RuntimeException e) {
      return resource;
    }
    List<Part> ps = parts;
    Part owner = owner(ps, file);
    if (owner == null) {
      return file.toString().replace('\\', '/');
    }
    if (prefixed || ps.size() > 1) {
      Path base = file.startsWith(owner.displayBase) && !file.equals(owner.displayBase) ? owner.displayBase : owner.baseDir;
      return owner.name + "/" + base.relativize(file).toString().replace('\\', '/');
    }
    return owner.baseDir.relativize(file).toString().replace('\\', '/');
  }

  /** ファイルを含むソース（基準がいちばん深いもの）。なければ null */
  private static Part owner(List<Part> ps, Path file) {
    Part owner = null;
    for (Part p : ps) {
      if (p.baseDir != null && file.startsWith(p.baseDir) && !file.equals(p.baseDir)
          && (owner == null || p.baseDir.getNameCount() > owner.baseDir.getNameCount())) {
        owner = p;
      }
    }
    return owner;
  }

  /** ファイルにソースの名前を付けて見せるときの、ソースの名前（付けないなら空） */
  public List<String> sourceNames() {
    List<String> out = new ArrayList<>();
    List<Part> ps = parts;
    if (prefixed || ps.size() > 1) {
      for (Part p : ps) {
        out.add(p.name);
      }
    }
    return out;
  }

  public String projectKey() {
    return projectKey;
  }

  /** JAR 1 つだけから読み込んでいるときの JAR。そうでなければ null */
  public Path jar() {
    List<Part> ps = parts;
    return ps.size() == 1 ? ps.get(0).jar : null;
  }

  /** すべてのソースがファイルに保存できない（JAR だけなど） */
  public boolean isReadOnly() {
    List<Part> ps = parts;
    if (ps.isEmpty()) {
      return false;
    }
    for (Part p : ps) {
      if (!p.readOnly) {
        return false;
      }
    }
    return true;
  }

  /** このファイルは保存できない（JAR から取り出したものなど） */
  public boolean isReadOnly(Path file) {
    Part owner = owner(parts, file.toAbsolutePath().normalize());
    return owner != null && owner.readOnly;
  }

  /** 保存できないソースの、画面のパスの頭（「ソースの名前/」。ソースが 1 つなら、すべて保存できないときに空文字） */
  public List<String> readOnlyPrefixes() {
    List<Part> ps = parts;
    List<String> out = new ArrayList<>();
    for (Part p : ps) {
      if (p.readOnly) {
        out.add(prefixed || ps.size() > 1 ? p.name + "/" : "");
      }
    }
    return out;
  }

  /** プロジェクトのソースの、名前ごとのディレクトリ（application.yml のデータソースを接続先にする） */
  public Map<String, Path> projectDirs() {
    Map<String, Path> out = new LinkedHashMap<>();
    for (Part p : parts) {
      if (p.projectDir != null) {
        out.put(p.name, p.projectDir);
      }
    }
    return out;
  }

  /** 変更を監視するディレクトリ */
  public List<Path> watchRoots() {
    List<Path> out = new ArrayList<>();
    for (Part p : parts) {
      out.addAll(p.watchRoots);
    }
    return out;
  }

  List<Path> mapperFiles() {
    List<Path> out = new ArrayList<>();
    for (Part p : parts) {
      try {
        out.addAll(p.files.get());
      } catch (RuntimeException e) {
        // 読めないソースは飛ばす（reload() でエラーとして出す）
      }
    }
    return out;
  }

  /** ソースごとの mapper XML の数（画面に出す）。読めなければ -1 */
  Map<String, Integer> fileCounts() {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (Part p : parts) {
      try {
        out.put(p.name, p.files.get().size());
      } catch (RuntimeException e) {
        out.put(p.name, -1);
      }
    }
    return out;
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
