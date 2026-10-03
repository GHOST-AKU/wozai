package dev.ghost.wozai;

import java.io.IOException;
import dev.ghost.nearbyim.i18n.LocalizedIOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Exercises real file locks and migration with injected Windows environment paths. */
public final class DataLocationTests {
    private static int passed;
    private static final String PEER = "12345678-1234-1234-1234-123456789abc";
    private static final String MESSAGE = "abcdefab-1234-1234-1234-123456789abc";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wozai-data-location-test-");
        try {
            selection(root.resolve("selection"));
            migration(root.resolve("migration"));
            brandedUpgrade(root.resolve("branded-upgrade"));
            lockOnlyRetry(root.resolve("retry"));
            activeLegacy(root.resolve("active-legacy"));
            activeTarget(root.resolve("active-target"));
            alreadyPopulated(root.resolve("existing"));
            explicitOverride(root.resolve("override"));
            corruptAndBlocked(root.resolve("blocked"));
            symlinks(root.resolve("symlinks"));
            readOnly(root.resolve("readonly"));
            concurrentPreparation(root.resolve("concurrent"));
            System.out.println("DataLocationTests: " + passed + " checks passed");
        } finally { deleteTree(root); }
    }

    private static DataLocation.Selection packaged(Path install, Path local) throws IOException {
        Map<String, String> properties = Map.of("wozai.installDir", install.toString());
        Map<String, String> environment = Map.of("LOCALAPPDATA", local.toString());
        return DataLocation.select(properties::get, environment::get, true);
    }

    private static void selection(Path root) throws IOException {
        Path install = root.resolve("portable");
        Path launch = install.resolve("app").resolve("..");
        Path local = root.resolve("system-drive").resolve("Local");
        var chosen = packaged(launch, local);
        check(chosen.path().equals(install.resolve("data")), "Launcher root was not normalized");
        check(chosen.legacyPath().equals(local.resolve("WoZai")), "Legacy path was not retained");
        check(chosen.portable(), "Packaged launch did not select portable mode");
        check(!chosen.path().startsWith(Path.of("").toAbsolutePath().resolve("data")), "Portable storage used the working directory");

        var overridden = DataLocation.select(Map.of("wozai.dataDir", root.resolve("custom").toString(), "wozai.installDir", launch.toString())::get,
                environment -> { throw new AssertionError("An explicit override queried the legacy environment"); }, true);
        check(overridden.path().equals(root.resolve("custom")), "Explicit data override lost precedence");
        check(!overridden.portable() && overridden.legacyPath() == null, "Explicit override triggered migration");

        var developer = DataLocation.select(key -> null, Map.of("LOCALAPPDATA", local.toString())::get, true);
        check(developer.path().equals(local.resolve("WoZai")), "Unpackaged Windows default changed");
        var posix = DataLocation.select(Map.of("user.home", root.resolve("home").toString())::get, key -> null, false);
        check(posix.path().equals(root.resolve("home/.local/share/wozai")), "Unpackaged POSIX default changed");
        try { DataLocation.select(Map.of("wozai.installDir", launch.toString())::get, key -> null, true); throw new AssertionError("Missing legacy location was ignored"); }
        catch (LocalizedIOException expected) { check(expected.text.key.equals("dataEnvironmentMissing") && expected.text.arguments[0].equals("LOCALAPPDATA"), "Missing legacy environment had no structured error"); }
    }

    private static DesktopIdentity.Identity createProfile(Path root) throws Exception {
        Files.createDirectories(root);
        DesktopIdentity.Identity identity = DesktopIdentity.load(root.resolve("identity.properties"));
        try (DesktopStore store = new DesktopStore(root)) {
            store.peer(new DesktopStore.Peer(PEER, "朋友", "trusted-key", "192.168.1.2:3123"));
            store.save(PEER, new DesktopStore.Message(MESSAGE, "保存的消息\n🙂", 12345, true, "delivered"));
            store.draft(PEER, "继续编辑的草稿");
            store.setSetting("language", "en");
            store.setSetting("nickname", "原有昵称");
        }
        return identity;
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String relative = root.relativize(file).toString();
                if (!relative.equals("instance.lock")) result.put(relative, Files.readAllBytes(file));
            }
        }
        return result;
    }

    private static void sameBytes(Map<String, byte[]> expected, Map<String, byte[]> actual, String context) {
        check(expected.keySet().equals(actual.keySet()), context + " changed the file list");
        for (String name : expected.keySet()) check(Arrays.equals(expected.get(name), actual.get(name)), context + " changed " + name);
    }

    private static void migration(Path root) throws Exception {
        Path local = root.resolve("Local"), legacy = local.resolve("WoZai"), install = root.resolve("on-another-drive");
        DesktopIdentity.Identity original = createProfile(legacy);
        FileTime timestamp = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(legacy.resolve("settings.properties"), timestamp);
        Map<String, byte[]> before = snapshot(legacy);
        var location = packaged(install.resolve("app/.."), local);
        try (DataLocation.Prepared prepared = location.prepare()) {
            check(prepared.path().equals(install.resolve("data")), "Preparation changed the chosen directory");
            sameBytes(before, snapshot(prepared.path()), "Migration");
            sameBytes(before, snapshot(legacy), "Source preservation");
            check(Files.exists(legacy.resolve("instance.lock")), "Migration removed the legacy lock");
            check(Files.getLastModifiedTime(prepared.path().resolve("settings.properties")).equals(timestamp), "Migration changed persisted file timestamp");
            DesktopIdentity.Identity restored = DesktopIdentity.load(prepared.path().resolve("identity.properties"));
            check(restored.id().equals(original.id()), "Migration generated a new UUID");
            check(restored.signer().publicKey().equals(original.signer().publicKey()), "Migration changed the signing identity");
            try (DesktopStore store = new DesktopStore(prepared.path())) {
                check(store.peer(PEER).publicKey().equals("trusted-key"), "Migration lost trust");
                check(store.messages(PEER).get(0).body().equals("保存的消息\n🙂"), "Migration lost history");
                check(store.draft(PEER).equals("继续编辑的草稿"), "Migration lost drafts");
                check(store.setting("nickname", "missing").equals("原有昵称"), "Migration lost settings");
            }
        }
        assertNoStage(install);
        try (var prepared = location.prepare()) {
            check(DesktopIdentity.load(prepared.path().resolve("identity.properties")).id().equals(original.id()), "Restart replaced the migrated identity");
        }
    }

    private static void lockOnlyRetry(Path root) throws Exception {
        Path local = root.resolve("Local"), legacy = local.resolve("WoZai"), install = root.resolve("software"), target = install.resolve("data");
        var identity = createProfile(legacy);
        Files.createDirectories(target);
        Files.createFile(target.resolve("instance.lock"));
        try (var prepared = packaged(install, local).prepare()) {
            check(DesktopIdentity.load(prepared.path().resolve("identity.properties")).id().equals(identity.id()), "Leftover lock suppressed legacy migration");
        }
        assertNoStage(install);
    }

    private static void brandedUpgrade(Path root) throws Exception {
        Path local=root.resolve("Local"), oldPortable=root.resolve("WoZai/data"), install=root.resolve("NearbyIM");
        var oldIdentity=createProfile(oldPortable);
        createProfile(local.resolve("WoZai"));
        try(DesktopStore old=new DesktopStore(oldPortable)) { old.draft(PEER,"便携版最新草稿"); }
        Map<String,byte[]> portableBefore=snapshot(oldPortable), localBefore=snapshot(local.resolve("WoZai"));
        var location=packaged(install,local);
        check(location.legacyPath().equals(oldPortable),"Brand rename selected stale system-drive data instead of the old portable profile");
        try(var prepared=location.prepare()) {
            sameBytes(portableBefore,snapshot(prepared.path()),"Branded portable upgrade");
            sameBytes(portableBefore,snapshot(oldPortable),"Old portable source");
            sameBytes(localBefore,snapshot(local.resolve("WoZai")),"Older system-drive source");
            check(DesktopIdentity.load(prepared.path().resolve("identity.properties")).id().equals(oldIdentity.id()),"Brand rename created a new device identity");
            try(DesktopStore restored=new DesktopStore(prepared.path())) { check(restored.draft(PEER).equals("便携版最新草稿"),"Brand rename restored a stale draft"); }
        }
        check(packaged(root.resolve("WoZai"),local).path().equals(oldPortable),"Updating inside the old package directory changed its data path");
        assertNoStage(install);
    }

    private static void activeLegacy(Path root) throws Exception {
        Path local = root.resolve("Local"), legacy = local.resolve("WoZai"), install = root.resolve("software");
        createProfile(legacy);
        Map<String, byte[]> before = snapshot(legacy);
        try (DesktopStore active = new DesktopStore(legacy)) {
            fails(packaged(install, local), "legacyDataInUse", "Active legacy instance was migrated");
            check(!Files.exists(install.resolve("data/identity.properties")), "Failed migration generated an identity");
            sameBytes(before, snapshot(legacy), "Locked legacy source");
        }
        try (var prepared = packaged(install, local).prepare()) {
            check(Files.exists(prepared.path().resolve("identity.properties")), "Migration could not retry after legacy instance closed");
        }
        assertNoStage(install);
    }

    private static void activeTarget(Path root) throws Exception {
        Path local = root.resolve("Local"), install = root.resolve("software"), target = install.resolve("data");
        createProfile(local.resolve("WoZai"));
        Files.createDirectories(target);
        try (DesktopStore active = new DesktopStore(target)) {
            fails(packaged(install, local), "dataInUse", "Active lock-only target was replaced");
            check(!Files.exists(target.resolve("identity.properties")), "Active lock-only target generated identity");
        }
        try (var prepared = packaged(install, local).prepare()) { check(Files.exists(prepared.path().resolve("identity.properties")), "Lock-only target could not retry"); }
    }

    private static void alreadyPopulated(Path root) throws Exception {
        Path local = root.resolve("Local"), legacy = local.resolve("WoZai"), install = root.resolve("software"), target = install.resolve("data");
        var legacyIdentity = createProfile(legacy);
        var portableIdentity = createProfile(target);
        try (DesktopStore legacyStore = new DesktopStore(legacy)) { legacyStore.setSetting("source-only", "do not merge"); }
        Map<String, byte[]> before = snapshot(target);
        // An independent active legacy identity must not stop the selected portable identity.
        try (DesktopStore activeLegacy = new DesktopStore(legacy); var prepared = packaged(install, local).prepare()) {
            sameBytes(before, snapshot(target), "Existing portable profile");
            check(!portableIdentity.id().equals(legacyIdentity.id()), "Test did not create distinct identities");
            check(DesktopIdentity.load(prepared.path().resolve("identity.properties")).id().equals(portableIdentity.id()), "Portable identity was replaced by the legacy identity");
            try (DesktopStore store = new DesktopStore(target)) { check(store.setting("source-only", "absent").equals("absent"), "Independent profiles were merged"); }
        }
    }

    private static void explicitOverride(Path root) throws Exception {
        Path local = root.resolve("Local"), legacy = local.resolve("WoZai"), install = root.resolve("software"), override = root.resolve("test-data");
        createProfile(legacy);
        var selection = DataLocation.select(Map.of("wozai.dataDir", override.toString(), "wozai.installDir", install.toString())::get,
                Map.of("LOCALAPPDATA", local.toString())::get, true);
        try (DesktopStore activeLegacy = new DesktopStore(legacy); var prepared = selection.prepare()) {
            check(prepared.path().equals(override), "Explicit override was switched");
            check(!Files.exists(override.resolve("identity.properties")), "Override unexpectedly migrated a legacy identity");
            try (DesktopStore store = new DesktopStore(override)) {
                var identity = DesktopIdentity.load(override.resolve("identity.properties"));
                check(!identity.id().equals(DesktopIdentity.load(legacy.resolve("identity.properties")).id()), "Explicit override copied legacy identity");
            }
        }
        check(!Files.exists(install.resolve("data")), "Explicit override created portable data as well");
    }

    private static void corruptAndBlocked(Path root) throws Exception {
        Path local = root.resolve("Local"), legacy = local.resolve("WoZai"), install = root.resolve("software"), target = install.resolve("data");
        createProfile(legacy);
        Files.createDirectories(target);
        Files.writeString(target.resolve("settings.properties"), "language=en\n");
        fails(packaged(install, local), "dataIdentityMissing", "Populated target without identity was overwritten");
        check(Files.readString(target.resolve("settings.properties")).equals("language=en\n"), "Corrupt portable target was modified");
        check(!Files.exists(target.resolve("identity.properties")), "Corrupt portable target generated identity");

        Path blockedInstall = root.resolve("blocked-software");
        Files.writeString(blockedInstall, "file barrier");
        fails(packaged(blockedInstall, local), "dataPathNotDirectory", "Non-directory installation root was accepted");
        check(Files.readString(blockedInstall).equals("file barrier"), "Blocked install root was replaced");

        Path malformedInstall = root.resolve("malformed-software"), malformedTarget = malformedInstall.resolve("data");
        createProfile(malformedTarget);
        deleteTree(malformedTarget.resolve("drafts"));
        Files.writeString(malformedTarget.resolve("drafts"), "bad directory shape");
        fails(packaged(malformedInstall, local), "dataPathNotDirectory", "File in place of drafts directory was accepted");

        Path badLocal = root.resolve("bad-Local"), badLegacy = badLocal.resolve("WoZai"), badInstall = root.resolve("bad-source-software");
        createProfile(badLegacy);
        byte[] oversized = new byte[65537]; Arrays.fill(oversized, (byte) 'a');
        Files.write(badLegacy.resolve("settings.properties"), oversized);
        fails(packaged(badInstall, badLocal), "dataPreparationFailed", "Corrupt legacy file was migrated");
        check(!Files.exists(badInstall.resolve("data/identity.properties")), "Failed migration left a partial identity");
        check(Arrays.equals(Files.readAllBytes(badLegacy.resolve("settings.properties")), oversized), "Failed migration changed legacy bytes");
        assertNoStage(badInstall);
    }

    private static void symlinks(Path root) throws Exception {
        Path local = root.resolve("Local"), legacy = local.resolve("WoZai"), install = root.resolve("software"), outside = root.resolve("outside");
        createProfile(legacy);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "must stay outside");
        try { Files.createSymbolicLink(legacy.resolve("linked"), outside); }
        catch (UnsupportedOperationException | FileSystemException unavailable) { return; }
        fails(packaged(install, local), "dataUnsafeEntry", "Symlink in legacy data was followed");
        check(!Files.exists(install.resolve("data/identity.properties")), "Unsafe legacy tree left a partial migration");
        check(Files.readString(outside.resolve("keep.txt")).equals("must stay outside"), "Migration modified a symlink destination");
        Files.delete(legacy.resolve("linked"));

        Path linkedInstall = root.resolve("linked-software");
        Files.createSymbolicLink(linkedInstall, outside);
        fails(packaged(linkedInstall, local), "dataUnsafeEntry", "Symlink installation ancestor was followed");
        check(!Files.exists(outside.resolve("data")), "Symlink installation created outside data");

        Path targetInstall = root.resolve("target-software"); Files.createDirectories(targetInstall);
        Files.createSymbolicLink(targetInstall.resolve("data"), outside);
        fails(packaged(targetInstall, local), "dataUnsafeEntry", "Symlink target was followed");
        check(!Files.exists(outside.resolve("identity.properties")), "Symlink target generated outside identity");

        Path lockInstall = root.resolve("lock-software"), lockTarget = lockInstall.resolve("data");
        Files.createDirectories(lockTarget);
        Files.createSymbolicLink(lockTarget.resolve("instance.lock"), outside.resolve("keep.txt"));
        fails(packaged(lockInstall, local), "dataNotRegular", "Symlink instance lock was followed");
        check(Files.readString(outside.resolve("keep.txt")).equals("must stay outside"), "Lock handling modified symlink destination");
    }

    private static void readOnly(Path root) throws Exception {
        Files.createDirectories(root);
        if (!Files.getFileStore(root).supportsFileAttributeView("posix")) return;
        Path local = root.resolve("Local"), install = root.resolve("software");
        createProfile(local.resolve("WoZai"));
        Files.createDirectories(install);
        Files.setPosixFilePermissions(install, PosixFilePermissions.fromString("r-x------"));
        try {
            fails(packaged(install, local), "dataReadOnly", "Read-only install root was accepted");
            check(!Files.exists(install.resolve("data")), "Read-only install root created data");
        } finally { Files.setPosixFilePermissions(install, PosixFilePermissions.fromString("rwx------")); }

        Path target = install.resolve("data"); Files.createDirectories(target);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r-x------"));
        try {
            fails(packaged(install, local), "dataReadOnly", "Read-only portable target was accepted");
            check(!Files.exists(target.resolve("identity.properties")), "Read-only target generated identity");
        } finally { Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------")); }

        Path override = root.resolve("override"); Files.createDirectories(override);
        Files.setPosixFilePermissions(override, PosixFilePermissions.fromString("r-x------"));
        try {
            fails(new DataLocation.Selection(override, null, false), "dataReadOnly", "Read-only explicit override was accepted");
            check(!Files.exists(override.resolve("identity.properties")), "Read-only override generated identity");
        } finally { Files.setPosixFilePermissions(override, PosixFilePermissions.fromString("rwx------")); }
    }

    private static void concurrentPreparation(Path root) throws Exception {
        Path local = root.resolve("Local"), install = root.resolve("software");
        var selection = packaged(install, local);
        try (var prepared = selection.prepare()) {
            fails(selection, "dataPreparingAlready", "Concurrent startup acquired migration guard");
            try (DesktopStore store = new DesktopStore(prepared.path())) {
                DesktopIdentity.load(prepared.path().resolve("identity.properties"));
            }
        }
        try (var prepared = selection.prepare()) { check(Files.exists(prepared.path().resolve("identity.properties")), "Guard was not released after preparation"); }
    }

    private static void fails(DataLocation.Selection location, String expected, String failure) throws Exception {
        try (var ignored = location.prepare()) { throw new AssertionError(failure); }
        catch (LocalizedIOException e) { check(e.text.key.equals(expected), failure + ": incorrect error key " + e.text.key); }
    }

    private static void assertNoStage(Path parent) throws IOException {
        try (var entries = Files.list(parent)) { check(entries.noneMatch(p -> p.getFileName().toString().startsWith(".wozai-migrate-")), "Migration left a staging directory"); }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
