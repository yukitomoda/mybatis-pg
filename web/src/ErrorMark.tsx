// 画面の中のエラーは、文を出さずに印だけを出し、ログのその行へ移れるようにする（文はマウスを乗せると出る）
import { logIdOf, useShowLog } from './notices';

export function ErrorMark({ error, label = 'エラー' }: { error: string; label?: string }) {
  const showLog = useShowLog();
  const first = error.split('\n')[0].trim();
  return (
    <span className="error-mark" title={error}>
      ⚠ {label}
      <button type="button" className="link" onClick={() => showLog({ id: logIdOf(error), q: first.slice(0, 80) })}>
        ログで見る
      </button>
    </span>
  );
}
