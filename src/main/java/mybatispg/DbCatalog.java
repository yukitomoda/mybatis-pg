package mybatispg;

import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB のカタログ（スキーマ・表・コメント）を、画面に出すスキーマに絞って読む。
 * 関係のないスキーマが多い DB（表が何万もある）でも軽くするため、表の一覧とコメントは出すスキーマのものだけを読む。
 *
 * <p>コメントはほとんど変わらないので、接続先ごとにメモリとファイル（~/.mybatis-pg/comments/）に覚えておき、
 * 出すスキーマが変わったときと、リレーションを解析したときだけ読み直す。
 */
final class DbCatalog {

  /** システムのスキーマを除く条件（n は pg_namespace） */
  private static final String NOT_SYSTEM = "n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\\_%'";
  private static final String TABLE_KINDS = "c.relkind IN ('r', 'p', 'v', 'm', 'f')";

  /** 表と列のコメント（読んだときに出していたスキーマ。null なら search_path のスキーマ） */
  static final class Comments {
    List<String> schemas;
    long fetchedAt;
    Map<String, String> tables = new TreeMap<>();
    Map<String, String> columns = new TreeMap<>();
  }

  /** null ならファイルに残さない */
  private final Path dir;
  private final Map<String, Comments> memory = new ConcurrentHashMap<>();

  DbCatalog(Path dir) {
    this.dir = dir;
  }

  /**
   * 出すスキーマに絞る条件（n は pg_namespace）。schemas が null なら search_path のスキーマ。
   * パラメータは 1 つ（schemas が null のときはなし）
   */
  static String schemaCondition(List<String> schemas) {
    return schemas == null ? "n.nspname = ANY (current_schemas(false))" : "n.nspname = ANY (?::text[])";
  }

  private static void bind(PreparedStatement ps, Connection c, List<String> schemas) throws SQLException {
    if (schemas != null) {
      ps.setArray(1, c.createArrayOf("text", schemas.toArray()));
    }
  }

  /** スキーマ（システムのものを除く）と、その表の数、search_path にあるか */
  static List<Map<String, Object>> schemas(Connection c) throws SQLException {
    List<Map<String, Object>> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement("SELECT n.nspname, count(c.oid), n.nspname = ANY (current_schemas(false))"
        + " FROM pg_namespace n LEFT JOIN pg_class c ON c.relnamespace = n.oid AND " + TABLE_KINDS
        + " WHERE " + NOT_SYSTEM + " GROUP BY n.nspname ORDER BY n.nspname");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", rs.getString(1));
        m.put("tables", rs.getLong(2));
        m.put("searchPath", rs.getBoolean(3));
        out.add(m);
      }
    }
    return out;
  }

  /** 出すスキーマの表（schema.table）の一覧 */
  static List<String> tables(Connection c, List<String> schemas) throws SQLException {
    List<String> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement("SELECT n.nspname || '.' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
        + " WHERE " + TABLE_KINDS + " AND " + NOT_SYSTEM + " AND " + schemaCondition(schemas) + " ORDER BY 1")) {
      bind(ps, c, schemas);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  /** 出すスキーマの、表と列のコメントを DB から読む */
  static Comments readComments(Connection c, List<String> schemas) throws SQLException {
    Comments out = new Comments();
    out.schemas = schemas == null ? null : new ArrayList<>(schemas);
    try (PreparedStatement ps = c.prepareStatement("SELECT n.nspname, c.relname, a.attname, d.description FROM pg_description d"
        + " JOIN pg_class c ON c.oid = d.objoid AND d.classoid = 'pg_class'::regclass"
        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
        + " LEFT JOIN pg_attribute a ON d.objsubid > 0 AND a.attrelid = c.oid AND a.attnum = d.objsubid AND NOT a.attisdropped"
        + " WHERE " + TABLE_KINDS + " AND " + NOT_SYSTEM + " AND " + schemaCondition(schemas)
        + " AND (d.objsubid = 0 OR a.attname IS NOT NULL)")) {
      bind(ps, c, schemas);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String table = rs.getString(1) + "." + rs.getString(2);
          if (rs.getString(3) == null) {
            out.tables.put(table, rs.getString(4));
          } else {
            out.columns.put(table + "." + rs.getString(3), rs.getString(4));
          }
        }
      }
    }
    out.fetchedAt = System.currentTimeMillis();
    return out;
  }

  /** 覚えておいたコメント。出すスキーマが変わっていれば null */
  Comments cached(String connectionId, List<String> schemas) {
    Comments m = memory.get(connectionId);
    if (m == null) {
      m = load(connectionId);
      if (m != null) {
        memory.put(connectionId, m);
      }
    }
    return m != null && same(m.schemas, schemas) ? m : null;
  }

  void put(String connectionId, Comments comments) {
    memory.put(connectionId, comments);
    if (dir == null) {
      return;
    }
    try {
      Files.createDirectories(dir);
      Path f = file(connectionId);
      Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
      try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        Json.GSON.toJson(comments, w);
      }
      Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      // 残せなくても、メモリにはある
    }
  }

  private Comments load(String connectionId) {
    if (dir == null) {
      return null;
    }
    Path f = file(connectionId);
    if (!Files.isRegularFile(f)) {
      return null;
    }
    try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
      Comments c = Json.GSON.fromJson(r, Comments.class);
      return c != null && c.tables != null && c.columns != null ? c : null;
    } catch (IOException | JsonParseException e) {
      return null;
    }
  }

  private Path file(String connectionId) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(connectionId.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 12; i++) {
        sb.append(String.format("%02x", h[i]));
      }
      return dir.resolve(sb + ".json");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean same(List<String> a, List<String> b) {
    return a == null ? b == null : b != null && new java.util.TreeSet<>(a).equals(new java.util.TreeSet<>(b));
  }
}
