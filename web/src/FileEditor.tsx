// テンプレートのタブ: statement を含む mapper XML ファイル全体を編集する。編集した内容は下書きとして保存し、元のファイルは変えない
import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react';
import type { Api, OpenTarget } from './api';
import { ApiError } from './api';
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
  /** 保存されているファイルの内容（どのファイルのものかも持ち、別のファイルの内容を取り違えないようにする） */
  const [loaded, setLoaded] = useState<{ file: string; content: string } | null>(null);
  const saved = loaded?.file === file ? loaded.content : null;
  const setSaved = (content: string) => setLoaded({ file, content });
  const [loadError, setLoadError] = useState<string | null>(null);
  const draft = drafts.get(file);
  /** 編集中の文字（どのファイルのものかも持つ） */
  const [editing, setEditing] = useState<{ file: string; text: string } | null>(null);
  const text = editing?.file === file ? editing.text : null;
  const setText = (t: string | null) => setEditing(t === null ? null : { file, text: t });
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
        setLoaded({ file, content: r.content });
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
  // ファイルの内容を読み込むまでは、エディタを出さない（前のファイルの内容を新しいファイルのものとして扱わないように）
  const content = saved === null ? null : (text ?? draft?.content ?? saved);

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

  const [saving, setSaving] = useState(false);
  const [saveMessage, setSaveMessage] = useState<{ ok: boolean; text: string } | null>(null);
  /** 編集した内容をファイルに保存する */
  const save = async () => {
    const current = text ?? draft?.content;
    if (saving || saved === null || current === undefined || current === null || (!draft && text === null)) return;
    if (errors.length > 0 && !window.confirm('XML にエラーがあります。このままファイルに保存しますか？')) return;
    setSaving(true);
    setSaveMessage(null);
    try {
      // 下書きの保存が後から届いて、下書きが残らないようにする
      flush();
      await drafts.settle(file);
      const base = draft?.base ?? saved;
      try {
        await api.saveFile(file, base, current, false);
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 409)) throw e;
        if (!window.confirm(`編集を始めた後に、${file} が変更されています。上書きして保存しますか？`)) return;
        await api.saveFile(file, base, current, true);
      }
      drafts.forget(file);
      setSaved(current);
      setText(null);
      setSaveMessage({ ok: true, text: '保存しました' });
    } catch (e) {
      onApiError(e);
      setSaveMessage({ ok: false, text: 'ファイルに保存できません: ' + (e instanceof Error ? e.message : String(e)) });
    } finally {
      setSaving(false);
    }
  };
  // 「保存しました」は少しで消す。編集を始めたら消す
  useEffect(() => {
    if (!saveMessage?.ok) return;
    const t = window.setTimeout(() => setSaveMessage(null), 3000);
    return () => window.clearTimeout(t);
  }, [saveMessage]);
  useEffect(() => {
    if (text !== null) setSaveMessage((m) => (m?.ok ? null : m));
  }, [text]);

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
          <button type="button" className="run" disabled={!draft || saving} title="Ctrl+S" onClick={save}>
            ファイルに保存
          </button>
          <button
            type="button"
            disabled={!draft}
            onClick={() => {
              if (!window.confirm(`${file} の編集を取り消して、保存されている内容に戻しますか？`)) return;
              window.clearTimeout(timer.current);
              pending.current = null;
              setText(null);
              drafts.discard([file]);
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
        <XmlEditor key={file} value={content} onChange={change} errors={errors} focus={focus} label={`${file} の編集`} onSave={save} />
      </Suspense>
      {/* エラーは高さの変わらない 1 行に出す（出たり消えたりしてもエディタが動かないように） */}
      <div className="editor-status" role="status">
        {saveMessage ? (
          <span className={saveMessage.ok ? 'ok-text' : 'error-text'}>{saveMessage.text}</span>
        ) : drafts.error ? (
          <span className="error-text">{drafts.error}</span>
        ) : errors.length > 0 ? (
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
