package mybatisrender;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 使い方:
 * <pre>
 * java -jar mybatis-xml-editor.jar [--port 番号] --project プロジェクトのディレクトリ
 * java -jar mybatis-xml-editor.jar [--port 番号] mapperのファイルまたはディレクトリ...
 * </pre>
 */
public final class Main {

  private static final int DEFAULT_PORT = 7171;
  private static final String USAGE = "使い方:\n"
      + "  java -jar mybatis-xml-editor.jar [--port 番号] --project プロジェクトのディレクトリ\n"
      + "  java -jar mybatis-xml-editor.jar [--port 番号] mapperのファイルまたはディレクトリ...";

  private Main() {
  }

  public static void main(String[] args) throws Exception {
    int port = DEFAULT_PORT;
    Path project = null;
    List<Path> sources = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      if ("--port".equals(args[i]) && i + 1 < args.length) {
        port = Integer.parseInt(args[++i]);
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

    String token = newToken();
    ToolServer server = ToolServer.start(workspace, port, token);
    System.out.println("ブラウザで開いてください: http://127.0.0.1:" + server.getPort() + "/?token=" + token);
  }

  private static void printSummary(MapperWorkspace workspace, String label) {
    SqlRenderer renderer = workspace.renderer();
    System.out.println(label + ": statement " + renderer.statementIds().size() + " 件、エラー "
        + renderer.getLoadErrors().size() + " 件");
    for (LoadError e : renderer.getLoadErrors()) {
      System.out.println("  " + e);
    }
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
