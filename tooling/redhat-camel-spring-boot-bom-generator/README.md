# Full flattened platform BOM

This module generates the product platform BOM from `src/main/resources/pom.xml`.
During `prepare-package`, the `export-flattened-bom` goal also writes a compact
inventory containing the complete effective `dependencyManagement` of that
generated POM.

The inventory expands imported BOMs and resolves version properties using Maven.
It retains every managed entry, including test dependencies, classifiers,
optional flags and exclusions. It does not resolve library JARs or calculate
which libraries a particular starter actually uses. Starter dependency trees
remain the source for that information.

## Files and publication

The module creates:

```text
target/redhat-camel-spring-boot-bom/pom.xml
target/redhat-camel-spring-boot-bom/flattened-full-pom.xml
```

The original POM remains the main BOM. The additional XML is installed under
the same product coordinates and version, with type `xml` and classifier
`flattened-full`:

```text
com.redhat.camel.springboot.platform:camel-spring-boot-bom:xml:flattened-full:VERSION
```

For a release version, the repository directory contains:

```text
camel-spring-boot-bom-VERSION.pom
camel-spring-boot-bom-VERSION-flattened-full.xml
```

The existing `pncrelease` / `deploy-file` execution publishes both files together.
The inventory is a build artifact; it is not committed into a productized tag.
Its inclusion in the delivered MRRC must also be verified by the product build.

## Build locally

Use the Maven wrapper and the JDK supported by this branch. Build the imported
CSB BOM in the same reactor as the producer; the import lives in a generated
file, so Maven cannot discover that module through `-am` alone:

```sh
./mvnw -B -ntp \
  -pl tooling/camel-spring-boot-bom,tooling/redhat-camel-spring-boot-bom-generator \
  -am clean package -DskipTests
```

All version properties used by the source template must be defined by the build.
In the current 4.22.1 midstream environment, `neethi-version` and
`zstd-jni-version` may need explicit values from the intended dependency set:

```sh
./mvnw -B -ntp \
  -pl tooling/camel-spring-boot-bom,tooling/redhat-camel-spring-boot-bom-generator \
  -am clean install -DskipTests \
  -Dneethi-version="$NEETHI_VERSION" -Dzstd-jni-version="$ZSTD_JNI_VERSION"
```

Set those variables to the versions selected for your build. They are substituted
by resource filtering before export; the exporter does not invent missing
versions. A product build uses its aligned versions. An unresolved value in the
generated BOM fails the build rather than producing an incomplete inventory.

`package` creates the files; `install` also puts them in the local Maven
repository. Neither `-DskipTests` nor `-Dmaven.test.skip=true` disables export.

## Model isolation and validation

The goal reads the generated product POM, not the technical producer's POM.
The product POM does not need a parent. Maven handles any parents of its
imported BOMs normally.

The producer's explicit profiles and user properties are not applied again when
building the consumer model. A separate model cache prevents imported models
from retaining earlier build-time overrides. Repository configuration and the
current reactor remain available. Default, JDK and OS profile activation in
the input POMs follows Maven behavior in the build environment.

The exporter fails on missing or unresolved versions, remaining imports,
duplicate managed keys, version ranges, `LATEST`/`RELEASE`, and non-portable
system dependencies. It never replaces the source POM. A failed invocation
removes its previous output so that a stale file cannot appear successful.

Run the plugin tests and the isolated reactor fixtures:

```sh
./mvnw -B -ntp -pl tooling/camel-spring-boot-generator-maven-plugin \
  -am clean verify -Pflattened-bom-it
```

The fixtures check nested imports, inherited management, import precedence,
explicit overrides, type/classifier identity, field preservation, isolation
from producer properties, generation without `install`, both test-skip flags,
and failure on a missing import. The Invoker plugin is used only by these tests.

After a local `install`, an independent reference can be generated from the
exact original file:

```sh
./mvnw -B -ntp -N \
  -f tooling/redhat-camel-spring-boot-bom-generator/target/redhat-camel-spring-boot-bom/pom.xml \
  org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom \
  -Doutput=effective-reference.xml
```

Compare the complete managed entries keyed by groupId, artifactId, type and
classifier, including version, scope, optional and exclusions. Matching only
the number of entries is insufficient. The reference contains other Maven
sections, so a textual diff of the whole POM is not meaningful.
