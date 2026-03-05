package com.baskette.dropship.service;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class GitCloneService {

    private static final Logger log = LoggerFactory.getLogger(GitCloneService.class);
    private static final long BUILD_TIMEOUT_MINUTES = 5;
    private static final Path JDK_CACHE_DIR = Path.of("/tmp/dropship-jdk");
    private static final String ADOPTIUM_JDK_URL =
            "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse";

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpJdk() {
        try {
            Path jdk = ensureJdk();
            log.info("JDK warm-up complete: {}", jdk);
        } catch (Exception e) {
            log.warn("JDK warm-up failed (will retry on first build): {}", e.getMessage());
        }
    }

    /**
     * Clone a public git repository and zip its contents into a byte array
     * suitable for CF package upload.
     *
     * @param repoUrl      HTTPS git repository URL
     * @param branch       branch or tag to checkout (null for repo default)
     * @param subdirectory subdirectory within the repo to stage (null for repo root)
     * @return zipped source bytes
     */
    public Mono<byte[]> cloneAndZip(String repoUrl, String branch, String subdirectory) {
        return Mono.fromCallable(() -> {
            Path tempDir = Files.createTempDirectory("dropship-git-");
            try {
                log.info("Cloning repo: url={}, branch={}, subdirectory={}",
                        repoUrl, branch != null ? branch : "(default)", subdirectory);

                CloneCommand cloneCommand = Git.cloneRepository()
                        .setURI(repoUrl)
                        .setDirectory(tempDir.toFile())
                        .setDepth(1);

                if (branch != null && !branch.isBlank()) {
                    cloneCommand.setBranch(branch);
                }

                try (Git git = cloneCommand.call()) {
                    log.info("Clone completed: {}", tempDir);
                }

                Path sourceRoot = tempDir;
                if (subdirectory != null && !subdirectory.isBlank()) {
                    sourceRoot = tempDir.resolve(subdirectory);
                    if (!Files.isDirectory(sourceRoot)) {
                        throw new IllegalArgumentException(
                                "Subdirectory does not exist in repo: " + subdirectory);
                    }
                }

                byte[] zipped;
                if (isJavaProject(sourceRoot)) {
                    log.info("Java project detected, building before staging");
                    buildProject(sourceRoot);
                    Path artifact = findOutputArtifact(sourceRoot);
                    log.info("Built artifact: {}", artifact.getFileName());
                    // JAR is already a valid zip — upload directly so CF extracts
                    // the exploded app (META-INF/, BOOT-INF/) for the buildpack
                    zipped = Files.readAllBytes(artifact);
                } else {
                    zipped = zipDirectory(sourceRoot);
                }
                log.info("Zipped output: {} bytes", zipped.length);
                return zipped;
            } finally {
                deleteRecursively(tempDir);
            }
        });
    }

    byte[] zipDirectory(Path sourceDir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    // Skip .git directory
                    if (dir.getFileName() != null && dir.getFileName().toString().equals(".git")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    String entryName = sourceDir.relativize(file).toString();
                    // Skip files inside .git (safety check)
                    if (entryName.startsWith(".git/") || entryName.startsWith(".git\\")) {
                        return FileVisitResult.CONTINUE;
                    }
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return baos.toByteArray();
    }

    boolean isJavaProject(Path sourceRoot) {
        return Files.exists(sourceRoot.resolve("pom.xml"))
                || Files.exists(sourceRoot.resolve("build.gradle"))
                || Files.exists(sourceRoot.resolve("build.gradle.kts"));
    }

    void buildProject(Path sourceRoot) throws IOException, InterruptedException {
        boolean isMaven = Files.exists(sourceRoot.resolve("pom.xml"));
        String wrapperScript;
        String fallbackCommand;
        List<String> buildArgs;

        if (isMaven) {
            wrapperScript = "./mvnw";
            fallbackCommand = "mvn";
            buildArgs = List.of("package", "-DskipTests", "-q");
        } else {
            wrapperScript = "./gradlew";
            fallbackCommand = "gradle";
            buildArgs = List.of("build", "-x", "test", "-q");
        }

        // Make wrapper executable if it exists
        Path wrapperPath = sourceRoot.resolve(wrapperScript.substring(2));
        if (Files.exists(wrapperPath)) {
            wrapperPath.toFile().setExecutable(true);
        }

        String command = Files.exists(wrapperPath) ? wrapperScript : fallbackCommand;
        List<String> fullCommand = Stream.concat(Stream.of(command), buildArgs.stream())
                .collect(Collectors.toList());

        log.info("Running build: {}", String.join(" ", fullCommand));

        Path jdkHome = ensureJdk();

        ProcessBuilder pb = new ProcessBuilder(fullCommand)
                .directory(sourceRoot.toFile())
                .redirectErrorStream(true);

        pb.environment().put("JAVA_HOME", jdkHome.toString());
        String path = pb.environment().getOrDefault("PATH", "");
        pb.environment().put("PATH", jdkHome.resolve("bin") + ":" + path);

        Process process = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Build timed out after " + BUILD_TIMEOUT_MINUTES + " minutes");
        }

        if (process.exitValue() != 0) {
            log.error("Build failed with exit code {}. Output:\n{}", process.exitValue(), output);
            throw new IOException("Build failed (exit code " + process.exitValue() + "): " + output);
        }

        log.info("Build completed successfully");
    }

    Path ensureJdk() throws IOException, InterruptedException {
        // Check if current Java home already has javac (local dev with JDK)
        String currentJavaHome = System.getProperty("java.home");
        if (currentJavaHome != null) {
            Path javac = Path.of(currentJavaHome, "bin", "javac");
            if (Files.exists(javac)) {
                log.info("Using existing JDK at {}", currentJavaHome);
                return Path.of(currentJavaHome);
            }
        }

        // Check for cached JDK
        if (Files.isDirectory(JDK_CACHE_DIR)) {
            try (Stream<Path> dirs = Files.list(JDK_CACHE_DIR)) {
                var cached = dirs
                        .filter(p -> p.getFileName().toString().startsWith("jdk-"))
                        .findFirst();
                if (cached.isPresent()) {
                    log.info("Using cached JDK at {}", cached.get());
                    return cached.get();
                }
            }
        }

        // Download Adoptium JDK
        log.info("Downloading JDK 21 from Adoptium...");
        Files.createDirectories(JDK_CACHE_DIR);
        Path tarball = JDK_CACHE_DIR.resolve("jdk.tar.gz");

        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ADOPTIUM_JDK_URL))
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tarball));

        long sizeMb = Files.size(tarball) / (1024 * 1024);
        log.info("Downloaded JDK tarball: {} MB", sizeMb);

        // Extract
        Process extract = new ProcessBuilder("tar", "xzf", tarball.toString(),
                "-C", JDK_CACHE_DIR.toString())
                .redirectErrorStream(true)
                .start();
        boolean extractDone = extract.waitFor(5, TimeUnit.MINUTES);
        if (!extractDone || extract.exitValue() != 0) {
            throw new IOException("Failed to extract JDK tarball");
        }

        Files.deleteIfExists(tarball);

        // Find extracted directory
        try (Stream<Path> dirs = Files.list(JDK_CACHE_DIR)) {
            Path jdkDir = dirs
                    .filter(p -> p.getFileName().toString().startsWith("jdk-"))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "JDK extraction failed — no jdk-* directory found in " + JDK_CACHE_DIR));
            log.info("JDK installed at {}", jdkDir);
            return jdkDir;
        }
    }

    Path findOutputArtifact(Path sourceRoot) throws IOException {
        Path artifactDir;
        java.util.function.Predicate<String> excludeFilter;

        if (Files.exists(sourceRoot.resolve("pom.xml"))) {
            artifactDir = sourceRoot.resolve("target");
            excludeFilter = name -> name.endsWith("-sources.jar")
                    || name.endsWith("-javadoc.jar")
                    || name.endsWith(".original");
        } else {
            artifactDir = sourceRoot.resolve("build/libs");
            excludeFilter = name -> name.endsWith("-plain.jar")
                    || name.endsWith("-sources.jar");
        }

        if (!Files.isDirectory(artifactDir)) {
            throw new IOException("Build output directory not found: " + artifactDir);
        }

        try (Stream<Path> files = Files.list(artifactDir)) {
            return files
                    .filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !excludeFilter.test(p.getFileName().toString()))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "No JAR artifact found in " + artifactDir));
        }
    }

    byte[] zipSingleFile(Path file) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(file.getFileName().toString()));
            Files.copy(file, zos);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private void deleteRecursively(Path path) {
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to delete temp directory: {}", path, e);
        }
    }
}
