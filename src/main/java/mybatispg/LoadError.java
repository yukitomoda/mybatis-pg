package mybatispg;

/** mapper XML の読み込みエラー。行番号は XML の構文エラーの場合だけ分かる */
public final class LoadError {
  public final String source;
  public final Integer line;
  public final Integer column;
  public final String message;

  public LoadError(String source, Integer line, Integer column, String message) {
    this.source = source;
    this.line = line;
    this.column = column;
    this.message = message;
  }

  @Override
  public String toString() {
    return source + (line == null ? "" : ":" + line + ":" + column) + ": " + message;
  }
}
