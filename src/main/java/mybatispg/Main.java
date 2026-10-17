package mybatispg;

import java.io.IOException;
import java.net.BindException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 使い方:
 * <pre>
 * java -jar mybatis-pg.jar [オプション] --project プロジェクトのディレクトリ
 * java -jar mybatis-pg.jar [オプション] mapperのファイルまたはディレクトリ...
 * java -jar mybatis-pg.jar [オプション] --jar アプリのJAR（war）
 * </pre>
 */
public final class Main {

  private static final int DEFAULT_PORT = 7171;
  /** 設定（接続先・共通パラメータ）を置くディレクトリ。ホームディレクトリの下 */
  private static final String CONFIG_DIR = ".mybatis-pg";
  /** 改名前（mybatis-xml-editor）の設定のディレクトリ */
  private static final String OLD_CONFIG_DIR = ".mybatis-xml-editor";
  private static final String USAGE = "使い方:\n"
      + "  java -jar mybatis-pg.jar [オプション] --project プロジェクトのディレクトリ\n"
      + "  java -jar mybatis-pg.jar [オプション] mapperのファイルまたはディレクトリ...\n"
      + "  java -jar mybatis-pg.jar [オプション] --jar アプリのJAR（war）\n"
      + "                       JAR の中（中の JAR を含む）の mapper XML を読み込む。編集して試せるが、ファイルには保存できない\n"
      + "オプション:\n"
      + "  --port 番号          待ち受けるポート。省略すると 7171 から順に空いているポートを使う（複数起動できる）。0 なら OS が選ぶ\n"
      + "  --public-url URL     リバースプロキシ経由で公開する URL。{port} は使ったポートに置き換える\n"
      + "                       （例: https://example.com/proxy/{port}/）。複数指定可\n"
      + "  --editor コマンド    ファイルを開くコマンド。{file} と {line} を置き換える（既定: VS Code の code コマンドを探して使う）\n"
      + "                       例: --editor \"code --goto {file}:{line}\"\n"
      + "  --version            バージョンを表示して終了する";

  private Main() {
  }

  public static void main(String[] args) throws Exception {
    // null なら空いているポートを探す
    Integer port = null;
    Path project = null;
    Path jar = null;
    List<Path> sources = new ArrayList<>();
    List<String> publicUrls = new ArrayList<>();
    String editorCommand = null;
    for (int i = 0; i < args.length; i++) {
      if ("--version".equals(args[i])) {
        System.out.println("mybatis-pg " + version());
        return;
      } else if ("--port".equals(args[i]) && i + 1 < args.length) {
        port = Integer.parseInt(args[++i]);
      } else if ("--editor".equals(args[i]) && i + 1 < args.length) {
        editorCommand = args[++i];
      } else if ("--public-url".equals(args[i]) && i + 1 < args.length) {
        publicUrls.add(args[++i]);
      } else if ("--project".equals(args[i]) && i + 1 < args.length) {
        project = absolute(args[++i]);
      } else if ("--jar".equals(args[i]) && i + 1 < args.length) {
        jar = absolute(args[++i]);
      } else if (args[i].startsWith("-")) {
        usage();
      } else if (args[i].endsWith(".jar") || args[i].endsWith(".war")) {
        jar = absolute(args[i]);
      } else {
        sources.add(absolute(args[i]));
      }
    }
    // --project・mapper のファイル・--jar のどれか 1 つだけを指定する
    if ((project != null ? 1 : 0) + (sources.isEmpty() ? 0 : 1) + (jar != null ? 1 : 0) != 1) {
      usage();
    }
    System.out.println("mybatis-pg " + version());

    MapperWorkspace workspace;
    if (jar != null) {
      if (!Files.isRegularFile(jar)) {
        System.err.println("ファイルがありません: " + jar);
        System.exit(2);
      }
      System.out.println("JAR の中の mapper XML を読み込みます（ファイルには保存できません）: " + jar);
      workspace = MapperWorkspace.ofJar(jar);
    } else if (project != null) {
      if (!Files.isDirectory(project)) {
        System.err.println("ディレクトリがありません: " + project);
        System.exit(2);
      }
      MapperLocations locations = MapperLocations.of(project);
      if (locations.getConfigFile() != null && !locations.getPatterns().isEmpty()) {
        System.out.println("mapper-locations: " + locations.getPatterns() + "（" + locations.getConfigFile() + "）");
      } else {
        System.out.println("mapper-locations の設定がないため、配下の mapper XML をすべて対象にします");
      }
      workspace = MapperWorkspace.ofProject(project);
    } else {
      workspace = MapperWorkspace.ofPaths(sources);
    }
    printSummary(workspace, "読み込み");

    final MapperWorkspace ws = workspace;
    Runnable reload = () -> {
      ws.reload();
      printSummary(ws, "再読み込み");
    };
    if (workspace.jar() != null) {
      FileWatcher.startFile(workspace.jar(), reload);
      System.out.println("監視中: " + workspace.jar());
    } else {
      FileWatcher.start(workspace.watchRoots(), reload);
      System.out.println("監視中: " + workspace.watchRoots());
    }

    Path configDir = Paths.get(System.getProperty("user.home"), CONFIG_DIR);
    migrateConfig(Paths.get(System.getProperty("user.home"), OLD_CONFIG_DIR), configDir);

    // 接続先の設定（パスワードは保存しない）
    Path connectionsFile = configDir.resolve("connections.json");
    ConnectionStore connections = ConnectionStore.load(connectionsFile);
    System.out.println("接続先の設定: " + connectionsFile);

    // 共通パラメータ（プロジェクトごと）
    Path globalsFile = configDir.resolve("globals.json");
    GlobalStore globals = GlobalStore.load(globalsFile, workspace.projectKey());
    System.out.println("共通パラメータ: " + globalsFile);

    // mapper XML の編集中の内容（プロジェクトごと）
    Path draftsDir = configDir.resolve("drafts");
    DraftStore drafts = DraftStore.open(draftsDir, workspace.projectKey());
    System.out.println("編集中の mapper XML: " + draftsDir);

    String token = newToken();
    ToolServer server = startServer(workspace, connections, globals, port, token);
    server.useDrafts(drafts);
    // 接続先のパスワード（マスターパスワードで暗号化して保存する）
    Path secretsFile = configDir.resolve("secrets.json");
    server.useVault(PasswordVault.load(secretsFile));
    System.out.println("保存したパスワード（暗号化）: " + secretsFile);
    // このアプリだけで使う mapper XML（プロジェクトごと）
    Path playgroundDir = configDir.resolve("playground");
    server.usePlaygrounds(PlaygroundStore.open(playgroundDir, workspace.projectKey()));
    System.out.println("プレイグラウンド: " + playgroundDir);
    List<URI> urls = new ArrayList<>();
    for (String template : publicUrls) {
      URI url = publicUrl(resolvePort(template, server.getPort()));
      server.addPublicUrl(url);
      urls.add(url);
    }
    EditorLauncher editor = editorCommand != null ? EditorLauncher.of(editorCommand) : EditorLauncher.detect(System.getenv());
    server.setEditor(editor);
    System.out.println(editor.isAvailable() ? "エディタ: " + editor.describe()
        : "エディタ: コマンドが見つからないため、画面からファイルを開けません（--editor で指定できます）");
    System.out.println("ブラウザで開いてください: http://127.0.0.1:" + server.getPort() + "/?token=" + token);
    for (URI url : urls) {
      System.out.println("公開 URL: " + url + "?token=" + token);
    }
  }

