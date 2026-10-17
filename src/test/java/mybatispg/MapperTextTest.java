package mybatispg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapperTextTest {

  @TempDir
  Path dir;

  @Test
  void XML宣言の文字コードで読み書きする() throws Exception {
    Charset sjis = Charset.forName("Windows-31J");
    String xml = "<?xml version=\"1.0\" encoding=\"Windows-31J\"?>\n<mapper><!-- 日本語 --></mapper>\n";
    Path f = dir.resolve("a.xml");
    Files.write(f, xml.getBytes(sjis));
    assertEquals(xml, MapperText.read(f));

    MapperText.write(f, xml.replace("日本語", "変更"));
    assertArrayEquals(xml.replace("日本語", "変更").getBytes(sjis), Files.readAllBytes(f));
    assertArrayEquals(xml.getBytes(sjis), MapperText.encode(xml));
  }

  @Test
  void 宣言がなければUTF8_BOMは保つ() throws Exception {
    Path f = dir.resolve("b.xml");
    Files.write(f, "\uFEFF<mapper>あ</mapper>".getBytes(StandardCharsets.UTF_8));
    String text = MapperText.read(f);
    assertEquals("\uFEFF<mapper>あ</mapper>", text);
    MapperText.write(f, text.replace("あ", "い"));
    assertArrayEquals("\uFEFF<mapper>い</mapper>".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(f));
  }

  @Test
  void 元のファイルがCRLFならCRLFで書く() throws Exception {
    Path f = dir.resolve("c.xml");
    Files.write(f, "<mapper>\r\n</mapper>\r\n".getBytes(StandardCharsets.UTF_8));
    MapperText.write(f, "<mapper>\n  <x/>\n</mapper>\n");
    assertEquals("<mapper>\r\n  <x/>\r\n</mapper>\r\n", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));

    Path lf = dir.resolve("d.xml");
    Files.write(lf, "<mapper>\n</mapper>\n".getBytes(StandardCharsets.UTF_8));
    MapperText.write(lf, "<mapper>\n  <x/>\n</mapper>\n");
    assertEquals("<mapper>\n  <x/>\n</mapper>\n", new String(Files.readAllBytes(lf), StandardCharsets.UTF_8));
  }
}
