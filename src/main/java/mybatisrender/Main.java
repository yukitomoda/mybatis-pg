package mybatisrender;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 使い方: java -jar mybatis-xml-editor.jar [--port 番号] mapperのファイルまたはディレクトリ...
 */
public final class Main {

  private static final int DEFAULT_PORT = 7171;

  private Main() {
  }

  public static void main(String[] args) throws Exception {
    int port = DEFAULT_PORT;
    List<Path> sources = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      if ("--port".equals(args[i]) && i + 1 < args.length) {
        port = Integer.parseInt(args[++i]);
      } else if (args[i].startsWith("-")) {
        usage();
        return;
      } else {
        sources.add(Paths.get(args[i]).toAbsolutePath().normalize());
      }
    }
    if (sources.isEmpty()) {
      usage();
      return;
    }

    MapperWorkspace workspace = new MapperWorkspace(sources);
    SqlRenderer renderer = workspace.renderer();
    System.out.println("statement: " + renderer.statementIds().size() + " 件");
    for (LoadError e : renderer.getLoadErrors()) {
      System.out.println("読み込みエラー: " + e);
    }

    String token = newToken();
    ToolServer server = ToolServer.start(workspace, port, token);
    System.out.println("ブラウザで開いてください: http://127.0.0.1:" + server.getPort() + "/?token=" + token);
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
    System.err.println("使い方: java -jar mybatis-xml-editor.jar [--port 番号] mapperのファイルまたはディレクトリ...");
    System.exit(2);
  }
}
