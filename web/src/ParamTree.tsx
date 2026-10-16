// パラメータのツリー入力。XML から抜き出した項目を並べ、値の入力・型の選択・手での追加ができる
import { useRef, useState } from 'react';
import { hasTemplate } from './gen';
import type { GenContext } from './GenInput';
import { GenPicker, GenPreview } from './GenInput';
import type { ParamSchema, Path, PType, PValue } from './params';
import type { TextType } from './params';
import { TYPE_LABELS, convertType, isValidDate, isValidTimestamp, defaultType, effectiveValue, emptyValue, getAt, inheritedAt, isValidNumber, overrideAt, pathLabel } from './params';

interface Props {
  schema: ParamSchema;
  /** 編集する値（個別の値、または共通パラメータの値） */
  value: PValue | undefined;
  onChange: (value: PValue | undefined) => void;
  /**
   * 個別の値を編集するときの共通パラメータ。指定すると、共通や既定（foreach の空リスト）から来る値を薄く表示し、
   * 編集するとその位置だけ個別の値として上書きする。
   */
  global?: PValue;
  /** 共通パラメータの編集。XML の項目と関係なく並べ、「未参照」の印を出さない */
  free?: boolean;
  /** 生成値（式）の計算 */
  gen: GenContext;
}

const USAGE_LABELS: Record<string, string> = {
  BIND: '#{}',
  RAW: '${}',
  CONDITION: '条件',
  ITERATE: 'foreach',
};

/** 木の全体で共有する情報 */
interface Context {
  local: PValue | undefined;
  /** 実際に渡す値（共通 + 個別 + 既定）。共通を使わない編集では local と同じ */
  effective: PValue | undefined;
  /** 個別の値がないときに来る値。共通を使わない編集では undefined */
  inherited: PValue | undefined;
  global: PValue | undefined;
  layered: boolean;
  free: boolean;
  gen: GenContext;
  update: (path: Path, v: PValue | undefined) => void;
}

export function ParamTree({ schema, value, onChange, global, free = false, gen }: Props) {
  const layered = !free;
  const effective = layered ? effectiveValue(schema, global, value) : value;
  const inherited = layered ? effectiveValue(schema, global, undefined) : undefined;
  const update = (path: Path, v: PValue | undefined) => {
    const next = layered ? overrideAt(value, effective, path, v) : overrideAt(value, value, path, v);
    onChange(next && next.t === 'object' && next.fields.length === 0 ? undefined : next);
  };
  const ctx: Context = { local: value, effective, inherited, global, layered, free, gen, update };
  return (
    <div className="tree" role="tree">
      <Children ctx={ctx} schema={free ? null : schema} path={[]} depth={0} />
      <AddField depth={0} existing={fieldNames(schema, getAt(effective, []))} onAdd={(name) => update([name], { t: 'string', v: '' })} />
    </div>
  );
}

interface NodeProps {
  ctx: Context;
  schema: ParamSchema | null;
  path: Path;
  depth: number;
}

/** オブジェクトの子項目。XML で参照されている項目を先に、値だけにある項目を後に並べる */
function Children({ ctx, schema, path, depth }: NodeProps) {
  const known = schema?.properties ?? [];
  const knownNames = new Set(known.map((p) => p.name as string));
  const shown = path.length === 0 ? ctx.effective : getAt(ctx.effective, path);
  // XML で参照されていない共通の値は、個別の表示では出さない（他の statement 用の値のため）
  const extra =
    shown && shown.t === 'object'
      ? shown.fields.filter(([k]) => !knownNames.has(k) && (ctx.free || getAt(ctx.local, [...path, k]) !== undefined))
      : [];
  return (
    <>
      {known.map((child) => (
        <Row key={child.name} ctx={ctx} name={child.name as string} schema={child} path={[...path, child.name as string]} depth={depth} />
      ))}
      {extra.map(([k]) => (
        <Row key={`extra:${k}`} ctx={ctx} name={k} schema={null} path={[...path, k]} depth={depth} />
      ))}
    </>
  );
}

