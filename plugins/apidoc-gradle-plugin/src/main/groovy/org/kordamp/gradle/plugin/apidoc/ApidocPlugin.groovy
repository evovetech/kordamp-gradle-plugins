/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2018-2019 Andres Almiray.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kordamp.gradle.plugin.apidoc

import org.gradle.BuildAdapter
import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.javadoc.Groovydoc
import org.gradle.api.tasks.javadoc.Javadoc
import org.kordamp.gradle.plugin.AbstractKordampPlugin
import org.kordamp.gradle.plugin.base.BasePlugin
import org.kordamp.gradle.plugin.base.ProjectConfigurationExtension
import org.kordamp.gradle.plugin.groovydoc.GroovydocPlugin
import org.kordamp.gradle.plugin.javadoc.JavadocPlugin

import static org.kordamp.gradle.PluginUtils.resolveEffectiveConfig
import static org.kordamp.gradle.plugin.base.BasePlugin.isRootProject

/**
 * Configures {@code aggregateApidocs} {@code aggregateApidocsJar} tasks on the root project.
 *
 * @author Andres Almiray
 * @since 0.1.0
 */
class ApidocPlugin extends AbstractKordampPlugin {
    static final String AGGREGATE_APIDOCS_TASK_NAME = 'aggregateApidocs'
    static final String AGGREGATE_JAVADOCS_TASK_NAME = 'aggregateJavadocs'
    static final String AGGREGATE_JAVADOCS_JAR_TASK_NAME = 'aggregateJavadocsJar'
    static final String AGGREGATE_GROOVYDOCS_TASK_NAME = 'aggregateGroovydocs'
    static final String AGGREGATE_GROOVYDOCS_JAR_TASK_NAME = 'aggregateGroovydocsJar'

    Project project

    void apply(Project project) {
        this.project = project

        if (isRootProject(project)) {
            if (project.childProjects.size()) {
                project.childProjects.values().each {
                    configureProject(it)
                }
                configureRootProject(project)
            } else {
                configureProject(project)
                configureRootProject(project)
            }
        } else {
            configureProject(project)
        }
    }

    static void applyIfMissing(Project project) {
        if (!project.plugins.findPlugin(ApidocPlugin)) {
            project.plugins.apply(ApidocPlugin)
        }
    }

    private void configureProject(Project project) {
        if (hasBeenVisited(project)) {
            return
        }
        setVisited(project, true)

        BasePlugin.applyIfMissing(project)
        JavadocPlugin.applyIfMissing(project)
        GroovydocPlugin.applyIfMissing(project)
    }

    private void configureRootProject(Project project) {
        if (hasBeenVisited(project)) {
            return
        }
        setVisited(project, true)

        BasePlugin.applyIfMissing(project)

        if (isRootProject(project) && !project.childProjects.isEmpty()) {
            List<Task> aggregateJavadocTasks = createAggregateJavadocsTask(project)
            createAggregateGroovydocsTask(project, aggregateJavadocTasks[0])
            createAggregateApidocTask(project)

            project.gradle.addBuildListener(new BuildAdapter() {
                @Override
                void projectsEvaluated(Gradle gradle) {
                    doConfigureRootProject(project)
                }
            })
        }
    }

