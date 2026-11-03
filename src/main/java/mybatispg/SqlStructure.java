package mybatispg;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.xmltags.ChooseSqlNode;
import org.apache.ibatis.scripting.xmltags.DynamicContext;
import org.apache.ibatis.scripting.xmltags.ForEachSqlNode;
import org.apache.ibatis.scripting.xmltags.IfSqlNode;
import org.apache.ibatis.scripting.xmltags.MixedSqlNode;
import org.apache.ibatis.scripting.xmltags.SqlNode;
import org.apache.ibatis.scripting.xmltags.TrimSqlNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 組み立てた SQL の構造（どこが &lt;if&gt;・&lt;foreach&gt; から出たか）。SQL タブで色を付けて見せるため。
 *
 * <p>statement の XML の写しの &lt;if&gt; の中身と &lt;foreach&gt;（全体と 1 回ごとの中身）の前後に印の文字（私用領域）を入れて
 * 組み立て、印から範囲を割り出す。&lt;where&gt;・&lt;set&gt;・&lt;trim&gt; は先頭の AND・末尾の , などを取り除くので、
 * 写しでは印を飛ばして取り除くものに差し替える。実行に使う組み立てには手を入れない。
 * 印を除いた SQL が実際の SQL と（空白を除いて）一致しなければ、範囲は返さない。
 */
final class SqlStructure {

  private static final char OPEN = '';
  private static final char CLOSE = '';
  private static final char END = '';

  private SqlStructure() {
  }

  /** 範囲（実際の SQL の中の位置）。kind: if・foreach・item（foreach の 1 回分）・param（#{} の値） */
  static final class Span {
    final int start;
    final int end;
    final String kind;
    final String label;
    /** 入れ子の深さ（外側ほど小さい。空白を除くと範囲が同じになるとき、内と外を決める） */
    final int depth;

    Span(int start, int end, String kind, String label, int depth) {
      this.start = start;
      this.end = end;
      this.kind = kind;
      this.label = label;
      this.depth = depth;
    }
  }

  /** 印を入れた写しの、組み立てる節と、印の番号ごとの種類・説明 */
  static final class Prepared {
    final SqlNode root;
    final Map<Integer, String[]> marks;

    Prepared(SqlNode root, Map<Integer, String[]> marks) {
      this.root = root;
      this.marks = marks;
    }
  }

  /** statement の XML の写しに印を入れ、組み立てる節を作る */
  static Prepared prepare(Configuration configuration, XNode script, Class<?> parameterType,
      java.util.function.BiFunction<XNode, Class<?>, SqlNode> parse) {
    Node copy = script.getNode().cloneNode(true);
    Map<Integer, String[]> marks = new LinkedHashMap<>();
    mark(copy, marks);
    SqlNode root = parse.apply(script.newXNode(copy), parameterType);
    return new Prepared(markerAware(configuration, root), marks);
  }

  private static void mark(Node node, Map<Integer, String[]> marks) {
    NodeList children = node.getChildNodes();
    List<Node> list = new ArrayList<>();
    for (int i = 0; i < children.getLength(); i++) {
      list.add(children.item(i));
    }
    for (Node child : list) {
      mark(child, marks);
    }
    if (!(node instanceof Element)) {
      return;
    }
    Element e = (Element) node;
    String name = e.getTagName();
    if ("if".equals(name)) {
      int id = marks.size();
      marks.put(id, new String[] {"if", "<if test=\"" + e.getAttribute("test") + "\">"});
      wrapInside(e, id);
    } else if ("foreach".equals(name)) {
      int item = marks.size();
      marks.put(item, new String[] {"item", "<foreach collection=\"" + e.getAttribute("collection") + "\"> の 1 回分"});
      wrapInside(e, item);
      int id = marks.size();
      marks.put(id, new String[] {"foreach", "<foreach collection=\"" + e.getAttribute("collection") + "\" item=\""
          + e.getAttribute("item") + "\">"});
      Node parent = e.getParentNode();
      parent.insertBefore(e.getOwnerDocument().createTextNode(open(id)), e);
      parent.insertBefore(e.getOwnerDocument().createTextNode(close(id)), e.getNextSibling());
    }
  }

  private static void wrapInside(Element e, int id) {
    e.insertBefore(e.getOwnerDocument().createTextNode(open(id)), e.getFirstChild());
    e.appendChild(e.getOwnerDocument().createTextNode(close(id)));
  }