function Row({ ctx, name, schema, path, depth, removable }: NodeProps & { name: string; removable?: boolean }) {
  const unreferenced = schema === null && !ctx.free;
  const local = getAt(ctx.local, path);
  const shown = local ?? getAt(ctx.effective, path);
  const inherited = ctx.layered ? inheritedAt(ctx.local, ctx.inherited, path) : undefined;
  // 個別の値がなく、共通または既定から来ている
  const origin = local === undefined && shown !== undefined ? (getAt(ctx.global, path) !== undefined ? '共通' : '既定') : null;
  const type = shown?.t;
  const set = (v: PValue | undefined) => ctx.update(path, v);
  // XML で参照されているのに値がない（多くは入力のし忘れ）
  const missing =
    !ctx.free && shown === undefined && schema !== null && (schema.usages.length > 0 || schema.properties.length > 0 || schema.element !== null);

  return (
    <>
      <div className={`row${unreferenced ? ' unreferenced' : ''}${origin ? ' inherited' : ''}${missing ? ' missing' : ''}`}>
        <span
          className="name"
          style={{ paddingLeft: `${depth * 1.25}rem` }}
          title={unreferenced ? 'XML では参照されていない項目' : undefined}
        >
          {missing && (
            <span className="warn-mark" title="値が未設定です（NULL として扱われます）" aria-label="未設定">
              ⚠
            </span>
          )}
          {name}
        </span>
        <span className="usages">
          {origin && (
            <span className={origin === '共通' ? 'badge global' : 'badge muted'} title={origin === '共通' ? '共通パラメータの値' : '既定の値'}>
              {origin}
            </span>
          )}
          {unreferenced ? <span className="badge muted">未参照</span> : schema?.usages.map((u) => <span key={u} className="badge">{USAGE_LABELS[u]}</span>)}
        </span>
        <select
          aria-label={`${name} の型`}
          value={type ?? ''}
          onChange={(e) => set(e.target.value === '' ? undefined : convertType(shown, e.target.value as PType))}
        >
          {/* 共通や既定の値がある項目は、「共通に戻す」で個別の値を消す */}
          {inherited === undefined && <option value="">未設定</option>}
          {(Object.keys(TYPE_LABELS) as PType[]).map((t) => (
            <option key={t} value={t}>
              {TYPE_LABELS[t]}
            </option>
          ))}
        </select>
        <span className="editor">
          <Editor schema={schema} value={shown} set={set} name={name} genKey={pathLabel(path)} gen={ctx.gen} />
        </span>
        {local !== undefined && inherited !== undefined ? (
          <button
            type="button"
            className="icon"
            aria-label={`${name} を${getAt(ctx.global, path) !== undefined ? '共通' : '既定'}の値に戻す`}
            title={getAt(ctx.global, path) !== undefined ? '共通の値に戻す' : '既定の値に戻す'}
            onClick={() => set(undefined)}
          >
            ↺
          </button>
        ) : (
          (unreferenced || removable || (ctx.free && local !== undefined)) && (
            <button type="button" className="icon" aria-label={`${name} を削除`} onClick={() => set(undefined)}>
              ×
            </button>
          )
        )}
      </div>
      {(type === 'object' || (type === undefined && schema && schema.properties.length > 0)) && (
        <>
          <Children ctx={ctx} schema={schema} path={path} depth={depth + 1} />
          {type === 'object' && (
            <AddField
              depth={depth + 1}
              existing={fieldNames(schema, shown)}
              onAdd={(n) => ctx.update([...path, n], { t: 'string', v: '' })}
            />
          )}
        </>
      )}
      {type === 'list' &&
        shown!.t === 'list' &&
        shown!.items.map((_, i) => (
          <Row
            key={i}
            ctx={ctx}
            name={`[${i}]`}
            schema={schema?.element ?? emptyElementSchema}
            path={[...path, i]}
            depth={depth + 1}
            removable
          />
        ))}
      {(type === 'list' || (type === undefined && schema?.element)) && (
        <div className="row add" style={{ paddingLeft: `${(depth + 1) * 1.25 + 0.5}rem` }}>
          <button
            type="button"
            onClick={() => {
              const items = shown && shown.t === 'list' ? shown.items : [];
              ctx.update([...path, items.length], emptyValue(defaultType(schema?.element)));
            }}
          >
            ＋要素
          </button>
        </div>
      )}
    </>
  );
}

/** XML 側で要素の形が分からないリストの要素 */
const emptyElementSchema: ParamSchema = { name: '[]', usages: [], typeHints: [], properties: [], element: null };

/** 値の入力欄。未設定のときに入力すると、推測した型で設定する */
function Editor({
  schema,
  value,
  set,
  name,
  genKey,
  gen,
}: {
  schema: ParamSchema | null;
  value: PValue | undefined;
  set: (v: PValue | undefined) => void;
  name: string;
  genKey: string;
  gen: GenContext;
}) {
  const t = value?.t ?? defaultType(schema);
  if (value === undefined && (t === 'list' || t === 'object')) {
    return <span className="placeholder">未設定（NULL として扱われます）</span>;
  }
  switch (t) {
    case 'string':
    case 'number':
    case 'timestamp':
    case 'date': {
      const kind = value?.t ?? t;
      const text = value && 'v' in value && typeof value.v === 'string' ? value.v : '';
      return (
        <TextEditor
          name={name}
          text={text}
          kind={kind as TextType}
          unset={value === undefined}
          onChange={(v) => set({ t: kind as TextType, v })}
          genKey={genKey}
          gen={gen}
        />
      );
    }
    case 'boolean':
      return (
        <label className="check">
          <input
            type="checkbox"
            aria-label={`${name} の値`}
            checked={value?.t === 'boolean' && value.v}
            onChange={(e) => set({ t: 'boolean', v: e.target.checked })}
          />
          {value === undefined ? '未設定' : value.t === 'boolean' && value.v ? 'true' : 'false'}
        </label>
      );
    case 'null':
      return <span className="placeholder">NULL</span>;
    case 'list':
      return <span className="placeholder">{value && value.t === 'list' ? `${value.items.length} 件` : ''}</span>;
    case 'object':
      return null;
  }
}

