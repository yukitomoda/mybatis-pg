package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectDataSourcesTest {

  @TempDir
  Path project;

  private void write(String name, String content) throws Exception {
    Path f = project.resolve("src/main/resources/" + name);
    Files.createDirectories(f.getParent());
    Files.write(f, content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void 基本の設定とプロファイルごとの設定を接続先にする() throws Exception {
    write("application.yml", "spring:\n  datasource:\n    url: jdbc:postgresql://10.0.0.1:5432/app\n    username: postgres\n    password: secret\n"
        + "---\nspring:\n  config:\n    activate:\n      on-profile: dev\n  datasource:\n    url: jdbc:postgresql://localhost/app_dev\n"
        + "---\nspring:\n  config:\n    activate:\n      on-profile: other\nserver:\n  port: 8081\n");
    write("application-prod.properties", "spring.datasource.url=jdbc:postgresql://prod:6543/app?sslmode=require\nspring.datasource.password=${DB_PASSWORD}\n");

    Map<String, String> env = new HashMap<>();
    List<ProjectDataSources.Source> s = ProjectDataSources.read(project, env, new Properties());
    assertEquals(3, s.size());
    assertEquals("application.yml", s.get(0).name);
    assertEquals("src/main/resources/application.yml", s.get(0).file);
    assertEquals("jdbc:postgresql://10.0.0.1:5432/app", s.get(0).url);
    assertEquals("secret", s.get(0).password);
    assertNull(s.get(0).error);
    // dev: url だけを変え、ユーザーとパスワードは基本の設定のもの（データソースを変えない other は出さない）
    assertEquals("application.yml（dev）", s.get(1).name);
    assertEquals("jdbc:postgresql://localhost/app_dev", s.get(1).url);
    assertEquals("postgres", s.get(1).user);
    // prod: 埋められない ${DB_PASSWORD}
    assertEquals("application.yml（prod）", s.get(2).name);
    assertEquals("src/main/resources/application-prod.properties", s.get(2).file);
    assertTrue(s.get(2).error.contains("DB_PASSWORD"), s.get(2).error);

    env.put("DB_PASSWORD", "from-env");
    ProjectDataSources.Source prod = ProjectDataSources.read(project, env, new Properties()).get(2);
    assertEquals("from-env", prod.password);
    assertNull(prod.error);
  }

  @Test
  void 値の埋め込み() {
    Map<String, String> env = new HashMap<>();
    env.put("SPRING_DATASOURCE_HOST", "db.example");
    Properties sys = new Properties();
    sys.setProperty("db.port", "6000");
    Map<String, String> config = new HashMap<>();
    config.put("app.db", "main");
    List<String> missing = new java.util.ArrayList<>();
    assertEquals("jdbc:postgresql://db.example:6000/main?x=1",
        ProjectDataSources.resolve("jdbc:postgresql://${spring.datasource.host}:${db.port}/${app.db}?x=${X:1}", config, env, sys, missing, 0));
    assertTrue(missing.isEmpty());
    ProjectDataSources.resolve("${NOPE}", config, env, sys, missing, 0);
    assertEquals(Collections.singletonList("NOPE"), missing);
  }

  @Test
  void 接続先の一覧に並べ_変えられないようにする() throws Exception {
    write("application.properties", "spring.datasource.url=jdbc:postgresql://h:15432/d?stringtype=unspecified\nspring.datasource.username=u\n");
    ConnectionStore store = ConnectionStore.inMemory();
    store.useProject(project);
    ConnectionStore.Profile p = store.list().get(0);
    assertEquals("project:default", p.id);
    assertEquals("application.properties", p.name);
    assertEquals("h", p.host);
    assertEquals(15432, p.port);
    assertEquals("d", p.database);
    assertEquals("stringtype=unspecified", p.params);
    assertEquals("src/main/resources/application.properties", p.source);
    assertThrows(IllegalArgumentException.class, () -> store.save(p, "x"));
    assertThrows(IllegalArgumentException.class, () -> store.delete(p.id));
    // PostgreSQL でない URL は使えない
    write("application.properties", "spring.datasource.url=jdbc:mysql://h/d\n");
    assertTrue(store.list().get(0).error.contains("PostgreSQL"));
    assertThrows(java.sql.SQLException.class, () -> store.open("project:default"));
  }
}
