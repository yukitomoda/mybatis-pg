package mybatispg;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * mapper XML から、statement の要素をファイルに書かれたままの形で切り出す。
 *
 * <p>要素の位置は XML パーサで求める（コメントや CDATA の中の文字列に惑わされないため）。
 */
public final class StatementSource {

  private static final Set<String> STATEMENT_TAGS = new HashSet<>(Arrays.asList("select", "insert", "update", "delete"));

  /** 切り出した要素 */
  public static final class Snippet {
    /** 要素が始まる行（1 始まり） */
    public final int line;
    /** 要素の文字列。2 行目以降の共通の字下げは取り除く */
    public final String xml;

    Snippet(int line, String xml) {
      this.line = line;
      this.xml = xml;
    }
  }

  private StatementSource() {
  }

  /**
   * @param localId 名前空間を除いた id。selectKey（"insert!selectKey"）は元の statement を返す
   * @return 見つからなければ null
   */
  public static Snippet find(Path file, String localId) throws IOException {
    String id = localId.endsWith("!selectKey") ? localId.substring(0, localId.length() - "!selectKey".length()) : localId;
    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    Finder finder = new Finder(id);
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      // DTD を取りに行かない
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      SAXParser parser = factory.newSAXParser();
      parser.parse(new InputSource(new StringReader(text)), finder);
    } catch (Found found) {
      // 見つかった時点で読み込みを止める
    } catch (SAXException | ParserConfigurationException e) {
      return null;
    }
    if (finder.endLine < 0) {
      return null;
    }
    int[] lineStarts = lineStarts(text);
    // Locator が示すのは開始タグの直後なので、そこから遡ってタグの先頭を探す
    int startTagEnd = offset(lineStarts, finder.startLine, finder.startColumn);
    int start = text.lastIndexOf("<" + finder.tag, startTagEnd - 1);
    int end = offset(lineStarts, finder.endLine, finder.endColumn);
    if (start < 0 || end > text.length() || end <= start) {
      return null;
    }
    int line = lineOf(lineStarts, start);
    String firstLinePrefix = text.substring(lineStarts[line - 1], start);
    String raw = text.substring(start, end);
    // 1 行目の字下げ（タグより前が空白だけなら）も含めて、共通の字下げを取り除く
    String body = firstLinePrefix.trim().isEmpty() ? firstLinePrefix + raw : raw;
    return new Snippet(line, dedent(body));
  }

  private static final class Found extends SAXException {
    Found() {
      super("found");
    }
  }

  private static final class Finder extends DefaultHandler {
    private final String id;
    private Locator locator;
    private int depth;
    /** 見つけた statement の深さ（未発見なら -1） */
    private int foundDepth = -1;
    String tag;
    int startLine;
    int startColumn;
    int endLine = -1;
    int endColumn;

    Finder(String id) {
      this.id = id;
    }

    @Override
    public void setDocumentLocator(Locator locator) {
      this.locator = locator;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      depth++;
      // statement は mapper の直下（深さ 2）にある
      if (foundDepth < 0 && depth == 2 && STATEMENT_TAGS.contains(qName) && id.equals(attributes.getValue("id"))) {
        foundDepth = depth;
        tag = qName;
        startLine = locator.getLineNumber();
        startColumn = locator.getColumnNumber();
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
      if (depth == foundDepth) {
        endLine = locator.getLineNumber();
        endColumn = locator.getColumnNumber();
        throw new Found();
      }
      depth--;
    }
  }

  private static int[] lineStarts(String text) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        starts.add(i + 1);
      }
    }
    int[] result = new int[starts.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = starts.get(i);
    }
    return result;
  }

  /** 行と列（どちらも 1 始まり）から文字の位置を求める */
  private static int offset(int[] lineStarts, int line, int column) {
    return lineStarts[Math.min(line, lineStarts.length) - 1] + column - 1;
  }

  private static int lineOf(int[] lineStarts, int offset) {
    int line = 1;
    while (line < lineStarts.length && lineStarts[line] <= offset) {
      line++;
    }
    return line;
  }

  /** 空行を除いた全行に共通する先頭の空白を取り除く */
  static String dedent(String s) {
    String[] lines = s.replace("\r\n", "\n").split("\n", -1);
    int common = Integer.MAX_VALUE;
    for (String l : lines) {
      if (l.trim().isEmpty()) {
        continue;
      }
      int n = 0;
      while (n < l.length() && (l.charAt(n) == ' ' || l.charAt(n) == '\t')) {
        n++;
      }
      common = Math.min(common, n);
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        sb.append('\n');
      }
      String l = lines[i];
      sb.append(l.trim().isEmpty() ? "" : l.substring(Math.min(common, l.length())));
    }
    return sb.toString();
  }
}
