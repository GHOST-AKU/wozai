package dev.ghost.wozai;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.function.Function;

/** Chooses one data directory and prepares it without replacing an existing device identity. */
public final class DataLocation {
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final String INSTANCE_LOCK = "instance.lock";

    private DataLocation() { }

    public static Selection select() {
        return select(System::getProperty, System::getenv, DesktopIdentity.windows());
    }

    // Inject both lookups so Windows launcher paths can be tested on other platforms.
    static Selection select(Function<String, String> properties, Function<String, String> environment, boolean windows) {
        String override = properties.apply("wozai.dataDir");
        if (present(override)) return new Selection(absolute(Path.of(override)), null, false);
        Path legacy = developerPath(properties, environment, windows);
        String install = properties.apply("wozai.installDir");
        if (present(install)) {
            Path root=absolute(Path.of(install));
            // NearbyIM replaces the old WoZai package name. Prefer its current portable
            // profile over the older copy in LOCALAPPDATA, while retaining both sources.
            if(root.getParent()!=null) {
                Path previous=root.getParent().resolve("WoZai/data");
                if(exists(previous)) {
                    try { if(!Files.isDirectory(previous,NOFOLLOW)||!noUserData(previous))legacy=previous; }
                    catch(IOException e) { throw new IllegalStateException("无法检查旧版便携数据目录："+previous,e); }
                }
            }
            return new Selection(root.resolve("data"),legacy,true);
        }
        return new Selection(legacy, null, false);
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static Path absolute(Path path) { return path.toAbsolutePath().normalize(); }

    private static Path developerPath(Function<String, String> properties, Function<String, String> environment, boolean windows) {
        if (windows) {
            String local = environment.apply("LOCALAPPDATA");
            if (!present(local)) throw new IllegalStateException("未设置 LOCALAPPDATA，无法确定原有数据目录。");
            return absolute(Path.of(local).resolve("WoZai"));
        }
        String home = properties.apply("user.home");
        if (!present(home)) throw new IllegalStateException("未设置用户主目录，无法确定数据目录。");
        return absolute(Path.of(home).resolve(".local").resolve("share").resolve("wozai"));
    }

    public record Selection(Path path, Path legacyPath, boolean portable) {
        public Selection {
            path = absolute(path);
            if (legacyPath != null) legacyPath = absolute(legacyPath);
        }

        /** Keep this resource open until DesktopStore has acquired its instance lock. */
        public Prepared prepare() throws IOException {
            Path parent = path.getParent();
            if (parent == null) throw problem("数据目录不能是文件系统根目录", path);
            checkAncestors(path);
            Files.createDirectories(parent);
            checkAncestors(parent);
            writableDirectory(parent);
            Path guardPath = parent.resolve("." + path.getFileName() + "-startup.lock");
            HeldLock guard = HeldLock.acquire(guardPath, "另一个“我在”进程正在准备此数据目录");
            try {
                boolean empty = inspectTarget(path);
                if (empty && portable && legacyPath != null && !path.equals(legacyPath) && exists(legacyPath)) {
                    if (path.startsWith(legacyPath) || legacyPath.startsWith(path))
                        throw problem("便携数据目录与旧数据目录重叠，无法安全迁移", path);
                    migrate(legacyPath, path);
                }
                if (!exists(path)) Files.createDirectory(path);
                AtomicFiles.privatePermissions(path, true);
                validateTree(path, true);
                return new Prepared(path, guard);
            } catch (IOException | RuntimeException e) {
                try { guard.close(); } catch (IOException close) { e.addSuppressed(close); }
                throw new IOException("无法安全准备数据目录：" + path + "\n" + e.getMessage(), e);
            }
        }
    }

    public static final class Prepared implements AutoCloseable {
        private final Path path;
        private final HeldLock guard;
        private Prepared(Path path, HeldLock guard) { this.path = path; this.guard = guard; }
        public Path path() { return path; }
        @Override public void close() throws IOException { guard.close(); }
    }

    /** Empty and lock-only directories are retries, not evidence of a second identity. */
    private static boolean inspectTarget(Path target) throws IOException {
        if (!exists(target)) return true;
        directory(target);
        writableFile(target);
        try (HeldLock ignored = HeldLock.acquire(target.resolve(INSTANCE_LOCK), "此数据目录正在被另一个“我在”进程使用")) {
            validateTree(target, true);
            boolean empty = noUserData(target);
            if (!empty) requireIdentity(target);
            return empty;
        }
    }

    private static void migrate(Path source, Path target) throws IOException {
        checkAncestors(source);
        directory(source);
        try (HeldLock ignored = HeldLock.acquire(source.resolve(INSTANCE_LOCK), "旧版“我在”仍在运行，请先关闭后再迁移数据")) {
            validateTree(source, false);
            if (noUserData(source)) return;
            requireIdentity(source);
            Path stage = Files.createTempDirectory(target.getParent(), ".wozai-migrate-");
            try {
                AtomicFiles.privatePermissions(stage, true);
                copyTree(source, stage);
                validateTree(stage, true);
                // Both old and new launchers using this target are excluded by the startup lock.
                if (!inspectTarget(target)) throw problem("便携数据目录已经有数据，迁移已取消", target);
                if (exists(target)) {
                    Files.deleteIfExists(target.resolve(INSTANCE_LOCK));
                    Files.delete(target);
                }
                try { Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) {
                    throw new IOException("此磁盘不支持安全的原子目录迁移；旧数据已保留，未创建新设备身份。", e);
                }
            } finally {
                if (exists(stage)) removeStage(stage);
            }
        }
    }

    private static void copyTree(Path source, Path stage) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                ordinary(dir, attrs);
                Path destination = stage.resolve(source.relativize(dir));
                if (!dir.equals(source)) Files.createDirectory(destination);
                AtomicFiles.privatePermissions(destination, true);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                ordinary(file, attrs);
                if (file.equals(source.resolve(INSTANCE_LOCK))) return FileVisitResult.CONTINUE;
                Path destination = stage.resolve(source.relativize(file));
                Files.copy(file, destination, NOFOLLOW, StandardCopyOption.COPY_ATTRIBUTES);
                ordinary(destination, Files.readAttributes(destination, BasicFileAttributes.class, NOFOLLOW));
                AtomicFiles.privatePermissions(destination, false);
                // Flush the complete copy before it becomes the application's data directory.
                try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE, NOFOLLOW)) { channel.force(true); }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class, NOFOLLOW);
                Files.getFileAttributeView(stage.resolve(source.relativize(dir)), BasicFileAttributeView.class, NOFOLLOW)
                        .setTimes(attrs.lastModifiedTime(), attrs.lastAccessTime(), attrs.creationTime());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean noUserData(Path root) throws IOException {
        try (var entries = Files.list(root)) { return entries.allMatch(p -> p.getFileName().toString().equals(INSTANCE_LOCK)); }
    }

    private static void requireIdentity(Path root) throws IOException {
        if (!exists(root.resolve("identity.properties")))
            throw problem("数据目录已有文件但缺少设备身份；为保护原有记录，未创建新身份", root);
    }

    private static void validateTree(Path root, boolean writable) throws IOException {
        checkAncestors(root);
        directory(root);
        for (String name : Set.of("identity.properties", "settings.properties", INSTANCE_LOCK)) {
            Path file = root.resolve(name);
            if (exists(file) && !Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW).isRegularFile())
                throw problem("数据文件不是普通文件", file);
        }
        for (String name : Set.of("peers", "messages", "drafts")) {
            Path dir = root.resolve(name);
            if (exists(dir)) directory(dir);
        }
        flatFiles(root.resolve("peers"));
        flatFiles(root.resolve("drafts"));
        Path messages = root.resolve("messages");
        if (exists(messages)) {
            try (var children = Files.list(messages)) {
                for (Path conversation : children.toList()) { directory(conversation); flatFiles(conversation); }
            }
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                ordinary(dir, attrs);
                if (writable) writableDirectory(dir);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                ordinary(file, attrs);
                if (file.getFileName().toString().endsWith(".properties")) AtomicFiles.read(file);
                if (writable && !file.getFileName().toString().equals(INSTANCE_LOCK)) writableFile(file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void flatFiles(Path directory) throws IOException {
        if (!exists(directory)) return;
        try (var entries = Files.list(directory)) {
            for (Path file : entries.toList()) {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW);
                ordinary(file, attrs);
                if (!attrs.isRegularFile()) throw problem("记录文件被目录占用", file);
            }
        }
    }

    private static void ordinary(Path path, BasicFileAttributes attrs) throws IOException {
        if (attrs.isSymbolicLink() || attrs.isOther() || (!attrs.isDirectory() && !attrs.isRegularFile()))
            throw problem("数据目录包含符号链接或特殊文件，无法安全使用", path);
    }

    private static void directory(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        ordinary(path, attrs);
        if (!attrs.isDirectory()) throw problem("数据目录被普通文件占用", path);
    }

    private static void checkAncestors(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path component : path) {
            current = current.resolve(component);
            if (!exists(current)) continue;
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, NOFOLLOW);
            ordinary(current, attrs);
            if (!attrs.isDirectory()) throw problem("目录路径被普通文件占用", current);
        }
    }

    private static void writableDirectory(Path path) throws IOException {
        writableFile(path);
        Path probe = Files.createTempFile(path, ".wozai-write-", ".tmp");
        try {
            AtomicFiles.privatePermissions(probe, false);
            try (FileChannel channel = FileChannel.open(probe, StandardOpenOption.WRITE, NOFOLLOW)) {
                channel.write(ByteBuffer.wrap(new byte[]{0})); channel.force(true);
            }
        } finally { Files.deleteIfExists(probe); }
    }

    private static void writableFile(Path path) throws IOException {
        if (!Files.isWritable(path)) throw problem("数据目录或文件不可写，请将软件放到可读写的位置", path);
        // Also respect an explicitly read-only POSIX directory when tests run as an administrator.
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, NOFOLLOW);
            if (permissions.stream().noneMatch(p -> p == PosixFilePermission.OWNER_WRITE || p == PosixFilePermission.GROUP_WRITE || p == PosixFilePermission.OTHERS_WRITE))
                throw problem("数据目录或文件设置为只读，请将软件放到可读写的位置", path);
        }
    }

    private static boolean exists(Path path) { return Files.exists(path, NOFOLLOW); }
    private static IOException problem(String message, Path path) { return new IOException(message + "：" + path); }

    private static void removeStage(Path stage) throws IOException {
        Files.walkFileTree(stage, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class HeldLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private HeldLock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        static HeldLock acquire(Path path, String busy) throws IOException {
            if (exists(path) && !Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW).isRegularFile())
                throw problem("数据锁不是普通文件", path);
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW);
            try {
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException e) { throw new IOException(busy + "。", e); }
                if (lock == null) throw new IOException(busy + "。");
                AtomicFiles.privatePermissions(path, false);
                return new HeldLock(channel, lock);
            } catch (IOException | RuntimeException e) {
                try { channel.close(); } catch (IOException close) { e.addSuppressed(close); }
                throw e;
            }
        }
        @Override public void close() throws IOException {
            try { if (lock.isValid()) lock.release(); }
            finally { channel.close(); }
        }
    }
}
