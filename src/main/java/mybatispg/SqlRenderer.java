package mybatispg;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.builder.xml.XMLStatementBuilder;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.xml.sax.SAXParseException;

/**
 * mapper XML を MyBatis 本体で読み込み、statement を展開して
 * 「? 付き SQL」と「値を埋め込んだ SQL」を作る。
 */
public class SqlRenderer {

  /** #{} の位置を示す目印。SQL 本文に現れない NUL 文字で囲む */
  private static final char MARKER = '\u0000';

  private final Configuration configuration;
  private final List<LoadError> loadErrors = new ArrayList<>();

  private SqlRenderer() {
    configuration = new Configuration();
    configuration.getLanguageRegistry().setDefaultDriverClass(TracingLanguageDriver.class);
  }

  /**
   * mapper XML を読み込む。読み込めないファイルや statement はエラーとして記録し、残りは使えるようにする。
   */
  public static SqlRenderer load(List<Path> mapperFiles) {
    return load(mapperFiles, Collections.<LoadError>emptyList());
  }

  /** @param initialErrors 読み込み前に起きたエラー（設定ファイルの読み込み失敗など） */
  public static SqlRenderer load(List<Path> mapperFiles, List<LoadError> initialErrors) {
    SqlRenderer renderer = new SqlRenderer();
    renderer.loadErrors.addAll(initialErrors);
    for (Path file : mapperFiles) {
      renderer.loadFile(file);
    }
    renderer.resolveIncompleteStatements();
    return renderer;
  }

  /**
   * 1 つのファイルを、保存していない内容（下書き）で読み込み直したものを作る。この renderer は変えない。
   * 作ったものには、そのファイルの statement だけが入る。include 先の &lt;sql&gt; は、ほかのファイルの読み込み済みのものを使う
   * （このファイルの名前空間のものは、下書きの内容を使う）。読み込みのエラーは getLoadErrors() に入る。
   */
  public SqlRenderer withDraft(Path file, String content) {
    String resource = file.toString();
    Set<String> namespaces = new HashSet<>();
    for (String id : statementIds()) {
      if (resource.equals(statementSource(id)) && id.lastIndexOf('.') > 0) {
        namespaces.add(id.substring(0, id.lastIndexOf('.')));
      }
    }
    SqlRenderer draft = new SqlRenderer();
    Map<String, XNode> fragments = draft.configuration.getSqlFragments();
    for (Map.Entry<String, XNode> e : configuration.getSqlFragments().entrySet()) {
      String key = e.getKey();
      int dot = key.lastIndexOf('.');
      // 短縮名は完全名を入れたときに作られる。このファイルの名前空間のものは下書きから読む
      if (dot > 0 && !namespaces.contains(key.substring(0, dot)) && !fragments.containsKey(key)) {
        fragments.put(key, e.getValue());
      }
    }
    draft.loadFile(file, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    draft.resolveIncompleteStatements();
    return draft;
  }

  private void loadFile(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      loadFile(file, in);
    } catch (java.io.IOException e) {
      loadErrors.add(new LoadError(file.toString(), null, null, Errors.describe(e)));
    }
  }

  private void loadFile(Path file, InputStream in) {
    try {
      byte[] xml = MapperPreprocessor.preprocess(in, file.toUri().toString());
      new XMLMapperBuilder(new ByteArrayInputStream(xml), configuration, file.toString(),
          configuration.getSqlFragments()).parse();
    } catch (SAXParseException e) {
      loadErrors.add(new LoadError(file.toString(), e.getLineNumber(), e.getColumnNumber(), e.getMessage()));
    } catch (Exception e) {
      loadErrors.add(new LoadError(file.toString(), null, null, Errors.describe(e)));
    }
  }

  /**
   * include 先が後のファイルにある statement は「未完成」として保留されている。
   * 全ファイルを読み込んだ後に 1 つずつ組み立て、失敗したものはエラーとして外す。
   * （MyBatis 既定の動作では、未完成の statement が 1 つでも残ると全 statement の取得が失敗する）
   */
  private void resolveIncompleteStatements() {
    Collection<XMLStatementBuilder> incomplete = configuration.getIncompleteStatements();
    synchronized (incomplete) {
      Iterator<XMLStatementBuilder> it = incomplete.iterator();
      while (it.hasNext()) {
        XMLStatementBuilder builder = it.next();
        try {
          builder.parseStatementNode();
        } catch (Exception e) {
          loadErrors.add(new LoadError(statementLabel(builder), null, null, Errors.describe(e)));
        }
        it.remove();
      }
    }
  }

  /** エラー表示用に、未完成の statement の id を取り出す */
  private static String statementLabel(XMLStatementBuilder builder) {
    try {
      Field f = XMLStatementBuilder.class.getDeclaredField("context");
      f.setAccessible(true);
      XNode context = (XNode) f.get(builder);
      XNode mapper = context.getParent();
      String namespace = mapper == null ? null : mapper.getStringAttribute("namespace");
      return "statement " + (namespace == null ? "" : namespace + ".") + context.getStringAttribute("id");
    } catch (ReflectiveOperationException | RuntimeException e) {
      return "statement（id 不明）";
    }
  }

