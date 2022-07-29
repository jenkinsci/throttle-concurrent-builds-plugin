= Throttle Concurrent Builds Plugin
:toc:
:toc-placement!:
:toc-title:
ifdef::env-github[]
:tip-caption: :bulb:
:note-caption: :information_source:
:important-caption: :heavy_exclamation_mark:
:caution-caption: :fire:
:warning-caption: :warning:
endif::[]

https://ci.jenkins.io/job/Plugins/job/throttle-concurrent-builds-plugin/job/master/[image:https://ci.jenkins.io/job/Plugins/job/throttle-concurrent-builds-plugin/job/master/badge/icon[Build Status]]
https://github.com/jenkinsci/throttle-concurrent-builds-plugin/graphs/contributors[image:https://img.shields.io/github/contributors/jenkinsci/throttle-concurrent-builds-plugin.svg[Contributors]]
https://plugins.jenkins.io/throttle-concurrents[image:https://img.shields.io/jenkins/plugin/v/throttle-concurrents.svg[Jenkins Plugin]]
https://github.com/jenkinsci/throttle-concurrent-builds-plugin/releases/latest[image:https://img.shields.io/github/release/jenkinsci/throttle-concurrent-builds-plugin.svg?label=changelog[GitHub release]]
https://plugins.jenkins.io/throttle-concurrents[image:https://img.shields.io/jenkins/plugin/i/throttle-concurrents.svg?color=blue[Jenkins Plugin Installs]]

toc::[]

== Introduction

This plugin allows for throttling the number of concurrent builds of a project running per node or globally.

== Getting started

This plugin supports three modes:

* Throttling of runs by one or multiple category
* Throttling of multiple runs of the same `AbstractProject` job (not recommended)
* Throttling of runs by parameter values

For each mode it is possible to setup global, label-specific, and node-specific limits for concurrent runs.
If multiple throttling categories are defined, each requirement needs to be satisfied in order for the task to be taken off the queue.

Usage specifics:

* If the throttling category cannot be satisfied, the task submission stays in the queue until the locked category becomes available.
* The submission can be terminated manually or by timeout.
* This plugin throttles tasks only on common executors. Flyweight tasks are not throttled.
* If the jobs are organized into a chain (e.g., via Parameterized Trigger build steps), each run in the chain is counted independently. For example, if _ProjectA_ and _ProjectB_ use category `cat_A` on the same node, two executors are required from the category pool. Improper configuration of categories/jobs may result in a deadlock of such build chains due to consumption of all executors and waiting for downstream executions blocked in the queue.

=== Global configuration

Global configuration allows defining global categories.
For each category you can set up global, label-specific, and node-specific restrictions for executor numbers.
After configuration, it is possible to select and use the categories in job configurations.
For example:

image:doc/images/global_categoryConfig.png[Global Category Configuration]

To set an unlimited value of concurrent builds for a restriction, use 0.

Also global configuration could be configured via https://plugins.jenkins.io/configuration-as-code/[Jenkins Configuration as Code] (JCasC) as following:
[source,yaml]
----
unclassified:
  throttleJobProperty:
    categories:
    - categoryName: "myThrottleCategory"
      maxConcurrentTotal: 5
      maxConcurrentPerNode: 2
      nodeLabeledPairs:
      - throttledNodeLabel: "docker"
        maxConcurrentPerNodeLabeled: 1
----

=== Throttling of classic job types

Classic job types (e.g., Freestyle, Matrix, and Job DSL) can be configured via job properties in the job configuration screen.
For example:

image:doc/images/abstractProject_jobProperty.png[Throttle Job Property]

There are two modes: _Throttle this project alone_ and _Throttle this project as part of one or more categories_.
Only one mode can be enabled.

Throttle this project alone::
* For this option you should configure _Maximum Total Concurrent Builds_ and/or _Maximum Concurrent Builds Per Node_.
* To set an unlimited value of concurrent builds for a restriction, use 0.
* With this option categories are ignored.
Throttle this project as part of one or more categories::
* For this option you should specify enabled categories using checkboxes.
* With this option the _Maximum Total Concurrent Builds_ and _Maximum Concurrent Builds Per Node_ fields are ignored.
Prevent multiple jobs with identical parameters from running concurrently::
* This option adds additional throttling by parameter values.

