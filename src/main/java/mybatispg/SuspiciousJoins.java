package mybatispg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 疑わしい結合: 解析で見つけた列の組（結合）のうち、ほかの根拠と食い違うもの。
 * <ul>
 * <li>源流が食い違う: その組を除いたほかの根拠から求めた、両側の源流（一意の列なら、その列自身）が重ならない。
 * 片方にしかほかの根拠がない組も対象にする（o.customer_id = p.id で、customer_id はほかの SQL から customers.id を指すと分かっている）。
 * どちらにもほかの根拠がなければ、判断できないので出さない。
 * 食い違う相手の根拠（SQL の数）がその組の根拠より少なければ、その組のほうが正しいとみなして出さない</li>
 * <li>違う表の同じ名前の主キーどうし: users.id = posts.id など（1 列で一意で、名前が同じ列どうし）</li>
 * </ul>
 */
final class SuspiciousJoins {

  private SuspiciousJoins() {
  }

  static final class Suspicion {
    final String a;
    final String b;
    final String kind;
    final List<String> statements;
    /** 理由（画面に出す） */
    final String reason;
    /** 両側の源流（一意の列なら、その列自身） */
    final List<String> rootsA;
    final List<String> rootsB;
    /** 問題なしとして隠した */
    final boolean ignored;

    Suspicion(Relations.Link l, String reason, Set<String> rootsA, Set<String> rootsB, boolean ignored) {
      this.a = l.a;
      this.b = l.b;
      this.kind = l.kind;
      this.statements = new ArrayList<>(l.statements);
      this.reason = reason;
      this.rootsA = new ArrayList<>(rootsA);
      this.rootsB = new ArrayList<>(rootsB);
      this.ignored = ignored;
    }
  }

  private static String tableOf(String column) {
    return column.substring(0, column.lastIndexOf('.'));
  }

  private static String nameOf(String column) {
    return column.substring(column.lastIndexOf('.') + 1);
  }

  /**
   * @param links リレーション（Relations.links で作ったもの。判定の間だけ、組を 1 つずつ無効にする）
   * @param manual 手で設定したリレーション（null なら、ないものとする）
   */
  static List<Suspicion> find(Predicate<String> isUnique, List<Relations.Link> links, ManualRelations manual) {
    Function<String, List<String>> manualParents = Relations.manualParents(isUnique, manual);
    List<Suspicion> out = new ArrayList<>();
    // 列ごとのつながり（判定の間、組を 1 つずつ無効にするが、無効かどうかは引くときに見る）
    Relations.LinkIndex index = new Relations.LinkIndex(links);
    for (Relations.Link l : links) {
      if (l.disabled || l.manual) {
        continue;
      }
      // この組を除いた、ほかの根拠から求めた源流
      l.disabled = true;
      List<Relations.Origin> oa;
      List<Relations.Origin> ob;
      try {
        oa = Relations.origins(isUnique, index, l.a, manualParents);
        ob = Relations.origins(isUnique, index, l.b, manualParents);
      } finally {
        l.disabled = false;
      }
      Set<String> ea = roots(oa);
      Set<String> eb = roots(ob);
      // ほかの根拠の強さ（源流までたどった道の SQL の数。手で設定したものは強いとみなす）。
      // その組の根拠より少なければ、その組のほうが正しいとみなして出さない（同じ数なら両方を出す）
      int strength = 0;
      for (Relations.Origin o : oa) {
        strength = Math.max(strength, o.manual ? Integer.MAX_VALUE : o.count);
      }
      for (Relations.Origin o : ob) {
        strength = Math.max(strength, o.manual ? Integer.MAX_VALUE : o.count);
      }
      Set<String> ra = isUnique.test(l.a) ? single(l.a) : ea;
      Set<String> rb = isUnique.test(l.b) ? single(l.b) : eb;
      boolean ignored = manual != null && manual.isIgnored(l.a, l.b);
      // 根拠の数が同じなら、列の名前が相手の表の名前を含む組を正しいとみなす（owner_employee_id = employees.id）
      boolean tieButNamed = strength == l.statements.size() && (namesTable(l.a, l.b) || namesTable(l.b, l.a));
      if ((!ea.isEmpty() || !eb.isEmpty()) && !ra.isEmpty() && !rb.isEmpty() && disjoint(ra, rb) && strength >= l.statements.size()
          && !tieButNamed) {
        out.add(new Suspicion(l, "源流が食い違う", ra, rb, ignored));
      } else if (isUnique.test(l.a) && isUnique.test(l.b) && !tableOf(l.a).equals(tableOf(l.b)) && nameOf(l.a).equals(nameOf(l.b))) {
        out.add(new Suspicion(l, "違う表の、同じ名前の主キーどうし", ra, rb, ignored));
      }
    }
    return out;
  }

  /** 列の名前が、相手の列の表の名前（単数にしたものも）を含む（customer_id と customers） */
  static boolean namesTable(String column, String other) {
    String name = nameOf(column).toLowerCase();
    String t = tableOf(other);
    t = t.substring(t.indexOf('.') + 1).toLowerCase();
    String single = t.endsWith("ies") ? t.substring(0, t.length() - 3) + "y" : t.endsWith("ses") ? t.substring(0, t.length() - 2)
        : t.endsWith("s") ? t.substring(0, t.length() - 1) : t;
    return single.length() >= 3 && (name.contains(single) || name.contains(t));
  }

  private static Set<String> roots(List<Relations.Origin> origins) {
    Set<String> out = new LinkedHashSet<>();
    for (Relations.Origin o : origins) {
      out.add(o.column);
    }
    return out;
  }

  private static Set<String> single(String column) {
    Set<String> out = new LinkedHashSet<>();
    out.add(column);
    return out;
  }

  private static boolean disjoint(Set<String> x, Set<String> y) {
    for (String s : x) {
      if (y.contains(s)) {
        return false;
      }
    }
    return true;
  }
}
