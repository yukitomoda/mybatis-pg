package mybatispg;

import java.util.ArrayList;
import java.util.List;

final class Errors {

  private Errors() {
  }

  /**
   * 例外の原因をたどって、重複しないメッセージをつなげる。
   * MyBatis の例外は外側のメッセージに原因のメッセージを含めることが多いため、含まれているものは省く。
   */
  static String describe(Throwable e) {
    List<String> messages = new ArrayList<>();
    for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
      String m = t.getMessage() == null ? t.getClass().getName() : t.getMessage().trim();
      boolean duplicated = false;
      for (String prev : messages) {
        if (prev.contains(m)) {
          duplicated = true;
          break;
        }
      }
      if (!duplicated) {
        messages.add(m);
      }
    }
    return String.join("\n", messages);
  }
}
