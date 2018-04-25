#!/usr/bin/env groovy

/**
 * Wrapper for running the ATH see README and runATH.txt for full documentation
 */

def call(Map params = [:]) {
    def athUrl = params.get('athUrl', 'https://github.com/jenkinsci/acceptance-test-harness.git')
    def athRevision = params.get('athRevision', 'master')
    def metadataFile = params.get('metadataFile', 'essentials.yml')
    def jenkins = params.get('jenkins', 'latest')

    def mirror = "http://mirrors.jenkins.io/"
    def defaultCategory = "org.jenkinsci.test.acceptance.junit.SmokeTest"
    def metadata
    def athContainerImage
    def isLocalATH
    def isVersionNumber

    def athSourcesFolder = "athSources"

    def supportedBrowsers = ["firefox"]

    def skipExecution = false

    def localPluginsStashName = env.RUN_ATH_LOCAL_PLUGINS_STASH_NAME ?: "localPlugins"

    infra.ensureInNode(env, env.RUN_ATH_SOURCES_AND_VALIDATION_NODE ?: "docker,highmem", {
        if (!fileExists(metadataFile)) {
            echo "Skipping ATH execution because the metadata file does not exist. Current value is ${metadataFile}."
            skipExecution = true
            return
        }

        stage("Getting ATH sources and Jenkins war") {
            // Start validation
            metadata = readYaml(file: metadataFile)?.ath
            if (metadata == null) {
                echo "Skipping ATH execution because the metadata file does not contain an ath section"
                skipExecution = true
                return
            }
            if (metadata == 'default') {
                echo "Using default configuration for ATH"
                metadata = [:]
            } else if (metadata.browsers == null) {
                echo "The provided metadata file does not include the browsers property, using firefox as default"
            }
            // Allow to override athUrl and athRevision from metadata file
            athUrl = metadata.athUrl ?: athUrl
            isLocalATH = athUrl.startsWith("file://")
            athRevision = metadata.athRevision ?: athRevision

            // Allow override of jenkins version from metadata file
            jenkins = metadata.jenkins ?: jenkins
            isVersionNumber = (jenkins =~ /^\d+([.]\d+)*$/).matches()

            if (!isLocalATH) {
                echo 'Checking connectivity to ATH sources…'
                sh "git ls-remote --exit-code -h ${athUrl}"
            }
            infra.stashJenkinsWar(jenkins)
            // Validation ended

            // ATH
            if (isLocalATH) { // Deal with already existing ATH sources
                athSourcesFolder = athUrl - "file://"
            } else {
                dir(athSourcesFolder) {
                    checkout changelog: true, poll: false, scm: [$class           : 'GitSCM', branches: [[name: athRevision]],
                                                                 userRemoteConfigs: [[url: athUrl]]]
                }
            }
            dir(athSourcesFolder) {
                // We may need to run things in parallel later, so avoid several checkouts
                stash name: "athSources"
            }

        }
    })

    infra.ensureInNode(env, env.RUN_ATH_DOCKER_NODE ?: "docker,highmem", {
        if (skipExecution) {
            return
        }
        stage("Running ATH") {
            dir("athSources") {
                unstash name: "athSources"
                def uid = sh(script: "id -u", returnStdout: true)
                def gid = sh(script: "id -g", returnStdout: true)
                athContainerImage = docker.build('jenkins/ath', "--build-arg=uid='$uid' --build-arg=gid='$gid' -f src/main/resources/ath-container/Dockerfile .")
            }

            def testsToRun = metadata.tests?.join(",")
            def categoriesToRun = metadata.categories?.join(",")
            def browsers = metadata.browsers ?: ["firefox"]
            def failFast = metadata.failFast ?: false
            def rerunCount = metadata.rerunFailingTestsCount ?: 0
            // Elvis fails in case useLocalSnapshots == false in metadata File
            def localSnapshots = metadata.useLocalSnapshots != null ? metadata.useLocalSnapshots : true

            if (testsToRun == null && categoriesToRun == null) {
                categoriesToRun = defaultCategory
            }

            def testingbranches = ["failFast": failFast]
            for (browser in browsers) {
                if (supportedBrowsers.contains(browser)) {

                    def currentBrowser = browser
                    def containerArgs = "-v /var/run/docker.sock:/var/run/docker.sock -e SHARED_DOCKER_SERVICE=true -e EXERCISEDPLUGINREPORTER=textfile -u ath-user"
                    def commandBase = "./run.sh ${currentBrowser} ./jenkins.war -B -Dmaven.test.failure.ignore=true -DforkCount=1 -B -Dsurefire.rerunFailingTestsCount=${rerunCount}"

                    if (testsToRun) {
                        testingbranches["ATH individual tests-${currentBrowser}"] = {
                            dir("test${currentBrowser}") {
                                def discriminator = "-Dtest=${testsToRun}"
                                test(discriminator, commandBase, localSnapshots, localPluginsStashName, containerArgs, athContainerImage)
                            }
                        }
                    }
                    if (categoriesToRun) {
                        testingbranches["ATH categories-${currentBrowser}"] = {
                            dir("categories${currentBrowser}") {
                                def discriminator = "-Dgroups=${categoriesToRun}"
                                test(discriminator, commandBase, localSnapshots, localPluginsStashName, containerArgs, athContainerImage)
                            }
                        }
                    }
                } else {
                    echo "${browser} is not yet supported"
                }
            }

            parallel testingbranches
        }

    })
}

private void test(discriminator, commandBase, localSnapshots, localPluginsStashName, containerArgs, athContainerImage) {
    unstashResources(localSnapshots, localPluginsStashName)
    athContainerImage.inside(containerArgs) {
        realtimeJUnit(testResults: 'target/surefire-reports/TEST-*.xml', testDataPublishers: [[$class: 'AttachmentPublisher']]) {
            sh 'eval "$(./vnc.sh)" && ' + prepareCommand(commandBase, discriminator, localSnapshots, localPluginsStashName)
        }
    }
}

private String prepareCommand(commandBase, discriminator, localSnapshots, localPluginsStashName ) {
    def command = commandBase + " ${discriminator}"
    if (localSnapshots && localPluginsStashName) {
        command = "LOCAL_JARS=${getLocalPluginsList()} " + command
    }
    command
}

private void unstashResources(localSnapshots, localPluginsStashName) {
    unstash name: "athSources"
    unstash name: "jenkinsWar"
    dir("localPlugins") {
        if (localSnapshots && localPluginsStashName) {
            unstash name: localPluginsStashName
        }
    }
}

private String getLocalPluginsList() {
    dir("localPlugins") {
       return sh(script : "ls -p -d -1 ${pwd()}/*.* | tr '\n' ':'| sed 's/.\$//'", returnStdout: true).trim()
    }
}