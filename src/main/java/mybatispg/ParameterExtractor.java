package mybatispg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ibatis.builder.ParameterExpression;
import org.apache.ibatis.ognl.ASTChain;
import org.apache.ibatis.ognl.ASTConst;
import org.apache.ibatis.ognl.ASTMethod;
import org.apache.ibatis.ognl.ASTProperty;
import org.apache.ibatis.ognl.ComparisonExpression;
import org.apache.ibatis.ognl.Ognl;
import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * statement の XML（include 解決後）をたどり、参照しているパラメータを木として抜き出す。
 *
 * <p>抽出元は #{}、${}、if / when の test、foreach の collection、bind の value。
 * foreach の item / index と bind の name はローカル変数として扱い、item は collection の要素に読み替える。
 */
public final class ParameterExtractor {

  private final ParamNode root = new ParamNode(null);
  private final List<String> warnings = new ArrayList<>();

  private ParameterExtractor() {
  }

  public static Result extract(Node statement) {
    ParameterExtractor extractor = new ParameterExtractor();
    extractor.walk(statement, extractor.new Scope(null));
    return new Result(extractor.root, extractor.warnings);
  }

  public static final class Result {
    public final ParamNode root;
    /** 解析できなかった式など。抽出は続行している */
    public final List<String> warnings;

    Result(ParamNode root, List<String> warnings) {
      this.root = root;
      this.warnings = warnings;
    }
  }

  // ---- XML ----

