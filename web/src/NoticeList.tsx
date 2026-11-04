// 画面の右下のエラーの知らせ（押すとログのその行へ移る）
import { dismiss, useNotices, useShowLog } from './notices';

export function Notices() {
  const notices = useNotices();
  const showLog = useShowLog();
  if (notices.length === 0) return null;
  return (
    <div className="notices" role="status" aria-live="polite">
      {notices.map((n) => (
        <div key={n.key} className="notice error">
          <span className="notice-text" title={n.message}>
            ⚠ {n.message.split('\n')[0]}
          </span>
          <button
            type="button"
            className="link"
            onClick={() => {
              dismiss(n.key);
              showLog({ id: n.logId, q: n.message.split('\n')[0].slice(0, 80) });
            }}
          >
            ログで見る
          </button>
          <button type="button" className="icon" aria-label="閉じる" onClick={() => dismiss(n.key)}>
            ×
          </button>
        </div>
      ))}
    </div>
  );
}
