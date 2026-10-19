package mybatispg;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Spring Boot のプロジェクトの設定（src/main/resources の application.yml など）から、データソースの設定
 * （spring.datasource.url・username・password）を読む。
 *
 * <p>基本の設定（application.yml・.yaml・.properties）と、プロファイルごとの設定（application-dev.yml や、
 * spring.config.activate.on-profile を付けた文書）を、それぞれ接続先にする。プロファイルの設定は基本の設定に重ねる。
 * ${NAME} や ${NAME:既定値} は、環境変数・システムプロパティ・同じ設定の値から埋める。
 */
final class ProjectDataSources {

  /** 設定から読んだ接続先 */
  static final class Source {
    /** 接続先の id（project: で始める） */
    final String id;
    /** 一覧に出す名前（application.yml、application.yml（dev）など） */
    final String name;
    /** 読んだファイル（プロジェクトからの相対パス） */
    final String file;
    final String url;
    final String user;
    final String password;
    /** 使えない理由（埋められない ${}、PostgreSQL でない URL など）。使えるなら null */
    final String error;

    Source(String id, String name, String file, String url, String user, String password, String error) {
      this.id = id;
      this.name = name;
      this.file = file;
      this.url = url;
      this.user = user;
      this.password = password;
      this.error = error;
    }
  }