  private static String open(int id) {
    return OPEN + String.valueOf(id) + END;
  }

  private static String close(int id) {
    return CLOSE + String.valueOf(id) + END;
  }

  /** 写しの &lt;where&gt;・&lt;set&gt;・&lt;trim&gt; を、印を飛ばして取り除くものに差し替える（写しなので、そのまま書き換える） */
  private static SqlNode markerAware(Configuration configuration, SqlNode node) {
    try {
      if (node instanceof MixedSqlNode || node instanceof ChooseSqlNode) {
        for (String f : node instanceof MixedSqlNode ? new String[] {"contents"} : new String[] {"ifSqlNodes"}) {
          @SuppressWarnings("unchecked")
          List<SqlNode> list = (List<SqlNode>) field(node.getClass(), f).get(node);
          for (int i = 0; i < list.size(); i++) {
            list.set(i, markerAware(configuration, list.get(i)));
          }
        }
        if (node instanceof ChooseSqlNode) {
          Field d = field(ChooseSqlNode.class, "defaultSqlNode");
          if (d.get(node) != null) {
            d.set(node, markerAware(configuration, (SqlNode) d.get(node)));
          }
        }
        return node;
      }
      if (node instanceof IfSqlNode || node instanceof ForEachSqlNode) {
        Field c = field(node.getClass(), "contents");
        c.set(node, markerAware(configuration, (SqlNode) c.get(node)));
        return node;
      }
      if (node instanceof TrimSqlNode) {
        @SuppressWarnings("unchecked")
        List<String> pre = (List<String>) field(TrimSqlNode.class, "prefixesToOverride").get(node);
        @SuppressWarnings("unchecked")
        List<String> suf = (List<String>) field(TrimSqlNode.class, "suffixesToOverride").get(node);
        return new MarkerTrim(configuration, markerAware(configuration, (SqlNode) field(TrimSqlNode.class, "contents").get(node)),
            (String) field(TrimSqlNode.class, "prefix").get(node), (String) field(TrimSqlNode.class, "suffix").get(node), pre, suf);
      }
      return node;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("MyBatis の節を読めません", e);
    }
  }

