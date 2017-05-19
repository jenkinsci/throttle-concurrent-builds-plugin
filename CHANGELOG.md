Changelog
===

## 1.10.0

Release date: Coming soon

* [JENKINS-31801](https://issues.jenkins-ci.org/browse/JENKINS-31801) - 
Add support of Jenkins Pipeline job throttling by category.
[Documentation](README.md)

## 1.9.0 

Release date: (Apr 11, 2016)

* [JENKINS-25326](https://issues.jenkins-ci.org/browse/JENKINS-25326) - 
Elevate user to SYSTEM during build throttling, fixes the issue with jobs in Folders.
* Bump the core dependency to 1.609.3 to skip versions with known Queue incompatibility (_`1.535` .. `1.609.2`_)
* FindBugs fixes and test suite improvements

## 1.8.5 

Release date: (Apr 06, 2016)

* [PR #38](https://github.com/jenkinsci/throttle-concurrent-builds-plugin/pull/38) - 
Support throttling based on parameter value examination.
* Bump the core dependency to `1.596.1`

## 1.8.4 

Release date: (Oct 09, 2014)

* Count builds running on a node's `OneOffExecutor` list when making throttling decisions
  * Fixes the throttling of build flows from Build Flow Plugin ( [JENKINS-24748](https://issues.jenkins-ci.org/browse/JENKINS-24748), 
    [JENKINS-21335](https://issues.jenkins-ci.org/browse/JENKINS-21335), 
    [JENKINS-17512](https://issues.jenkins-ci.org/browse/JENKINS-17512), etc.)
  * Fixes the throttling of Matrix parent jobs

## 1.8.3 

Release date: (Jul 2, 2014)

* [JENKINS-13619](https://issues.jenkins-ci.org/browse/JENKINS-13619) -
Add support of Matrix configurations throttling .

## 1.8.2 

Release date: (Mar 7, 2014)

* [JENKINS-21044](https://issues.jenkins-ci.org/browse/JENKINS-21044) -
Fixes blocker issue with thread concurrency locks.

## 1.8.1 

Release date: (Dec 08, 2013) - UNSTABLE

* [JENKINS-19623](https://issues.jenkins-ci.org/browse/JENKINS-19623) -
Minimize security checks to improve performance of build queue dispatcher.

:exclamation: The version has a "blocker" issue caused by threads concurrency. See [JENKINS-21044](https://issues.jenkins-ci.org/browse/JENKINS-21044) for more info.

## 1.8 

Release date: (Sep 20, 2013)

* [JENKINS-19645](https://issues.jenkins-ci.org/browse/JENKINS-19645) -
Categories optionally configured with pairs of throttled node labels.
* Fix for working on Jenkins `1.480.3`.
* Optimize performance by changing from `getComputers()` to `getNodes()`.
* [JENKINS-12240](https://issues.jenkins-ci.org/browse/JENKINS-12240) - 
Fixing handling of MatrixProjects and MatrixConfigurations.

## 1.7 

Release date: (2011)

* You now choose either to throttle the builds for just this project, or to throttle as part of a category.

## 1.6 

Release date: (Apr 25, 2011)

* Now checks all criteria until one rejects the build or all have been checked, rather than allowing the build to run as soon as one criteria passes.

## 1.5 

Release date: (Feb 23, 2011)

* Matrix configurations are now throttled under the rules defined for their parent matrix project.

## 1.4 

Release date: (Feb 22, 2011)

* Fixed problem with categories not checking for projects in pending state.

## 1.3 

Release date: (Oct 15, 2010)

* Multiple categories per job now allowed.

## 1.2 

Release date: (Sept 27, 2010)

* [JENKINS-7221](https://issues.jenkins-ci.org/browse/JENKINS-7221) -
Categories weren't saving due to not having a setter.

## 1.1.1 

Release date: (Sept 24, 2010)

* Added ability to turn off throttling of a job, because it was not possible.
* [JENKINS-7559](https://issues.jenkins-ci.org/browse/JENKINS-7559) -
Fixed a problem when run in Hudson 1.377 or greater, due to changes in queue logic.

## 1.1

* Added support for "categories" - throttling multiple jobs as if they were one.

## 1.0

* Initial version.
