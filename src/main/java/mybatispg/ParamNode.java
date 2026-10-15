package mybatispg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * statement が参照するパラメータの木。入力 UI の項目の元になる。
 * 子に名前付きの項目があればオブジェクト、要素（element）があればリストとして扱う。
 */
public final class ParamNode {

  /** どこで参照されているか */
  public enum Usage {
    /** #{} でバインドされる */
    BIND,
    /** ${} で SQL にそのまま埋め込まれる */
    RAW,
    /** test 式や bind の式で評価される */
    CONDITION,
    /** foreach の collection として繰り返される */
    ITERATE
  }

  /** 式から推測した値の型 */
  public enum TypeHint {
    STRING, NUMBER, BOOLEAN
  }

  private final String name;
  private final Map<String, ParamNode> properties = new LinkedHashMap<>();
  private ParamNode element;
  private final Set<Usage> usages = EnumSet.noneOf(Usage.class);
  private final Set<TypeHint> typeHints = EnumSet.noneOf(TypeHint.class);

  ParamNode(String name) {
    this.name = name;
  }

  /** 項目名。ルートは null、リストの要素は "[]" */
  public String getName() {
    return name;
  }

  public Map<String, ParamNode> getProperties() {
    return Collections.unmodifiableMap(properties);
  }

  /** リストの要素。リストでなければ null */
  public ParamNode getElement() {
    return element;
  }

  public Set<Usage> getUsages() {
    return Collections.unmodifiableSet(usages);
  }

  public Set<TypeHint> getTypeHints() {
    return Collections.unmodifiableSet(typeHints);
  }

  ParamNode property(String propertyName) {
    ParamNode child = properties.get(propertyName);
    if (child == null) {
      child = new ParamNode(propertyName);
      properties.put(propertyName, child);
    }
    return child;
  }

  ParamNode element() {
    if (element == null) {
      element = new ParamNode("[]");
    }
    return element;
  }

  void addUsage(Usage usage) {
    usages.add(usage);
  }

  void addTypeHint(TypeHint hint) {
    typeHints.add(hint);
  }

  /**
   * 木を 1 項目 1 行で表す（ルートは除く）。
   * 例: "cond.name CONDITION STRING"、"ids[] BIND"
   */
  public List<String> describe() {
    List<String> lines = new ArrayList<>();
    describe("", lines);
    return lines;
  }

  private void describe(String path, List<String> lines) {
    for (ParamNode child : properties.values()) {
      String childPath = path.isEmpty() ? child.name : path + "." + child.name;
      lines.add(child.line(childPath));
      child.describe(childPath, lines);
    }
    if (element != null) {
      String childPath = path + "[]";
      lines.add(element.line(childPath));
      element.describe(childPath, lines);
    }
  }

  private String line(String path) {
    StringBuilder sb = new StringBuilder(path);
    for (Usage u : usages) {
      sb.append(' ').append(u);
    }
    for (TypeHint h : typeHints) {
      sb.append(' ').append(h);
    }
    return sb.toString();
  }
}
