package mybatispg;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Spring Boot プロジェクトの設定（mybatis.mapper-locations）から mapper XML を探す。
 *
 * <p>classpath: / classpath*: は src/main/resources と src/main/java を起点にする。
 * 設定が見つからない場合は、起点の配下にある mapper XML をすべて対象にする。
 */
public final class MapperLocations {

  private static final List<String> CLASSPATH_ROOTS = Arrays.asList("src/main/resources", "src/main/java");
  private static final List<String> CONFIG_FILES =
      Arrays.asList("application.yml", "application.yaml", "application.properties");
  /** Spring の緩いキー解釈に合わせ、区切りと大文字小文字を無視して比較する */
  private static final String KEY = "mybatis.mapperlocations";
  private static final Pattern MAPPER_ROOT = Pattern.compile("<mapper[\\s>]");

  private final List<Path> classpathRoots;
  /** 設定が見つからなければ空 */
  private final List<String> patterns;
  /** 設定を読んだファイル。見つからなければ null */
  private final Path configFile;
  /** 設定ファイルを読み込めなかった場合のエラー。resolve() で例外にする */
  private final String configError;

  private MapperLocations(List<Path> classpathRoots, List<String> patterns, Path configFile, String configError) {
    this.classpathRoots = classpathRoots;
    this.patterns = patterns;
    this.configFile = configFile;
    this.configError = configError;
  }

  public static MapperLocations of(Path projectDir) {
    List<Path> roots = new ArrayList<>();
    for (String r : CLASSPATH_ROOTS) {
      Path p = projectDir.resolve(r);
      if (Files.isDirectory(p)) {
        roots.add(p);
      }
    }
    for (Path root : roots) {
      for (String name : CONFIG_FILES) {
        Path config = root.resolve(name);
        if (Files.isRegularFile(config)) {
          List<String> patterns;
          try {
            patterns = readPatterns(config);
          } catch (IllegalStateException e) {
            return new MapperLocations(roots, Collections.<String>emptyList(), config, e.getMessage());
          }
          if (!patterns.isEmpty()) {
            return new MapperLocations(roots, patterns, config, null);
          }
        }
      }
    }
    return new MapperLocations(roots, Collections.<String>emptyList(), null, null);
  }

  public List<String> getPatterns() {
    return Collections.unmodifiableList(patterns);
  }

  public Path getConfigFile() {
    return configFile;
  }

  /** 監視の起点。classpath の起点と、file: で指定されたディレクトリ */
  public List<Path> watchRoots() {
    Set<Path> roots = new LinkedHashSet<>(classpathRoots);
    for (String pattern : patterns) {
      if (pattern.startsWith("file:")) {
        Path base = Paths.get(fixedPrefix(pattern.substring("file:".length())));
        roots.add(Files.isDirectory(base) ? base : base.getParent());
      }
    }
    roots.remove(null);
    return new ArrayList<>(roots);
  }

  /** パターンに合う mapper XML を探す。呼ぶたびにファイルシステムを見直す */
  public List<Path> resolve() {
    if (configError != null) {
      throw new IllegalStateException(configError);
    }
    Set<Path> files = new LinkedHashSet<>();
    if (patterns.isEmpty()) {
      for (Path root : classpathRoots) {
        files.addAll(walk(root, null));
      }
    }
    for (String pattern : patterns) {
      if (pattern.startsWith("file:")) {
        String path = pattern.substring("file:".length()).replace('\\', '/');
        String prefix = fixedPrefix(path);
        files.addAll(walk(Paths.get(prefix), antToRegex(path.substring(prefix.length()))));
      } else {
        String path = pattern.replaceFirst("^classpath\\*?:", "").replaceFirst("^/", "");
        String prefix = fixedPrefix(path);
        Pattern regex = antToRegex(path.substring(prefix.length()));
        for (Path root : classpathRoots) {
          files.addAll(walk(root.resolve(prefix), regex));
        }
      }
    }
    return new ArrayList<>(files);
  }

  // ---- 設定の読み込み ----

