package mybatisrender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ParameterExtractorTest {

  private static SqlRenderer renderer;

  @BeforeAll
  static void setUp() throws Exception {
    Path res = Paths.get("src/test/resources");
    renderer = SqlRenderer.load(Arrays.asList(res.resolve("mappers/UserMapper.xml"), res.resolve("mappers/Common.xml"),
        res.resolve("params/ParamMapper.xml")));
    assertEquals(java.util.Collections.emptyList(), renderer.getLoadErrors());
  }

  @Test
  void 静的SQLのバインド変数とjdbcTypeからの型推測() {
    assertEquals(Arrays.asList("id BIND", "name BIND STRING"), describe("sample.UserMapper.findStatic"));
  }

  @Test
  void bindとforeachのローカル変数を元の項目に読み替える() {
    // namePattern（bind）と id（foreach の item）はローカル変数なので項目にならない
    assertEquals(Arrays.asList(
        "cond",
        "cond.name CONDITION",
        "cond.status BIND CONDITION",
        "ids CONDITION ITERATE",
        "ids[] BIND",
        "deleted BIND",
        "orderBy RAW"), describe("sample.UserMapper.search"));
  }

  @Test
  void foreachの要素がオブジェクトの場合() {
    assertEquals(Arrays.asList("users ITERATE", "users[]", "users[].id BIND", "users[].name BIND"),
        describe("sample.UserMapper.bulkInsert"));
  }

  @Test
  void 定数との比較から型を推測し特殊な名前を扱う() {
    assertEquals(Arrays.asList(
        "status BIND CONDITION NUMBER",
        "type BIND CONDITION STRING",
        "label BIND CONDITION STRING",
        "flag CONDITION BOOLEAN",
        "code BIND CONDITION",
        "memo CONDITION",
        "opts",
        "opts.mode BIND CONDITION",
        "limit BIND NUMBER"), describe("sample.ParamMapper.hints"));
  }

  @Test
  void 入れ子のforeachと添字付きのプロパティ() {
    // index（i）はローカル変数なので項目にならない
    assertEquals(Arrays.asList(
        "rows",
        "rows[]",
        "rows[].id BIND",
        "groups ITERATE",
        "groups[]",
        "groups[].members ITERATE",
        "groups[].members[]",
        "groups[].members[].name BIND"), describe("sample.ParamMapper.nested"));
  }

  @Test
  void 解析できない式は警告にして続行する() {
    ParameterExtractor.Result r = renderer.parameters("sample.ParamMapper.broken");
    assertEquals(Collections.singletonList("b BIND"), r.root.describe());
    assertEquals(1, r.warnings.size());
    assertTrue(r.warnings.get(0).startsWith("式を解析できません: a =="), r.warnings.get(0));
  }

  private static java.util.List<String> describe(String id) {
    ParameterExtractor.Result r = renderer.parameters(id);
    assertEquals(Collections.emptyList(), r.warnings);
    return r.root.describe();
  }
}
