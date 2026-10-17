// テンプレートのタブ: statement を含む mapper XML ファイル全体を編集する。編集した内容は下書きとして保存し、元のファイルは変えない
import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react';
import type { Api, OpenTarget } from './api';
import type { Drafts } from './drafts';
import { statementLine } from './drafts';
import type { EditorError } from './XmlEditor';

// エディタ（CodeMirror）は大きいので、テンプレートのタブを開いたときに読み込む
const XmlEditor = lazy(() => import('./XmlEditor').then((m) => ({ default: m.XmlEditor })));

/** 入力が止まってから下書きに反映するまでの時間 */
const DRAFT_DELAY = 300;

export function FileEditor({
  api,
  statementId,
  file,
  drafts,
  generation,
  onApiError,
  opener,
  visible,
}: {
  api: Api;
  statementId: string;
  /** statement を定義したファイル（画面のパス） */
  file: string;
  drafts: Drafts;
  generation: number;
  onApiError: (e: unknown) => void;
  opener: { label: string; open: (target: OpenTarget) => void } | null;
  /** タブが表示されている */
  visible: boolean;
}) {
  /** 保存されているファイルの内容 */
  const [saved, setSaved] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const draft = drafts.get(file);
  const [text, setText] = useState<string | null>(null);
  const [errors, setErrors] = useState<EditorError[]>([]);
  const [missing, setMissing] = useState(false);
  const timer = useRef<number | undefined>(undefined);

  // ファイルが変わったら（読み込み直しを含む）取り直す
  useEffect(() => {
    if (generation === 0) return;
    let active = true;
    api.file(file).then(
      (r) => {
        if (!active) return;
        setSaved(r.content);
        setLoadError(null);
      },
      (e) => {
        if (!active) return;
        onApiError(e);
        setLoadError(String(e.message ?? e));
      },
    );
    return () => {
      active = false;
    };
  }, [api, file, generation]);

  // 別のファイルを開いたら、編集中の文字を入れ替える
  useEffect(() => {
    setText(null);
    setErrors([]);
    setMissing(false);
  }, [file]);
  const content = text ?? draft?.content ?? saved;

  /** まだ下書きに入れていない入力 */
  const pending = useRef<(() => void) | null>(null);
  const flush = () => {
    window.clearTimeout(timer.current);
    pending.current?.();
    pending.current = null;
  };
  const change = (next: string) => {
    setText(next);
    window.clearTimeout(timer.current);
    pending.current = () => {
      if (saved !== null) drafts.set(file, draft?.base ?? saved, next);
    };
    timer.current = window.setTimeout(flush, DRAFT_DELAY);
  };
  // 別のファイルに移るときや閉じるときは、入力を残さず下書きに入れる
  useEffect(() => flush, [file]);

  // 下書きを読み込んでみて、エラーを出す
  useEffect(() => {
    if (!draft) {
      setErrors([]);
      setMissing(false);
      return;
    }
    const abort = new AbortController();
    api.checkDraft(file, draft.content, abort.signal).then(
      (r) => {
        setErrors(r.errors.map((e) => ({ line: e.line, message: e.message })));
        setMissing(!r.statements.includes(statementId));
      },
      (e) => {
        if (abort.signal.aborted) return;
        onApiError(e);
      },
    );
    return () => abort.abort();
  }, [api, file, draft?.content, statementId]);

  // statement を選んだら、その行へ移る（編集のたびには移らない）
  const focusLine = useMemo(() => (content === null ? null : statementLine(content, statementId)), [statementId, content === null]);
  /** エラーの行へ移る（押すたびに n を増やして、同じ行でも移る） */
  const [jump, setJump] = useState({ line: 0, n: 0 });
  // 別の statement を選んだら、その statement の行へ移る
  useEffect(() => setJump({ line: 0, n: 0 }), [statementId]);
  const focus = !visible
    ? null
    : jump.n > 0
      ? { key: `${statementId}#${jump.n}`, line: jump.line }
      : focusLine !== null
        ? { key: statementId, line: focusLine }
        : null;

  if (loadError) return <pre className="render-error">{loadError}</pre>;
  if (content === null) return null;
  const stale = draft && saved !== null && draft.base !== saved;
  return (
    <div className="file-editor">
      <div className="sql-title">
        <code className="template-file" title={file}>
          {file}
        </code>
        {draft && (
          <span className="badge warn" title="ファイルには保存していません">
            編集中
          </span>
        )}
        <span className="actions">
          {/* 編集の有無で行の高さが変わらないよう、ボタンは常に出す */}
          <button
            type="button"
            disabled={!draft}
            onClick={() => {
              if (!window.confirm(`${file} の編集を取り消して、保存されている内容に戻しますか？`)) return;
              window.clearTimeout(timer.current);
              pending.current = null;
              setText(null);
              drafts.discard(file);
            }}
          >
            編集を取り消す
          </button>
          {opener && (
            <button type="button" onClick={() => opener.open({ statementId })}>
              {opener.label}
            </button>
          )}
        </span>
      </div>
      {stale && <p className="notes warn">編集を始めた後に、ファイルが変更されました。編集中の内容は変更前のファイルをもとにしています。</p>}
      <Suspense fallback={<p className="hint">エディタを読み込んでいます…</p>}>
        <XmlEditor key={file} value={content} onChange={change} errors={errors} focus={focus} label={`${file} の編集`} />
      </Suspense>
      {/* エラーは高さの変わらない 1 行に出す（出たり消えたりしてもエディタが動かないように） */}
      <div className="editor-status" role="status">
        {errors.length > 0 ? (
          <button
            type="button"
            className="link error-text"
            title={errors.map((e) => (e.line !== null ? `${e.line} 行目: ` : '') + e.message).join('\n')}
            onClick={() => setJump((j) => ({ line: errors[0].line ?? 1, n: j.n + 1 }))}
          >
            {errors[0].line !== null && `${errors[0].line} 行目: `}
            {errors[0].message}
            {errors.length > 1 && `（ほか ${errors.length - 1} 件）`}
          </button>
        ) : missing ? (
          <span className="warn-text">編集中の XML に、この statement（{statementId}）がありません</span>
        ) : null}
      </div>
    </div>
  );
}
