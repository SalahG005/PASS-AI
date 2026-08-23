package com.pass.ai_assistant_backend.project.skeleton;

import com.pass.ai_assistant_backend.project.dto.CreateSkeletonProjectRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProjectSkeletonGeneratorTest {

    private final ProjectSkeletonGenerator generator = new ProjectSkeletonGenerator();

    @TempDir
    Path tempDir;

    @Test
    void generatesSpringBootMavenProject() throws IOException {
        CreateSkeletonProjectRequest req = springRequest("Demo API", "com.pass", "demo-api",
                List.of("web", "jpa", "postgresql", "validation"));

        generator.generate(tempDir, req);

        assertTrue(Files.exists(tempDir.resolve("pom.xml")));
        assertTrue(Files.exists(tempDir.resolve("src/main/java/com/pass/demo_api/DemoApiApplication.java")));
        assertTrue(Files.exists(tempDir.resolve("src/main/resources/application.properties")));
        assertTrue(Files.exists(tempDir.resolve("src/test/java/com/pass/demo_api/DemoApiApplicationTests.java")));
        String pom = Files.readString(tempDir.resolve("pom.xml"));
        assertTrue(pom.contains("spring-boot-starter-webmvc"));
        assertTrue(pom.contains("spring-boot-starter-data-jpa"));
        assertTrue(pom.contains("postgresql"));
    }

    @Test
    void generatesSpringBootWithSecurityConfig() throws IOException {
        CreateSkeletonProjectRequest req = springRequest("Secure App", "com.pass", "secure-app",
                List.of("web", "security"));

        generator.generate(tempDir, req);

        assertTrue(Files.exists(tempDir.resolve("src/main/java/com/pass/secure_app/config/SecurityConfig.java")));
    }

    @Test
    void generatesAngularProjectWithRoutingAndMaterial() throws IOException {
        CreateSkeletonProjectRequest req = angularRequest("My Angular App", true, true);

        generator.generate(tempDir, req);

        assertTrue(Files.exists(tempDir.resolve("package.json")));
        assertTrue(Files.exists(tempDir.resolve("angular.json")));
        assertTrue(Files.exists(tempDir.resolve("src/main.ts")));
        assertTrue(Files.exists(tempDir.resolve("src/app/app.routes.ts")));
        assertTrue(Files.exists(tempDir.resolve("src/app/home/home.ts")));
        String pkg = Files.readString(tempDir.resolve("package.json"));
        assertTrue(pkg.contains("@angular/material"));
    }

    @Test
    void rejectsInvalidStack() {
        CreateSkeletonProjectRequest req = new CreateSkeletonProjectRequest();
        req.setProjectName("Test");
        req.setStack("django");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> generator.validate(req));
        assertTrue(ex.getReason().contains("spring-boot"));
    }

    @Test
    void slugArtifactProducesValidMavenId() {
        assertEquals("my-project", ProjectSkeletonGenerator.slugArtifact("My Project"));
        assertEquals("demo-api", ProjectSkeletonGenerator.slugArtifact("Demo API"));
        assertTrue(ProjectSkeletonGenerator.slugArtifact("123").startsWith("app-"));
    }

    @Test
    void springBootSkeletonCompilesWithMaven() throws Exception {
        assumeTrue(canRun("mvn", "-v"), "Maven not available — skipping compile verification");

        CreateSkeletonProjectRequest req = springRequest("Compile Test", "com.pass", "compile-test",
                List.of("web", "validation"));
        Path projectDir = tempDir.resolve("spring-compile-test");
        generator.generate(projectDir, req);

        Process process = startProcess(projectDir, "mvn", "-q", "-DskipTests", "compile");
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "mvn compile timed out");
        assertEquals(0, process.exitValue(), "mvn compile failed for generated Spring Boot skeleton");
    }

    @Test
    void angularSkeletonBuildsWithNpm() throws Exception {
        assumeTrue(canRun("npm", "-v"), "npm not available — skipping build verification");

        CreateSkeletonProjectRequest req = angularRequest("Build Test", true, false);
        Path projectDir = tempDir.resolve("angular-build-test");
        generator.generate(projectDir, req);

        Process npmInstall = startProcess(projectDir, "npm", "install", "--no-audit", "--no-fund");
        assertTrue(npmInstall.waitFor(300, TimeUnit.SECONDS), "npm install timed out");
        assertEquals(0, npmInstall.exitValue(), "npm install failed for generated Angular skeleton");

        Process ngBuild = startProcess(projectDir, "npx", "ng", "build", "--configuration=development");
        assertTrue(ngBuild.waitFor(300, TimeUnit.SECONDS), "ng build timed out");
        assertEquals(0, ngBuild.exitValue(), "ng build failed for generated Angular skeleton");
    }

    private static CreateSkeletonProjectRequest springRequest(
            String name, String groupId, String artifactId, List<String> deps
    ) {
        CreateSkeletonProjectRequest req = new CreateSkeletonProjectRequest();
        req.setProjectName(name);
        req.setStack("spring-boot");
        req.setGroupId(groupId);
        req.setArtifactId(artifactId);
        req.setJavaVersion("17");
        req.setPackaging("jar");
        req.setDependencies(deps);
        return req;
    }

    private static CreateSkeletonProjectRequest angularRequest(String name, boolean routing, boolean material) {
        CreateSkeletonProjectRequest req = new CreateSkeletonProjectRequest();
        req.setProjectName(name);
        req.setStack("angular");
        req.setRouting(routing);
        req.setMaterial(material);
        return req;
    }

    private static Process startProcess(Path dir, String... command) throws IOException {
        ProcessBuilder pb;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            String[] wrapped = new String[command.length + 2];
            wrapped[0] = "cmd.exe";
            wrapped[1] = "/c";
            System.arraycopy(command, 0, wrapped, 2, command.length);
            pb = new ProcessBuilder(wrapped);
        } else {
            pb = new ProcessBuilder(command);
        }
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private static boolean canRun(String... command) {
        try {
            Process p = startProcess(null, command);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
