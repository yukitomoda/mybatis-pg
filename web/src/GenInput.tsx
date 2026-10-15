// 生成値（式）の入力の補助: 結果のプレビュー、↻、ƒ パネル
import { useEffect, useRef, useState } from 'react';
import type { GenCache } from './gen';
import { FUNCTIONS, buildExpr, evaluateTemplate, hasTemplate } from './gen';

/** 式の計算に使うもの（画面全体で 1 つ） */
export interface GenContext {
  cache: GenCache;
  /** ↻ のたびに増える（計算し直すきっかけ） */
  version: number;
  regenerate: (key: string) => void;
}

/** 式のある欄の下に出す、計算した結果 */
export function GenPreview({ text, numeric, genKey, gen }: { text: string; numeric: boolean; genKey: string; gen: GenContext }) {
  if (!hasTemplate(text)) return null;
  let result: { text: string; volatile: boolean } | null = null;
  let error: string | null = null;
  try {
    result = evaluateTemplate(text, genKey, gen.cache, numeric);
  } catch (e) {
    error = (e as Error).message;
  }
  return (
    <div className={error ? 'gen-preview error-text' : 'gen-preview'}>
      {error ?? (
        <>
          <span className="arrow">→</span>
          <code>{numeric ? result!.text : `'${result!.text}'`}</code>
          {result!.volatile && (
            <button type="button" className="icon regen" title="作り直す" aria-label="値を作り直す" onClick={() => gen.regenerate(genKey)}>
              ↻
            </button>
          )}
        </>
      )}
    </div>
  );
}

/** ƒ パネル: 関数を選び、引数を入れ、プレビューを見て式を挿入する */
export function GenPicker({ numeric, onInsert, onClose }: { numeric: boolean; onInsert: (expr: string) => void; onClose: () => void }) {
  const choices = FUNCTIONS.filter((f) => !numeric || f.numeric);
  const [name, setName] = useState(choices[0].name);
  const [args, setArgs] = useState<string[]>([]);
  const panel = useRef<HTMLDivElement>(null);
  const spec = choices.find((f) => f.name === name) ?? choices[0];
  const expr = buildExpr(spec.name, spec.args.map((_, i) => args[i] ?? ''));

  // プレビューは一時的に計算する（画面の値には影響しない）
  let preview: string;
  let ok = true;
  try {
    const r = evaluateTemplate(expr, 'preview', new Map(), numeric);
    preview = numeric ? r.text : `'${r.text}'`;
  } catch (e) {
    preview = (e as Error).message;
    ok = false;
  }

  useEffect(() => {
    panel.current?.querySelector<HTMLElement>('select')?.focus();
  }, []);

  return (
    <div
      className="gen-picker"
      ref={panel}
      role="dialog"
      aria-label="式を挿入"
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.stopPropagation();
          onClose();
        }
      }}
    >
      <label>
        関数
        <select
          value={spec.name}
          onChange={(e) => {
            setName(e.target.value);
            setArgs([]);
          }}
        >
          {choices.map((f) => (
            <option key={f.name} value={f.name}>
              {f.label}（{f.name}）
            </option>
          ))}
        </select>
      </label>
      {spec.args.map((a, i) => (
        <label key={a.label}>
          {a.label}
          <input
            type="text"
            value={args[i] ?? ''}
            placeholder={a.placeholder}
            onChange={(e) => {
              const next = [...args];
              next[i] = e.target.value;
              setArgs(next);
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && ok) onInsert(expr);
            }}
          />
          {a.hint && <span className="hint">{a.hint}</span>}
        </label>
      ))}
      <div className="gen-picker-preview">
        <code>{expr}</code>
        <span className={ok ? undefined : 'error-text'}>→ {preview}</span>
      </div>
      <div className="actions">
        <button type="button" disabled={!ok} onClick={() => onInsert(expr)}>
          挿入
        </button>
        <button type="button" onClick={onClose}>
          取消
        </button>
      </div>
    </div>
  );
}