  private static List<String> readPatterns(Path config) {
    Map<String, Object> flat = new LinkedHashMap<>();
    try (InputStream in = Files.newInputStream(config)) {
      if (config.getFileName().toString().endsWith(".properties")) {
        Properties props = new Properties();
        props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String name : props.stringPropertyNames()) {
          flat.put(name, props.getProperty(name));
        }
      } else {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        // プロファイルごとの文書（---）も含め、すべての文書から集める
        for (Object doc : yaml.loadAll((Reader) new InputStreamReader(in, StandardCharsets.UTF_8))) {
          flatten("", doc, flat);
        }
      }
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException(config + " を読み込めません: " + e.getMessage(), e);
    }

    Set<String> patterns = new LinkedHashSet<>();
    for (Map.Entry<String, Object> e : flat.entrySet()) {
      // リストの要素は "mybatis.mapper-locations[0]" のようなキーになる
      String key = e.getKey().replaceAll("\\[\\d+\\]$", "");
      if (normalizeKey(key).equals(KEY) && e.getValue() != null) {
        for (String p : e.getValue().toString().split(",")) {
          if (!p.trim().isEmpty()) {
            patterns.add(p.trim());
          }
        }
      }
    }
    return new ArrayList<>(patterns);
  }

  @SuppressWarnings("unchecked")
  private static void flatten(String prefix, Object value, Map<String, Object> out) {
    if (value instanceof Map) {
      for (Map.Entry<Object, Object> e : ((Map<Object, Object>) value).entrySet()) {
        String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
        flatten(key, e.getValue(), out);
      }
    } else if (value instanceof List) {
      List<Object> list = (List<Object>) value;
      for (int i = 0; i < list.size(); i++) {
        flatten(prefix + "[" + i + "]", list.get(i), out);
      }
    } else if (!prefix.isEmpty()) {
      out.put(prefix, value);
    }
  }

  private static String normalizeKey(String key) {
    return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
  }

  // ---- Ant 形式のパターン ----

  /** ワイルドカードを含まない先頭のディレクトリ部分（"mapper/**\/*.xml" なら "mapper/"） */
  static String fixedPrefix(String pattern) {
    int wildcard = pattern.length();
    for (char c : new char[] {'*', '?', '{'}) {
      int i = pattern.indexOf(c);
      if (i >= 0 && i < wildcard) {
        wildcard = i;
      }
    }
    if (wildcard == pattern.length()) {
      return pattern;
    }
    return pattern.substring(0, pattern.lastIndexOf('/', wildcard) + 1);
  }

  /** Ant 形式（Spring の PathMatcher と同じ解釈）を正規表現にする。** は 0 個以上のディレクトリに合う */
  static Pattern antToRegex(String ant) {
    StringBuilder re = new StringBuilder();
    for (int i = 0; i < ant.length(); i++) {
      char c = ant.charAt(i);
      if (c == '*' && i + 1 < ant.length() && ant.charAt(i + 1) == '*') {
        if (i + 2 < ant.length() && ant.charAt(i + 2) == '/') {
          re.append("(?:.*/)?");
          i += 2;
        } else {
          re.append(".*");
          i++;
        }
      } else if (c == '*') {
        re.append("[^/]*");
      } else if (c == '?') {
        re.append("[^/]");
      } else {
        re.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(re.toString());
  }

  /** ディレクトリ配下の mapper XML をすべて返す */
  static List<Path> mapperXmlUnder(Path dir) {
    return walk(dir, null);
  }

  /** dir 配下で、dir からの相対パスが regex に合うファイルを返す。regex が null なら mapper XML をすべて */
  private static List<Path> walk(Path dir, Pattern regex) {
    if (Files.isRegularFile(dir) && regex != null && regex.pattern().isEmpty()) {
      return Collections.singletonList(dir);
    }
    if (!Files.isDirectory(dir)) {
      return Collections.emptyList();
    }
    try (Stream<Path> stream = Files.walk(dir)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(p -> regex == null
              ? p.getFileName().toString().endsWith(".xml") && isMapperXml(p)
              : regex.matcher(dir.relativize(p).toString().replace('\\', '/')).matches())
          .sorted()
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new IllegalStateException(dir + " を走査できません", e);
    }
  }

  /** 先頭部分にルート要素 mapper があれば mapper XML とみなす */
  static boolean isMapperXml(Path file) {
    byte[] head = new byte[4096];
    try (InputStream in = Files.newInputStream(file)) {
      int n = in.read(head);
      return n > 0 && MAPPER_ROOT.matcher(new String(head, 0, n, StandardCharsets.UTF_8)).find();
    } catch (IOException e) {
      return false;
    }
  }
}
