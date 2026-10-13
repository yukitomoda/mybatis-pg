// パラメータのツリー入力。XML から抜き出した項目を並べ、値の入力・型の選択・手での追加ができる
import { useState } from 'react';
import type { ParamSchema, Path, PType, PValue } from './params';
import { TYPE_LABELS, convertType, defaultType, emptyValue, getAt, isValidNumber, setAt } from './params';

interface Props {
  schema: ParamSchema;
  value: PValue | undefined;
  onChange: (value: PValue | undefined) => void;
}

const USAGE_LABELS: Record<string, string> = {
  BIND: '#{}',
  RAW: '${}',
  CONDITION: '条件',
  ITERATE: 'foreach',
};

export function ParamTree({ schema, value, onChange }: Props) {
  const update = (path: Path, v: PValue | undefined) => {
    const next = setAt(value, path, v);
    onChange(next && next.t === 'object' && next.fields.length === 0 ? undefined : next);
  };
  return (
    <div className="tree" role="tree">
      <Children schema={schema} value={value} path={[]} depth={0} update={update} />
      <AddField depth={0} existing={fieldNames(schema, value)} onAdd={(name) => update([name], { t: 'string', v: '' })} />
    </div>
  );
}

interface NodeProps {
  schema: ParamSchema | null;
  value: PValue | undefined;
  path: Path;
  depth: number;
  update: (path: Path, v: PValue | undefined) => void;
}

/** オブジェクトの子項目。XML で参照されている項目を先に、値だけにある項目を後に並べる */
function Children({ schema, value, path, depth, update }: NodeProps) {
  const known = schema?.properties ?? [];
  const knownNames = new Set(known.map((p) => p.name as string));
  const extra = value && value.t === 'object' ? value.fields.filter(([k]) => !knownNames.has(k)) : [];
  return (
    <>
      {known.map((child) => (
        <Row
          key={child.name}
          name={child.name as string}
          schema={child}
          value={getAt(value, [child.name as string])}
          path={[...path, child.name as string]}
          depth={depth}
          update={update}
        />
      ))}
      {extra.map(([k, v]) => (
        <Row key={`extra:${k}`} name={k} schema={null} value={v} path={[...path, k]} depth={depth} update={update} />
      ))}
    </>
  );
}

function Row({ name, schema, value, path, depth, update, removable }: NodeProps & { name: string; removable?: boolean }) {
  const unreferenced = schema === null;
  const type = value?.t;
  const set = (v: PValue | undefined) => update(path, v);

  return (
    <>
      <div className={`row${unreferenced ? ' unreferenced' : ''}`}>
        <span
          className="name"
          style={{ paddingLeft: `${depth * 1.25}rem` }}
          title={unreferenced ? 'XML では参照されていない項目' : undefined}
        >
          {name}
        </span>
        <span className="usages">
          {unreferenced ? <span className="badge muted">未参照</span> : schema!.usages.map((u) => <span key={u} className="badge">{USAGE_LABELS[u]}</span>)}
        </span>
        <select
          aria-label={`${name} の型`}
          value={type ?? ''}
          onChange={(e) => set(e.target.value === '' ? undefined : convertType(value, e.target.value as PType))}
        >
          <option value="">未設定</option>
          {(Object.keys(TYPE_LABELS) as PType[]).map((t) => (
            <option key={t} value={t}>
              {TYPE_LABELS[t]}
            </option>
          ))}
        </select>
        <span className="editor">
          <Editor schema={schema} value={value} set={set} name={name} />
        </span>
        {(unreferenced || removable) && (
          <button type="button" className="icon" aria-label={`${name} を削除`} onClick={() => set(undefined)}>
            ×
          </button>
        )}
      </div>
      {(type === 'object' || (type === undefined && schema && schema.properties.length > 0)) && (
        <>
          <Children schema={schema} value={value} path={path} depth={depth + 1} update={update} />
          {type === 'object' && (
            <AddField
              depth={depth + 1}
              existing={fieldNames(schema, value)}
              onAdd={(n) => update([...path, n], { t: 'string', v: '' })}
            />
          )}
        </>
      )}
      {type === 'list' &&
        value!.t === 'list' &&
        value!.items.map((item, i) => (
          <ListItem
            key={i}
            index={i}
            schema={schema?.element ?? null}
            value={item}
            path={[...path, i]}
            depth={depth + 1}
            update={update}
          />
        ))}
      {(type === 'list' || (type === undefined && schema?.element)) && (
        <div className="row add" style={{ paddingLeft: `${(depth + 1) * 1.25 + 0.5}rem` }}>
          <button
            type="button"
            onClick={() => {
              const items = value && value.t === 'list' ? value.items : [];
              update([...path, items.length], emptyValue(defaultType(schema?.element)));
            }}
          >
            ＋要素
          </button>
        </div>
      )}
    </>
  );
}

function ListItem({ index, schema, value, path, depth, update }: NodeProps & { index: number }) {
  return (
    <Row
      name={`[${index}]`}
      schema={schema ?? emptyElementSchema}
      value={value}
      path={path}
      depth={depth}
      update={update}
      removable
    />
  );
}

/** XML 側で要素の形が分からないリストの要素 */
const emptyElementSchema: ParamSchema = { name: '[]', usages: [], typeHints: [], properties: [], element: null };

/** 値の入力欄。未設定のときに入力すると、推測した型で設定する */
function Editor({ schema, value, set, name }: { schema: ParamSchema | null; value: PValue | undefined; set: (v: PValue | undefined) => void; name: string }) {
  const t = value?.t ?? defaultType(schema);
  if (value === undefined && (t === 'list' || t === 'object')) {
    return <span className="placeholder">未設定（NULL として扱われます）</span>;
  }
  switch (t) {
    case 'string':
    case 'number': {
      const text = value && (value.t === 'string' || value.t === 'number') ? value.v : '';
      const invalid = value?.t === 'number' && !isValidNumber(text);
      return (
        <input
          type="text"
          aria-label={`${name} の値`}
          className={invalid ? 'invalid' : undefined}
          inputMode={t === 'number' ? 'decimal' : undefined}
          placeholder={value === undefined ? '未設定' : t === 'number' ? '数値' : ''}
          value={text}
          onChange={(e) => set({ t: value?.t === 'number' || (value === undefined && t === 'number') ? 'number' : 'string', v: e.target.value })}
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
