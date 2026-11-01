// 結果ビューの表: 並べ替え・絞り込み・TSV でコピー・強調する列・セルを押してパラメータに値を入れる
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { PValue } from './params';

const NUMBER_TYPES = /^(int2|int4|int8|smallint|integer|bigint|numeric|decimal|float4|float8|real|double precision|serial|bigserial|oid)\b/;
const STRING_TYPES = /^(text|varchar|character|bpchar|char|name|citext|"char")\b/;

/**
 * セルの値（文字）を、列の型に合わせてパラメータの値にする。型は JDBC の名前（int4、timestamptz など）でも、
 * format_type の名前（integer、timestamp without time zone など）でもよい。
 */
export function cellToParam(text: string | null, type: string): PValue {
  if (text === null) return { t: 'null' };
  const t = type.toLowerCase();
  if (NUMBER_TYPES.test(t)) return { t: 'number', v: text };
  if (t.startsWith('timestamp')) return { t: 'timestamp', v: text.replace(/(\.\d+)?([+-]\d{2}(:\d{2})?)?$/, '') };
  if (t === 'date') return { t: 'date', v: text };
  if (t === 'bool' || t === 'boolean') return { t: 'boolean', v: text === 't' || text === 'true' };
  if (STRING_TYPES.test(t)) return { t: 'string', v: text };
  // 列挙型・uuid・jsonb など: その型でバインドする（配列の _int4 は int4[]）
  return { t: 'pg', type: t.startsWith('_') ? `${t.slice(1)}[]` : type, v: text };
}

const isNumberType = (type: string | undefined) => !!type && NUMBER_TYPES.test(type.toLowerCase());

