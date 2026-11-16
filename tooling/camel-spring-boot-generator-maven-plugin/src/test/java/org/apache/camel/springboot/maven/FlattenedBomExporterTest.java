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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlattenedBomExporterTest {

    @TempDir
    Path directory;

    @Test
    void preservesAllManagedFieldsWithoutMutatingInput() {
        Model input = model();
        Dependency dependency = dependency("library", "2.0");
        dependency.setType("test-jar");
        dependency.setClassifier("tests");
        dependency.setScope("test");
        dependency.setOptional("true");
        Exclusion exclusion = new Exclusion();
        exclusion.setGroupId("test.excluded");
        exclusion.setArtifactId("unwanted");
        dependency.addExclusion(exclusion);
        input.getDependencyManagement().addDependency(dependency);
        input.setParent(new Parent());
        input.setBuild(new Build());
        input.addProfile(new Profile());
        input.addProperty("build-only", "ignore");

        Model compact = FlattenedBomExporter.compact(input);
        assertThat(compact.getGroupId()).isEqualTo("test.platform");
        assertThat(compact.getArtifactId()).isEqualTo("platform-bom");
        assertThat(compact.getVersion()).isEqualTo("1.0");
        assertThat(compact.getPackaging()).isEqualTo("pom");
        assertThat(compact.getParent()).isNull();
        assertThat(compact.getBuild()).isNull();
        assertThat(compact.getProfiles()).isEmpty();
        assertThat(compact.getProperties()).isEmpty();
        Dependency actual = compact.getDependencyManagement().getDependencies().get(0);
        assertThat(actual).usingRecursiveComparison().isEqualTo(dependency);
        assertThat(actual).isNotSameAs(dependency);
        actual.getExclusions().get(0).setArtifactId("changed");
        assertThat(exclusion.getArtifactId()).isEqualTo("unwanted");
        assertThat(input.getParent()).isNotNull();
    }

    @Test
    void retainsUnusedEntriesAndDistinctTypesAndClassifiersInStableOrder() throws Exception {
        Model input = model();
        Dependency tests = dependency("library", "2.0");
        tests.setClassifier("tests");
        Dependency testJar = dependency("library", "3.0");
        testJar.setType("test-jar");
        Dependency unused = dependency("unused", "4.0");
        unused.setScope("test");
        input.getDependencyManagement().setDependencies(List.of(unused, tests, testJar, dependency("library", "1.0")));
        Model compact = FlattenedBomExporter.compact(input);
        assertThat(compact.getDependencyManagement().getDependencies()).extracting(FlattenedBomExporter::key)
                .containsExactly("test.lib:library:jar:", "test.lib:library:jar:tests",
                        "test.lib:library:test-jar:", "test.lib:unused:jar:");
        Path output = directory.resolve("nested/flattened.xml");
        FlattenedBomExporter.write(compact, output);
        byte[] first = Files.readAllBytes(output);
        FlattenedBomExporter.write(FlattenedBomExporter.compact(input), output);
        assertThat(Files.readAllBytes(output)).isEqualTo(first);
        try (var reader = Files.newBufferedReader(output)) {
            Model roundTrip = new MavenXpp3Reader().read(reader);
            assertThat(roundTrip.getDependencyManagement().getDependencies()).hasSize(4);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "${unresolved}", "[1,2)", "[1.0]", "(,2.0]", "LATEST", "RELEASE"})
    void rejectsNonConcreteVersions(String version) {
        Model input = model();
        input.getDependencyManagement().addDependency(dependency("library", version));
        assertThatThrownBy(() -> FlattenedBomExporter.compact(input))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("test.lib:library:jar:");
    }

    @Test
    void rejectsDuplicateKeysIncludingImplicitJar() {
        Model input = model();
        Dependency implicitJar = dependency("library", "1.0");
        implicitJar.setType(null);
        input.getDependencyManagement().setDependencies(List.of(implicitJar, dependency("library", "2.0")));
        assertThatThrownBy(() -> FlattenedBomExporter.compact(input)).hasMessageContaining("Duplicate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"import", "system", "${scope}"})
    void rejectsUnresolvedOrNonPortableScopes(String scope) {
        Model input = model();
        Dependency dependency = dependency("library", "1.0");
        dependency.setScope(scope);
        input.getDependencyManagement().addDependency(dependency);
        assertThatThrownBy(() -> FlattenedBomExporter.compact(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyManagementAndUnresolvedExclusionsOrCoordinates() {
        Model input = model();
        assertThatThrownBy(() -> FlattenedBomExporter.compact(input)).hasMessageContaining("dependencyManagement");
        Dependency dependency = dependency("library", "1.0");
        input.getDependencyManagement().addDependency(dependency);
        Exclusion exclusion = new Exclusion();
        exclusion.setGroupId("test.lib");
        exclusion.setArtifactId("${missing}");
        dependency.addExclusion(exclusion);
        assertThatThrownBy(() -> FlattenedBomExporter.compact(input)).hasMessageContaining("exclusion");
        dependency.getExclusions().clear();
        input.setVersion("${revision}");
        assertThatThrownBy(() -> FlattenedBomExporter.compact(input)).hasMessageContaining("version");
    }

    @Test
    void failedWriteDoesNotLeaveTemporaryFiles() throws Exception {
        Path destination = directory.resolve("destination");
        Files.createDirectory(destination);
        Files.writeString(destination.resolve("keep"), "existing");
        assertThatThrownBy(() -> FlattenedBomExporter.write(model(), destination)).isInstanceOf(IOException.class);
        try (var children = Files.list(directory)) {
            assertThat(children.toList()).containsExactly(destination);
        }
    }

    private static Model model() {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("test.platform");
        model.setArtifactId("platform-bom");
        model.setVersion("1.0");
        model.setDependencyManagement(new DependencyManagement());
        return model;
    }

    private static Dependency dependency(String artifactId, String version) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("test.lib");
        dependency.setArtifactId(artifactId);
        dependency.setVersion(version);
        return dependency;
    }
}
