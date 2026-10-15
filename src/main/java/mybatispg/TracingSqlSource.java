package mybatispg;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.DynamicContext;
import org.apache.ibatis.scripting.xmltags.SqlNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;

/**
 * MyBatis 本体の SqlSource に処理を委ねつつ、#{} が残った段階の SQL も返せる SqlSource。
 */
public class TracingSqlSource implements SqlSource {

  private final Configuration configuration;
  private final SqlSource original;
  private final SqlNode rootSqlNode;
  private final Node script;

  public TracingSqlSource(Configuration configuration, SqlSource original, SqlNode rootSqlNode, Node script) {
    this.configuration = configuration;
    this.original = original;
    this.rootSqlNode = rootSqlNode;
    this.script = script;
  }

  /** statement の XML 要素（include 解決後、selectKey 除去後） */
  public Node getScript() {
    return script;
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    return original.getBoundSql(parameterObject);
  }

  /**
   * 動的タグと ${} を評価し終え、#{} だけが残った SQL を返す。
   * DynamicSqlSource#getBoundSql が SqlSourceBuilder に渡す文字列と同じもの。
   */
  public String getSqlBeforeParameterMapping(Object parameterObject) {
    DynamicContext context = new DynamicContext(configuration, parameterObject);
    rootSqlNode.apply(context);
    return context.getSql();
  }
}
