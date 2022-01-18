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

        stage(config.taskName) {
            println config.taskName
        }

        stage("Download binary from fileserver") {
            sh """
                wget -q -c ${config.cacheCodeURL} -O - | tar -xz
            """
        }

        stage("Test") {
            dir("${ws}/${config.repo}") {
                sh '''
                    go get github.com/jstemmer/go-junit-report
                    go get github.com/axw/gocov/gocov
                    go get github.com/AlekSi/gocov-xml
                '''
                sh config.params["shellScript"]
            }
        }

    }  
    catch (err) {
        throw err
    } finally {
        def utReport = config.params["utReport"]
        def covReport = config.params["covReport"]
        def coverageRate = config.params["coverageRate"]
        sh """
            wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-ut/agent-ut.py
            python3 agent-ut.py ${config.repo}/${utReport} ${config.repo}/${covReport} ${coverageRate}
            
            wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_ut/tiinsight-agent-ut.py
            python3 tiinsight-agent-ut.py ${config.repo} ${config.branch} ${config.commitID} ${config.taskName} ${config.repo}/${covReport} ${config.repo}/${utReport} ${BUILD_URL}
        """
        ENV_TEST_SUMMARY = sh(script: "cat test_summary.info", returnStdout: true).trim()
        println ENV_TEST_SUMMARY
        currentBuild.description = "${ENV_TEST_SUMMARY}"

        junit testResults: "${config.repo}/${utReport}"
        if (currentBuild.result == 'UNSTABLE') {
            currentBuild.result = 'FAILURE'
        }
        echo currentBuild.result
        cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: "${config.repo}/${covReport}", lineCoverageTargets: "${coverageRate}, ${coverageRate}, ${coverageRate}", maxNumberOfBuilds: 10, onlyStable: false, sourceEncoding: 'ASCII', zoomCoverageChart: false
        echo currentBuild.result
    }
}

taskConfig = common.loadTaskConfig(INPUT_JSON)
common.runWithPod(taskConfig,runBody) 


