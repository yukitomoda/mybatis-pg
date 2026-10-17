// mapper XML のエディタ（CodeMirror）
import { indentLess, indentMore } from '@codemirror/commands';
import { HighlightStyle, indentUnit, syntaxHighlighting } from '@codemirror/language';
import { xml } from '@codemirror/lang-xml';
import type { Diagnostic } from '@codemirror/lint';
import { lintGutter, setDiagnostics } from '@codemirror/lint';
import { Annotation, EditorState } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { tags } from '@lezer/highlight';
import { basicSetup } from 'codemirror';
import { useEffect, useRef } from 'react';

export interface EditorError {
  /** 1 始まり。分からなければ null（先頭に出す） */
  line: number | null;
  message: string;
}

/** 外から内容を入れ替えた変更の印。入力ではないので onChange を呼ばない */
const external = Annotation.define<boolean>();

/** 画面の色（ライト・ダーク）に合わせる */
const theme = EditorView.theme({
  '&': { height: '100%', fontSize: '13px', backgroundColor: 'var(--panel)', color: 'var(--text)' },
  '.cm-scroller': { fontFamily: 'var(--mono)' },
  '.cm-gutters': { backgroundColor: 'var(--code-bg)', color: 'var(--muted)', borderRight: '1px solid var(--border)' },
  '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: 'rgba(127, 127, 127, 0.08)' },
  '&.cm-focused': { outline: 'none' },
  '.cm-content': { caretColor: 'var(--text)' },
  '&.cm-focused .cm-cursor': { borderLeftColor: 'var(--text)' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: 'var(--accent-bg) !important' },
});

/** テンプレートの表示と同じ色（styles.css の --hl-*） */
const highlight = HighlightStyle.define([
  { tag: [tags.tagName, tags.angleBracket], color: 'var(--hl-tag)' },
  { tag: tags.attributeName, color: 'var(--hl-attr)' },
  { tag: tags.attributeValue, color: 'var(--hl-value)' },
  { tag: [tags.comment, tags.processingInstruction, tags.documentMeta], color: 'var(--muted)' },
]);

/** ファイルの字下げの書き方（タブ文字か、スペース何個か）を、行頭の字下げから推し量る */
export function detectIndent(text: string): string {
  let tabs = 0;
  const widths = new Map<number, number>();
  let prev = 0;
  for (const line of text.split('\n')) {
    const m = /^[ \t]*/.exec(line)![0];
    if (m.length === line.length) continue;
    if (m.startsWith('\t')) {
      tabs++;
      continue;
    }
    // 前の行との字下げの差を数える
    const diff = Math.abs(m.length - prev);
    if (diff === 2 || diff === 4) widths.set(diff, (widths.get(diff) ?? 0) + 1);
    prev = m.length;
  }
  const two = widths.get(2) ?? 0;
  const four = widths.get(4) ?? 0;
  if (tabs > two + four) return '\t';
  return four > two ? '    ' : '  ';
}

/**
 * Tab キー: 選択がなければカーソルの位置に字下げを入れ、選択があればその行をまとめて字下げする。Shift+Tab で戻す。
 * （Esc を押してから Tab を押すと、エディタの外へ移れる）
 */
const tabKeys = keymap.of([
  {
    key: 'Tab',
    run: (view) => {
      if (view.state.selection.ranges.some((r) => !r.empty)) return indentMore(view);
      const unit = view.state.facet(indentUnit);
      view.dispatch(view.state.replaceSelection(unit));
      return true;
    },
    shift: indentLess,
  },
]);

export function XmlEditor({
  value,
  onChange,
  errors,
  focus,
  label,
  onSave,
}: {
  value: string;
  onChange: (value: string) => void;
  errors: EditorError[];
  /** 表示する行（1 始まり）。key が変わったときだけ移る（同じ key のまま表示し直しても、スクロールの位置を保つ） */
  focus: { key: string; line: number } | null;
  label: string;
  /** Ctrl+S（Mac は ⌘S） */
  onSave: () => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const changeRef = useRef(onChange);
  changeRef.current = onChange;
  const saveRef = useRef(onSave);
  saveRef.current = onSave;

  useEffect(() => {
    const v = new EditorView({
      parent: host.current!,
      state: EditorState.create({
        doc: value,
        extensions: [
          basicSetup,
          xml(),
          indentUnit.of(detectIndent(value)),
          tabKeys,
          keymap.of([
            {
              key: 'Mod-s',
              preventDefault: true,
              run: () => {
                saveRef.current();
                return true;
              },
            },
          ]),
          syntaxHighlighting(highlight),
          lintGutter(),
          theme,
          EditorView.contentAttributes.of({ 'aria-label': label }),
          EditorView.updateListener.of((u) => {
            if (u.docChanged && !u.transactions.some((t) => t.annotation(external))) changeRef.current(u.state.doc.toString());
          }),
        ],
      }),
    });
    view.current = v;
    return () => {
      v.destroy();
      view.current = null;
    };
  }, []);

  // 外から内容が変わったとき（編集の取り消し、ファイルの読み込み直し）
  useEffect(() => {
    const v = view.current;
    if (!v || v.state.doc.toString() === value) return;
    v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: value }, annotations: external.of(true) });
  }, [value]);

  // statement の行へ移る
  const focused = useRef<string | null>(null);
  useEffect(() => {
    const v = view.current;
    if (!v || !focus || focus.key === focused.current || focus.line > v.state.doc.lines) return;
    focused.current = focus.key;
    const line = v.state.doc.line(focus.line);
    v.dispatch({ selection: { anchor: line.from }, effects: EditorView.scrollIntoView(line.from, { y: 'start', yMargin: 40 }) });
  }, [focus?.key, focus?.line]);

  useEffect(() => {
    const v = view.current;
    if (!v) return;
    const doc = v.state.doc;
    const diagnostics: Diagnostic[] = errors.map((e) => {
      const line = doc.line(Math.min(Math.max(e.line ?? 1, 1), doc.lines));
      return { from: line.from, to: line.to, severity: 'error', message: e.message };
    });
    v.dispatch(setDiagnostics(v.state, diagnostics));
  }, [errors]);

  return <div className="xml-editor" ref={host} />;
}