  static final String PREFIX = "project:";
  private static final String RESOURCES = "src/main/resources";
  private static final String[] EXTENSIONS = {".properties", ".yml", ".yaml"};
  private static final Pattern PROFILE_FILE = Pattern.compile("application-([^.]+)\\.(properties|yml|yaml)");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}");

  private ProjectDataSources() {
  }

  /** 設定の 1 つの文書（プロファイルを指定していなければ profile は null） */
  private static final class Doc {
    final String file;
    final String profile;
    final Map<String, String> values;

    Doc(String file, String profile, Map<String, String> values) {
      this.file = file;
      this.profile = profile;
      this.values = values;
    }
  }

  static List<Source> read(Path projectDir, Map<String, String> env, Properties system) {
    Path resources = projectDir.resolve(RESOURCES);
    List<Source> out = new ArrayList<>();
    if (!Files.isDirectory(resources)) {
      return out;
    }
    List<Doc> docs = new ArrayList<>();
    String baseFile = null;
    for (String ext : EXTENSIONS) {
      Path f = resources.resolve("application" + ext);
      if (Files.isRegularFile(f)) {
        baseFile = baseFile == null ? RESOURCES + "/" + f.getFileName() : baseFile;
        docs.addAll(load(f, RESOURCES + "/" + f.getFileName(), null));
      }
    }
    Map<String, String> profileFiles = new TreeMap<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(resources, "application-*")) {
      for (Path f : files) {
        Matcher m = PROFILE_FILE.matcher(f.getFileName().toString());
        if (m.matches()) {
          docs.addAll(load(f, RESOURCES + "/" + f.getFileName(), m.group(1)));
          profileFiles.putIfAbsent(m.group(1), RESOURCES + "/" + f.getFileName());
        }
      }
    } catch (IOException e) {
      // ディレクトリを読めなければ、プロファイルごとのファイルはないものとする
    }

    // 基本の設定（プロファイルを指定していない文書を、読んだ順に重ねる）
    Map<String, String> base = new LinkedHashMap<>();
    List<String> profiles = new ArrayList<>();
    for (Doc d : docs) {
      if (d.profile == null) {
        base.putAll(d.values);
      } else if (!profiles.contains(d.profile)) {
        profiles.add(d.profile);
      }
    }
    if (baseFile != null && base.containsKey("spring.datasource.url")) {
      out.add(source(PREFIX + "default", baseFile.substring(baseFile.lastIndexOf('/') + 1), baseFile, base, env, system));
    }
    for (String profile : profiles) {
      Map<String, String> merged = new LinkedHashMap<>(base);
      boolean overrides = false;
      String file = profileFiles.get(profile);
      for (Doc d : docs) {
        if (profile.equals(d.profile)) {
          merged.putAll(d.values);
          overrides |= d.values.keySet().stream().anyMatch(k -> k.startsWith("spring.datasource."));
          file = file == null ? d.file : file;
        }
      }
      // データソースを変えないプロファイルは、基本の設定と同じなので出さない
      if (overrides && merged.containsKey("spring.datasource.url")) {
        String shown = (baseFile != null ? baseFile.substring(baseFile.lastIndexOf('/') + 1) : "application") + "（" + profile + "）";
        out.add(source(PREFIX + profile, shown, file, merged, env, system));
      }
    }
    return out;
  }

  private static Source source(String id, String name, String file, Map<String, String> values, Map<String, String> env,
      Properties system) {
    List<String> missing = new ArrayList<>();
    String url = resolve(values.get("spring.datasource.url"), values, env, system, missing, 0);
    String user = resolve(values.get("spring.datasource.username"), values, env, system, missing, 0);
    String password = resolve(values.get("spring.datasource.password"), values, env, system, missing, 0);
    String error = null;
    if (!missing.isEmpty()) {
      error = "${" + String.join("}、${", missing) + "} を埋められません（環境変数かシステムプロパティで渡してください）";
    } else if (url == null || !url.startsWith("jdbc:postgresql:")) {
      error = "PostgreSQL の URL ではありません: " + url;
    }
    return new Source(id, name, file, url, user, password, error);
  }

  /** ${NAME} と ${NAME:既定値} を埋める。埋められなければ missing に名前を足す */
  static String resolve(String value, Map<String, String> config, Map<String, String> env, Properties system, List<String> missing,
      int depth) {
    if (value == null || depth > 10) {
      return value;
    }
    Matcher m = PLACEHOLDER.matcher(value);
    StringBuffer out = new StringBuffer();
    while (m.find()) {
      String name = m.group(1).trim();
      String found = lookup(name, config, env, system);
      if (found == null) {
        found = m.group(2);
      }
      if (found == null) {
        missing.add(name);
        found = m.group();
      } else {
        found = resolve(found, config, env, system, missing, depth + 1);
      }
      m.appendReplacement(out, Matcher.quoteReplacement(found));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** システムプロパティ、環境変数（Spring と同じく DB_URL や SPRING_DATASOURCE_URL の形も）、同じ設定の値の順に探す */
  private static String lookup(String name, Map<String, String> config, Map<String, String> env, Properties system) {
    if (system.getProperty(name) != null) {
      return system.getProperty(name);
    }
    if (env.get(name) != null) {
      return env.get(name);
    }
    String envName = name.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    if (env.get(envName) != null) {
      return env.get(envName);
    }
    return config.get(normalize(name));
  }

  /** 1 つのファイルを、文書ごとに読む（YAML の --- で区切った文書は、それぞれのプロファイルの設定になりうる） */
  private static List<Doc> load(Path file, String shown, String fileProfile) {
    List<Doc> docs = new ArrayList<>();
    try (InputStream in = Files.newInputStream(file)) {
      if (file.getFileName().toString().endsWith(".properties")) {
        Properties props = new Properties();
        props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
          values.put(normalize(name), props.getProperty(name));
        }
        docs.add(new Doc(shown, fileProfile, values));
      } else {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        for (Object doc : yaml.loadAll((Reader) new InputStreamReader(in, StandardCharsets.UTF_8))) {
          Map<String, String> values = new LinkedHashMap<>();
          flatten("", doc, values);
          String profile = values.containsKey("spring.config.activate.on-profile") ? values.get("spring.config.activate.on-profile")
              : values.get("spring.profiles");
          docs.add(new Doc(shown, profile != null ? profile.trim() : fileProfile, values));
        }
      }
    } catch (IOException | RuntimeException e) {
      // 読めない設定ファイルは使わない（mapper-locations の読み込みでエラーを出す）
    }
    return docs;
  }

  @SuppressWarnings("unchecked")
  private static void flatten(String prefix, Object value, Map<String, String> out) {
    if (value instanceof Map) {
      for (Map.Entry<Object, Object> e : ((Map<Object, Object>) value).entrySet()) {
        flatten(prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey(), e.getValue(), out);
      }
    } else if (value instanceof List) {
      List<Object> list = (List<Object>) value;
      for (int i = 0; i < list.size(); i++) {
        flatten(prefix + "[" + i + "]", list.get(i), out);
      }
    } else if (!prefix.isEmpty() && value != null) {
      out.put(normalize(prefix), String.valueOf(value));
    }
  }

  /** Spring の緩いキー解釈に合わせる（spring.datasource.userName も spring.datasource.user-name も同じ） */
  static String normalize(String key) {
    StringBuilder b = new StringBuilder();
    for (String part : key.split("\\.")) {
      if (b.length() > 0) {
        b.append('.');
      }
      String p = part.replace("_", "-").toLowerCase(Locale.ROOT);
      // datasource・on-profile などはそのまま。username は user-name とも書けるので、- を除いた形でも比べる
      b.append(p.equals("user-name") ? "username" : p.equals("data-source") ? "datasource" : p);
    }
    return b.toString();
  }
}
