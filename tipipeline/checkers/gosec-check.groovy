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

            stage("Gosec check") {
                def cmd = config.params["shellScript"]
                sh """
                curl -sfL https://raw.githubusercontent.com/securego/gosec/master/install.sh | sh -s -- -b ${ws}/bin v2.9.1
                """
                sh """
                export PATH=${ws}/bin:\$PATH
                gosec -version
                
                ${cmd}
                """
            }

        }
        currentBuild.result = "SUCCESS"

    } catch (err) {
        throw err
    } finally {
        sh """
            wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-gosec/agent-gosec.py
            python3 agent-gosec.py ${config.repo}/results.xml
        """
        ENV_GOSEC_SUMMARY = sh(script: "cat test_summary.info", returnStdout: true).trim()
        println ENV_GOSEC_SUMMARY
        currentBuild.description = "${ENV_GOSEC_SUMMARY}"

        sh """
            wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_gosec/tiinsight-agent-gosec.py
            python3 tiinsight-agent-gosec.py ${config.repo} ${config.branch} ${config.commitID} ${config.repo}/results.xml ${BUILD_URL}
        """

        junit(
                allowEmptyResults: true,
                testResults: "${config.repo}/results.xml"
        )
    }
}

taskConfig = common.loadTaskConfig(INPUT_JSON)
common.runWithPod(taskConfig,runBody) 


