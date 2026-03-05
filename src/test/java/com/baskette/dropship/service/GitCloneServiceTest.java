package com.baskette.dropship.service;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitCloneServiceTest {

    private GitCloneService gitCloneService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        gitCloneService = new GitCloneService();
    }

    @Test
    void cloneAndZipClonesRepoAndProducesZip() throws Exception {
        // Create a local bare repo as a test fixture
        Path bareRepo = createLocalBareRepo("hello.txt", "Hello World");

        byte[] zipped = gitCloneService.cloneAndZip(
                bareRepo.toUri().toString(), null, null).block();

        assertThat(zipped).isNotNull();
        assertThat(zipped.length).isGreaterThan(0);

        Set<String> entries = zipEntryNames(zipped);
        assertThat(entries).contains("hello.txt");
        assertThat(entries).noneMatch(e -> e.startsWith(".git/") || e.equals(".git"));
    }

    @Test
    void cloneAndZipCheckoutsBranch() throws Exception {
        Path bareRepo = tempDir.resolve("branch-test.git");
        try (Git git = Git.init().setBare(false).setDirectory(tempDir.resolve("work").toFile()).call()) {
            Files.writeString(tempDir.resolve("work/main.txt"), "main content");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").call();

            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            Files.writeString(tempDir.resolve("work/feature.txt"), "feature content");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("feature commit").call();

            // Clone to bare repo so we can use it as remote
            Git.cloneRepository()
                    .setURI(tempDir.resolve("work").toUri().toString())
                    .setDirectory(bareRepo.toFile())
                    .setBare(true)
                    .call()
                    .close();
        }

        byte[] zipped = gitCloneService.cloneAndZip(
                bareRepo.toUri().toString(), "feature", null).block();

        Set<String> entries = zipEntryNames(zipped);
        assertThat(entries).contains("feature.txt");
        assertThat(entries).contains("main.txt");
    }

    @Test
    void cloneAndZipScopesToSubdirectory() throws Exception {
        Path bareRepo = tempDir.resolve("subdir-test.git");
        try (Git git = Git.init().setBare(false).setDirectory(tempDir.resolve("work2").toFile()).call()) {
            Path subDir = tempDir.resolve("work2/src/main");
            Files.createDirectories(subDir);
            Files.writeString(subDir.resolve("App.java"), "class App {}");
            Files.writeString(tempDir.resolve("work2/README.md"), "readme");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").call();

            Git.cloneRepository()
                    .setURI(tempDir.resolve("work2").toUri().toString())
                    .setDirectory(bareRepo.toFile())
                    .setBare(true)
                    .call()
                    .close();
        }

        byte[] zipped = gitCloneService.cloneAndZip(
                bareRepo.toUri().toString(), null, "src/main").block();

        Set<String> entries = zipEntryNames(zipped);
        assertThat(entries).contains("App.java");
        assertThat(entries).doesNotContain("README.md");
    }

    @Test
    void cloneAndZipThrowsForInvalidSubdirectory() throws Exception {
        Path bareRepo = createLocalBareRepo("file.txt", "content");

        assertThatThrownBy(() -> gitCloneService.cloneAndZip(
                bareRepo.toUri().toString(), null, "nonexistent").block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Subdirectory does not exist in repo");
    }

    @Test
    void cloneAndZipThrowsForInvalidUrl() {
        assertThatThrownBy(() -> gitCloneService.cloneAndZip(
                "file:///nonexistent/path/no-such-repo.git", null, null).block())
                .isNotNull();
    }

    @Test
    void cloneAndZipCleansUpTempDirectory() throws Exception {
        Path bareRepo = createLocalBareRepo("cleanup.txt", "content");

        // Count temp dirs before
        long tempDirsBefore = countDropshipTempDirs();

        gitCloneService.cloneAndZip(bareRepo.toUri().toString(), null, null).block();

        // After completion, the temp dir should be cleaned up
        long tempDirsAfter = countDropshipTempDirs();
        assertThat(tempDirsAfter).isLessThanOrEqualTo(tempDirsBefore);
    }

    @Test
    void isJavaProjectDetectsMaven() throws Exception {
        Path dir = tempDir.resolve("maven-proj");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        assertThat(gitCloneService.isJavaProject(dir)).isTrue();
    }

    @Test
    void isJavaProjectDetectsGradle() throws Exception {
        Path dir = tempDir.resolve("gradle-proj");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("build.gradle"), "apply plugin: 'java'");
        assertThat(gitCloneService.isJavaProject(dir)).isTrue();
    }

    @Test
    void isJavaProjectDetectsGradleKts() throws Exception {
        Path dir = tempDir.resolve("gradle-kts-proj");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { java }");
        assertThat(gitCloneService.isJavaProject(dir)).isTrue();
    }

    @Test
    void isJavaProjectReturnsFalseForNonJava() throws Exception {
        Path dir = tempDir.resolve("node-proj");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("package.json"), "{}");
        assertThat(gitCloneService.isJavaProject(dir)).isFalse();
    }

    @Test
    void findOutputArtifactFindsMavenJar() throws Exception {
        Path dir = tempDir.resolve("maven-find");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Path target = dir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("app-1.0.jar"), "jar-content");
        Files.writeString(target.resolve("app-1.0-sources.jar"), "sources");
        Files.writeString(target.resolve("app-1.0-javadoc.jar"), "javadoc");
        Files.writeString(target.resolve("app-1.0.jar.original"), "original");

        Path artifact = gitCloneService.findOutputArtifact(dir);
        assertThat(artifact.getFileName().toString()).isEqualTo("app-1.0.jar");
    }

    @Test
    void findOutputArtifactFindsGradleJar() throws Exception {
        Path dir = tempDir.resolve("gradle-find");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("build.gradle"), "apply plugin: 'java'");
        Path buildLibs = dir.resolve("build/libs");
        Files.createDirectories(buildLibs);
        Files.writeString(buildLibs.resolve("app-1.0.jar"), "jar-content");
        Files.writeString(buildLibs.resolve("app-1.0-plain.jar"), "plain");
        Files.writeString(buildLibs.resolve("app-1.0-sources.jar"), "sources");

        Path artifact = gitCloneService.findOutputArtifact(dir);
        assertThat(artifact.getFileName().toString()).isEqualTo("app-1.0.jar");
    }

    @Test
    void findOutputArtifactThrowsWhenNoArtifact() throws Exception {
        Path dir = tempDir.resolve("no-artifact");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.createDirectories(dir.resolve("target"));

        assertThatThrownBy(() -> gitCloneService.findOutputArtifact(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No JAR artifact found");
    }

    @Test
    void findOutputArtifactThrowsWhenNoBuildDir() throws Exception {
        Path dir = tempDir.resolve("no-build-dir");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        assertThatThrownBy(() -> gitCloneService.findOutputArtifact(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Build output directory not found");
    }

    @Test
    void zipSingleFileCreatesZipWithOneEntry() throws Exception {
        Path jarFile = tempDir.resolve("app.jar");
        Files.writeString(jarFile, "fake-jar-content");

        byte[] zipped = gitCloneService.zipSingleFile(jarFile);
        Set<String> entries = zipEntryNames(zipped);
        assertThat(entries).containsExactly("app.jar");
    }

    @Test
    void nonJavaProjectZipsSourceAsIs() throws Exception {
        Path bareRepo = tempDir.resolve("node-repo.git");
        Path workDir = tempDir.resolve("node-work");
        try (Git git = Git.init().setBare(false).setDirectory(workDir.toFile()).call()) {
            Files.writeString(workDir.resolve("package.json"), "{}");
            Files.writeString(workDir.resolve("index.js"), "console.log('hi')");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").call();

            Git.cloneRepository()
                    .setURI(workDir.toUri().toString())
                    .setDirectory(bareRepo.toFile())
                    .setBare(true)
                    .call()
                    .close();
        }

        byte[] zipped = gitCloneService.cloneAndZip(
                bareRepo.toUri().toString(), null, null).block();

        Set<String> entries = zipEntryNames(zipped);
        assertThat(entries).contains("package.json", "index.js");
    }

    @Test
    void zipDirectoryExcludesGitMetadata() throws Exception {
        Path dir = tempDir.resolve("ziptest");
        Files.createDirectories(dir.resolve(".git/objects"));
        Files.writeString(dir.resolve(".git/config"), "git config");
        Files.writeString(dir.resolve("source.java"), "class Source {}");

        byte[] zipped = gitCloneService.zipDirectory(dir);

        Set<String> entries = zipEntryNames(zipped);
        assertThat(entries).contains("source.java");
        assertThat(entries).noneMatch(e -> e.contains(".git"));
    }

    private Path createLocalBareRepo(String fileName, String content) throws Exception {
        Path workDir = tempDir.resolve("source-work");
        Path bareDir = tempDir.resolve("source.git");

        try (Git git = Git.init().setBare(false).setDirectory(workDir.toFile()).call()) {
            Files.writeString(workDir.resolve(fileName), content);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial commit").call();
        }

        Git.cloneRepository()
                .setURI(workDir.toUri().toString())
                .setDirectory(bareDir.toFile())
                .setBare(true)
                .call()
                .close();

        return bareDir;
    }

    private Set<String> zipEntryNames(byte[] zipBytes) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
                zis.closeEntry();
            }
        }
        return names;
    }

    private long countDropshipTempDirs() throws IOException {
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        if (!Files.isDirectory(tmpDir)) return 0;
        try (var stream = Files.list(tmpDir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("dropship-git-")).count();
        }
    }
}
