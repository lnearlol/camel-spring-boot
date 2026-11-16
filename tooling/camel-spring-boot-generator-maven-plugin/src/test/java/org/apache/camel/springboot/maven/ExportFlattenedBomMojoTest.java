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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.DefaultRepositoryCache;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportFlattenedBomMojoTest {

    @TempDir
    Path directory;
    @Mock
    MavenSession session;
    @Mock
    MavenProject project;
    @Mock
    ProjectBuilder projectBuilder;
    @Mock
    ProjectBuildingResult result;
    @InjectMocks
    ExportFlattenedBomMojo mojo;

    private Path source;
    private Path output;

    @BeforeEach
    void prepareFiles() throws Exception {
        source = directory.resolve("product-pom.xml");
        output = directory.resolve("flattened.xml");
        Files.writeString(source, """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>test.platform</groupId><artifactId>bom</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                </project>
                """);
        setField("sourcePom", source.toFile());
        setField("outputFile", output.toFile());
    }

    @Test
    void buildsProductFileWithoutProducerPropertiesOrProfiles() throws Exception {
        DefaultProjectBuildingRequest original = new DefaultProjectBuildingRequest();
        original.setProject(project);
        Properties properties = new Properties();
        properties.setProperty("library.version", "99.0");
        original.setUserProperties(properties);
        original.setProfiles(List.of(new Profile()));
        original.setActiveProfileIds(List.of("pncrelease"));
        when(session.getProjectBuildingRequest()).thenReturn(original);
        DefaultRepositorySystemSession repositorySession = new DefaultRepositorySystemSession();
        repositorySession.setCache(new DefaultRepositoryCache());
        when(session.getRepositorySession()).thenReturn(repositorySession);
        when(projectBuilder.build(eq(source.toFile()), any(ProjectBuildingRequest.class))).thenReturn(result);
        when(result.getProject()).thenReturn(new MavenProject(model()));
        byte[] before = Files.readAllBytes(source);

        mojo.execute();

        ArgumentCaptor<ProjectBuildingRequest> captured = ArgumentCaptor.forClass(ProjectBuildingRequest.class);
        verify(projectBuilder).build(eq(source.toFile()), captured.capture());
        ProjectBuildingRequest request = captured.getValue();
        assertThat(request).isNotSameAs(original);
        assertThat(request.getProject()).isNull();
        assertThat(request.isResolveDependencies()).isFalse();
        assertThat(request.isProcessPlugins()).isFalse();
        assertThat(request.getUserProperties()).isEmpty();
        assertThat(request.getProfiles()).isEmpty();
        assertThat(request.getActiveProfileIds()).isEmpty();
        assertThat(request.getRepositorySession().getCache()).isNotSameAs(repositorySession.getCache());
        assertThat(original.getUserProperties()).containsEntry("library.version", "99.0");
        assertThat(original.getProject()).isSameAs(project);
        assertThat(Files.readString(output)).contains("<version>2.0</version>");
        assertThat(Files.readAllBytes(source)).isEqualTo(before);
    }

    @Test
    void removesStaleOutputWhenModelBuildingFails() throws Exception {
        Files.writeString(output, "previous successful output");
        when(session.getProjectBuildingRequest()).thenReturn(new DefaultProjectBuildingRequest());
        when(session.getRepositorySession()).thenReturn(new DefaultRepositorySystemSession());
        when(projectBuilder.build(eq(source.toFile()), any(ProjectBuildingRequest.class)))
                .thenThrow(new ProjectBuildingException("test.platform:bom", "Missing imported BOM", source.toFile()));
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining(source.toString());
        assertThat(output).doesNotExist();
    }

    @Test
    void refusesToOverwriteSourceIncludingSymbolicLinks() throws Exception {
        setField("outputFile", source.toFile());
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class);
        assertThat(source).isRegularFile();
        Files.createSymbolicLink(output, source);
        setField("outputFile", output.toFile());
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class);
        assertThat(source).isRegularFile();
        verifyNoInteractions(projectBuilder);
    }

    @Test
    void refusesDifferentEffectiveCoordinates() throws Exception {
        when(session.getProjectBuildingRequest()).thenReturn(new DefaultProjectBuildingRequest());
        when(session.getRepositorySession()).thenReturn(new DefaultRepositorySystemSession());
        Model effective = model();
        effective.setArtifactId("producer");
        when(projectBuilder.build(eq(source.toFile()), any(ProjectBuildingRequest.class))).thenReturn(result);
        when(result.getProject()).thenReturn(new MavenProject(effective));
        assertThatThrownBy(mojo::execute).hasMessageContaining("coordinates");
        assertThat(output).doesNotExist();
    }

    @Test
    void rejectsUnreadableOrUnfilteredSourceAndClearsPreviousOutput() throws Exception {
        Files.writeString(source, Files.readString(source).replace("1.0", "${revision}"));
        Files.writeString(output, "old");
        assertThatThrownBy(mojo::execute).hasMessageContaining("version");
        assertThat(output).doesNotExist();
        Files.writeString(source, "not XML");
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class);
        Files.delete(source);
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class);
        verifyNoInteractions(projectBuilder);
    }

    private void setField(String name, File value) throws Exception {
        Field field = ExportFlattenedBomMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    private static Model model() {
        Model model = new Model();
        model.setGroupId("test.platform");
        model.setArtifactId("bom");
        model.setVersion("1.0");
        Dependency dependency = new Dependency();
        dependency.setGroupId("test.lib");
        dependency.setArtifactId("library");
        dependency.setVersion("2.0");
        DependencyManagement management = new DependencyManagement();
        management.addDependency(dependency);
        model.setDependencyManagement(management);
        return model;
    }
}
