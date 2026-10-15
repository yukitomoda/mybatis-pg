package mybatispg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Java のクラスがなくても読み込めるよう、mapper XML からクラス参照を取り除く。
 * いずれも結果のマッピングや型変換に関わるもので、SQL の展開結果には影響しない。
 *
 * <ul>
 * <li>resultMap / cache / cache-ref 要素を削除する</li>
 * <li>statement と selectKey の parameterType / resultType / resultMap 属性を削除する</li>
 * <li>parameterMap の type を map にし、parameter の javaType / typeHandler 属性を削除する</li>
 * <li>SQL 本文の #{...} から javaType / typeHandler オプションを削除する</li>
 * </ul>
 */
public final class MapperPreprocessor {

  private static final Set<String> REMOVED_ELEMENTS = new HashSet<>(Arrays.asList("resultMap", "cache", "cache-ref"));
  private static final Set<String> STATEMENT_ELEMENTS =
      new HashSet<>(Arrays.asList("select", "insert", "update", "delete", "selectKey"));
  private static final Set<String> STATEMENT_CLASS_ATTRIBUTES =
      new HashSet<>(Arrays.asList("parameterType", "resultType", "resultMap"));
  private static final Set<String> PARAMETER_CLASS_OPTIONS = new HashSet<>(Arrays.asList("javaType", "typeHandler"));

  private MapperPreprocessor() {
  }

  /** 前処理した mapper XML を返す。XML の構文エラーは元のファイルの行番号付きで SAXException になる */
  public static byte[] preprocess(InputStream in, String location) throws IOException, SAXException {
    Document doc = parse(in, location);
    Element mapper = doc.getDocumentElement();
    process(mapper);
    if (!hasElementChild(mapper)) {
      // DTD 上 mapper は子要素が 1 つ以上必要なため、resultMap だけの mapper には空の sql 断片を残す
      Element sql = doc.createElement("sql");
      sql.setAttribute("id", "__mybatispg_empty__");
      mapper.appendChild(sql);
    }
    return serialize(doc);
  }

  private static Document parse(InputStream in, String location) throws IOException, SAXException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setValidating(false);
    factory.setNamespaceAware(false);
    factory.setCoalescing(false);
    try {
      DocumentBuilder builder = factory.newDocumentBuilder();
      // DTD をネットワークから取りに行かないよう、MyBatis 同梱の DTD を使う
      builder.setEntityResolver(new XMLMapperEntityResolver());
      return builder.parse(in, location);
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void process(Node node) {
    List<Node> children = new ArrayList<>();
    NodeList list = node.getChildNodes();
    for (int i = 0; i < list.getLength(); i++) {
      children.add(list.item(i));
    }
    for (Node child : children) {
      switch (child.getNodeType()) {
        case Node.ELEMENT_NODE:
          Element e = (Element) child;
          String name = e.getTagName();
          if (REMOVED_ELEMENTS.contains(name)) {
            node.removeChild(e);
            continue;
          }
          if (STATEMENT_ELEMENTS.contains(name)) {
            for (String attr : STATEMENT_CLASS_ATTRIBUTES) {
              e.removeAttribute(attr);
            }
          } else if ("parameterMap".equals(name)) {
            e.setAttribute("type", "map");
          } else if ("parameter".equals(name)) {
            e.removeAttribute("javaType");
            e.removeAttribute("typeHandler");
          }
          process(e);
          break;
        case Node.TEXT_NODE:
        case Node.CDATA_SECTION_NODE:
          child.setNodeValue(stripParameterClassOptions(child.getNodeValue()));
          break;
        default:
          break;
      }
    }
  }

  /**
   * SQL 本文中の #{...} から javaType / typeHandler オプションを取り除く。
   * トークンの切り出しは MyBatis の GenericTokenParser と同じ規則（\#{ と \} はエスケープ）に従い、
   * それ以外の文字はエスケープ記号も含めてそのまま残す。
   */
  static String stripParameterClassOptions(String text) {
    StringBuilder out = new StringBuilder();
    int from = 0;
    while (true) {
      int start = text.indexOf("#{", from);
      if (start < 0) {
        break;
      }
      if (start > 0 && text.charAt(start - 1) == '\\') {
        out.append(text, from, start + 2);
        from = start + 2;
        continue;
      }
      int end = start + 2;
      while (true) {
        end = text.indexOf('}', end);
        if (end < 0 || text.charAt(end - 1) != '\\') {
          break;
        }
        end++;
      }
      if (end < 0) {
        // 閉じていないトークンは MyBatis でも置き換えられないため、そのまま残す
        break;
      }
      out.append(text, from, start).append("#{").append(stripOptions(text.substring(start + 2, end))).append('}');
      from = end + 1;
    }
    out.append(text, from, text.length());
    return out.toString();
  }

  /** "prop,javaType=X, jdbcType=VARCHAR" のような中身から、該当するオプションだけを除く */
  private static String stripOptions(String content) {
    List<String> parts = splitTopLevel(content);
    StringBuilder out = new StringBuilder(parts.get(0));
    for (int i = 1; i < parts.size(); i++) {
      String part = parts.get(i);
      int eq = part.indexOf('=');
      String key = eq < 0 ? part.trim() : part.substring(0, eq).trim();
      if (!PARAMETER_CLASS_OPTIONS.contains(key)) {
        out.append(',').append(part);
      }
    }
    return out.toString();
  }

  /** 括弧の外にあるカンマで分割する（先頭の "(式)" 形式の中のカンマでは切らない） */
  private static List<String> splitTopLevel(String s) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int from = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        parts.add(s.substring(from, i));
        from = i + 1;
      }
    }
    parts.add(s.substring(from));
    return parts;
  }

  private static boolean hasElementChild(Element e) {
    NodeList list = e.getChildNodes();
    for (int i = 0; i < list.getLength(); i++) {
      if (list.item(i).getNodeType() == Node.ELEMENT_NODE) {
        return true;
      }
    }
    return false;
  }

  private static byte[] serialize(Document doc) {
    try {
      Transformer t = TransformerFactory.newInstance().newTransformer();
      t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      // XMLMapperBuilder は DTD で検証するため、DOCTYPE を残す
      DocumentType doctype = doc.getDoctype();
      if (doctype != null) {
        if (doctype.getPublicId() != null) {
          t.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, doctype.getPublicId());
        }
        if (doctype.getSystemId() != null) {
          t.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, doctype.getSystemId());
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      t.transform(new DOMSource(doc), new StreamResult(out));
      return out.toByteArray();
    } catch (TransformerException e) {
      throw new IllegalStateException(e);
    }
  }
}