For Matrix projects the property offers two additional checkboxes, which define throttling behavior for Matrix master runs and configuration runs.
For example:

image:doc/images/abstractProject_matrixFlags.png[Throttle Job Property for Matrix]

=== Throttling of Pipeline jobs

==== `throttle()` step

Starting in `throttle-concurrents-2.0`, this plugin allows throttling of particular Pipeline blocks by categories.
For this purpose you can use the `throttle()` step.

How does it work?

* If a `throttle()` step is used, all explicit and implicit `node()` invocations within this step are throttled.
* If a `node()` step is used within a `parallel()` block, each parallel branch is throttled separately.
* Throttling of Pipeline steps in `throttle()` takes precedence over other throttling logic, such as job properties in Pipeline and other job types.
* If the specified category is missing, `throttle()` execution fails the run.

==== Warning regarding restarting the Jenkins controller

WARNING: Due to a deadlock (as described in https://issues.jenkins.io/browse/JENKINS-44747[JENKINS-44747]), a change has been made which can theoretically result in throttle categories being ignored in running Pipelines immediately after the Jenkins controller has been restarted.
This will be investigated further in https://issues.jenkins.io/browse/JENKINS-44756[JENKINS-44756] but was considered necessary in order to resolve the deadlock scenario.

== Examples

=== Example 1: Throttling of `node()` runs

[source,groovy]
----
// Throttle a single operation
throttle(['test_2']) {
    node() {
        sh "sleep 500"
        echo "Done"
    }
}
----

=== Example 2: Throttling of parallel steps

[source,groovy]
----
// The script below triggers 6 subtasks in parallel.
// Then tasks are throttled according to the category settings.
def labels = ['1', '2', '3', '4', '5', '6']
def builders = [:]
for (x in labels) {
    def label = x // Need to bind the label variable before the closure

    // Create a map to pass in to the 'parallel' step so we can fire all the builds at once
    builders[label] = {
      node('linux') {
        sh "sleep 5"
      }
    }
}

throttle(['myThrottleCategory1', 'myThrottleCategory2']) {
  parallel builders
}
----

=== Example 3: Throttling of declarative pipelines

To throttle concurrent builds to 1, configure a global category and add an options property to the pipeline.

image:doc/images/global_categoryConfig3.png[Global Category Configuration Test3]

[source,groovy]
----
pipeline {
    agent any

    // Throttle a declarative pipeline via options
    options {
      throttleJobProperty(
          categories: ['test_3'],
          throttleEnabled: true,
          throttleOption: 'category'
      )
    }

    stages {
        stage('sleep') {
            steps {
                sh "sleep 500"
                echo "Done"
            }
        }
    }
}
----

=== Example 4: Throttling of declarative stages

It is possible to throttle a stage of a declarative pipeline if the stage assigns an agent. The throttle step should be placed in the options block of the stage.

[source,groovy]
----
pipeline {
    agent none

    stages {
        stage('sleep') {
            agent any
            options {
                throttle(['test_4'])
            }
            steps {
                sh "sleep 500"
                echo "Done"
            }
        }
    }
}
----

== Unsupported use cases

This section contains links to the use cases which are *not* supported.

=== Throttling of code blocks without a `node()` definition

A feature request is logged as https://issues.jenkins.io/browse/JENKINS-44411[JENKINS-44411].

=== Throttling Pipeline via job properties

WARNING: Starting in `throttle-concurrents-2.0`, using this option is not recommended.
Use the `throttle()` step instead.

Starting in `throttle-concurrents-1.8.5`, this plugin supports the definition of throttling settings via job properties.
The behavior of such definition *may differ* from your expectation and *may change* in new plugin versions.

Current behavior:

* If the property is defined, Pipeline jobs are throttled as any other project.
* Pipeline jobs are throttled on the top level as a single instance. They are considered a single job even if there are declarations like `parallel()`.
* Node requirements are considered for the root Pipeline task only, so effectively only the master node is checked.

Use this option at your own risk.

== License

Licensed under link:LICENSE[the MIT License].

== Changelog

* xref:CHANGELOG.adoc[Changelog]

== Issues

Report issues and enhancements in the https://issues.jenkins.io/[Jenkins issue tracker].
Use the `throttle-concurrent-builds-plugin` component in the `JENKINS` project.

== Contributing

Refer to our https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md[contribution guidelines].