    private void doConfigureRootProject(Project project) {
        ProjectConfigurationExtension effectiveConfig = resolveEffectiveConfig(project)
        setEnabled(effectiveConfig.apidoc.enabled)

        if (!enabled) {
            return
        }

        if (!project.childProjects.isEmpty()) {
            List<Javadoc> javadocs = []
            project.tasks.withType(Javadoc) { Javadoc javadoc -> if (javadoc.name != AGGREGATE_JAVADOCS_TASK_NAME && javadoc.enabled) javadocs << javadoc }
            project.childProjects.values().each { Project p ->
                if (p in effectiveConfig.apidoc.excludedProjects()) return
                p.tasks.withType(Javadoc) { Javadoc javadoc -> if (javadoc.enabled) javadocs << javadoc }
            }
            javadocs = javadocs.unique()

            List<Groovydoc> groovydocs = []
            project.tasks.withType(Groovydoc) { Groovydoc groovydoc -> if (groovydoc.name != AGGREGATE_GROOVYDOCS_TASK_NAME && groovydoc.enabled) groovydocs << groovydoc }
            project.childProjects.values().each { Project p ->
                if (p in effectiveConfig.apidoc.excludedProjects()) return
                p.tasks.withType(Groovydoc) { Groovydoc groovydoc -> if (groovydoc.enabled) groovydocs << groovydoc }
            }
            groovydocs = groovydocs.unique()

            Javadoc aggregateJavadocs = project.tasks.findByName(AGGREGATE_JAVADOCS_TASK_NAME)
            Jar aggregateJavadocsJar = project.tasks.findByName(AGGREGATE_JAVADOCS_JAR_TASK_NAME)
            Groovydoc aggregateGroovydocs = project.tasks.findByName(AGGREGATE_GROOVYDOCS_TASK_NAME)
            Jar aggregateGroovydocsJar = project.tasks.findByName(AGGREGATE_GROOVYDOCS_JAR_TASK_NAME)
            Task aggregateApidocTask = project.tasks.findByName(AGGREGATE_APIDOCS_TASK_NAME)

            if (javadocs && !effectiveConfig.groovydoc.replaceJavadoc) {
                aggregateJavadocs.configure { task ->
                    task.enabled true
                    task.dependsOn javadocs
                    task.source javadocs.source
                    task.classpath = project.files(javadocs.classpath)

                    effectiveConfig.javadoc.applyTo(task)
                    task.options.footer = "Copyright &copy; ${effectiveConfig.info.copyrightYear} ${effectiveConfig.info.authors.join(', ')}. All rights reserved."
                }
                aggregateJavadocsJar.configure {
                    enabled true
                    from aggregateJavadocs.destinationDir
                }

                aggregateApidocTask.enabled = true
                aggregateApidocTask.dependsOn aggregateJavadocs
            }

            if (groovydocs && effectiveConfig.groovydoc.enabled) {
                if (effectiveConfig.groovydoc.replaceJavadoc) {
                    aggregateGroovydocsJar.classifier = 'javadoc'
                }

                aggregateGroovydocs.configure { task ->
                    task.enabled true
                    task.dependsOn groovydocs + javadocs
                    task.source groovydocs.source + javadocs.source
                    task.classpath = project.files(groovydocs.classpath + javadocs.classpath)
                    task.groovyClasspath = project.files(groovydocs.groovyClasspath.flatten().unique())

                    effectiveConfig.groovydoc.applyTo(task)
                    task.footer = "Copyright &copy; ${effectiveConfig.info.copyrightYear} ${effectiveConfig.info.authors.join(', ')}. All rights reserved."
                }
                aggregateGroovydocsJar.configure {
                    enabled true
                    from aggregateGroovydocs.destinationDir
                }

                aggregateApidocTask.enabled = true
                aggregateApidocTask.dependsOn aggregateGroovydocs
            }
        }
    }

    private List<Task> createAggregateJavadocsTask(Project project) {
        Javadoc aggregateJavadocs = project.tasks.create(AGGREGATE_JAVADOCS_TASK_NAME, Javadoc) {
            enabled false
            group JavaBasePlugin.DOCUMENTATION_GROUP
            description 'Aggregates Javadoc API docs for all projects.'
            destinationDir project.file("${project.buildDir}/docs/javadoc")
            if (JavaVersion.current().isJava8Compatible()) {
                options.addStringOption('Xdoclint:none', '-quiet')
            }
        }

        Jar aggregateJavadocsJar = project.tasks.create(AGGREGATE_JAVADOCS_JAR_TASK_NAME, Jar) {
            enabled false
            dependsOn aggregateJavadocs
            group JavaBasePlugin.DOCUMENTATION_GROUP
            description 'An archive of the aggregate Javadoc API docs'
            classifier 'javadoc'
        }

        [aggregateJavadocs, aggregateJavadocsJar]
    }

    private List<Task> createAggregateGroovydocsTask(Project project, Task aggregateJavadoc) {
        Groovydoc aggregateGroovydocs = project.tasks.create(AGGREGATE_GROOVYDOCS_TASK_NAME, Groovydoc) {
            enabled false
            group JavaBasePlugin.DOCUMENTATION_GROUP
            description 'Aggregates Groovy API docs for all projects.'
            destinationDir project.file("${project.buildDir}/docs/groovydoc")
            classpath = aggregateJavadoc.classpath
        }

        Jar aggregateGroovydocsJar = project.tasks.create(AGGREGATE_GROOVYDOCS_JAR_TASK_NAME, Jar) {
            enabled false
            dependsOn aggregateGroovydocs
            group JavaBasePlugin.DOCUMENTATION_GROUP
            description 'An archive of the aggregate Groovy API docs'
            classifier 'groovydoc'
        }

        [aggregateGroovydocs, aggregateGroovydocsJar]
    }

    private Task createAggregateApidocTask(Project project) {
        project.tasks.create(AGGREGATE_APIDOCS_TASK_NAME, DefaultTask) {
            enabled false
            group JavaBasePlugin.DOCUMENTATION_GROUP
            description 'Aggregates all API docs for all projects.'
        }
    }
}
