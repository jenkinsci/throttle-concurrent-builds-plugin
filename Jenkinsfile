#!/usr/bin/env groovy

/* `buildPlugin` step provided by: https://github.com/jenkins-infra/pipeline-library */
buildPlugin(useAci: true, configurations: [
  [ platform: "linux", jdk: "8", jenkins: null ],
  [ platform: "windows", jdk: "8", jenkins: null ],
  [ platform: "linux", jdk: "8", jenkins: "2.164.1", javaLevel: "8" ],
  [ platform: "windows", jdk: "8", jenkins: "2.164.1", javaLevel: "8" ]
])
