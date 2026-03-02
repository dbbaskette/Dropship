package com.baskette.dropship.integration;

import com.baskette.dropship.model.StagingResult;
import com.baskette.dropship.model.TaskLogs;
import com.baskette.dropship.model.TaskResult;
import com.baskette.dropship.service.LogService;
import com.baskette.dropship.service.StagingService;
import com.baskette.dropship.service.TaskService;
import org.cloudfoundry.client.v3.applications.DeleteApplicationRequest;
import org.cloudfoundry.client.v3.applications.GetApplicationRequest;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@SpringBootTest
@ActiveProfiles("integration")
@TestMethodOrder(OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DropshipIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DropshipIntegrationTest.class);
    private static final Duration STAGING_TIMEOUT = Duration.ofMinutes(6);
    private static final Duration TASK_TIMEOUT = Duration.ofMinutes(5);

    @Autowired
    private StagingService stagingService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private LogService logService;

    @Autowired
    private ReactorCloudFoundryClient cfClient;

    private StagingResult stagingResult;
    private TaskResult taskResult;
    private String appName;
    private final List<String> appGuidsToCleanup = new CopyOnWriteArrayList<>();

    @AfterAll
    void cleanupApps() {
        for (String appGuid : appGuidsToCleanup) {
            try {
                log.info("Cleaning up app: {}", appGuid);
                cfClient.applicationsV3()
                        .delete(DeleteApplicationRequest.builder()
                                .applicationId(appGuid)
                                .build())
                        .block(Duration.ofSeconds(30));
                log.info("Deleted app: {}", appGuid);
            } catch (Exception e) {
                log.warn("Best-effort cleanup failed for app {}: {}", appGuid, e.getMessage());
            }
        }
    }

    @Test
    @Order(1)
    void stageCode_success() throws Exception {
        byte[] sourceBundle = createSourceBundle();

        stagingResult = stagingService.stage(sourceBundle, "java_buildpack", null, null)
                .block(STAGING_TIMEOUT);

        assertThat(stagingResult).isNotNull();
        assertThat(stagingResult.success()).isTrue();
        assertThat(stagingResult.dropletGuid()).isNotNull().isNotBlank();

        if (stagingResult.appGuid() != null) {
            appGuidsToCleanup.add(stagingResult.appGuid());
            appName = lookupAppName(stagingResult.appGuid());
        }

        log.info("Staging succeeded: dropletGuid={}, appGuid={}, appName={}",
                stagingResult.dropletGuid(), stagingResult.appGuid(), appName);
    }

    @Test
    @Order(2)
    void runTask_success() {
        assumeThat(stagingResult).isNotNull();
        assumeThat(stagingResult.success()).isTrue();

        taskResult = taskService.runTask(
                        stagingResult.appGuid(),
                        stagingResult.dropletGuid(),
                        "java -cp . Main",
                        null, null, null)
                .block(TASK_TIMEOUT);

        assertThat(taskResult).isNotNull();
        assertThat(taskResult.exitCode()).isEqualTo(0);
        assertThat(taskResult.state()).isEqualTo(TaskResult.State.SUCCEEDED);

        log.info("Task succeeded: taskGuid={}, exitCode={}",
                taskResult.taskGuid(), taskResult.exitCode());
    }

    @Test
    @Order(3)
    void getTaskLogs_containsExpectedOutput() {
        assumeThat(taskResult).isNotNull();
        assumeThat(taskResult.taskGuid()).isNotNull();
        assumeThat(appName).isNotNull();

        TaskLogs taskLogs = logService.getTaskLogs(
                        taskResult.taskGuid(), appName, null, null)
                .block(Duration.ofSeconds(30));

        assertThat(taskLogs).isNotNull();
        assertThat(taskLogs.entries()).isNotEmpty();

        boolean containsHello = taskLogs.entries().stream()
                .anyMatch(entry -> entry.message().contains("Hello, Dropship!"));
        assertThat(containsHello)
                .as("Log entries should contain 'Hello, Dropship!' output")
                .isTrue();

        log.info("Retrieved {} log entries for task {}",
                taskLogs.entries().size(), taskResult.taskGuid());
    }

    @Test
    @Order(4)
    void stageCode_failureWithInvalidSource() {
        byte[] invalidSource = createInvalidSourceBundle();

        StagingResult result = stagingService.stage(invalidSource, "java_buildpack", null, null)
                .block(STAGING_TIMEOUT);

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isNotNull().isNotBlank();

        if (result.appGuid() != null) {
            appGuidsToCleanup.add(result.appGuid());
        }

        log.info("Staging failure (expected): errorMessage={}", result.errorMessage());
    }

    @Test
    @Order(5)
    void runTask_failureWithInvalidCommand() {
        assumeThat(stagingResult).isNotNull();
        assumeThat(stagingResult.success()).isTrue();

        TaskResult result = taskService.runTask(
                        stagingResult.appGuid(),
                        stagingResult.dropletGuid(),
                        "nonexistent-command-that-does-not-exist",
                        null, null, null)
                .block(TASK_TIMEOUT);

        assertThat(result).isNotNull();
        assertThat(result.exitCode()).isNotEqualTo(0);

        log.info("Task failure (expected): exitCode={}, state={}",
                result.exitCode(), result.state());
    }

    private byte[] createSourceBundle() throws Exception {
        Path mainJava = Path.of(
                getClass().getClassLoader()
                        .getResource("fixtures/hello-world/Main.java")
                        .toURI());

        Path outputDir = Files.createTempDirectory("dropship-it-compile-");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            assertThat(compiler).as("JDK required: JavaCompiler not available").isNotNull();

            int result = compiler.run(null, null, null,
                    "-d", outputDir.toString(), mainJava.toString());
            assertThat(result).as("Main.java compilation should succeed").isEqualTo(0);

            Path mainClass = outputDir.resolve("Main.class");
            byte[] classBytes = Files.readAllBytes(mainClass);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                // Main.class at root for `java -cp . Main`
                zos.putNextEntry(new ZipEntry("Main.class"));
                zos.write(classBytes);
                zos.closeEntry();

                // Executable JAR for java_buildpack detection
                zos.putNextEntry(new ZipEntry("hello.jar"));
                zos.write(createExecutableJar(classBytes));
                zos.closeEntry();
            }

            return baos.toByteArray();
        } finally {
            deleteRecursively(outputDir);
        }
    }

    private byte[] createExecutableJar(byte[] mainClassBytes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Main");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
            jos.putNextEntry(new JarEntry("Main.class"));
            jos.write(mainClassBytes);
            jos.closeEntry();
        }
        return baos.toByteArray();
    }

    private byte[] createInvalidSourceBundle() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("invalid.txt"));
            zos.write("this is not valid java source or bytecode".getBytes());
            zos.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    private String lookupAppName(String appGuid) {
        return cfClient.applicationsV3()
                .get(GetApplicationRequest.builder()
                        .applicationId(appGuid)
                        .build())
                .map(response -> response.getName())
                .block(Duration.ofSeconds(10));
    }

    private void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var entries = Files.list(path)) {
                    entries.forEach(this::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", path, e.getMessage());
        }
    }
}
