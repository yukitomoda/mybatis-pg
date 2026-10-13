package mybatisrender;

import java.io.IOException;
import java.math.BigDecimal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberStrategy;
import com.google.gson.stream.JsonReader;

/** API で使う JSON の変換 */
final class Json {

  /**
   * JSON の数値を、整数は Long（収まらなければ BigDecimal）、小数は BigDecimal にする。
   * Gson 既定の Double では 1 が 1.0 になり、0.1 のような値も正確に表せないため。
   */
  private static final ToNumberStrategy NUMBER_STRATEGY = new ToNumberStrategy() {
    @Override
    public Number readNumber(JsonReader in) throws IOException {
      String s = in.nextString();
      if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
        try {
          return Long.parseLong(s);
        } catch (NumberFormatException e) {
          // long に収まらない整数
        }
      }
      return new BigDecimal(s);
    }
  };

  static final Gson GSON = new GsonBuilder()
      .setObjectToNumberStrategy(NUMBER_STRATEGY)
      .serializeNulls()
      .disableHtmlEscaping()
      .create();

  private Json() {
  }
}
