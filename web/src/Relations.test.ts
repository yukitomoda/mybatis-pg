import { describe, expect, it } from "vitest";
import { duration, toText } from "./Relations";

describe("リレーションのテキスト", () => {
  it("親子・兄弟・兄弟をたどった源流・解析できなかったものをまとめる", () => {
    const text = toText(
      {
        updatedAt: 0,
        statements: 3,
        errors: [{ id: "m.broken", error: "ERROR: syntax error\n詳しく" }],
        links: [
          {
            a: "app.posts.user_id",
            b: "app.users.id",
            kind: "parent",
            statements: ["m.a", "m.b"],
          },
          {
            a: "app.post_histories.user_id",
            b: "app.posts.user_id",
            kind: "sibling",
            statements: ["m.c"],
          },
        ],
        origins: [
          {
            column: "app.posts.user_id",
            origins: [
              { column: "app.users.id", path: ["app.posts.user_id"], count: 2 },
            ],
          },
          {
            column: "app.post_histories.user_id",
            origins: [
              {
                column: "app.users.id",
                path: ["app.post_histories.user_id", "app.posts.user_id"],
                count: 1,
              },
            ],
          },
        ],
      },
      true,
    );
    expect(text).toContain("posts.user_id → users.id　[2 件] m.a, m.b");
    expect(text).toContain("## 兄弟（どちらも一意でない）（1 組）");
    expect(text).toContain(
      "post_histories.user_id ＝ posts.user_id　[1 件] m.c",
    );
    // 直接の親は親子に出ているので、源流には兄弟をたどったものだけ
    expect(text).toContain("## 兄弟をたどった源流（1 件）");
    expect(text).toContain(
      "post_histories.user_id → users.id　（posts.user_id 経由、1 件の SQL）",
    );
    expect(text).toContain("m.broken: ERROR: syntax error\n");
  });
});

describe("時間の表し方", () => {
  it("秒・分・時間", () => {
    expect(duration(40_000)).toBe("40 秒");
    expect(duration(3 * 60_000)).toBe("3 分");
    expect(duration(65 * 60_000)).toBe("1 時間 5 分");
    expect(duration(120 * 60_000)).toBe("2 時間");
  });
});