export function DataTable({
  columns,
  types,
  rows,
  lead,
  rowClass,
  highlight,
  onCell,
  actions,
  truncated,
  titles,
  onRow,
}: {
  columns: string[];
  /** 列の型（なければ空文字） */
  types: string[];
  rows: (string | null)[][];
  /** 先頭に足す列（値の候補の「件数」など）。見出しと、行ごとの文字 */
  lead?: { header: string; cells: string[] };
  /** 行ごとの class（値の候補の「入力中」の行など） */
  rowClass?: (index: number) => string | undefined;
  /** 強調する列（値の候補で、パラメータと比べている列） */
  highlight?: string | null;
  /** セルを押したとき（パラメータに値を入れる）。なければ押せない */
  onCell?: (text: string | null, type: string) => void;
  /** 帯に足す操作（結果の「続きを取得」など） */
  actions?: ReactNode;
  /** 取った行のほかにも行がある（続きがある） */
  truncated?: boolean;
  /** 列の説明（DB のコメント。見出しにマウスを乗せると出す） */
  titles?: (string | undefined)[];
  /** 行の左端の「⤷」を押したとき（その行から関連データを集める）。なければ出さない */
  onRow?: (index: number) => void;
}) {
  const [filter, setFilter] = useState('');
  const [sort, setSort] = useState<{ col: number; desc: boolean } | null>(null);
  const [copied, setCopied] = useState(false);
  // 手で変えた列の幅（px。列の番号ごと）。列が変わったら（別の結果を見たら）戻す
  const [widths, setWidths] = useState<Record<number, number>>({});
  const columnsKey = columns.join('\u0000');
  useEffect(() => setWidths({}), [columnsKey]);
  // 幅を変えるドラッグの直後の click で、並べ替えないため
  const resizing = useRef(false);

  const startResize = (e: React.MouseEvent, col: number) => {
    e.preventDefault();
    e.stopPropagation();
    const th = (e.currentTarget as HTMLElement).parentElement!;
    const x0 = e.clientX;
    const w0 = th.getBoundingClientRect().width;
    resizing.current = true;
    const move = (m: MouseEvent) => setWidths((old) => ({ ...old, [col]: Math.max(40, Math.round(w0 + m.clientX - x0)) }));
    const up = () => {
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', up);
      document.body.classList.remove('col-resizing');
      window.setTimeout(() => (resizing.current = false));
    };
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', up);
    document.body.classList.add('col-resizing');
  };
  const widthStyle = (col: number) =>
    widths[col] === undefined ? undefined : { width: widths[col], minWidth: widths[col], maxWidth: widths[col] };

  // 絞り込みと並べ替え（元の行の番号を持つ）
  const order = useMemo(() => {
    const q = filter.trim().toLowerCase();
    let idx = rows.map((_, i) => i);
    if (q) idx = idx.filter((i) => rows[i].some((v) => v !== null && v.toLowerCase().includes(q)));
    if (sort) {
      const num = isNumberType(types[sort.col]);
      idx = [...idx].sort((a, b) => {
        const x = rows[a][sort.col];
        const y = rows[b][sort.col];
        // NULL は最後
        if (x === null || y === null) return x === y ? 0 : x === null ? 1 : -1;
        const c = num ? Number(x) - Number(y) : x.localeCompare(y, 'ja');
        return sort.desc ? -c : c;
      });
    }
    return idx;
  }, [rows, types, filter, sort]);

  /** 見えている行を、TSV か CSV でコピーする */
  const copy = (format: 'tsv' | 'csv') => {
    const visible = order.map((i) => rows[i]);
    const esc = (v: string | null) => (v === null ? '' : v.replace(/[\t\n\r]/g, ' '));
    const text = format === 'csv' ? toCsv(columns, visible) : [columns.join('\t'), ...visible.map((r) => r.map(esc).join('\t'))].join('\n');
    navigator.clipboard.writeText(text).then(
      () => {
        setCopied(true);
        window.setTimeout(() => setCopied(false), 1500);
      },
      () => undefined,
    );
  };
  const target = highlight ? columns.indexOf(highlight) : -1;

  return (
    <div className="data-table">
      <div className="data-table-bar">
        <input type="search" placeholder="絞り込む" value={filter} onChange={(e) => setFilter(e.target.value)} />
        <span className="hint">
          {order.length === rows.length ? `${rows.length} 行` : `${order.length} / ${rows.length} 行`}
          {truncated && '（続きあり）'}
        </span>
        {actions}
        {copied && <span className="hint">コピーしました</span>}
        <TableMenu
          items={[
            { label: 'TSV でコピー', title: '見えている行を、タブ区切りでコピーします（Excel に貼れます）', onSelect: () => copy('tsv') },
            { label: 'CSV でコピー', title: '見えている行を、CSV でコピーします', onSelect: () => copy('csv') },
          ]}
        />
      </div>
      <div className="data-table-scroll">
        <table>
          <thead>
            <tr>
              {onRow && <th className="row-action" aria-label="関連データ" />}
              {lead && <th className="lead">{lead.header}</th>}
              {columns.map((c, i) => (
                <th
                  key={i}
                  className={i === target ? 'target' : undefined}
                  title={[titles?.[i], types[i]].filter(Boolean).join('\n') || undefined}
                  style={widthStyle(i)}
                  onClick={() => {
                    if (resizing.current) return;
                    setSort(sort?.col === i ? (sort.desc ? null : { col: i, desc: true }) : { col: i, desc: false });
                  }}
                >
                  {c}
                  {sort?.col === i && <span className="sort-mark">{sort.desc ? ' ▼' : ' ▲'}</span>}
                  <span
                    className="col-resize"
                    title="ドラッグで幅を変える（ダブルクリックで戻す）"
                    onMouseDown={(e) => startResize(e, i)}
                    onClick={(e) => e.stopPropagation()}
                    onDoubleClick={(e) => {
                      e.stopPropagation();
                      setWidths((old) => {
                        const next = { ...old };
                        delete next[i];
                        return next;
                      });
                    }}
                  />
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {order.map((r) => (
              <tr key={r} className={rowClass?.(r)}>
                {onRow && (
                  <td className="row-action">
                    <button
                      type="button"
                      className="icon"
                      title="この行から関連データを集める"
                      aria-label="関連データ"
                      onClick={() => onRow(r)}
                    >
                      ⤷
                    </button>
                  </td>
                )}
                {lead && <td className="lead">{lead.cells[r]}</td>}
                {rows[r].map((v, c) => (
                  <td
                    key={c}
                    className={
                      [c === target ? 'target' : '', v === null ? 'null' : '', onCell ? 'insertable' : ''].filter(Boolean).join(' ') ||
                      undefined
                    }
                    title={v ?? 'NULL'}
                    style={widthStyle(c)}
                    onClick={onCell ? () => onCell(v, types[c] ?? '') : undefined}
                  >
                    {v ?? 'NULL'}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** 表の操作のメニュー（⋯ を押すと開く。項目を選ぶか、外を押すと閉じる） */
/** 表の「⋯」のメニューの項目 */
type MenuItem = { label: string; title?: string; onSelect: () => void };

/** CSV（NULL は空。カンマ・引用符・改行を含む値は引用符で囲む） */
function toCsv(columns: string[], rows: (string | null)[][]): string {
  const cell = (v: string | null) => (v === null ? '' : /[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
  return [columns, ...rows].map((r) => r.map(cell).join(',')).join('\r\n');
}

function TableMenu({ items }: { items: MenuItem[] }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);
  return (
    <span className="table-menu" ref={box}>
      <button type="button" className="icon" aria-label="表の操作" aria-expanded={open} title="表の操作" onClick={() => setOpen(!open)}>
        ⋯
      </button>
      {open && (
        <span className="table-menu-list" role="menu">
          {items.map((it) => (
            <button
              key={it.label}
              type="button"
              role="menuitem"
              title={it.title}
              onClick={() => {
                setOpen(false);
                it.onSelect();
              }}
            >
              {it.label}
            </button>
          ))}
        </span>
      )}
    </span>
  );
}
