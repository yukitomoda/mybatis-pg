package mybatispg;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ディレクトリをサブディレクトリも含めて監視し、mapper XML や設定ファイルが変わったら通知する。
 * IDE の保存では短時間に複数のイベントが出るため、静かになってから 1 回だけ通知する。
 */
public final class FileWatcher implements Closeable {

  private static final long QUIET_MILLIS = 300;
  private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(".git", "node_modules", "target", "build"));

  private final WatchService service;
  private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
  private final Runnable onChange;
  private final Thread thread;
  /** 1 つのファイルだけを見張るとき（JAR など）のそのファイル。null ならディレクトリの mapper XML や設定ファイル */
  private final Path singleFile;

  private FileWatcher(List<Path> roots, Path singleFile, Runnable onChange) throws IOException {
    this.service = FileSystems.getDefault().newWatchService();
    this.onChange = onChange;
    this.singleFile = singleFile;
    if (singleFile != null) {
      // ファイルのあるディレクトリだけを見る（サブディレクトリは見ない）
      Path dir = singleFile.getParent();
      keys.put(dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), dir);
    }
    for (Path root : roots) {
      if (Files.isDirectory(root)) {
        registerTree(root);
      }
    }
    this.thread = new Thread(this::loop, "file-watcher");
    thread.setDaemon(true);
  }

  public static FileWatcher start(List<Path> roots, Runnable onChange) throws IOException {
    FileWatcher w = new FileWatcher(roots, null, onChange);
    w.thread.start();
    return w;
  }

  /** 1 つのファイル（JAR など）が作り直されたら通知する */
  public static FileWatcher startFile(Path file, Runnable onChange) throws IOException {
    FileWatcher w = new FileWatcher(java.util.Collections.<Path>emptyList(), file.toAbsolutePath().normalize(), onChange);
    w.thread.start();
    return w;
  }

  @Override
  public void close() throws IOException {
    thread.interrupt();
    service.close();
  }

  private void registerTree(Path root) throws IOException {
    Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        keys.put(dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private void loop() {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        boolean relevant = handle(service.take());
        WatchKey next;
        while ((next = service.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS)) != null) {
          relevant |= handle(next);
        }
        if (relevant) {
          try {
            onChange.run();
          } catch (RuntimeException e) {
            System.err.println("再読み込みに失敗しました: " + Errors.describe(e));
          }
        }
      }
    } catch (InterruptedException | ClosedWatchServiceException e) {
      // 終了
    }
  }

  /** 対象のファイルに関わるイベントがあれば true */
  private boolean handle(WatchKey key) {
    Path dir = keys.get(key);
    boolean relevant = false;
    for (WatchEvent<?> event : key.pollEvents()) {
      if (event.kind() == OVERFLOW) {
        relevant = true;
        continue;
      }
      if (dir == null) {
        continue;
      }
      Path child = dir.resolve((Path) event.context());
      if (singleFile != null) {
        relevant |= child.equals(singleFile);
        continue;
      }
      if (event.kind() == ENTRY_CREATE && Files.isDirectory(child)) {
        // 新しいディレクトリにも mapper XML が置かれうる
        try {
          registerTree(child);
        } catch (IOException e) {
          System.err.println(child + " を監視できません: " + e.getMessage());
        }
        relevant = true;
      } else if (isTarget(child)) {
        relevant = true;
      }
    }
    if (!key.reset()) {
      keys.remove(key);
      // 監視中のディレクトリが消えた場合、その中のファイルも消えている
      relevant = true;
    }
    return relevant;
  }

  private static boolean isTarget(Path file) {
    String name = file.getFileName().toString();
    return name.endsWith(".xml") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties");
  }
}
