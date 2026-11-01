package mybatispg;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 関連データの抜き出し: リレーションをたどって、ある行に関連する行（親・子）を集める。
 * 親は自動で、子は 1 段ずつ画面の操作でたどる（どちらも画面が呼ぶ）。
 */
final class RelatedData {

  private RelatedData() {
  }

  /** 隣の表（その表とのつながり。複合キーは列の組でまとめる） */
  static final class Neighbor {
    /** parent: この表が参照する表（1 対 1 を含む）、child: この表を参照する表 */
    final String direction;
    final String table;
    /** この表の列 */
    final List<String> columns = new ArrayList<>();
    /** 相手の表の列（columns と同じ順） */
    final List<String> otherColumns = new ArrayList<>();
    final boolean manual;
    /** 子のとき、相手の列（otherColumns）から始まるインデックスがあるか（ないと表を全件読む） */
    Boolean indexed;

    Neighbor(String direction, String table, boolean manual) {
      this.direction = direction;
      this.table = table;
      this.manual = manual;
    }
  }

  private static String tableOf(String column) {
    return column.substring(0, column.lastIndexOf('.'));
  }

  private static String nameOf(String column) {
    return column.substring(column.lastIndexOf('.') + 1);
  }

  /**
   * 表の隣の表。無効にしたつながりと兄弟は使わない。複数の列でできた鍵で決めた親子は、列の組を 1 つにまとめる。
   * 自己参照（上司など）は、親と子の両方に出す。
   *
   * @param links リレーション（解析したものと、手で設定したもの）
   */
  static List<Neighbor> neighbors(String table, List<Relations.Link> links) {
    Map<String, Neighbor> out = new LinkedHashMap<>();
    for (Relations.Link l : links) {
      if (l.disabled || "sibling".equals(l.kind)) {
        continue;
      }
      // 1 対 1 は、どちらから見ても親として扱う（自動で含める）
      boolean oneToOne = "one-to-one".equals(l.kind);
      for (int side = 0; side < 2; side++) {
        String mine = side == 0 ? l.a : l.b;
        String other = side == 0 ? l.b : l.a;
        if (!tableOf(mine).equals(table)) {
          continue;
        }
        // parent の a は子（b が親）
        String direction = oneToOne || side == 0 ? "parent" : "child";
        String group = direction + "\u0000" + tableOf(other) + "\u0000" + (l.compositeKey != null ? l.compositeKey : mine + "\u0000" + other);
        Neighbor n = out.get(group);
        if (n == null) {
          n = new Neighbor(direction, tableOf(other), l.manual);
          out.put(group, n);
        }
        if (!n.columns.contains(nameOf(mine))) {
          n.columns.add(nameOf(mine));
          n.otherColumns.add(nameOf(other));
        }
      }
    }
    return new ArrayList<>(out.values());
  }

  /** 子の表で、相手の列から始まるインデックスがあるかを調べて、indexed に入れる */
  static void markIndexed(Connection c, List<Neighbor> neighbors) throws SQLException {
    List<String> schemas = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (Neighbor n : neighbors) {
      if ("child".equals(n.direction)) {
        schemas.add(n.table.substring(0, n.table.indexOf('.')));
        names.add(n.table.substring(n.table.indexOf('.') + 1));
      }
    }
    if (schemas.isEmpty()) {
      return;
    }
    // 表ごとの、インデックスの先頭の列（定義の順）
    Map<String, List<List<String>>> indexes = new LinkedHashMap<>();
    try (PreparedStatement ps = c.prepareStatement("SELECT n.nspname || '.' || c.relname,"
        + " ARRAY(SELECT a.attname::text FROM unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)"
        + " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum ORDER BY k.ord)"
        + " FROM unnest(?::text[], ?::text[]) AS x(s, t) JOIN pg_namespace n ON n.nspname = x.s"
        + " JOIN pg_class c ON c.relnamespace = n.oid AND c.relname = x.t JOIN pg_index i ON i.indrelid = c.oid")) {
      ps.setArray(1, c.createArrayOf("text", schemas.toArray()));
      ps.setArray(2, c.createArrayOf("text", names.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          List<String> cols = new ArrayList<>();
          for (Object o : (Object[]) rs.getArray(2).getArray()) {
            cols.add(String.valueOf(o));
          }
          indexes.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(cols);
        }
      }
    } finally {
      c.rollback();
    }
    for (Neighbor n : neighbors) {
      if (!"child".equals(n.direction)) {
        continue;
      }
      boolean ok = false;
      Set<String> want = new HashSet<>(n.otherColumns);
      for (List<String> idx : indexes.getOrDefault(n.table, new ArrayList<List<String>>())) {
        // インデックスの先頭の列が、引く列の組をすべて含む（順は問わない）
        if (idx.size() >= want.size() && new HashSet<>(idx.subList(0, want.size())).equals(want)) {
          ok = true;
        }
      }
      n.indexed = ok;
    }
  }

