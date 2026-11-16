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
import groovy.xml.XmlSlurper

def xml = new XmlSlurper().parse(new File(basedir, 'producer/target/flattened.xml'))
def entries = xml.dependencyManagement.dependencies.dependency.collectEntries { dep ->
    String type = dep.type.text() ?: 'jar'
    [("${dep.groupId.text()}:${dep.artifactId.text()}:${type}:${dep.classifier.text()}".toString()): dep.version.text()]
}
assert entries == [
    'test.lib:inherited:jar:': '1.0',
    'test.lib:nested:jar:': '2.0',
    'test.lib:winner:jar:': '1.0',
    'test.lib:overridden:jar:': '3.0',
    'test.lib:library:jar:': '1.0',
    'test.lib:library:test-jar:tests': '2.0'
]
def tests = xml.dependencyManagement.dependencies.dependency.find { it.classifier.text() == 'tests' }
assert tests.scope.text() == 'test'
assert tests.optional.text() == 'true'
assert tests.exclusions.exclusion.groupId.text() == 'test.excluded'
assert tests.exclusions.exclusion.artifactId.text() == '*'
assert xml.parent.size() == 0
assert xml.build.size() == 0
assert xml.profiles.size() == 0
assert !xml.dependencyManagement.dependencies.dependency.any { it.scope.text() == 'import' }
assert new File(basedir, 'producer/source-pom.xml').text.contains('<scope>import</scope>')
assert !new File(localRepositoryPath, 'test/flattened/first-bom/1.0/first-bom-1.0.pom').exists()
