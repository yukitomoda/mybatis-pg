// statement 一覧のツリー（ディレクトリ → ファイル → statement）

export interface StatementEntry {
  id: string;
  /** 定義したファイル。/ 区切りの相対パス（基準の外なら絶対パス） */
  file: string;
  /** SELECT・INSERT・UPDATE・DELETE など */
  type?: string;
  /** 下書きで足した（保存されているファイルにない） */
  added?: boolean;
}

export type TreeNode =
  | { kind: 'dir'; key: string; label: string; children: TreeNode[] }
  | { kind: 'file'; key: string; label: string; statements: StatementEntry[] };

interface RawDir {
  dirs: Map<string, RawDir>;
  files: Map<string, StatementEntry[]>;
}

const emptyDir = (): RawDir => ({ dirs: new Map(), files: new Map() });

/**
 * ファイルのパスからツリーを作る。
 * 子が 1 つしかないディレクトリは、子とまとめて 1 つの項目（例: src/main/、mapper/UserMapper.xml）にする。
 */
export function buildTree(entries: StatementEntry[]): TreeNode[] {
  const root = emptyDir();
  for (const e of entries) {
    // 絶対パスの先頭の / は、最初のディレクトリ名に含める
    const parts = e.file.split('/');
    if (parts[0] === '' && parts.length > 1) {
      parts.shift();
      parts[0] = '/' + parts[0];
    }
    let dir = root;
    for (const name of parts.slice(0, -1)) {
      let next = dir.dirs.get(name);
      if (!next) {
        next = emptyDir();
        dir.dirs.set(name, next);
      }
      dir = next;
    }
    const fileName = parts[parts.length - 1];
    const list = dir.files.get(fileName) ?? [];
    list.push(e);
    dir.files.set(fileName, list);
  }
  return children(root, '');
}

function children(dir: RawDir, prefix: string): TreeNode[] {
  const dirs: TreeNode[] = [...dir.dirs.entries()].map(([name, d]) => compact(name, d, prefix));
  const files: TreeNode[] = [...dir.files.entries()].map(([name, statements]) => ({
    kind: 'file',
    key: prefix + name,
    label: name,
    statements: [...statements].sort((a, b) => a.id.localeCompare(b.id)),
  }));
  const byLabel = (a: TreeNode, b: TreeNode) => a.label.localeCompare(b.label);
  return [...dirs.sort(byLabel), ...files.sort(byLabel)];
}

/** 子が 1 つだけのディレクトリを、子とまとめる */
function compact(name: string, dir: RawDir, prefix: string): TreeNode {
  const key = prefix + name + '/';
  const kids = children(dir, key);
  if (kids.length === 1) {
    const only = kids[0];
    return { ...only, label: name + '/' + only.label };
  }
  return { kind: 'dir', key, label: name, children: kids };
}

/** statement の id かファイルのパスに文字列を含むものだけを残す（大文字小文字は区別しない） */
export function filterEntries(entries: StatementEntry[], query: string): StatementEntry[] {
  const q = query.trim().toLowerCase();
  if (!q) return entries;
  return entries.filter((e) => e.id.toLowerCase().includes(q) || e.file.toLowerCase().includes(q));
}

/** statement に至るまでの項目（ディレクトリとファイル）の key。見つからなければ空 */
export function pathTo(nodes: TreeNode[], statementId: string): string[] {
  for (const n of nodes) {
    if (n.kind === 'file') {
      if (n.statements.some((s) => s.id === statementId)) return [n.key];
    } else {
      const sub = pathTo(n.children, statementId);
      if (sub.length > 0) return [n.key, ...sub];
    }
  }
  return [];
}

/** 名前空間を除いた statement の id */
export function shortId(id: string): string {
  return id.slice(id.lastIndexOf('.') + 1);
}
