package mybatispg;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ファイルをエディタ（既定は VS Code）で開く。
 *
 * <p>VS Code の統合ターミナルから起動していれば、{@code code} コマンドはその VS Code のウィンドウにつながる
 * （code-server や Remote-SSH でも同じ）。コマンドは起動オプションで変えられ、{file} と {line} を置き換えて実行する。
 */
public final class EditorLauncher {

  /** 終わるのを待つ時間。code コマンドはウィンドウに依頼するだけなので、すぐ終わる */
  private static final long TIMEOUT_SECONDS = 15;

  /** null ならエディタを開けない */
  private final List<String> command;

  private EditorLauncher(List<String> command) {
    this.command = command == null ? null : Collections.unmodifiableList(new ArrayList<>(command));
  }

  /** @param template 例: {@code code --goto {file}:{line}} */
  public static EditorLauncher of(String template) {
    List<String> argv = split(template);
    if (argv.isEmpty()) {
      throw new IllegalArgumentException("--editor のコマンドが空です");
    }
    return new EditorLauncher(argv);
  }

  /** 環境から VS Code のコマンドを探す。見つからなければ開けない状態にする */
  public static EditorLauncher detect(Map<String, String> env) {
    String code = findCodeCommand(env);
    return new EditorLauncher(code == null ? null : Arrays.asList(code, "--goto", "{file}:{line}"));
  }

  public static EditorLauncher disabled() {
    return new EditorLauncher(null);
  }

  public boolean isAvailable() {
    return command != null;
  }

  /** 画面に出すコマンド（置き換え前） */
  public String describe() {
    return command == null ? null : String.join(" ", command);
  }

  /**
   * 開く。コマンドが失敗したら、その出力を含めて例外にする。
   *
   * @param line 1 始まり。分からなければ 1
   */
  public void open(Path file, int line) throws IOException {
    if (command == null) {
      throw new IOException("エディタのコマンドが見つかりません。--editor で指定してください（例: --editor \"code --goto {file}:{line}\"）");
    }
    List<String> argv = new ArrayList<>();
    for (String a : command) {
      argv.add(a.replace("{file}", file.toString()).replace("{line}", String.valueOf(Math.max(1, line))));
    }
    Process p;
    try {
      p = new ProcessBuilder(argv).redirectErrorStream(true).start();
    } catch (IOException e) {
      throw new IOException("エディタを起動できません: " + String.join(" ", argv) + "\n" + e.getMessage(), e);
    }
    p.getOutputStream().close();
    String output = readAll(p.getInputStream());
    try {
      if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IOException("エディタのコマンドが終わりません: " + String.join(" ", argv));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("中断しました", e);
    }
    if (p.exitValue() != 0) {
      throw new IOException("エディタのコマンドが失敗しました（終了コード " + p.exitValue() + "）: " + String.join(" ", argv)
          + (output.trim().isEmpty() ? "" : "\n" + output.trim()));
    }
  }

  private static String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) > 0) {
      out.write(buf, 0, n);
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  /**
   * VS Code のコマンドを探す。
   * <ol>
   * <li>PATH にある code / code-server</li>
   * <li>VS Code のサーバ（code-server・Remote-SSH）に同梱のコマンド。統合ターミナルの環境変数から場所をたどる</li>
   * </ol>
   */
  static String findCodeCommand(Map<String, String> env) {
    String path = env.get("PATH");
    if (path != null) {
      for (String name : new String[] {"code", "code-server"}) {
        for (String dir : path.split(File.pathSeparator)) {
          if (!dir.isEmpty() && Files.isExecutable(Paths.get(dir, name))) {
            return Paths.get(dir, name).toString();
          }
        }
      }
    }
    // 同梱のコマンドは、統合ターミナルの中（VSCODE_IPC_HOOK_CLI がある）でだけウィンドウにつながる
    String node = env.get("VSCODE_GIT_ASKPASS_NODE");
    if (env.get("VSCODE_IPC_HOOK_CLI") != null && node != null) {
      Path base = Paths.get(node).getParent();
      if (base != null) {
        for (Path candidate : new Path[] {
            base.resolve("vscode/bin/remote-cli/code-server"), // code-server
            base.resolve("bin/remote-cli/code")}) { // VS Code のサーバ（Remote-SSH など）
          if (Files.isExecutable(candidate)) {
            return candidate.toString();
          }
        }
      }
    }
    return null;
  }

  /** 空白で区切る。"..." と '...' の中の空白は区切らない */
  static List<String> split(String s) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inArg = false;
    char quote = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        } else {
          cur.append(c);
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
        inArg = true;
      } else if (Character.isWhitespace(c)) {
        if (inArg) {
          out.add(cur.toString());
          cur.setLength(0);
          inArg = false;
        }
      } else {
        cur.append(c);
        inArg = true;
      }
    }
    if (quote != 0) {
      throw new IllegalArgumentException("--editor の引用符が閉じられていません: " + s);
    }
    if (inArg) {
      out.add(cur.toString());
    }
    return out;
  }
}
