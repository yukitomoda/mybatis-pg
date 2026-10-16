package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TypedValuesTest {

  private static Object decode(String json) {
    return TypedValues.decode(Json.GSON.fromJson(json, Object.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void 型付きの値を日時と日付に直す() {
    Map<String, Object> m = (Map<String, Object>) decode("{\"from\":{\"$type\":\"timestamp\",\"value\":\"2026-01-03 23:35\"},"
        + "\"day\":{\"$type\":\"date\",\"value\":\"2026-01-03\"},\"list\":[{\"$type\":\"timestamp\",\"value\":\"2026-01-03T01:02:03.5\"}],"
        + "\"other\":{\"$type\":\"x\",\"value\":1,\"extra\":2},\"n\":null}");
    assertEquals(Timestamp.valueOf("2026-01-03 23:35:00"), m.get("from"));
    assertEquals(Date.valueOf("2026-01-03"), m.get("day"));
    assertEquals(Timestamp.valueOf("2026-01-03 01:02:03.5"), ((List<Object>) m.get("list")).get(0));
    // キーが 2 つでなければ型付きの値ではない（普通のオブジェクト）
    assertTrue(m.get("other") instanceof Map);
  }

  @Test
  void 誤り() {
    assertThrows(IllegalArgumentException.class, () -> decode("{\"a\":{\"$type\":\"timestamp\",\"value\":\"tomorrow\"}}"));
    assertThrows(IllegalArgumentException.class, () -> decode("{\"a\":{\"$type\":\"date\",\"value\":\"2026-01-03 10:00\"}}"));
    assertThrows(IllegalArgumentException.class, () -> decode("{\"a\":{\"$type\":\"time\",\"value\":\"2026-01-03\"}}"));
  }

  @Test
  void リテラルとPREPAREの型() {
    assertEquals("TIMESTAMP '2026-01-03 23:35:00'", SqlLiteral.of(Timestamp.valueOf("2026-01-03 23:35:00")));
    assertEquals("TIMESTAMP '2026-01-03 23:35:00.5'", SqlLiteral.of(Timestamp.valueOf("2026-01-03 23:35:00.5")));
    assertEquals("DATE '2026-01-03'", SqlLiteral.of(Date.valueOf("2026-01-03")));
    assertEquals("timestamp", QueryExecutor.pgType(Timestamp.valueOf("2026-01-03 23:35:00"), false));
    assertEquals("date", QueryExecutor.pgType(Date.valueOf("2026-01-03"), false));
  }
}
