package mybatispg;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 画面の「ログ」のために、{@link DbLog} のファイルを新しい順に読む。ファイルを後ろから読み、絞り込みに当たるものを集める。
 * 1 回に読む行数に上限を設け、超えたら、そこまでを返して続きの位置を知らせる（解析で何千行も書いた日でも、待たせすぎないように）。
 *
 * <p>位置（cursor）は「ファイル名:バイト位置」で、その位置より前に始まる行から読む。前の版のテキストのログ（.log）は、
 * 時刻と本文だけの行として読む。
 */
final class LogReader {

  private LogReader() {
  }

  /** 絞り込み */
  static final class Query {
    /** エラーのあるものだけ */
    boolean errorsOnly;
    /** 解析の操作も含める */
    boolean includeAnalysis;
    /** 文字（大文字・小文字を区別しない。行のどこかに含むもの） */
    String text;
  }

  /** 新しい順のログのファイル（同じ日なら、新しい形の .jsonl を先に） */
  static List<Path> files(Path dir) {
    List<Path> out = new ArrayList<>();
    if (dir == null || !Files.isDirectory(dir)) {
      return out;
    }
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "db-*.{log,jsonl}")) {
      for (Path p : ds) {
        if (DbLog.FILE.matcher(p.getFileName().toString()).matches()) {
          out.add(p);
        }
      }
    } catch (IOException e) {
      return out;
    }
    out.sort((a, b) -> {
      Matcher x = DbLog.FILE.matcher(a.getFileName().toString());
      Matcher y = DbLog.FILE.matcher(b.getFileName().toString());
      x.matches();
      y.matches();
      int c = y.group(1).compareTo(x.group(1));
      return c != 0 ? c : y.group(2).compareTo(x.group(2));
    });
    return out;
  }

  /**
   * @param cursor この位置より前から読む（null なら、いちばん新しいところから）
   * @param limit 返す数の上限
   * @param maxScan 読む行数の上限
   * @return {"entries": [{..., "id"}], "next": 続きの位置（もうなければ null）, "capped": 読む行数の上限で止めたか}
   */
  static Map<String, Object> read(Path dir, String cursor, Query query, int limit, int maxScan) throws IOException {
    List<Path> files = files(dir);
    int index = 0;
    long end = Long.MAX_VALUE;
    if (cursor != null) {
      int colon = cursor.lastIndexOf(':');
      String name = cursor.substring(0, colon);
      end = Long.parseLong(cursor.substring(colon + 1));
      index = -1;
      for (int i = 0; i < files.size(); i++) {
        if (files.get(i).getFileName().toString().equals(name)) {
          index = i;
        }
      }
      if (index < 0) {
        // 消えたファイル: それより古いファイルから読む
        index = files.size();
        for (int i = 0; i < files.size(); i++) {
          if (files.get(i).getFileName().toString().compareTo(name) < 0) {
            index = i;
            end = Long.MAX_VALUE;
            break;
          }
        }
      }
    }
    List<JsonObject> entries = new ArrayList<>();
    int[] scanned = {0};
    String next = null;
    boolean capped = false;
    String needle = query.text == null || query.text.trim().isEmpty() ? null : query.text.trim().toLowerCase(Locale.ROOT);
    for (int i = index; i < files.size(); i++) {
      Path f = files.get(i);
      String name = f.getFileName().toString();
      boolean json = name.endsWith(".jsonl");
      long stopped = readBackward(f, end, (start, bytes) -> {
        scanned[0]++;
        String line = new String(bytes, StandardCharsets.UTF_8);
        JsonObject e = matches(line, json, needle, query);
        if (e != null) {
          e.addProperty("id", name + ":" + start);
          entries.add(e);
        }
        return entries.size() < limit && scanned[0] < maxScan;
      });
      end = Long.MAX_VALUE;
      if (stopped >= 0) {
        next = name + ":" + stopped;
        capped = entries.size() < limit;
        break;
      }
      if (i + 1 < files.size()) {
        // このファイルを読み終えた。続きは次のファイルから
        next = files.get(i + 1).getFileName().toString() + ":" + Long.MAX_VALUE;
      } else {
        next = null;
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("entries", entries);
    out.put("next", next);
    out.put("capped", capped);
    out.put("scanned", scanned[0]);
    return out;
  }

  /** 行を 1 件にし、絞り込みに当たれば返す（当たらなければ null） */
  private static JsonObject matches(String line, boolean json, String needle, Query query) {
    if (line.trim().isEmpty() || needle != null && !line.toLowerCase(Locale.ROOT).contains(needle)) {
      return null;
    }
    JsonObject e;
    if (json) {
      try {
        e = JsonParser.parseString(line).getAsJsonObject();
      } catch (JsonParseException | IllegalStateException ex) {
        return null;
      }
    } else {
      // 前の版のテキストのログ: 「yyyy-MM-dd HH:mm:ss.SSS 本文」
      e = new JsonObject();
      e.addProperty("t", line.length() >= 23 ? line.substring(0, 23) : line);
      e.addProperty("kind", "text");
      String msg = line.length() > 24 ? line.substring(24) : "";
      e.addProperty("msg", msg);
      if (msg.contains(" エラー ") || msg.contains(" 失敗 ")) {
        e.addProperty("err", msg);
      }
    }
    if (query.errorsOnly && !e.has("err")) {
      return null;
    }
    if (!query.includeAnalysis && e.has("src") && "解析".equals(e.get("src").getAsString())) {
      return null;
    }
    return e;
  }

  /** 1 行を受け取る。false を返すと、そこで止める */
  interface LineSink {
    boolean accept(long start, byte[] line);
  }

  /**
   * ファイルを後ろから 1 行ずつ読む。end より前に始まる行から（end が行の途中なら、その行の終わりまでを 1 行とする）。
   *
   * @return 止めたときは、最後に渡した行の先頭の位置（続きはその位置より前から）。ファイルの頭まで読んだら -1
   */
  static long readBackward(Path f, long end, LineSink sink) throws IOException {
    try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
      long length = raf.length();
      long pos = Math.min(end, length);
      // end が行の途中なら、その行の終わりまで広げる
      if (pos > 0 && pos < length) {
        raf.seek(pos - 1);
        if (raf.read() != '\n') {
          raf.seek(pos);
          int b;
          while ((b = raf.read()) != -1 && b != '\n') {
            pos++;
          }
        }
      }
      byte[] carry = new byte[0];
      while (pos > 0) {
        int n = (int) Math.min(64 * 1024, pos);
        long from = pos - n;
        byte[] buf = new byte[n + carry.length];
        raf.seek(from);
        raf.readFully(buf, 0, n);
        System.arraycopy(carry, 0, buf, n, carry.length);
        int lineEnd = buf.length;
        for (int i = buf.length - 1; i >= 0; i--) {
          if (buf[i] == '\n') {
            if (i + 1 < lineEnd) {
              byte[] line = java.util.Arrays.copyOfRange(buf, i + 1, lineEnd);
              if (!sink.accept(from + i + 1, line)) {
                return from + i + 1;
              }
            }
            lineEnd = i;
          }
        }
        carry = java.util.Arrays.copyOfRange(buf, 0, lineEnd);
        pos = from;
      }
      if (carry.length > 0 && !sink.accept(0, carry)) {
        return 0;
      }
      return -1;
    }
  }
}
