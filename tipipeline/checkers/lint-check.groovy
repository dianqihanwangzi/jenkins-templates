properties([
        parameters([
                string(
                        defaultValue: '{}',
                        name: 'INPUT_JSON',
                        trim: true
                ),
        ])
])

common = {}
node("lightweight_pod") {
    container("golang") {
        checkout scm
        common = load "tipipeline/common.groovy"
    }
} 
def runBody = {config ->
   try {
        def ws = pwd()

        dir(config.repo) {
            stage(config.taskName) {
                println config.taskName
            }
            stage("Download code from fileserver") {
                sh """
                curl ${config.cacheCodeURL} | tar xz --strip-components=1
                """
            }

            stage("Download lint conf") {
                def lintConfigURL = config.params["lintConfigURL"]
                fileExist = sh(returnStatus: true, script: """
                    if curl --output /dev/null --silent --head --fail ${lintConfigURL}; then exit 0; else exit 1; fi
                    """)
                if (fileExist == 0) {
                    println "golangci lint config existed in devops-config"
                    sh """
                    curl -L -o .golangci.yml ${lintConfigURL}
                    """
                } else {
                    println "use default golangci lint config"
                }
            }

            stage("Lint check") {
                def cmd = config.params["shellScript"]
                sh """
                curl -sfL https://raw.githubusercontent.com/golangci/golangci-lint/master/install.sh| sh -s -- -b ${ws}/bin v1.42.0
                """
                sh """
                export PATH=${ws}/bin:\$PATH
                golangci-lint version
                
                ${cmd}
                """
            }
        }
        currentBuild.result = "SUCCESS"
        currentBuild.description = ""

    } catch (err) {
        throw err
    } finally {
        reportDir = config.params["reportDir"]
        sh """
        wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-lint/agent-lint.py
        python3 agent-lint.py ${config.repo}/${reportDir} || true

        wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_lint/tiinsight-agent-lint.py
        python3 tiinsight-agent-lint.py ${config.repo} ${config.branch} ${config.commitID} ${config.taskName} ${config.repo}/${reportDir} ${BUILD_URL}
        """
        def exists = fileExists "test_summary.info"
        if (exists) {
            ENV_TEST_SUMMARY = sh(script: "cat test_summary.info", returnStdout: true).trim()
            println ENV_TEST_SUMMARY
            currentBuild.description = "${ENV_TEST_SUMMARY}"
        } else {
            println "file not exist: test_summary.info"
        }
        
        junit(
            allowEmptyResults: true,
            testResults: "${config.repo}/${reportDir}"
        )
    }
}

taskConfig = common.loadTaskConfig(INPUT_JSON)
common.runWithPod(taskConfig,runBody) 