  private void walk(Node node, Scope scope) {
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      switch (child.getNodeType()) {
        case Node.TEXT_NODE:
        case Node.CDATA_SECTION_NODE:
          text(child.getNodeValue(), scope);
          break;
        case Node.ELEMENT_NODE:
          element((Element) child, scope);
          break;
        default:
          break;
      }
    }
  }

  private void element(Element e, Scope scope) {
    switch (e.getTagName()) {
      case "if":
      case "when":
        expression(e.getAttribute("test"), ParamNode.Usage.CONDITION, scope);
        walk(e, scope);
        break;
      case "foreach":
        foreach(e, scope);
        break;
      case "bind":
        expression(e.getAttribute("value"), ParamNode.Usage.CONDITION, scope);
        // bind した名前は DynamicContext に残るため、以降の兄弟要素からも参照できる
        scope.declareLocal(e.getAttribute("name"));
        break;
      default:
        walk(e, scope);
        break;
    }
  }

  private void foreach(Element e, Scope scope) {
    Scope inner = new Scope(scope);
    Ref collection = expression(e.getAttribute("collection"), ParamNode.Usage.ITERATE, scope);
    String item = e.getAttribute("item");
    if (!item.isEmpty()) {
      if (collection != null && collection.node != null) {
        inner.alias(item, collection.node.element());
      } else {
        inner.declareLocal(item);
      }
    }
    if (!e.getAttribute("index").isEmpty()) {
      inner.declareLocal(e.getAttribute("index"));
    }
    walk(e, inner);
  }

  private void text(String text, final Scope scope) {
    // トークンの切り出しは MyBatis 本体と同じ GenericTokenParser に任せる
    new GenericTokenParser("#{", "}", new TokenHandler() {
      @Override
      public String handleToken(String content) {
        bindToken(content, scope);
        return "";
      }
    }).parse(text);
    new GenericTokenParser("${", "}", new TokenHandler() {
      @Override
      public String handleToken(String content) {
        expression(content, ParamNode.Usage.RAW, scope);
        return "";
      }
    }).parse(text);
  }

  /** #{...} の中身。SqlSourceBuilder と同じく ParameterExpression で解析する */
  private void bindToken(String content, Scope scope) {
    ParameterExpression pe;
    try {
      pe = new ParameterExpression(content);
    } catch (RuntimeException ex) {
      warnings.add("#{" + content + "} を解析できません: " + ex.getMessage());
      return;
    }
    if (pe.containsKey("expression")) {
      expression(pe.get("expression"), ParamNode.Usage.BIND, scope);
      return;
    }
    ParamNode node = propertyPath(pe.get("property"), scope);
    if (node != null) {
      node.addUsage(ParamNode.Usage.BIND);
      ParamNode.TypeHint hint = hintFromJdbcType(pe.get("jdbcType"));
      if (hint != null) {
        node.addTypeHint(hint);
      }
    }
  }

  /** "a.b[0].c" のようなプロパティパスをたどる（DefaultParameterHandler の MetaObject と同じ規則） */
  private ParamNode propertyPath(String property, Scope scope) {
    if (property == null || property.isEmpty()) {
      return null;
    }
    PropertyTokenizer token = new PropertyTokenizer(property);
    ParamNode node = scope.lookup(token.getName());
    while (node != null) {
      if (token.getIndex() != null) {
        node = isInteger(token.getIndex()) ? node.element() : node.property(token.getIndex());
      }
      if (!token.hasNext()) {
        break;
      }
      token = token.next();
      node = node.property(token.getName());
    }
    return node;
  }

  // ---- OGNL ----

  /** 式の中でパラメータを指す部分 */
  private static final class Ref {
    /** ローカル変数などパラメータでない場合は null */
    final ParamNode node;
    /** メソッド呼び出しなどで途中までしかたどれなかった場合は false（型の推測に使わない） */
    final boolean exact;

    Ref(ParamNode node, boolean exact) {
      this.node = node;
      this.exact = exact;
    }
  }

  /** 式全体がパラメータ参照ならその参照を返す */
  private Ref expression(String expression, ParamNode.Usage usage, Scope scope) {
    if (expression == null || expression.trim().isEmpty()) {
      return null;
    }
    Object ast;
    try {
      ast = Ognl.parseExpression(expression);
    } catch (Exception ex) {
      warnings.add("式を解析できません: " + expression + " (" + ex.getMessage() + ")");
      return null;
    }
    return visit((org.apache.ibatis.ognl.Node) ast, usage, scope);
  }

  private Ref visit(org.apache.ibatis.ognl.Node n, ParamNode.Usage usage, Scope scope) {
    if (n instanceof ASTChain || n instanceof ASTProperty) {
      Ref ref = chain(n, usage, scope);
      if (ref != null && ref.node != null) {
        ref.node.addUsage(usage);
      }
      return ref;
    }
    List<Ref> operands = new ArrayList<>();
    for (int i = 0; i < n.jjtGetNumChildren(); i++) {
      operands.add(visit(n.jjtGetChild(i), usage, scope));
    }
    if (n instanceof ComparisonExpression && n.jjtGetNumChildren() == 2) {
      // "status == 1" のような定数との比較から型を推測する
      hintFromComparison(operands.get(0), n.jjtGetChild(1));
      hintFromComparison(operands.get(1), n.jjtGetChild(0));
    }
    return null;
  }

  /** a.b[0].c や a.size() のような連鎖をたどる。たどれない部分は通常の式として解析する */
  private Ref chain(org.apache.ibatis.ognl.Node n, ParamNode.Usage usage, Scope scope) {
    List<org.apache.ibatis.ognl.Node> links = new ArrayList<>();
    if (n instanceof ASTChain) {
      for (int i = 0; i < n.jjtGetNumChildren(); i++) {
        links.add(n.jjtGetChild(i));
      }
    } else {
      links.add(n);
    }

    String first = propertyName(links.get(0));
    if (first == null) {
      for (org.apache.ibatis.ognl.Node link : links) {
        visit(link, usage, scope);
      }
      return null;
    }
    ParamNode node = scope.lookup(first);
    boolean exact = true;
    for (int i = 1; i < links.size() && node != null; i++) {
      org.apache.ibatis.ognl.Node link = links.get(i);
      String name = propertyName(link);
      if (name != null) {
        node = node.property(name);
      } else if (link instanceof ASTProperty && ((ASTProperty) link).isIndexedAccess()) {
        Object index = constValue(link.jjtGetChild(0));
        if (index instanceof Number) {
          node = node.element();
        } else if (index instanceof String) {
          node = node.property((String) index);
        } else {
          visit(link.jjtGetChild(0), usage, scope);
          exact = false;
          break;
        }
      } else {
        // メソッド呼び出しなど。size() はコレクションとみなす
        if (link instanceof ASTMethod && "size".equals(((ASTMethod) link).getMethodName())) {
          node.element();
        }
        for (int j = 0; j < link.jjtGetNumChildren(); j++) {
          visit(link.jjtGetChild(j), usage, scope);
        }
        exact = false;
        break;
      }
    }
    return new Ref(node, exact);
  }

  /** 添字なしのプロパティアクセス（"name"）ならその名前を返す */
  private static String propertyName(org.apache.ibatis.ognl.Node n) {
    if (n instanceof ASTProperty && !((ASTProperty) n).isIndexedAccess() && n.jjtGetNumChildren() == 1) {
      Object v = constValue(n.jjtGetChild(0));
      if (v instanceof String) {
        return (String) v;
      }
    }
    return null;
  }

  private static Object constValue(org.apache.ibatis.ognl.Node n) {
    return n instanceof ASTConst ? ((ASTConst) n).getValue() : null;
  }

  private static void hintFromComparison(Ref ref, org.apache.ibatis.ognl.Node other) {
    if (ref == null || ref.node == null || !ref.exact || !(other instanceof ASTConst)) {
      return;
    }
    Object v = ((ASTConst) other).getValue();
    if (v instanceof Number) {
      ref.node.addTypeHint(ParamNode.TypeHint.NUMBER);
    } else if (v instanceof String || v instanceof Character) {
      // OGNL では 'A' のような 1 文字のリテラルは Character になる
      ref.node.addTypeHint(ParamNode.TypeHint.STRING);
    } else if (v instanceof Boolean) {
      ref.node.addTypeHint(ParamNode.TypeHint.BOOLEAN);
    }
  }

  private static ParamNode.TypeHint hintFromJdbcType(String jdbcType) {
    if (jdbcType == null) {
      return null;
    }
    switch (jdbcType.trim().toUpperCase(Locale.ROOT)) {
      case "TINYINT":
      case "SMALLINT":
      case "INTEGER":
      case "BIGINT":
      case "NUMERIC":
      case "DECIMAL":
      case "REAL":
      case "FLOAT":
      case "DOUBLE":
        return ParamNode.TypeHint.NUMBER;
      case "BOOLEAN":
      case "BIT":
        return ParamNode.TypeHint.BOOLEAN;
      case "CHAR":
      case "VARCHAR":
      case "LONGVARCHAR":
      case "NCHAR":
      case "NVARCHAR":
      case "LONGNVARCHAR":
      case "CLOB":
      case "NCLOB":
      // 日付はパラメータでは文字列で渡す
      case "DATE":
      case "TIME":
      case "TIMESTAMP":
        return ParamNode.TypeHint.STRING;
      default:
        return null;
    }
  }

  private static boolean isInteger(String s) {
    return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
  }

  // ---- スコープ ----

  /** 名前の解決先。ローカル変数はパラメータに対応しないので null を返す */
  private final class Scope {
    private final Scope parent;
    private final Map<String, ParamNode> names = new HashMap<>();

    Scope(Scope parent) {
      this.parent = parent;
    }

    void alias(String name, ParamNode node) {
      names.put(name, node);
    }

    void declareLocal(String name) {
      names.put(name, null);
    }

    ParamNode lookup(String name) {
      for (Scope s = this; s != null; s = s.parent) {
        if (s.names.containsKey(name)) {
          return s.names.get(name);
        }
      }
      if ("_parameter".equals(name)) {
        return root;
      }
      if ("_databaseId".equals(name)) {
        return null;
      }
      return root.property(name);
    }
  }
}