  private static Field field(Class<?> c, String name) throws NoSuchFieldException {
    Class<?> k = c;
    while (k != null) {
      try {
        Field f = k.getDeclaredField(name);
        f.setAccessible(true);
        return f;
      } catch (NoSuchFieldException e) {
        k = k.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name);
  }

  /** 印を飛ばして、先頭・末尾の決まった語を取り除く &lt;trim&gt;（MyBatis の TrimSqlNode と同じ動き） */
  private static final class MarkerTrim implements SqlNode {
    private final Configuration configuration;
    private final SqlNode contents;
    private final String prefix;
    private final String suffix;
    private final List<String> prefixes;
    private final List<String> suffixes;

    MarkerTrim(Configuration configuration, SqlNode contents, String prefix, String suffix, List<String> prefixes, List<String> suffixes) {
      this.configuration = configuration;
      this.contents = contents;
      this.prefix = prefix;
      this.suffix = suffix;
      this.prefixes = prefixes;
      this.suffixes = suffixes;
    }

    @Override
    public boolean apply(DynamicContext context) {
      Collect sub = new Collect(configuration, context);
      boolean result = contents.apply(sub);
      StringBuilder sql = new StringBuilder(sub.collected().trim());
      if (strip(sql.toString()).trim().length() > 0) {
        // 先頭: 空白と印を飛ばした所で、取り除く語を探す
        int at = skip(sql, 0, 1);
        String upper = sql.substring(at).toUpperCase(Locale.ENGLISH);
        if (prefixes != null) {
          for (String p : prefixes) {
            if (upper.startsWith(p)) {
              sql.delete(at, at + p.trim().length());
              break;
            }
          }
        }
        if (prefix != null) {
          sql.insert(0, " ").insert(0, prefix);
        }
        // 末尾: 空白と印を飛ばした所で、取り除く語を探す
        int end = skip(sql, sql.length() - 1, -1) + 1;
        String upperHead = sql.substring(0, end).toUpperCase(Locale.ENGLISH);
        if (suffixes != null) {
          for (String s : suffixes) {
            if (upperHead.endsWith(s) || upperHead.endsWith(s.trim())) {
              sql.delete(end - s.trim().length(), end);
              break;
            }
          }
        }
        if (suffix != null) {
          sql.append(" ").append(suffix);
        }
      }
      context.appendSql(sql.toString());
      return result;
    }
  }

  /** 空白と印を飛ばした位置（step: 1 なら前から、-1 なら後ろから） */
  private static int skip(CharSequence s, int from, int step) {
    int i = from;
    while (i >= 0 && i < s.length()) {
      char ch = s.charAt(i);
      if (Character.isWhitespace(ch)) {
        i += step;
      } else if (step > 0 && (ch == OPEN || ch == CLOSE)) {
        while (i < s.length() && s.charAt(i) != END) {
          i++;
        }
        i++;
      } else if (step < 0 && ch == END) {
        while (i >= 0 && s.charAt(i) != OPEN && s.charAt(i) != CLOSE) {
          i--;
        }
        i--;
      } else {
        break;
      }
    }
    return i;
  }

  /** 中身の SQL だけを集め、値（bind・foreach の項目）と番号は外側と共有する（MyBatis の FilteredDynamicContext と同じ） */
  private static final class Collect extends DynamicContext {
    private final DynamicContext delegate;
    private final StringBuilder sql = new StringBuilder();

    Collect(Configuration configuration, DynamicContext delegate) {
      super(configuration, null);
      this.delegate = delegate;
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }

    @Override
    public void appendSql(String s) {
      sql.append(' ').append(s);
    }

    @Override
    public String getSql() {
      return sql.toString().trim();
    }

    String collected() {
      return sql.toString();
    }
  }

  /** 印を除いた文字列 */
  static String strip(String marked) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < marked.length(); i++) {
      char ch = marked.charAt(i);
      if (ch == OPEN || ch == CLOSE) {
        while (i < marked.length() && marked.charAt(i) != END) {
          i++;
        }
      } else {
        out.append(ch);
      }
    }
    return out.toString();
  }

  /**
   * 印を入れた SQL から範囲を割り出し、実際の SQL の位置に直す（空白は比べない）。食い違えば空
   */
  static List<Span> spans(String marked, String actual, Map<Integer, String[]> marks) {
    StringBuilder plain = new StringBuilder();
    Deque<int[]> stack = new ArrayDeque<>();
    List<int[]> raw = new ArrayList<>();
    for (int i = 0; i < marked.length(); i++) {
      char ch = marked.charAt(i);
      if (ch == OPEN || ch == CLOSE) {
        int j = marked.indexOf(END, i);
        if (j < 0) {
          return new ArrayList<>();
        }
        int id = Integer.parseInt(marked.substring(i + 1, j));
        if (ch == OPEN) {
          stack.push(new int[] {id, plain.length(), stack.size()});
        } else {
          if (stack.isEmpty() || stack.peek()[0] != id) {
            return new ArrayList<>();
          }
          int[] o = stack.pop();
          raw.add(new int[] {id, o[1], plain.length(), o[2]});
        }
        i = j;
      } else {
        plain.append(ch);
      }
    }
    // 空白でない文字の並びで対応させる
    List<Integer> p = new ArrayList<>();
    List<Integer> a = new ArrayList<>();
    StringBuilder ps = new StringBuilder();
    StringBuilder as = new StringBuilder();
    for (int i = 0; i < plain.length(); i++) {
      if (!Character.isWhitespace(plain.charAt(i))) {
        p.add(i);
        ps.append(plain.charAt(i));
      }
    }
    for (int i = 0; i < actual.length(); i++) {
      if (!Character.isWhitespace(actual.charAt(i))) {
        a.add(i);
        as.append(actual.charAt(i));
      }
    }
    if (!ps.toString().equals(as.toString())) {
      return new ArrayList<>();
    }
    List<Span> out = new ArrayList<>();
    for (int[] r : raw) {
      // 範囲の中の、最初と最後の空白でない文字
      int first = lowerBound(p, r[1]);
      int last = lowerBound(p, r[2]) - 1;
      if (first > last) {
        continue;
      }
      String[] m = marks.get(r[0]);
      out.add(new Span(a.get(first), a.get(last) + 1, m[0], m[1], r[3]));
    }
    out.sort((x, y) -> x.start != y.start ? x.start - y.start : x.end != y.end ? y.end - x.end : x.depth - y.depth);
    return out;
  }

  private static int lowerBound(List<Integer> xs, int v) {
    int lo = 0;
    int hi = xs.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (xs.get(mid) < v) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}
