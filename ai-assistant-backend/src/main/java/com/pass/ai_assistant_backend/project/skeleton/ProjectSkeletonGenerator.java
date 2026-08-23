package com.pass.ai_assistant_backend.project.skeleton;

import com.pass.ai_assistant_backend.project.dto.CreateSkeletonProjectRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, template-based project skeleton generator (no LLM).
 */
@Component
public class ProjectSkeletonGenerator {

    private static final Pattern MAVEN_ID = Pattern.compile("^[a-z][a-z0-9.-]*$");
    private static final Pattern PROJECT_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}$");
    private static final Set<String> SPRING_DEPS = Set.of(
            "web", "jpa", "postgresql", "security", "lombok", "validation"
    );
    private static final Set<String> JAVA_VERSIONS = Set.of("17", "21");
    private static final Set<String> PACKAGING = Set.of("jar", "war");

    public void validate(CreateSkeletonProjectRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        String name = request.getProjectName();
        if (name == null || name.isBlank() || !PROJECT_NAME.matcher(name.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "projectName is required (letters, numbers, spaces, .-_ ; max 80 chars)");
        }
        String stack = normalizeStack(request.getStack());
        if (stack == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stack must be spring-boot or angular");
        }
        if ("spring-boot".equals(stack)) {
            validateSpringBoot(request);
        } else {
            validateAngular(request);
        }
    }

    public void generate(Path projectRoot, CreateSkeletonProjectRequest request) throws IOException {
        validate(request);
        String stack = normalizeStack(request.getStack());
        Files.createDirectories(projectRoot);
        if ("spring-boot".equals(stack)) {
            generateSpringBoot(projectRoot, request);
        } else {
            generateAngular(projectRoot, request);
        }
    }

    private void validateSpringBoot(CreateSkeletonProjectRequest request) {
        String groupId = defaultMavenId(request.getGroupId(), "com.pass");
        String artifactId = defaultMavenId(request.getArtifactId(), slugArtifact(request.getProjectName()));
        if (!MAVEN_ID.matcher(groupId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid groupId");
        }
        if (!MAVEN_ID.matcher(artifactId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid artifactId");
        }
        String javaVersion = request.getJavaVersion() == null || request.getJavaVersion().isBlank()
                ? "17" : request.getJavaVersion().trim();
        if (!JAVA_VERSIONS.contains(javaVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "javaVersion must be 17 or 21");
        }
        String packaging = request.getPackaging() == null || request.getPackaging().isBlank()
                ? "jar" : request.getPackaging().trim().toLowerCase(Locale.ROOT);
        if (!PACKAGING.contains(packaging)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "packaging must be jar or war");
        }
        for (String dep : normalizedDeps(request.getDependencies())) {
            if (!SPRING_DEPS.contains(dep)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown dependency: " + dep);
            }
        }
    }

    private void validateAngular(CreateSkeletonProjectRequest request) {
        // routing/material are optional booleans — no extra validation needed
    }

    private void generateSpringBoot(Path root, CreateSkeletonProjectRequest request) throws IOException {
        String groupId = defaultMavenId(request.getGroupId(), "com.pass");
        String artifactId = defaultMavenId(request.getArtifactId(), slugArtifact(request.getProjectName()));
        String javaVersion = request.getJavaVersion() == null || request.getJavaVersion().isBlank()
                ? "17" : request.getJavaVersion().trim();
        String packaging = request.getPackaging() == null || request.getPackaging().isBlank()
                ? "jar" : request.getPackaging().trim().toLowerCase(Locale.ROOT);
        Set<String> deps = normalizedDeps(request.getDependencies());
        if (deps.isEmpty()) {
            deps.add("web");
        }

        String packageName = groupId + "." + artifactId.replace('-', '_');
        String className = toClassName(artifactId) + "Application";
        String projectLabel = request.getProjectName().trim();

        write(root, "pom.xml", springPom(groupId, artifactId, javaVersion, packaging, deps));
        write(root, ".gitignore", springGitignore());
        write(root, "README.md", springReadme(projectLabel, artifactId, deps, packaging));
        write(root, "src/main/resources/application.properties", springApplicationProperties(deps));
        write(root, path("src/main/java", packagePath(packageName), className + ".java"),
                springApplicationClass(packageName, className, packaging, deps.contains("security")));
        write(root, path("src/test/java", packagePath(packageName), className + "Tests.java"),
                springApplicationTests(packageName, className));

        if (deps.contains("security")) {
            write(root, path("src/main/java", packagePath(packageName), "config", "SecurityConfig.java"),
                    springSecurityConfig(packageName));
        }
    }

    private void generateAngular(Path root, CreateSkeletonProjectRequest request) throws IOException {
        boolean routing = request.getRouting() == null || request.getRouting();
        boolean material = Boolean.TRUE.equals(request.getMaterial());
        String projectLabel = request.getProjectName().trim();
        String npmName = slugArtifact(projectLabel);

        write(root, "package.json", angularPackageJson(npmName, material));
        write(root, "angular.json", angularJson(npmName));
        write(root, "tsconfig.json", angularTsConfig());
        write(root, "tsconfig.app.json", angularTsConfigApp());
        write(root, "tsconfig.spec.json", angularTsConfigSpec());
        write(root, ".gitignore", angularGitignore());
        write(root, "README.md", angularReadme(projectLabel, npmName, routing, material));
        write(root, "src/index.html", angularIndexHtml(projectLabel));
        write(root, "src/main.ts", angularMainTs());
        write(root, "src/styles.css", angularStyles(material));
        write(root, "src/app/app.ts", angularAppTs(routing, material));
        write(root, "src/app/app.html", angularAppHtml(routing, material, projectLabel));
        write(root, "src/app/app.css", angularAppCss());
        write(root, "src/app/app.config.ts", angularAppConfig(routing, material));

        if (routing) {
            write(root, "src/app/app.routes.ts", angularAppRoutes());
            write(root, "src/app/home/home.ts", angularHomeTs(material));
            write(root, "src/app/home/home.html", angularHomeHtml(projectLabel, material));
            write(root, "src/app/home/home.css", "/* Home page styles */\n");
        }

        Files.createDirectories(root.resolve("public"));
    }

    // ── Spring Boot templates ──────────────────────────────────────────────

    private String springPom(String groupId, String artifactId, String javaVersion,
                             String packaging, Set<String> deps) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>4.1.0</version>
                        <relativePath/>
                    </parent>
                    <groupId>""").append(groupId).append("""
                </groupId>
                    <artifactId>""").append(artifactId).append("""
                </artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <packaging>""").append(packaging).append("""
                </packaging>
                    <name>""").append(artifactId).append("""
                </name>
                    <description>PASS AI skeleton Spring Boot project</description>
                    <properties>
                        <java.version>""").append(javaVersion).append("""
                </java.version>
                    </properties>
                    <dependencies>
                """);

        if (deps.contains("web")) {
            sb.append("""
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-webmvc</artifactId>
                            </dependency>
                    """);
        }
        if (deps.contains("jpa")) {
            sb.append("""
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-data-jpa</artifactId>
                            </dependency>
                    """);
        }
        if (deps.contains("security")) {
            sb.append("""
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-security</artifactId>
                            </dependency>
                    """);
        }
        if (deps.contains("validation")) {
            sb.append("""
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-validation</artifactId>
                            </dependency>
                    """);
        }
        if (deps.contains("postgresql")) {
            sb.append("""
                            <dependency>
                                <groupId>org.postgresql</groupId>
                                <artifactId>postgresql</artifactId>
                                <scope>runtime</scope>
                            </dependency>
                    """);
        }
        if (deps.contains("lombok")) {
            sb.append("""
                            <dependency>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
                                <optional>true</optional>
                            </dependency>
                    """);
        }
        if ("war".equals(packaging)) {
            sb.append("""
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-tomcat</artifactId>
                                <scope>provided</scope>
                            </dependency>
                    """);
        }
        if (deps.contains("web")) {
            sb.append("""
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-webmvc-test</artifactId>
                                <scope>test</scope>
                            </dependency>
                    """);
        }
        if (deps.contains("security")) {
            sb.append("""
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-security-test</artifactId>
                                <scope>test</scope>
                            </dependency>
                    """);
        }
        if (deps.contains("jpa")) {
            sb.append("""
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-data-jpa-test</artifactId>
                                <scope>test</scope>
                            </dependency>
                    """);
        }

        sb.append("""
                        </dependencies>
                        <build>
                            <plugins>
                                <plugin>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-maven-plugin</artifactId>
                                </plugin>
                            </plugins>
                        </build>
                    </project>
                """);
        return sb.toString();
    }

    private String springApplicationClass(String packageName, String className, String packaging, boolean security) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import org.springframework.boot.SpringApplication;\n");
        sb.append("import org.springframework.boot.autoconfigure.SpringBootApplication;\n");
        if ("war".equals(packaging)) {
            sb.append("import org.springframework.boot.builder.SpringApplicationBuilder;\n");
            sb.append("import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;\n");
        }
        sb.append("\n@SpringBootApplication\n");
        if ("war".equals(packaging)) {
            sb.append("public class ").append(className).append(" extends SpringBootServletInitializer {\n\n");
            sb.append("\t@Override\n");
            sb.append("\tprotected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {\n");
            sb.append("\t\treturn builder.sources(").append(className).append(".class);\n");
            sb.append("\t}\n\n");
        } else {
            sb.append("public class ").append(className).append(" {\n\n");
        }
        sb.append("\tpublic static void main(String[] args) {\n");
        sb.append("\t\tSpringApplication.run(").append(className).append(".class, args);\n");
        sb.append("\t}\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String springApplicationTests(String packageName, String className) {
        return """
                package %s;

                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                class %sTests {

                    @Test
                    void contextLoads() {
                    }
                }
                """.formatted(packageName, className);
    }

    private String springSecurityConfig(String packageName) {
        return """
                package %s.config;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
                import org.springframework.security.web.SecurityFilterChain;

                @Configuration
                @EnableWebSecurity
                public class SecurityConfig {

                    @Bean
                    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                                .csrf(csrf -> csrf.disable());
                        return http.build();
                    }
                }
                """.formatted(packageName);
    }

    private String springApplicationProperties(Set<String> deps) {
        StringBuilder sb = new StringBuilder();
        sb.append("spring.application.name=skeleton\n");
        if (deps.contains("jpa") || deps.contains("postgresql")) {
            sb.append("""
                    spring.datasource.url=jdbc:postgresql://localhost:5432/mydb
                    spring.datasource.username=postgres
                    spring.datasource.password=postgres
                    spring.jpa.hibernate.ddl-auto=update
                    """);
        }
        if (deps.contains("web")) {
            sb.append("server.port=8080\n");
        }
        return sb.toString();
    }

    private String springGitignore() {
        return """
                target/
                .idea/
                *.iml
                .classpath
                .project
                .settings/
                .vscode/
                """;
    }

    private String springReadme(String name, String artifactId, Set<String> deps, String packaging) {
        return """
                # %s

                Spring Boot skeleton generated by PASS AI (template-based, no LLM).

                ## Run

                ```bash
                mvn spring-boot:run
                ```

                ## Build

                ```bash
                mvn -q -DskipTests compile
                ```

                - Artifact: `%s`
                - Packaging: `%s`
                - Dependencies: %s
                """.formatted(name, artifactId, packaging, String.join(", ", deps));
    }

    // ── Angular templates ──────────────────────────────────────────────────

    private String angularPackageJson(String npmName, boolean material) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                {
                  "name": "%s",
                  "version": "0.0.0",
                  "scripts": {
                    "ng": "ng",
                    "start": "ng serve",
                    "build": "ng build",
                    "watch": "ng build --watch --configuration development",
                    "test": "ng test"
                  },
                  "private": true,
                  "packageManager": "npm@11.16.0",
                  "dependencies": {
                    "@angular/common": "^22.0.0",
                    "@angular/compiler": "^22.0.0",
                    "@angular/core": "^22.0.0",
                    "@angular/forms": "^22.0.0",
                    "@angular/platform-browser": "^22.0.0",
                    "@angular/router": "^22.0.0",
                """.formatted(npmName));
        if (material) {
            sb.append("""
                        "@angular/cdk": "^22.0.0",
                        "@angular/material": "^22.0.0",
                    """);
        }
        sb.append("""
                    "rxjs": "~7.8.0",
                    "tslib": "^2.3.0"
                  },
                  "devDependencies": {
                    "@angular/build": "^22.0.5",
                    "@angular/cli": "^22.0.5",
                    "@angular/compiler-cli": "^22.0.0",
                """);
        if (material) {
            sb.append("""
                        "@angular/animations": "^22.0.0",
                    """);
        }
        sb.append("""
                    "jsdom": "^28.0.0",
                    "typescript": "~6.0.2",
                    "vitest": "^4.0.8"
                  }
                }
                """);
        return sb.toString();
    }

    private String angularJson(String npmName) {
        return """
                {
                  "$schema": "./node_modules/@angular/cli/lib/config/schema.json",
                  "version": 1,
                  "cli": {
                    "packageManager": "npm"
                  },
                  "newProjectRoot": "projects",
                  "projects": {
                    "%s": {
                      "projectType": "application",
                      "root": "",
                      "sourceRoot": "src",
                      "prefix": "app",
                      "architect": {
                        "build": {
                          "builder": "@angular/build:application",
                          "options": {
                            "browser": "src/main.ts",
                            "tsConfig": "tsconfig.app.json",
                            "assets": [{ "glob": "**/*", "input": "public" }],
                            "styles": ["src/styles.css"]
                          },
                          "configurations": {
                            "production": {
                              "budgets": [
                                { "type": "initial", "maximumWarning": "500kB", "maximumError": "1MB" }
                              ],
                              "outputHashing": "all"
                            },
                            "development": {
                              "optimization": false,
                              "extractLicenses": false,
                              "sourceMap": true
                            }
                          },
                          "defaultConfiguration": "production"
                        },
                        "serve": {
                          "builder": "@angular/build:dev-server",
                          "configurations": {
                            "production": { "buildTarget": "%s:build:production" },
                            "development": { "buildTarget": "%s:build:development" }
                          },
                          "defaultConfiguration": "development"
                        },
                        "test": {
                          "builder": "@angular/build:unit-test"
                        }
                      }
                    }
                  }
                }
                """.formatted(npmName, npmName, npmName);
    }

    private String angularTsConfig() {
        return """
                {
                  "compileOnSave": false,
                  "compilerOptions": {
                    "strict": true,
                    "noImplicitOverride": true,
                    "noPropertyAccessFromIndexSignature": true,
                    "noImplicitReturns": true,
                    "noFallthroughCasesInSwitch": true,
                    "skipLibCheck": true,
                    "isolatedModules": true,
                    "experimentalDecorators": true,
                    "importHelpers": true,
                    "target": "ES2022",
                    "module": "preserve"
                  },
                  "angularCompilerOptions": {
                    "enableI18nLegacyMessageIdFormat": false,
                    "strictInjectionParameters": true,
                    "strictInputAccessModifiers": true
                  },
                  "files": [],
                  "references": [
                    { "path": "./tsconfig.app.json" },
                    { "path": "./tsconfig.spec.json" }
                  ]
                }
                """;
    }

    private String angularTsConfigApp() {
        return """
                {
                  "extends": "./tsconfig.json",
                  "compilerOptions": { "types": [] },
                  "include": ["src/**/*.ts"],
                  "exclude": ["src/**/*.spec.ts"]
                }
                """;
    }

    private String angularTsConfigSpec() {
        return """
                {
                  "extends": "./tsconfig.json",
                  "compilerOptions": {
                    "outDir": "./out-tsc/spec",
                    "types": ["vitest/globals"]
                  },
                  "include": ["src/**/*.d.ts", "src/**/*.spec.ts"]
                }
                """;
    }

    private String angularMainTs() {
        return """
                import { bootstrapApplication } from '@angular/platform-browser';
                import { appConfig } from './app/app.config';
                import { App } from './app/app';

                bootstrapApplication(App, appConfig)
                  .catch((err) => console.error(err));
                """;
    }

    private String angularIndexHtml(String title) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>%s</title>
                  <base href="/">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                </head>
                <body>
                  <app-root></app-root>
                </body>
                </html>
                """.formatted(escapeHtml(title));
    }

    private String angularAppTs(boolean routing, boolean material) {
        StringBuilder sb = new StringBuilder();
        sb.append("import { Component } from '@angular/core';\n");
        List<String> importNames = new ArrayList<>();
        if (routing) {
            sb.append("import { RouterOutlet } from '@angular/router';\n");
            importNames.add("RouterOutlet");
        }
        if (material && !routing) {
            sb.append("import { MatButtonModule } from '@angular/material/button';\n");
            importNames.add("MatButtonModule");
        }
        sb.append("\n@Component({\n");
        sb.append("  selector: 'app-root',\n");
        if (!importNames.isEmpty()) {
            sb.append("  imports: [").append(String.join(", ", importNames)).append("],\n");
        }
        sb.append("  templateUrl: './app.html',\n");
        sb.append("  styleUrl: './app.css'\n");
        sb.append("})\n");
        sb.append("export class App {}\n");
        return sb.toString();
    }

    private String angularAppHtml(boolean routing, boolean material, String title) {
        if (routing) {
            return """
                    <main class="shell">
                      <header>
                        <h1>%s</h1>
                      </header>
                      <router-outlet />
                    </main>
                    """.formatted(escapeHtml(title));
        }
        if (material) {
            return """
                    <main class="shell">
                      <h1>%s</h1>
                      <p>Angular skeleton with Angular Material.</p>
                      <button mat-raised-button color="primary">Get started</button>
                    </main>
                    """.formatted(escapeHtml(title));
        }
        return """
                <main class="shell">
                  <h1>%s</h1>
                  <p>Your Angular app is ready.</p>
                </main>
                """.formatted(escapeHtml(title));
    }

    private String angularAppCss() {
        return """
                .shell {
                  font-family: system-ui, sans-serif;
                  padding: 2rem;
                  max-width: 960px;
                  margin: 0 auto;
                }
                """;
    }

    private String angularAppConfig(boolean routing, boolean material) {
        StringBuilder sb = new StringBuilder();
        sb.append("import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';\n");
        if (routing) {
            sb.append("import { provideRouter } from '@angular/router';\n");
            sb.append("import { routes } from './app.routes';\n");
        }
        if (material) {
            sb.append("import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';\n");
        }
        sb.append("\nexport const appConfig: ApplicationConfig = {\n");
        sb.append("  providers: [\n");
        sb.append("    provideBrowserGlobalErrorListeners()");
        if (routing) {
            sb.append(",\n    provideRouter(routes)");
        }
        if (material) {
            sb.append(",\n    provideAnimationsAsync()");
        }
        sb.append("\n  ]\n};\n");
        return sb.toString();
    }

    private String angularAppRoutes() {
        return """
                import { Routes } from '@angular/router';
                import { Home } from './home/home';

                export const routes: Routes = [
                  { path: '', component: Home },
                  { path: '**', redirectTo: '' }
                ];
                """;
    }

    private String angularHomeTs(boolean material) {
        StringBuilder sb = new StringBuilder();
        sb.append("import { Component } from '@angular/core';\n");
        if (material) {
            sb.append("import { MatButtonModule } from '@angular/material/button';\n");
        }
        sb.append("\n@Component({\n");
        sb.append("  selector: 'app-home',\n");
        if (material) {
            sb.append("  imports: [MatButtonModule],\n");
        }
        sb.append("  templateUrl: './home.html',\n");
        sb.append("  styleUrl: './home.css'\n");
        sb.append("})\n");
        sb.append("export class Home {}\n");
        return sb.toString();
    }

    private String angularHomeHtml(String title, boolean material) {
        if (material) {
            return """
                    <section>
                      <h2>Welcome</h2>
                      <p>%s is ready to build.</p>
                      <button mat-stroked-button color="primary">Explore</button>
                    </section>
                    """.formatted(escapeHtml(title));
        }
        return """
                <section>
                  <h2>Welcome</h2>
                  <p>%s is ready to build.</p>
                </section>
                """.formatted(escapeHtml(title));
    }

    private String angularStyles(boolean material) {
        if (material) {
            return """
                    @import '@angular/material/prebuilt-themes/azure-blue.css';

                    html, body {
                      height: 100%%;
                      margin: 0;
                      font-family: Roboto, system-ui, sans-serif;
                    }
                    """;
        }
        return """
                html, body {
                  height: 100%;
                  margin: 0;
                  font-family: system-ui, sans-serif;
                }
                """;
    }

    private String angularGitignore() {
        return """
                node_modules/
                dist/
                .angular/
                .vscode/
                """;
    }

    private String angularReadme(String name, String npmName, boolean routing, boolean material) {
        return """
                # %s

                Angular skeleton generated by PASS AI (template-based, no LLM).

                ## Run

                ```bash
                npm install
                npm start
                ```

                ## Build

                ```bash
                npm install
                npm run build
                ```

                - Project: `%s`
                - Routing: %s
                - Angular Material: %s
                """.formatted(name, npmName, routing, material);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String normalizeStack(String stack) {
        if (stack == null || stack.isBlank()) {
            return null;
        }
        String s = stack.trim().toLowerCase(Locale.ROOT);
        if ("spring-boot".equals(s) || "springboot".equals(s)) {
            return "spring-boot";
        }
        if ("angular".equals(s)) {
            return "angular";
        }
        return null;
    }

    private static Set<String> normalizedDeps(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String dep : raw) {
            if (dep != null && !dep.isBlank()) {
                out.add(dep.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static String defaultMavenId(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    static String slugArtifact(String name) {
        String slug = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = "my-project";
        }
        if (!Character.isLetter(slug.charAt(0))) {
            slug = "app-" + slug;
        }
        return slug.length() > 40 ? slug.substring(0, 40).replaceAll("-+$", "") : slug;
    }

    static String toClassName(String artifactId) {
        String[] parts = artifactId.split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        if (sb.isEmpty()) {
            return "Application";
        }
        return sb.toString();
    }

    private static String path(String... segments) {
        return String.join("/", segments);
    }

    private static String packagePath(String packageName) {
        return packageName.replace('.', '/');
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative.replace('/', root.getFileSystem().getSeparator().charAt(0)));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
