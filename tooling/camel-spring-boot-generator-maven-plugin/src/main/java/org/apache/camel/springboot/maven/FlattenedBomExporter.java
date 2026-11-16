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
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;

/**
 * Exports the complete effective dependency management without changing the source model.
 */
final class FlattenedBomExporter {

    private FlattenedBomExporter() {
    }

    static Model compact(Model effective) {
        requireValue(effective.getGroupId(), "BOM groupId");
        requireValue(effective.getArtifactId(), "BOM artifactId");
        requireVersion(effective.getVersion(), "BOM version");
        if (effective.getDependencyManagement() == null
                || effective.getDependencyManagement().getDependencies().isEmpty()) {
            throw new IllegalArgumentException("The BOM has no dependencyManagement entries");
        }

        Map<String, Dependency> entries = new TreeMap<>();
        for (Dependency dependency : effective.getDependencyManagement().getDependencies()) {
            String key = key(dependency);
            requireValue(dependency.getGroupId(), key + " groupId");
            requireValue(dependency.getArtifactId(), key + " artifactId");
            requireVersion(dependency.getVersion(), key + " version");
            requireResolved(dependency.getType(), key + " type");
            requireResolved(dependency.getClassifier(), key + " classifier");
            requireResolved(dependency.getScope(), key + " scope");
            requireResolved(dependency.getOptional(), key + " optional");
            if ("import".equals(dependency.getScope()) || "system".equals(dependency.getScope())
                    || dependency.getSystemPath() != null) {
                throw new IllegalArgumentException("Unexpanded import or non-portable system dependency: " + key);
            }
            for (Exclusion exclusion : dependency.getExclusions()) {
                requireValue(exclusion.getGroupId(), key + " exclusion groupId");
                requireValue(exclusion.getArtifactId(), key + " exclusion artifactId");
            }
            if (entries.putIfAbsent(key, dependency.clone()) != null) {
                throw new IllegalArgumentException("Duplicate managed key: " + key);
            }
        }

        Model compact = new Model();
        compact.setModelVersion("4.0.0");
        compact.setGroupId(effective.getGroupId());
        compact.setArtifactId(effective.getArtifactId());
        compact.setVersion(effective.getVersion());
        compact.setPackaging("pom");
        compact.setName(effective.getName());
        compact.setDescription(effective.getDescription());
        for (License license : effective.getLicenses()) {
            compact.addLicense(license.clone());
        }
        DependencyManagement management = new DependencyManagement();
        management.setDependencies(new ArrayList<>(entries.values()));
        compact.setDependencyManagement(management);
        return compact;
    }

    static String key(Dependency dependency) {
        String type = dependency.getType();
        if (type == null || type.isBlank()) {
            type = "jar";
        }
        String classifier = dependency.getClassifier() == null ? "" : dependency.getClassifier();
        return dependency.getGroupId() + ":" + dependency.getArtifactId() + ":" + type + ":" + classifier;
    }

    static void write(Model compact, Path output) throws IOException {
        Path destination = output.toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "flattened-bom-", ".xml.tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                new MavenXpp3Writer().write(writer, compact);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireVersion(String value, String field) {
        requireValue(value, field);
        if (value.startsWith("[") || value.startsWith("(") || "LATEST".equals(value) || "RELEASE".equals(value)) {
            throw new IllegalArgumentException("Expected a concrete version for " + field + ": " + value);
        }
    }

    private static void requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        requireResolved(value, field);
    }

    private static void requireResolved(String value, String field) {
        if (value != null && value.contains("${")) {
            throw new IllegalArgumentException("Unresolved " + field + ": " + value);
        }
    }
}