  public List<LoadError> getLoadErrors() {
    return Collections.unmodifiableList(loadErrors);
  }

  public boolean hasStatement(String statementId) {
    return configuration.hasStatement(statementId, false);
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

  /** statement の種類（SELECT・INSERT・UPDATE・DELETE など） */
  public String statementType(String statementId) {
    return configuration.getMappedStatement(statementId, false).getSqlCommandType().name();
  }

  /** statement を定義したファイルのパス（読み込み時に resource として渡したもの） */
  public String statementSource(String statementId) {
    return configuration.getMappedStatement(statementId, false).getResource();
  }

  /** statement が参照しているパラメータを抜き出す */
  public ParameterExtractor.Result parameters(String statementId) {
    return ParameterExtractor.extract(tracingSqlSource(statementId).getScript());
  }

  private TracingSqlSource tracingSqlSource(String statementId) {
    SqlSource sqlSource = configuration.getMappedStatement(statementId).getSqlSource();
    if (!(sqlSource instanceof TracingSqlSource)) {
      throw new IllegalStateException("lang 属性などで別の LanguageDriver が使われています: " + statementId);
    }
    return (TracingSqlSource) sqlSource;
  }

  public Result render(String statementId, Object parameterObject) {
    MappedStatement ms = configuration.getMappedStatement(statementId);
    TracingSqlSource sqlSource = tracingSqlSource(statementId);

    // 1. 本番と同じ経路で ? 付き SQL とパラメータ対応を得る
    BoundSql boundSql = ms.getBoundSql(parameterObject);
    List<ParameterMapping> mappings = boundSql.getParameterMappings();

    // 2. #{} が残った段階の SQL を取り出し、#{} を ? ではなく目印に置き換える
    String beforeMapping = sqlSource.getSqlBeforeParameterMapping(parameterObject);
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
    StringBuilder numbered = new StringBuilder();
    int pos = 0;
    for (int i = 0; i < mappings.size(); i++) {
      ParameterMapping pm = mappings.get(i);
      String marker = MARKER + String.valueOf(i) + MARKER;
      int at = markedSql.indexOf(marker, pos);
      Object value = resolveValue(pm, boundSql, parameterObject);
      bindings.add(new Binding(i + 1, pm.getProperty(), value));
      literal.append(markedSql, pos, at).append(SqlLiteral.of(value));
      numbered.append(markedSql, pos, at).append('$').append(i + 1);
      pos = at + marker.length();
    }
    literal.append(markedSql.substring(pos));
    numbered.append(markedSql.substring(pos));

    return new Result(boundSql.getSql(), bindings, literal.toString(), numbered.toString());
  }

  /**
   * 実行用に、本番と同じ ? 付き SQL を作る。値は {@link Bound#setParameters} で MyBatis の TypeHandler を通して設定する。
   */
  public Bound bind(String statementId, Object parameterObject) {
    MappedStatement ms = configuration.getMappedStatement(statementId);
    if (ms.getStatementType() == StatementType.CALLABLE) {
      throw new IllegalStateException("statementType=\"CALLABLE\" の statement は実行できません");
    }
    List<String> warnings = new ArrayList<>();
    if (ms.getKeyGenerator() instanceof SelectKeyGenerator) {
      warnings.add("<selectKey> は実行しません（statement 本体だけを実行します）");
    }
    return new Bound(ms, parameterObject, ms.getBoundSql(parameterObject), warnings);
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

  /** 実行用に組み立てた statement */
  public static class Bound {
    private final MappedStatement ms;
    private final Object parameterObject;
    private final BoundSql boundSql;
    public final List<String> warnings;

    Bound(MappedStatement ms, Object parameterObject, BoundSql boundSql, List<String> warnings) {
      this.ms = ms;
      this.parameterObject = parameterObject;
      this.boundSql = boundSql;
      this.warnings = Collections.unmodifiableList(warnings);
    }

    public String sql() {
      return boundSql.getSql();
    }

    /** 本番の DefaultParameterHandler で値を設定する（null は jdbcTypeForNull の既定値 OTHER で渡る） */
    public void setParameters(PreparedStatement ps) throws SQLException {
      new DefaultParameterHandler(ms, parameterObject, boundSql).setParameters(ps);
    }
  }

  public static class Result {
    public final String placeholderSql;
    public final List<Binding> bindings;
    public final String literalSql;
    /** パラメータを $1, $2, ... にした SQL（PREPARE 用） */
    public final String numberedSql;

    Result(String placeholderSql, List<Binding> bindings, String literalSql, String numberedSql) {
      this.placeholderSql = placeholderSql;
      this.bindings = bindings;
      this.literalSql = literalSql;
      this.numberedSql = numberedSql;
    }
  }
}