/** 文字列・数値の入力欄。式を書け、ƒ ボタンから式を挿入できる */
const PLACEHOLDERS: Record<TextType, string> = {
  string: '',
  number: '数値',
  timestamp: '2026-01-03 23:35:00',
  date: '2026-01-03',
};

function TextEditor({
  name,
  text,
  kind,
  unset,
  onChange,
  genKey,
  gen,
}: {
  name: string;
  text: string;
  kind: TextType;
  unset: boolean;
  onChange: (text: string) => void;
  genKey: string;
  gen: GenContext;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [picking, setPicking] = useState(false);
  const numeric = kind === 'number';
  // 式は結果で確かめるので、式のある値は入力の段階では誤りにしない
  const valid = kind === 'number' ? isValidNumber : kind === 'timestamp' ? isValidTimestamp : kind === 'date' ? isValidDate : () => true;
  const invalid = !unset && !hasTemplate(text) && !valid(text);
  const insert = (expr: string) => {
    const el = input.current;
    const start = el?.selectionStart ?? text.length;
    const end = el?.selectionEnd ?? text.length;
    // 数値の欄で数値だけが入っているなら、式で置き換える
    const replaceAll = numeric && !hasTemplate(text);
    const next = replaceAll ? expr : text.slice(0, start) + expr + text.slice(end);
    onChange(next);
    setPicking(false);
    const caret = replaceAll ? expr.length : start + expr.length;
    requestAnimationFrame(() => {
      input.current?.focus();
      input.current?.setSelectionRange(caret, caret);
    });
  };
  return (
    <div className="text-editor">
      <div className="text-input">
        <input
          ref={input}
          type="text"
          aria-label={`${name} の値`}
          className={invalid ? 'invalid' : undefined}
          inputMode={numeric ? 'decimal' : undefined}
          placeholder={unset ? '未設定' : PLACEHOLDERS[kind]}
          value={text}
          onChange={(e) => onChange(e.target.value)}
        />
        <button
          type="button"
          className="icon fn"
          title="式を挿入（UUID、日時、UNIX 時刻など）"
          aria-label={`${name} に式を挿入`}
          aria-expanded={picking}
          onClick={() => setPicking(!picking)}
        >
          ƒ
        </button>
      </div>
      <GenPreview text={text} numeric={numeric} genKey={genKey} gen={gen} />
      {picking && <GenPicker numeric={numeric} onInsert={insert} onClose={() => setPicking(false)} />}
    </div>
  );
}

function fieldNames(schema: ParamSchema | null, value: PValue | undefined): Set<string> {
  const names = new Set((schema?.properties ?? []).map((p) => p.name as string));
  if (value && value.t === 'object') value.fields.forEach(([k]) => names.add(k));
  return names;
}

/** 手で項目を追加する */
function AddField({ depth, existing, onAdd }: { depth: number; existing: Set<string>; onAdd: (name: string) => void }) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState('');
  const trimmed = name.trim();
  const error = trimmed === '' ? null : existing.has(trimmed) ? 'すでにあります' : /^[A-Za-z_$][\w$]*$/.test(trimmed) ? null : '名前が不正です';
  const submit = () => {
    if (trimmed === '' || error) return;
    onAdd(trimmed);
    setName('');
    setEditing(false);
  };
  return (
    <div className="row add" style={{ paddingLeft: `${depth * 1.25 + 0.5}rem` }}>
      {editing ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          <input autoFocus type="text" aria-label="追加する項目名" placeholder="項目名" value={name} onChange={(e) => setName(e.target.value)} />
          <button type="submit" disabled={trimmed === '' || error !== null}>
            追加
          </button>
          <button type="button" onClick={() => setEditing(false)}>
            取消
          </button>
          {error && <span className="error-text">{error}</span>}
        </form>
      ) : (
        <button type="button" onClick={() => setEditing(true)}>
          ＋項目
        </button>
      )}
    </div>
  );
}
