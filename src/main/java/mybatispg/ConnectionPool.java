package mybatispg;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 使い終わった接続をとっておき、次に同じ接続先（と同じ設定）で開くときに使い回す。画面の操作のたびに接続し直すと、
 * ネットワーク越しの DB では接続と認証の往復が待ち時間の多くを占めるため。
 *
 * <p>貸し出した接続の close() で戻ってくる。戻すときに、ロールバックし、自動コミット・読み取り専用を元に戻し、
 * DISCARD ALL でセッションの状態（SET した設定・PREPARE した文・一時表など）を消してからとっておく。片付けられなければ閉じる。
 * しばらく使わなかった接続は閉じ、少し置いた接続は貸し出す前に生きているか確かめる。
 */
final class ConnectionPool {

  /** 1 つの接続先（と設定）でとっておく数 */
  static final int MAX_IDLE = 4;
  /** これより長く使わなかった接続は閉じる */
  static final long IDLE_MILLIS = 5 * 60_000L;
  /** これより長く置いた接続は、貸し出す前に生きているか確かめる */
  static final long VALIDATE_AFTER_MILLIS = 30_000L;

  /** 接続を開く（とっておいたものがないとき） */
  interface Opener {
    Connection open() throws SQLException;
  }

  private static final class Idle {
    final Connection c;
    final long since;

    Idle(Connection c, long since) {
      this.c = c;
      this.since = since;
    }
  }

  /** 鍵（接続先と、URL・ユーザー・パスワードなど）ごとの、とっておいた接続（新しいものが先） */
  private final Map<String, Deque<Idle>> idle = new HashMap<>();
  private final Thread sweeper;
  /** 時計（テストで差し替える） */
  java.util.function.LongSupplier clock = System::currentTimeMillis;

  ConnectionPool() {
    sweeper = new Thread(() -> {
      while (true) {
        try {
          Thread.sleep(60_000L);
        } catch (InterruptedException e) {
          return;
        }
        sweep();
      }
    }, "connection-pool-sweeper");
    sweeper.setDaemon(true);
    sweeper.start();
  }

  /** 鍵の接続を貸し出す。とっておいたものがなければ開く */
  Connection borrow(String key, Opener opener) throws SQLException {
    while (true) {
      Idle i;
      synchronized (this) {
        Deque<Idle> d = idle.get(key);
        i = d == null ? null : d.pollFirst();
      }
      if (i == null) {
        break;
      }
      long age = clock.getAsLong() - i.since;
      if (age > IDLE_MILLIS || !alive(i.c, age)) {
        closeQuietly(i.c);
        continue;
      }
      return lease(key, i.c);
    }
    return lease(key, opener.open());
  }

  private static boolean alive(Connection c, long age) {
    try {
      return !c.isClosed() && (age < VALIDATE_AFTER_MILLIS || c.isValid(3));
    } catch (SQLException e) {
      return false;
    }
  }

  /** close() で戻ってくる接続 */
  private Connection lease(String key, Connection raw) {
    boolean[] closed = {false};
    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, m, args) -> {
      switch (m.getName()) {
        case "close":
          if (!closed[0]) {
            closed[0] = true;
            giveBack(key, raw);
          }
          return null;
        case "isClosed":
          return closed[0] || raw.isClosed();
        case "equals":
          return proxy == args[0];
        case "hashCode":
          return System.identityHashCode(proxy);
        case "toString":
          return "pooled " + raw;
        default:
          if (closed[0]) {
            throw new SQLException("接続は閉じています");
          }
          try {
            return m.invoke(raw, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
      }
    });
  }

  /** 片付けてとっておく。片付けられないか、多すぎれば閉じる */
  private void giveBack(String key, Connection raw) {
    try {
      if (raw.isClosed()) {
        return;
      }
      if (!raw.getAutoCommit()) {
        raw.rollback();
        raw.setAutoCommit(true);
      }
      if (raw.isReadOnly()) {
        raw.setReadOnly(false);
      }
      try (Statement s = raw.createStatement()) {
        s.execute("DISCARD ALL");
      }
    } catch (SQLException | RuntimeException e) {
      closeQuietly(raw);
      return;
    }
    Connection extra = null;
    synchronized (this) {
      Deque<Idle> d = idle.computeIfAbsent(key, k -> new ArrayDeque<>());
      d.addFirst(new Idle(raw, clock.getAsLong()));
      if (d.size() > MAX_IDLE) {
        extra = d.pollLast().c;
      }
    }
    if (extra != null) {
      closeQuietly(extra);
    }
  }

  /** しばらく使わなかった接続を閉じる */
  void sweep() {
    long now = clock.getAsLong();
    java.util.List<Connection> old = new java.util.ArrayList<>();
    synchronized (this) {
      for (Iterator<Deque<Idle>> it = idle.values().iterator(); it.hasNext();) {
        Deque<Idle> d = it.next();
        d.removeIf(i -> {
          if (now - i.since > IDLE_MILLIS) {
            old.add(i.c);
            return true;
          }
          return false;
        });
        if (d.isEmpty()) {
          it.remove();
        }
      }
    }
    old.forEach(ConnectionPool::closeQuietly);
  }

  /** 接続先の、とっておいた接続を閉じる（接続先を変えた・消したとき）。prefix で始まる鍵のものすべて */
  void closeAll(String prefix) {
    java.util.List<Connection> old = new java.util.ArrayList<>();
    synchronized (this) {
      for (Iterator<Map.Entry<String, Deque<Idle>>> it = idle.entrySet().iterator(); it.hasNext();) {
        Map.Entry<String, Deque<Idle>> e = it.next();
        if (e.getKey().startsWith(prefix)) {
          e.getValue().forEach(i -> old.add(i.c));
          it.remove();
        }
      }
    }
    old.forEach(ConnectionPool::closeQuietly);
  }

  /** とっておいている数（テスト用） */
  synchronized int idleCount(String key) {
    Deque<Idle> d = idle.get(key);
    return d == null ? 0 : d.size();
  }

  private static void closeQuietly(Connection c) {
    try {
      c.close();
    } catch (SQLException | RuntimeException e) {
      // 閉じられなくても、使わない
    }
  }
}
