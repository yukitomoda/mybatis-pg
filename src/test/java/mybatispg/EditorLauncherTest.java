package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorLauncherTest {

  @TempDir
  Path dir;

  @Test
  void コマンドを空白で区切り引用符の中は区切らない() {
    assertEquals(Arrays.asList("code", "--goto", "{file}:{line}"), EditorLauncher.split("code  --goto {file}:{line}"));
    assertEquals(Arrays.asList("sh", "-c", "echo {file} > a b"), EditorLauncher.split("sh -c 'echo {file} > a b'"));
    assertEquals(Arrays.asList("my editor", ""), EditorLauncher.split("\"my editor\" ''"));
    assertThrows(IllegalArgumentException.class, () -> EditorLauncher.split("code \"x"));
  }

  @Test
  void ファイルと行を置き換えて実行する() throws Exception {
    Path out = dir.resolve("out.txt");
    EditorLauncher e = EditorLauncher.of("sh -c 'printf %s \"$0\" > " + out + "' {file}:{line}");
    e.open(dir.resolve("M.xml"), 12);
    assertEquals(dir.resolve("M.xml") + ":12", new String(Files.readAllBytes(out), StandardCharsets.UTF_8));
  }

  @Test
  void 失敗したら出力を含めて伝える() {
    IOException ex = assertThrows(IOException.class, () -> EditorLauncher.of("sh -c 'echo no window >&2; exit 3'").open(dir, 1));
    assertTrue(ex.getMessage().contains("終了コード 3"), ex.getMessage());
    assertTrue(ex.getMessage().contains("no window"), ex.getMessage());
    assertThrows(IOException.class, () -> EditorLauncher.of("no-such-command-xyz").open(dir, 1));
    assertThrows(IOException.class, () -> EditorLauncher.disabled().open(dir, 1));
  }

  private Path executable(Path p) throws Exception {
    Files.createDirectories(p.getParent());
    Files.write(p, "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
    return p;
  }

  @Test
  void VSCodeのコマンドを探す() throws Exception {
    Map<String, String> env = new HashMap<>();
    assertNull(EditorLauncher.findCodeCommand(env));
    assertFalse(EditorLauncher.detect(env).isAvailable());

    // code-server に同梱のコマンド（統合ターミナルの中でだけ使う）
    Path bundled = executable(dir.resolve("code-server/lib/vscode/bin/remote-cli/code-server"));
    env.put("VSCODE_GIT_ASKPASS_NODE", dir.resolve("code-server/lib/node").toString());
    assertNull(EditorLauncher.findCodeCommand(env));
    env.put("VSCODE_IPC_HOOK_CLI", "/tmp/vscode-ipc.sock");
    assertEquals(bundled.toString(), EditorLauncher.findCodeCommand(env));

    // PATH にあればそちらを使う
    Path code = executable(dir.resolve("bin/code"));
    env.put("PATH", "/no/such/dir:" + code.getParent());
    assertEquals(code.toString(), EditorLauncher.findCodeCommand(env));
    assertEquals(code + " --goto {file}:{line}", EditorLauncher.detect(env).describe());
  }

  @Test
  void Windowsでは拡張子のないcodeではなくcode_cmdを使う() throws Exception {
    // VS Code の bin には、Git Bash・WSL 向けの拡張子のない code と、code.cmd が並んでいる
    Path bin = dir.resolve("vscode/bin");
    Files.createDirectories(bin);
    Files.write(bin.resolve("code"), new byte[0]);
    Files.write(bin.resolve("code.cmd"), new byte[0]);
    Map<String, String> env = new HashMap<>();
    env.put("Path", "/no/such/dir;" + bin);
    env.put("PATHEXT", ".COM;.EXE;.BAT;.CMD");
    assertEquals(bin.resolve("code.cmd").toString(), EditorLauncher.findCodeCommand(env, true));

    // --editor に拡張子のない名前を書いても、code.cmd を起動する
    assertEquals(bin.resolve("code.cmd") + " --goto {file}:{line}", EditorLauncher.of("code --goto {file}:{line}", env, true).describe());
    // 拡張子があるもの・見つからないものはそのまま
    assertEquals("code.exe --goto {file}", EditorLauncher.of("code.exe --goto {file}", env, true).describe());
    assertEquals("subl {file}", EditorLauncher.of("subl {file}", env, true).describe());
    // Windows でなければ変えない
    assertEquals("code --goto {file}:{line}", EditorLauncher.of("code --goto {file}:{line}", env, false).describe());
  }
}
