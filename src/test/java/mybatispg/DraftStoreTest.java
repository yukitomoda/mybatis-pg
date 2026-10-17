package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DraftStoreTest {

  @TempDir
  Path dir;

  @Test
  void プロジェクトごとにファイルごとに保存する() throws Exception {
    DraftStore a = DraftStore.open(dir, "/project/a");
    a.save("m/A.xml", "base", "編集中");
    a.save("m/B.xml", "base", "b");
    DraftStore b = DraftStore.open(dir, "/project/b");
    b.save("m/A.xml", "base", "other");

    DraftStore reopened = DraftStore.open(dir, "/project/a");
    assertEquals(2, reopened.list().size());
    DraftStore.Draft first = reopened.list().stream().filter(d -> d.file.equals("m/A.xml")).findFirst().get();
    assertEquals("base", first.base);
    assertEquals("編集中", first.content);
    assertEquals(1, DraftStore.open(dir, "/project/b").list().size());

    // 元の内容と同じに戻したら消す
    reopened.save("m/B.xml", "base", "base");
    assertEquals(1, reopened.list().size());
    reopened.delete("m/A.xml");
    assertTrue(reopened.list().isEmpty());
    reopened.delete("never.xml");
  }

  @Test
  void 壊れたファイルは無視する() throws Exception {
    DraftStore s = DraftStore.open(dir, "/p");
    s.save("a.xml", "x", "y");
    Path projectDir = Files.list(dir).findFirst().get();
    Files.write(projectDir.resolve("broken.json"), "{".getBytes("UTF-8"));
    assertEquals(1, s.list().size());
  }
}
