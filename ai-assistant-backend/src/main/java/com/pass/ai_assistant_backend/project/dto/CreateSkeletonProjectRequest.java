package com.pass.ai_assistant_backend.project.dto;

import java.util.ArrayList;
import java.util.List;

public class CreateSkeletonProjectRequest {

    private String projectName;
    /** {@code spring-boot} or {@code angular} */
    private String stack;

    // Spring Boot
    private String groupId;
    private String artifactId;
    private String javaVersion;
    private String packaging;
    private List<String> dependencies = new ArrayList<>();

    // Angular
    private Boolean material;
    private Boolean routing;

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getStack() {
        return stack;
    }

    public void setStack(String stack) {
        this.stack = stack;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public void setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
    }

    public String getPackaging() {
        return packaging;
    }

    public void setPackaging(String packaging) {
        this.packaging = packaging;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies == null ? new ArrayList<>() : dependencies;
    }

    public Boolean getMaterial() {
        return material;
    }

    public void setMaterial(Boolean material) {
        this.material = material;
    }

    public Boolean getRouting() {
        return routing;
    }

    public void setRouting(Boolean routing) {
        this.routing = routing;
    }
}
