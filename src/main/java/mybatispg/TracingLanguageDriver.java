package mybatispg;

import java.lang.reflect.Method;
import java.util.List;

import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.xmltags.MixedSqlNode;
import org.apache.ibatis.scripting.xmltags.SqlNode;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.scripting.xmltags.XMLScriptBuilder;
import org.apache.ibatis.session.Configuration;

/**
 * XMLLanguageDriver と同じ SqlSource を作りつつ、#{} を ? に置き換える前の SQL を
 * 後から取り出せるよう、ルートの SqlNode を一緒に保持する。
 */
public class TracingLanguageDriver extends XMLLanguageDriver {

  @Override
  public SqlSource createSqlSource(Configuration configuration, XNode script, Class<?> parameterType) {
    // 本番と同じ展開結果を得るため、SqlSource 自体は MyBatis 本体に作らせる
    SqlSource original = super.createSqlSource(configuration, script, parameterType);
    // RawSqlSource は元の SqlNode を保持しないため、同じ XNode をもう一度解析して取っておく
    SqlNode rootSqlNode = parseRootSqlNode(configuration, script, parameterType);
    return new TracingSqlSource(configuration, original, rootSqlNode, script.getNode());
  }

  /**
   * XMLScriptBuilder#parseDynamicTags はバージョンにより可視性と戻り値が異なる
   * （3.4.x: パッケージプライベートで List&lt;SqlNode&gt;、3.5.x: protected で MixedSqlNode）ため、リフレクションで呼ぶ。
   */
  @SuppressWarnings("unchecked")
  private static SqlNode parseRootSqlNode(Configuration configuration, XNode script, Class<?> parameterType) {
    XMLScriptBuilder builder = new XMLScriptBuilder(configuration, script, parameterType);
    try {
      Method m = XMLScriptBuilder.class.getDeclaredMethod("parseDynamicTags", XNode.class);
      m.setAccessible(true);
      Object result = m.invoke(builder, script);
      if (result instanceof SqlNode) {
        return (SqlNode) result;
      }
      return new MixedSqlNode((List<SqlNode>) result);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("XMLScriptBuilder#parseDynamicTags を呼び出せません", e);
    }
  }
}