  /**
   * 改名前の設定を引き継ぐ。新しい場所にないファイルだけを写し、古い場所のファイルは残す。
   */
  static void migrateConfig(Path oldDir, Path newDir) throws IOException {
    for (String name : new String[] {"connections.json", "globals.json"}) {
      Path from = oldDir.resolve(name);
      Path to = newDir.resolve(name);
      if (Files.isRegularFile(from) && !Files.exists(to)) {
        Files.createDirectories(newDir);
        Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
        System.out.println("改名前の設定を引き継ぎました: " + from + " → " + to);
      }
    }
  }

  /** jar のマニフェストにあるバージョン。jar から起動していなければ「開発版」 */
  private static String version() {
    String v = Main.class.getPackage().getImplementationVersion();
    return v == null ? "（開発版）" : v;
  }

  private static void printSummary(MapperWorkspace workspace, String label) {
    SqlRenderer renderer = workspace.renderer();
    System.out.println(label + ": statement " + renderer.statementIds().size() + " 件、エラー "
        + renderer.getLoadErrors().size() + " 件");
    for (LoadError e : renderer.getLoadErrors()) {
      System.out.println("  " + e);
    }
  }

  /** 空いているポートを探すときに試す数（7171〜7270） */
  private static final int PORT_TRIES = 100;

  /**
   * サーバを起動する。ポートの指定がなければ、既定のポートから順に空いているポートを探す（複数起動できるようにする）。
   */
  static ToolServer startServer(MapperWorkspace workspace, ConnectionStore connections, GlobalStore globals,
      Integer port, String token) throws IOException {
    if (port != null) {
      return ToolServer.start(workspace, connections, globals, port, token);
    }
    for (int p = DEFAULT_PORT; p < DEFAULT_PORT + PORT_TRIES; p++) {
      try {
        return ToolServer.start(workspace, connections, globals, p, token);
      } catch (BindException e) {
        // 使われているので次を試す
      }
    }
    throw new IOException(DEFAULT_PORT + "〜" + (DEFAULT_PORT + PORT_TRIES - 1)
        + " のポートがすべて使われています。--port で指定してください");
  }

  /** 公開 URL の {port}（code-server の {{port}} も）を、使ったポートに置き換える */
  static String resolvePort(String template, int port) {
    return template.replace("{{port}}", String.valueOf(port)).replace("{port}", String.valueOf(port));
  }

  /** 末尾を / にそろえる（画面は相対パスで API を呼ぶため、ディレクトリとして開く必要がある） */
  private static URI publicUrl(String s) {
    URI url = URI.create(s.endsWith("/") ? s : s + "/");
    if (url.getScheme() == null || url.getHost() == null) {
      System.err.println("--public-url は https://ホスト名/パス/ の形で指定してください: " + s);
      System.exit(2);
    }
    return url;
  }

  private static Path absolute(String path) {
    return Paths.get(path).toAbsolutePath().normalize();
  }

  private static String newToken() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private static void usage() {
    System.err.println(USAGE);
    System.exit(2);
  }
}