  /**
   * 表から、列の値の組に当たる行を引く SQL（(c1, c2) IN ((?::型, ?::型), ...)。主キーがあれば主キーの順）。
   *
   * @param types 列ごとの型（format_type の名前）
   */
  static String rowsSql(String table, List<String> columns, int tuples, Map<String, String> types, List<String> pk, boolean count) {
    StringBuilder sb = new StringBuilder(count ? "SELECT count(*) FROM " : "SELECT * FROM ");
    sb.append(quote(table.substring(0, table.indexOf('.')))).append('.').append(quote(table.substring(table.indexOf('.') + 1)));
    sb.append(" WHERE (");
    for (int i = 0; i < columns.size(); i++) {
      sb.append(i == 0 ? "" : ", ").append(quote(columns.get(i)));
    }
    sb.append(") IN (");
    for (int t = 0; t < tuples; t++) {
      sb.append(t == 0 ? "(" : ", (");
      for (int i = 0; i < columns.size(); i++) {
        sb.append(i == 0 ? "" : ", ").append("?::").append(types.get(columns.get(i)));
      }
      sb.append(')');
    }
    sb.append(')');
    if (!count && !pk.isEmpty()) {
      for (int i = 0; i < pk.size(); i++) {
        sb.append(i == 0 ? " ORDER BY " : ", ").append(quote(pk.get(i)));
      }
    }
    return sb.toString();
  }

  /** 表の列の型（format_type）と、主キーの列（定義の順） */
  static Map<String, Object> describe(Connection c, String table) throws SQLException {
    String schema = table.substring(0, table.indexOf('.'));
    String name = table.substring(table.indexOf('.') + 1);
    Map<String, String> types = new LinkedHashMap<>();
    List<String> pk = new ArrayList<>();
    try {
      try (PreparedStatement ps = c.prepareStatement("SELECT a.attname, format_type(a.atttypid, a.atttypmod) FROM pg_attribute a"
          + " JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = ?::name AND c.relname = ?::name AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum")) {
        ps.setString(1, schema);
        ps.setString(2, name);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            types.put(rs.getString(1), rs.getString(2));
          }
        }
      }
      try (PreparedStatement ps = c.prepareStatement("SELECT a.attname FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid"
          + " JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY (i.indkey)"
          + " WHERE i.indisprimary AND n.nspname = ?::name AND c.relname = ?::name ORDER BY array_position(i.indkey::int2[], a.attnum)")) {
        ps.setString(1, schema);
        ps.setString(2, name);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            pk.add(rs.getString(1));
          }
        }
      }
    } finally {
      c.rollback();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("types", types);
    out.put("pk", pk);
    return out;
  }

  static String quote(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  /** 値の組の重なりを除く（NULL を含む組は、= で当たらないので除く） */
  static List<List<String>> distinctTuples(List<List<String>> values) {
    Set<List<String>> seen = new java.util.LinkedHashSet<>();
    for (List<String> v : values) {
      if (!v.contains(null)) {
        seen.add(Arrays.asList(v.toArray(new String[0])));
      }
    }
    return new ArrayList<>(seen);
  }
}
