package mybatisrender;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全 statement で使われているパラメータの項目を集計する。共通パラメータに入れる項目の候補にする。
 *
 * <p>項目はオブジェクトの項目をたどったパス（例: tenantId、cond.status）で数える。リストの要素の中は数えない。
 */
public final class ParameterNames {

  public static final class Entry {
    public final String path;
    /** 使っている statement の数 */
    public int count;
    public final Set<ParamNode.Usage> usages = EnumSet.noneOf(ParamNode.Usage.class);
    public final Set<ParamNode.TypeHint> typeHints = EnumSet.noneOf(ParamNode.TypeHint.class);
    /** どこかの statement で子の項目を持つ */
    public boolean object;
    /** どこかの statement でリストとして使われる */
    public boolean list;

    Entry(String path) {
      this.path = path;
    }
  }

  private ParameterNames() {
  }

  /** 使っている statement の多い順（同数ならパス順）に返す */
  public static List<Entry> collect(SqlRenderer renderer) {
    Map<String, Entry> entries = new LinkedHashMap<>();
    for (String id : renderer.statementIds()) {
      ParamNode root;
      try {
        root = renderer.parameters(id).root;
      } catch (RuntimeException e) {
        // パラメータを抜き出せない statement は数えない
        continue;
      }
      Map<String, Entry> seen = new LinkedHashMap<>();
      walk(root, "", seen);
      for (Entry e : seen.values()) {
        Entry total = entries.get(e.path);
        if (total == null) {
          total = new Entry(e.path);
          entries.put(e.path, total);
        }
        total.count++;
        total.usages.addAll(e.usages);
        total.typeHints.addAll(e.typeHints);
        total.object |= e.object;
        total.list |= e.list;
      }
    }
    List<Entry> result = new ArrayList<>(entries.values());
    result.sort((a, b) -> a.count != b.count ? b.count - a.count : a.path.compareTo(b.path));
    return result;
  }

  private static void walk(ParamNode node, String prefix, Map<String, Entry> seen) {
    for (ParamNode child : node.getProperties().values()) {
      String path = prefix.isEmpty() ? child.getName() : prefix + "." + child.getName();
      Entry e = new Entry(path);
      e.usages.addAll(child.getUsages());
      e.typeHints.addAll(child.getTypeHints());
      e.object = !child.getProperties().isEmpty();
      e.list = child.getElement() != null;
      seen.put(path, e);
      walk(child, path, seen);
    }
  }
}
