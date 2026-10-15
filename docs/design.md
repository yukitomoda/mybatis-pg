# MyBatis SQLレンダリングツール 設計まとめ

## 目的

MyBatis XMLを直接編集し、構造化したパラメータを与えて、A5M2にそのまま貼れるSQLを得るツールを作る。

- 現状: プレーンSQLを書く → A5M2で試す → MyBatis XMLに手作業で変換する。この変換の手間が大きい
- 狙い: XMLを正として編集し、展開済みSQLをA5M2で確認する流れに置き換える

## 対象環境

| 項目 | バージョン |
| --- | --- |
| mybatis-spring-boot-starter | 1.3.1 |
| MyBatis | 3.4.5（starter 1.3.1の前提） |
| Spring Boot | 1.5.6（starter 1.3.1の前提） |
| JDK | 1.8 |
| DB | PostgreSQL |

starterとMyBatis・Spring Bootの対応は[MyBatis公式ブログ](https://blog.mybatis.org/2017/08/mybatis-spring-boot-starter-122-131.html)による。

## 既存ツールの調査結果

要件を満たす既存ツールは見つからなかったため、自作する。

| ツール | 評価 |
| --- | --- |
| [mybatis-sql-viewer](https://github.com/linyimin0812/mybatis-sql-viewer)（IntelliJプラグイン） | 最も近い。引数のmock、実SQLへの展開、EXPLAIN付き実行ができる。ただし実質MySQL前提で、式評価が独自実装と思われ、本家と展開結果が食い違うリスクがある |
| MybatisX | この用途の機能は確認できない |
| MyBatis Code Helper Pro（有料） | この用途の機能は確証がない |
| MyBatis Log系プラグイン | 実行ログからSQLを復元するだけで、アプリの起動が必要 |

## 方針

展開はMyBatis本体に任せ、ローカルWebのGUIで操作する。Java型なしで動き、SQL実行はMVPに含めない。

- **展開処理**: MyBatis本体の `XMLMapperBuilder` → `getBoundSql()` をそのまま使い、本番と同じ展開結果を得る。MyBatisは3.4.5に固定し、ビルド時に `-Dmybatis.version` で差し替え可能にする
- **値の埋め込み**（実装済み）: `?` 付きSQLを後から文字列置換すると、文字列・コメント・演算子（`?|` など）中の `?` まで置き換えてしまう。そこで次のようにする
  - `LanguageDriver` を差し替え、SqlSourceは本体に作らせつつ、元の `SqlNode` を保持する
  - `#{}` が残った段階のSQLを取り出し、`#{}` を番号付きの目印に置き換える
  - 目印を `?` に戻したものがMyBatisの `?` 付きSQLと完全一致することを検証してから、目印をリテラルに置き換える
  - 値は `DefaultParameterHandler` と同じ手順で解決する
- **XMLの編集**: IDEで本物のファイルを編集し、ツールは `WatchService` で変更を監視する。`<include>` は別ファイルを参照できるため、変更があれば全mapperを読み込み直す
- **GUI**: ローカルWeb（JVMサーバ + ブラウザ）
  - ツール自体もJava 8で動かす
  - サーバはJDK標準の `com.sun.net.httpserver` かJavalin 4系。Spring Bootは使わない
  - フロントはReact + Vite。ビルド結果をjarに同梱する（社内ネットワークでCDNが使えない場合に備える）
  - XMLはブラウザで編集しないため、エディタ部品はSQLの表示とJSONの表示に使う程度。軽いCodeMirror 6で足りる見込み
  - 必須のセキュリティ対策: `127.0.0.1` のみにbind、Hostヘッダ検証、起動時トークンの検証、CORS不許可
  - ネイティブ（JavaFX等）やElectron / Tauriは採らない
- **型**: Java型なしで動くことを必須とする
  - パラメータは `Map` / `List` / プリミティブで渡す
  - クラス参照は読み込み前の前処理で取り除く（実装済み。展開結果には影響しない）
    - `resultMap` / `cache` / `cache-ref` 要素を削除
    - statementと `selectKey` の `parameterType` / `resultType` / `resultMap` 属性を削除
    - `parameterMap` の `type` を `map` にし、`parameter` の `javaType` / `typeHandler` を削除
    - SQL本文の `#{}` から `javaType` / `typeHandler` オプションを削除
  - 将来モデルクラスを作ったら、クラスパス指定で本来の型を使えるようにする
- **`databaseId`**: 使っていないため対応しない。`databaseId` 付きのstatementは読み込まれない
- **SQL実行**: MVPの後に追加した（次節）

## SQL実行

画面で接続先を選び、statementをPostgreSQLで実行する。

- **実行方法**: 本番と同じく、MyBatisが作る `?` 付きSQLに、`DefaultParameterHandler`（TypeHandler）で値を設定して実行する。値を埋め込んだSQLは実行しない
- **トランザクション**: 自動コミットを切り、常にトランザクションの中で実行する
  - 既定ではロールバックする
  - 「コミットする」を選んだときだけ、確認のうえコミットする。実行後は既定（ロールバック）に戻す
  - エラーになったら必ずロールバックする
- **接続先**: 名前を付けて複数登録し、画面で切り替える
  - `~/.mybatis-xml-editor/connections.json` に保存する（自分だけが読める権限）
  - パスワードはファイルに保存せず、ツールの起動中だけメモリに持つ。再起動したら画面で入力し直す
  - JDBCドライバ（pgjdbc）はjarに同梱する
- **結果**: 値はJDBCの `getString` の文字列で表示する（DBの表記のまま）。行数は先頭1000行まで。更新件数と実行時間も表示する
- **実行しないもの**: `<selectKey>`（本体だけを実行し、警告を出す）。`statementType="CALLABLE"` は実行できない

## パラメータ入力UI

入力はJSONを直接書く形にしない。JSONは、構造化した値をMyBatisに渡すための内部形式と保存形式として使う。

画面はツリー形式とし、statementのXMLから抜き出した項目を最初から並べておく。

```
(パラメータ)                               [＋項目]
  ├ cond          [オブジェクト ▼]          [＋項目] [×]
  │   ├ name      [文字列 ▼] [taro      ]            [×]
  │   └ status    [NULL   ▼]                        [×]
  ├ ids           [リスト ▼]                [＋要素] [×]
  │   ├ [0]       [数値 ▼]   [1         ]            [×]
  │   └ [1]       [数値 ▼]   [2         ]            [×]
  ├ deleted       [真偽値 ▼] [☐]                    [×]
  └ orderBy       [文字列 ▼] [id DESC   ]            [×]   ※ ${} なのでSQLにそのまま埋め込まれる
```

- **項目の抽出元**: `#{...}`、`${...}`、`<if test>` / `<when test>` の式、`<foreach collection>`、`<bind value>`
  - 式はMyBatis同梱のOGNLパーサで解析し、`cond.name` のようなプロパティ参照をたどる
  - `<foreach>` の `item` や `<bind>` の名前はローカル変数なので、元の項目に読み替える（例: `users` の各要素の `name`）
- **型の選択**: 文字列・数値・真偽値・NULL・リスト・オブジェクトから選ぶ。リテラルの出力（`1` と `'1'`）や `<if test="status == 1">` の判定が型で変わるため。`jdbcType` や式中の比較から推測できる場合は初期値にする
- **手での追加**: 解析で拾えなかった項目は「＋項目」で足せる
- **参照されていない項目**: XML側で参照されていない項目（打ち間違いや削除済み）はグレー表示などで区別する
- **JSON**: statementごとにJSONで保存する。画面にもJSONを表示し、貼り付けやまとめての書き換えに使えるようにする

## MVPの範囲

主な成果物は、値を埋め込んだSQL（A5M2にそのまま貼れるもの）。

1. mapper XMLを読み込み、statement idの一覧を表示する（`application.yml` の `mybatis.mapper-locations` から自動で探せると良い）
2. statementを選び、パラメータを入力する（上記の入力UI）
3. 入力やXMLが変わるたびに再レンダリングし、以下を表示する
   - 値を埋め込んだSQL（コピーボタン付き）。文字列は `''` でエスケープし、数値・`NULL`・真偽値（`TRUE` / `FALSE`）をPostgreSQLの書式で出力する。元の `#{name}` と値の対応を併記する
   - `?` 付きSQLとバインド値の一覧
   - エラー（XMLの構文エラー、OGNLの評価エラー、パラメータの不足）
4. statementごとのパラメータを保存し、次回も使えるようにする

## 既知の制約

- **Map方式でのずれ**: 本番では失敗する書き方が、ツールでは通ってしまう場合がある。将来、mapperインターフェースのシグネチャで検証して補う
  - 単一引数で `@Param` がない場合（本来は `_parameter` で参照する）
  - 単一の `List` 引数の場合（本来は `list` / `collection` で参照する）
- **リテラルとバインドの差**: 暗黙の型変換やインデックスの効き方が稀に変わる
- **日付**: パラメータに日付型はないので文字列で渡す。値を埋め込んだSQLではリテラル `'2026-10-01'` が日付に暗黙変換されて通るが、本番では文字列型（varchar）としてバインドされるため、`date_col = ?` が型の不一致でエラーになる場合がある（接続設定 `stringtype=unspecified` を使っていない場合）。つまりツールで通っても本番で失敗しうる
- **typeHandler**: 前処理で取り除くため、本番で値を変換するtypeHandler（例: enumをコード値に変える）を使っていても、入力した値がそのまま埋め込まれる。入力にはDBに入る形の値を書く
- **`lang` 属性**: 別のLanguageDriverを指定したstatementはレンダリングできない。クラスを参照している場合、そのmapperは読み込めない
- **Javaのメソッド呼び出し**: `<if test="@com.example.Util@isEmpty(x)">` のような式は、クラスがないため評価がエラーになる
- **アノテーション**: `@Select` などのアノテーションで書いたSQLは対象外
- **`?` 演算子**: pgjdbcは `#{}` 以外の `?`（`?|` などのjsonb演算子）もパラメータとみなすため、実行がエラーになる（本番でも同じ）。JDBCでは `??` と書く必要があるが、その場合は値を埋め込んだSQLにも `??` が残るため、A5M2では手で直す必要がある
- **`<selectKey>`**: statement一覧に `insert!selectKey` のような別項目として現れる。必要なら画面で隠す
