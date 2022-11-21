#!/usr/bin/env groovy

/* `buildPlugin` step provided by: https://github.com/jenkins-infra/pipeline-library */
buildPlugin(useContainerAgent: true, configurations: [
  [ platform: 'linux', jdk: '8' ],
  [ platform: 'linux', jdk: '11' ],
  [ platform: 'windows', jdk: '11' ],
  [ platform: 'linux', jdk: '17', jenkins: '2.342' ],
])
