package mybatispg;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mapper XML の文字の読み書き。文字コードは XML 宣言（encoding="..."）に従い、改行は元のファイルに合わせる。
 */
final class MapperText {

  private static final Pattern ENCODING = Pattern.compile("^\\s*<\\?xml[^>]*?\\bencoding\\s*=\\s*[\"']([A-Za-z0-9._:-]+)[\"']");

  private MapperText() {
  }

  /** ファイルを文字として読む */
  static String read(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    return new String(bytes, charsetOf(bytes));
  }

  /** XML の文字を、XML 宣言の文字コードでバイト列にする（宣言がなければ UTF-8） */
  static byte[] encode(String text) {
    return text.getBytes(declared(text));
  }

  /**
   * ファイルに書く。元のファイルが CRLF の改行なら CRLF にそろえる（エディタは LF にそろえるため）。
   * ファイルの権限や持ち主を変えないよう、置き換えずにその場で書き換える。
   */
  static void write(Path file, String text) throws IOException {
    String current = Files.exists(file) ? read(file) : "";
    if (current.contains("\r\n") && !text.contains("\r\n")) {
      text = text.replace("\n", "\r\n");
    }
    Files.write(file, encode(text));
  }

  /** バイト列の文字コード。BOM か XML 宣言から決め、分からなければ UTF-8 */
  static Charset charsetOf(byte[] bytes) {
    if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff
        || bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
      return StandardCharsets.UTF_16;
    }
    if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
      return StandardCharsets.UTF_8;
    }
    // 宣言は ASCII の範囲なので、1 バイトずつの文字として読んで探す
    return declared(new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.ISO_8859_1));
  }

  private static Charset declared(String text) {
    Matcher m = ENCODING.matcher(text.startsWith("\uFEFF") ? text.substring(1) : text);
    if (m.find()) {
      try {
        return Charset.forName(m.group(1));
      } catch (IllegalArgumentException e) {
        // 知らない文字コードは UTF-8 とする
      }
    }
    return StandardCharsets.UTF_8;
  }
}
