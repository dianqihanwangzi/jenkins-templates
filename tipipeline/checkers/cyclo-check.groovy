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

            stage("Download gocyclo") {
                sh """
                go install github.com/fzipp/gocyclo/cmd/gocyclo@latest
                """
            }

            stage("Cyclo check") {
                def cmd = config.params["shellScript"]
                sh """
                export PATH=${ws}/go/bin:\$PATH
                ${cmd}
                """
            }    
        }
        currentBuild.result = "SUCCESS"
    } catch (e) {
        println "error: ${e}"
        throw e
    } finally {
        println "finally"
        sh """
        wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_cyclo/tiinsight-agent-cyclo.py
        python3 tiinsight-agent-cyclo.py ${config.repo} ${config.branch} ${config.commitID} ${config.repo}/repo_cyclo.log ${BUILD_URL}
        """
        sh """
        wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-cyclo/agent-cyclo.py
        python3 agent-cyclo.py ${config.repo}/repo_cyclo.log
        """
        ENV_CYCLO_SUMMARY = sh(script: "cat cyclo_summary.info", returnStdout: true).trim()
        println ENV_CYCLO_SUMMARY
        currentBuild.description = "${ENV_CYCLO_SUMMARY}"
    }
}

taskConfig = common.loadTaskConfig(INPUT_JSON)
common.runWithPod(taskConfig,runBody) 


