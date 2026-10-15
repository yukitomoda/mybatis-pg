package mybatisrender;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 使い方:
 * <pre>
 * java -jar mybatis-xml-editor.jar [--port 番号] [--public-url URL] --project プロジェクトのディレクトリ
 * java -jar mybatis-xml-editor.jar [--port 番号] [--public-url URL] mapperのファイルまたはディレクトリ...
 * </pre>
 */
public final class Main {

  private static final int DEFAULT_PORT = 7171;
  private static final String USAGE = "使い方:\n"
      + "  java -jar mybatis-xml-editor.jar [オプション] --project プロジェクトのディレクトリ\n"
      + "  java -jar mybatis-xml-editor.jar [オプション] mapperのファイルまたはディレクトリ...\n"
      + "オプション:\n"
      + "  --port 番号          待ち受けるポート（既定: 7171）\n"
      + "  --public-url URL     リバースプロキシ経由で公開する URL（例: https://example.com/proxy/7171/）。複数指定可";

  private Main() {
  }

  public static void main(String[] args) throws Exception {
    int port = DEFAULT_PORT;
    Path project = null;
    List<Path> sources = new ArrayList<>();
    List<URI> publicUrls = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      if ("--port".equals(args[i]) && i + 1 < args.length) {
        port = Integer.parseInt(args[++i]);
      } else if ("--public-url".equals(args[i]) && i + 1 < args.length) {
        publicUrls.add(publicUrl(args[++i]));
      } else if ("--project".equals(args[i]) && i + 1 < args.length) {
        project = absolute(args[++i]);
      } else if (args[i].startsWith("-")) {
        usage();
      } else {
        sources.add(absolute(args[i]));
      }
    }
    if ((project == null) == sources.isEmpty()) {
      usage();
    }

    MapperWorkspace workspace;
    if (project != null) {
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
    FileWatcher.start(workspace.watchRoots(), () -> {
      ws.reload();
      printSummary(ws, "再読み込み");
    });
    System.out.println("監視中: " + workspace.watchRoots());

    // 接続先の設定（パスワードは保存しない）
    Path connectionsFile = Paths.get(System.getProperty("user.home"), ".mybatis-xml-editor", "connections.json");
    ConnectionStore connections = ConnectionStore.load(connectionsFile);
    System.out.println("接続先の設定: " + connectionsFile);

    // 共通パラメータ（プロジェクトごと）
    Path globalsFile = Paths.get(System.getProperty("user.home"), ".mybatis-xml-editor", "globals.json");
    Path base = workspace.baseDir();
    GlobalStore globals = GlobalStore.load(globalsFile, base == null ? "" : base.toString());
    System.out.println("共通パラメータ: " + globalsFile);

    String token = newToken();
    ToolServer server = ToolServer.start(workspace, connections, globals, port, token, publicUrls);
    System.out.println("ブラウザで開いてください: http://127.0.0.1:" + server.getPort() + "/?token=" + token);
    for (URI url : publicUrls) {
      System.out.println("公開 URL: " + url + "?token=" + token);
    }
  }

  private static void printSummary(MapperWorkspace workspace, String label) {
    SqlRenderer renderer = workspace.renderer();
    System.out.println(label + ": statement " + renderer.statementIds().size() + " 件、エラー "
        + renderer.getLoadErrors().size() + " 件");
    for (LoadError e : renderer.getLoadErrors()) {
      System.out.println("  " + e);
    }
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
