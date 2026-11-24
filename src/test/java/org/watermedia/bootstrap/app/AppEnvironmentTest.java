package org.watermedia.bootstrap.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Verifies the environment the launcher and the app share: a jar in a mods folder runs from the root of its Minecraft
 * instance, classes from a directory keep the working directory, any other jar keeps it while it is writable, and the
 * app resolves the same environment once it runs from the home the launcher chose.
 */
@DisplayName("AppEnvironment")
public class AppEnvironmentTest {
    @TempDir Path directory;

    @Test
    @DisplayName("A jar inside a mods folder is a Minecraft instance and runs from its root, whatever the working directory and the letter case")
    void minecraft() throws IOException {
        final Path cwd = Files.createDirectories(this.directory.resolve("desktop"));
        // ONE INSTANCE PER SPELLING: WINDOWS AND MACOS FOLD THE CASE OF A FOLDER NAME
        final List<String> names = List.of("mods", "Mods");
        for (int i = 0; i < names.size(); i++) {
            final Path instance = this.directory.resolve("instance" + i);
            final Path app = jar(instance.resolve(names.get(i)));
            assertEquals(new AppEnvironment(AppEnvironment.Type.MINECRAFT, instance, app), AppEnvironment.of(cwd, app));
        }
    }

    @Test
    @DisplayName("Classes from a directory are a development run in its working directory, even below a mods folder")
    void development() throws IOException {
        final Path cwd = Files.createDirectories(this.directory.resolve("run"));
        final Path classes = Files.createDirectories(this.directory.resolve("mods/classes"));
        assertEquals(new AppEnvironment(AppEnvironment.Type.DEVELOPMENT, cwd, classes), AppEnvironment.of(cwd, classes));
        assertEquals(new AppEnvironment(AppEnvironment.Type.DEVELOPMENT, cwd, cwd.resolveSibling("mods")), AppEnvironment.of(cwd, cwd.resolveSibling("mods")),
                "a classes directory named mods is no instance");
    }

    @Test
    @DisplayName("A jar outside a mods folder is standalone in a writable working directory, also below a mods folder")
    void standalone() throws IOException {
        final Path cwd = Files.createDirectories(this.directory.resolve("desktop"));
        for (final String folder: List.of("downloads", "mods/old")) {
            final Path app = jar(this.directory.resolve(folder));
            assertEquals(new AppEnvironment(AppEnvironment.Type.STANDALONE, cwd, app), AppEnvironment.of(cwd, app));
        }
    }

    @Test
    @DisplayName("A read-only working directory hands a standalone jar to its own folder, unless that one is read-only too")
    void readOnlyWorkingDirectory() throws IOException {
        final Path cwd = Files.createDirectories(this.directory.resolve("system"));
        final Path app = jar(this.directory.resolve("downloads"));
        // WINDOWS DENIES THE OWNER NEW FILES AND FOLDERS THROUGH THE ACL, THE OTHER SYSTEMS THROUGH THE PERMISSION BITS
        final AclFileAttributeView acl = Files.getFileAttributeView(cwd, AclFileAttributeView.class);
        final List<AclEntry> granted = acl == null ? null : acl.getAcl();
        try {
            if (acl == null) Files.setPosixFilePermissions(cwd, PosixFilePermissions.fromString("r-xr-xr-x"));
            else {
                final List<AclEntry> denied = new ArrayList<>(granted);
                denied.add(0, AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(acl.getOwner())
                        .setPermissions(AclEntryPermission.ADD_FILE, AclEntryPermission.ADD_SUBDIRECTORY).build());
                acl.setAcl(denied);
            }
            // A SUPERUSER WRITES ANYWHERE, SO THERE THE CASE HAS NOTHING TO SHOW
            assumeFalse(Files.isWritable(cwd), "this user writes into read-only directories");
            assertEquals(new AppEnvironment(AppEnvironment.Type.STANDALONE, app.getParent(), app), AppEnvironment.of(cwd, app));
            final Path stuck = cwd.resolve("watermedia.jar");
            assertEquals(new AppEnvironment(AppEnvironment.Type.STANDALONE, cwd, stuck), AppEnvironment.of(cwd, stuck), "nowhere to write keeps the working directory");
        } finally {
            // THE TEMPORARY DIRECTORY CLEANUP NEEDS ITS RIGHTS BACK
            if (acl == null) Files.setPosixFilePermissions(cwd, PosixFilePermissions.fromString("rwxr-xr-x"));
            else acl.setAcl(granted);
        }
    }

    @Test
    @DisplayName("The app started in the home the launcher chose resolves that same environment")
    void sameAnswerFromTheHome() throws IOException {
        final Path cwd = Files.createDirectories(this.directory.resolve("desktop"));
        for (final String folder: List.of("instance/mods", "downloads")) {
            final AppEnvironment launcher = AppEnvironment.of(cwd, jar(this.directory.resolve(folder)));
            assertEquals(launcher, AppEnvironment.of(launcher.home(), launcher.app()), folder);
        }
    }

    @Test
    @DisplayName("This test JVM is a development run in its working directory")
    void current() {
        assertEquals(AppEnvironment.Type.DEVELOPMENT, AppEnvironment.CURRENT.type());
        assertEquals(Path.of("").toAbsolutePath(), AppEnvironment.CURRENT.home());
        assertTrue(Files.isDirectory(AppEnvironment.CURRENT.app()), "the classes directory of the build");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static Path jar(final Path folder) throws IOException {
        return Files.createFile(Files.createDirectories(folder).resolve("watermedia.jar"));
    }
}
