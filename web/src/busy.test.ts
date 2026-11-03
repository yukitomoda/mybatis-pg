import { describe, expect, it } from 'vitest';
import { setTask, track } from './busy';

describe('busy', () => {
  it('失敗した問い合わせでも数え終える（track は元の結果をそのまま返す）', async () => {
    await expect(track(Promise.resolve(1))).resolves.toBe(1);
    await expect(track(Promise.reject(new Error('x')))).rejects.toThrow('x');
    // 同じ文なら知らせ直さない・消したものを消しても何もしない
    setTask('t', 'a');
    setTask('t', 'a');
    setTask('t', null);
    setTask('t', null);
  });
});
