/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.springboot.maven;

import java.io.File;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.Properties;

import javax.inject.Inject;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.eclipse.aether.DefaultRepositoryCache;
import org.eclipse.aether.DefaultRepositorySystemSession;

/**
 * Export the complete effective dependency management of a generated product BOM.
 * The source BOM remains the main artifact; the export is an additional inventory.
 */
@Mojo(name = "export-flattened-bom", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, threadSafe = true)
public class ExportFlattenedBomMojo extends AbstractMojo {

    /**
     * The generated product POM, after resource filtering.
     */
    @Parameter(property = "csb.flattenedBom.sourcePom", required = true)
    private File sourcePom;

    /**
     * The additional compact XML file; must differ from the source POM.
     */
    @Parameter(property = "csb.flattenedBom.outputFile", required = true)
    private File outputFile;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Inject
    private ProjectBuilder projectBuilder;

    @Override
    public void execute() throws MojoExecutionException {
        if (sourcePom == null || outputFile == null) {
            throw new MojoExecutionException("Both sourcePom and outputFile are required");
        }
        Path source = sourcePom.toPath().toAbsolutePath().normalize();
        Path output = outputFile.toPath().toAbsolutePath().normalize();
        try {
            if (source.equals(output) || (Files.exists(source) && Files.exists(output) && Files.isSameFile(source, output))) {
                throw new IllegalArgumentException("Source POM and flattened output must be different files");
            }
            // A failed invocation must not leave a previous export looking like a successful result.
            Files.deleteIfExists(output);
            Model original;
            try (Reader reader = Files.newBufferedReader(source)) {
                original = new MavenXpp3Reader().read(reader);
            }
            requireCoordinate(original.getGroupId(), "groupId");
            requireCoordinate(original.getArtifactId(), "artifactId");
            requireCoordinate(original.getVersion(), "version");

            // Retain repository/workspace access, but do not inherit the producer's model or overrides.
            ProjectBuildingRequest request = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            request.setProject(null);
            request.setResolveDependencies(false);
            request.setProcessPlugins(false);
            // Maven caches imported models after applying user properties. Rebuild them for the consumer model.
            DefaultRepositorySystemSession repositorySession = new DefaultRepositorySystemSession(session.getRepositorySession());
            repositorySession.setCache(new DefaultRepositoryCache());
            request.setRepositorySession(repositorySession);
            request.setLocalRepository(session.getLocalRepository());
            request.setRemoteRepositories(project.getRemoteArtifactRepositories());
            request.setUserProperties(new Properties());
            request.setProfiles(Collections.emptyList());
            request.setActiveProfileIds(Collections.emptyList());
            request.setInactiveProfileIds(Collections.emptyList());
            Model effective = projectBuilder.build(sourcePom, request).getProject().getModel();
            if (!Objects.equals(original.getGroupId(), effective.getGroupId())
                    || !Objects.equals(original.getArtifactId(), effective.getArtifactId())
                    || !Objects.equals(original.getVersion(), effective.getVersion())) {
                throw new IllegalArgumentException("Effective BOM coordinates differ from the source POM");
            }
            Model compact = FlattenedBomExporter.compact(effective);
            FlattenedBomExporter.write(compact, output);
            getLog().info("Exported " + compact.getDependencyManagement().getDependencies().size()
                    + " managed dependencies to " + output);
        } catch (Exception e) {
            throw new MojoExecutionException("Cannot export flattened BOM from " + source + ": " + e.getMessage(), e);
        }
    }

    private static void requireCoordinate(String value, String field) {
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new IllegalArgumentException("The generated source POM must have a resolved " + field);
        }
    }
}
