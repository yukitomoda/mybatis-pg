package mybatisrender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class ParameterNamesTest {

  @Test
  void 使っているstatementの多い順に数える() {
    SqlRenderer renderer = SqlRenderer.load(Collections.singletonList(Paths.get("src/test/resources/names/NameMapper.xml")));
    List<ParameterNames.Entry> entries = ParameterNames.collect(renderer);
    List<String> summary = new ArrayList<>();
    for (ParameterNames.Entry e : entries) {
      summary.add(e.path + "=" + e.count);
    }
    assertEquals(java.util.Arrays.asList("tenantId=3", "cond=2", "cond.flag=1", "cond.status=1", "ids=1"), summary);

    ParameterNames.Entry cond = entries.get(1);
    assertTrue(cond.object);
    ParameterNames.Entry ids = entries.get(4);
    assertTrue(ids.list);
    assertTrue(ids.usages.contains(ParamNode.Usage.ITERATE));
  }
}
