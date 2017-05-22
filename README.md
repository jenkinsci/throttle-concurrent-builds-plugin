Throttle Concurrent Builds Plugin
=== 

This plugin allows for throttling the number of concurrent builds of a project running per node or globally.

# Usage

The plugin supports two modes:

* Throttling of runs in Jenkins by one or multiple category
* Throttling of multiple runs of a same `AbstractProject` job (not recommended)
* Throttling of runs by parameter values

For each mode it is possible to setup global, label-specific, and node-specific limit of concurrent runs.
If multiple throttling categories defined, each requirement needs to be satisfied in order to pick the task from the queue.

Usage specifics:

* If throttling category cannot be satisfied, the task submission stays in queue until
the locked category becomes available. 
The submission can be terminated manually or by timeout.
* The plugin throttles tasks only only on common executors. 
Flyweight tasks will not be throttled.
* If the jobs are organized into a chain (e.g. via Parameterized Trigger build steps), each run in the chain is being counted independently.
E.g. if _ProjectA_ and _ProjectB_ use category `cat_A` on the same node, 2 executors will be required from the category pool.
Improper configuration of categories/jobs may cause deadlock of such build chains due to consumption of ll executors and waiting for downstream executions blocked in the queue.

## Global configuration

Global configuration allows defining global categories.
For each category you can setup global, label-specific, and node-specific restrictions for executor numbers.
After the configuration, it will be possible to select and use the categories in job configurations.

Configuration example:

![Global Category Configuration](doc/images/global_categoryConfig.png)

To set an unlimited value of concurrent builds for a restriction, use `0`.

## Throttling of classic job types

Classic job types (FreeStyle, Matrix, JobDSL) can be configured via job properties in the job configuration screen. 
Below you can find the configuration example:

![Throttle Job Property](doc/images/abstractProject_jobProperty.png)

* There are two modes: _Throttle This Project Alone_ and _Throttle this project as part of one or more categories_. 
Only one mode can be enabled. 
* _Throttle This Project Alone_
  * For this option you should configure _Maximum Total Concurrent Builds_ and/or _Maximum Concurrent Builds Per Node_
  * To set an unlimited value of concurrent builds for a restriction, use `0`
  * With this setting categories will be ignored
* _Throttle this project as part of one or more categories_
  * For this option you should specify enabled categories using checkboxes
  * _Maximum Total Concurrent Builds_ and _Maximum Concurrent Builds Per Node_ fields will be ignored
* _Prevent multiple jobs with identical parameters from running concurrently_
  * Adds additional throttling by parameter values

For _Matrix projects_ the property offers two additional checkboxes, 
which define throttling behavior for Matrix master run and configuration runs. 

![Throttle Job Property for Matrix](doc/images/abstractProject_matrixFlags.png)

## Throttling in Jenkins Pipeline

<!--TODO: Remove warning once JENKINS-31801 is integrated-->

### throttle() step

Starting from `throttle-concurrents-2.0` the plugin allows throttling particular Pipeline blocks by categories.
For this purpose you can use the `throttle()` step.
Throttling within a single job **is not supported**, you would need to define a special global category for the job.

How does it work?

* If `throttle()` step is defined, all explicit and implicit `node()` invocations within this step will be throttled.
* If `node()` step is defined within the `parallel()` block, each parallel branch will be throttled separately.
* Throttling of Pipeline steps in `throttle()` will take other throttling logic like job properties in Pipeline and other job types.
* If the specified category is missing, `throttle()` execution will fail the run.

#### Examples

**Example 1**: Throttling of node() runs

```groovy
// Throttle of a single operation
throttle(['test_2']) {
    node() {
        sh "sleep 500"
        echo "Done"
    }
}
```

**Example 2**: Throttling of parallel steps

```groovy
// The script below triggers 6 subtasks in parallel.
// Then tasks will be throttled according to the category settings.
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
```

##### Unsupported use-cases

This section contains links to the use-cases which **are not supported**

* Throttling of code blocks without `node()` definition.
Feature request:   [JENKINS-44411](https://issues.jenkins-ci.org/browse/JENKINS-44411).


### Throttling Pipeline via Job properties

:exclamation: **Warning!** It is not recommended to use this option starting from `throttle-concurrents-2.0`.
Use the `throttle()` step instead.

Plugin supports definition of throttling settings via job properties starting from `throttle-concurrents-1.8.5`. 
The behavior of such definition **may differ** from your expectation and **may change** in new plugin versions.

Current behavior:

* If the property is defined, Pipeline jobs will be throttled as any other project.
* Pipeline job will be throttled on the top level as a single instance, it will be considered as a single job even is there are declarations like `parallel()`.
* Node requirements will be considered for the Root Pipeline task only, so effectively only the master node will be checked

Use this option at your own risk.

# License

[MIT License](http://www.opensource.org/licenses/mit-license.php)


# Changelog

See [this page](CHANGELOG.md).

# Reporting issues

All issues should be reported to [Jenkins Issue Tracker](https://issues.jenkins-ci.org/secure/Dashboard.jspa).
Use the `throttle-concurrent-builds-plugin` component in the `JENKINS` project.
For more information about reporting issues to Jenkins see [this guide](https://wiki.jenkins-ci.org/display/JENKINS/How+to+report+an+issue).



