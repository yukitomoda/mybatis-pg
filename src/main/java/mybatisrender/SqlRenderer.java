package mybatisrender;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;

/**
 * mapper XML を MyBatis 本体で読み込み、statement を展開して
 * 「? 付き SQL」と「値を埋め込んだ SQL」を作る。
 */
public class SqlRenderer {

  /** #{} の位置を示す目印。SQL 本文に現れない NUL 文字で囲む */
  private static final char MARKER = '\u0000';

  private final Configuration configuration;

  public SqlRenderer(List<Path> mapperFiles) throws IOException {
    configuration = new Configuration();
    configuration.getLanguageRegistry().setDefaultDriverClass(TracingLanguageDriver.class);
    for (Path file : mapperFiles) {
      try (InputStream in = Files.newInputStream(file)) {
        new XMLMapperBuilder(in, configuration, file.toString(), configuration.getSqlFragments()).parse();
      }
    }
  }

  public List<String> statementIds() {
    List<String> ids = new ArrayList<>();
    for (Object o : configuration.getMappedStatements()) {
      // getMappedStatements() は短縮名と完全名の両方を含むため、完全名だけを拾う
      if (o instanceof MappedStatement) {
        String id = ((MappedStatement) o).getId();
        if (!ids.contains(id)) {
          ids.add(id);
        }
      }
    }
    Collections.sort(ids);
    return ids;
  }

  public Result render(String statementId, Object parameterObject) {
    MappedStatement ms = configuration.getMappedStatement(statementId);
    SqlSource sqlSource = ms.getSqlSource();
    if (!(sqlSource instanceof TracingSqlSource)) {
      throw new IllegalStateException("lang 属性などで別の LanguageDriver が使われています: " + statementId);
    }

    // 1. 本番と同じ経路で ? 付き SQL とパラメータ対応を得る
    BoundSql boundSql = ms.getBoundSql(parameterObject);
    List<ParameterMapping> mappings = boundSql.getParameterMappings();

    // 2. #{} が残った段階の SQL を取り出し、#{} を ? ではなく目印に置き換える
    String beforeMapping = ((TracingSqlSource) sqlSource).getSqlBeforeParameterMapping(parameterObject);
    if (beforeMapping.indexOf(MARKER) >= 0) {
      throw new IllegalStateException("SQL に NUL 文字が含まれています");
    }
    final int[] counter = {0};
    String markedSql = new GenericTokenParser("#{", "}", new TokenHandler() {
      @Override
      public String handleToken(String content) {
        return MARKER + String.valueOf(counter[0]++) + MARKER;
      }
    }).parse(beforeMapping);

    // 3. 目印を ? に戻したものが MyBatis の結果と一致することを確かめる（位置の対応が正しいことの保証）
    String markedAsPlaceholder = markedSql.replaceAll(MARKER + "\\d+" + MARKER, "?");
    if (counter[0] != mappings.size() || !markedAsPlaceholder.equals(boundSql.getSql())) {
      throw new IllegalStateException("MyBatis の展開結果と目印の位置が一致しません\n"
          + "MyBatis: " + boundSql.getSql() + "\n目印版: " + markedAsPlaceholder);
    }

    // 4. 値を解決し、目印をリテラルに置き換える
    List<Binding> bindings = new ArrayList<>();
    StringBuilder literal = new StringBuilder();
    int pos = 0;
    for (int i = 0; i < mappings.size(); i++) {
      ParameterMapping pm = mappings.get(i);
      String marker = MARKER + String.valueOf(i) + MARKER;
      int at = markedSql.indexOf(marker, pos);
      Object value = resolveValue(pm, boundSql, parameterObject);
      bindings.add(new Binding(i + 1, pm.getProperty(), value));
      literal.append(markedSql, pos, at).append(SqlLiteral.of(value));
      pos = at + marker.length();
    }
    literal.append(markedSql.substring(pos));

    return new Result(boundSql.getSql(), bindings, literal.toString());
  }

  /** DefaultParameterHandler#setParameters と同じ手順で値を解決する */
  private Object resolveValue(ParameterMapping pm, BoundSql boundSql, Object parameterObject) {
    if (pm.getMode() == ParameterMode.OUT) {
      return null;
    }
    String propertyName = pm.getProperty();
    if (boundSql.hasAdditionalParameter(propertyName)) {
      return boundSql.getAdditionalParameter(propertyName);
    } else if (parameterObject == null) {
      return null;
    } else if (configuration.getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass())) {
      return parameterObject;
    } else {
      MetaObject metaObject = configuration.newMetaObject(parameterObject);
      return metaObject.getValue(propertyName);
    }
  }

  public static class Binding {
    public final int index;
    public final String property;
    public final Object value;

    Binding(int index, String property, Object value) {
      this.index = index;
      this.property = property;
      this.value = value;
    }

    @Override
    public String toString() {
      return index + ": " + property + " = " + value;
    }
  }

  public static class Result {
    public final String placeholderSql;
    public final List<Binding> bindings;
    public final String literalSql;

    Result(String placeholderSql, List<Binding> bindings, String literalSql) {
      this.placeholderSql = placeholderSql;
      this.bindings = bindings;
      this.literalSql = literalSql;
    }
  }
}
