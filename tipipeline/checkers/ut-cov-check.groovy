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
                    sh config.params["shellScript"]
            }
        }

        stage("Codecov") {
            // upload coverage report to codecov
            // read coverage rate from codecov api
            dir("${ws}/${config.repo}") {
                // coverage file: test_report/*coverage.out
                def covReport = config.params["covReport"]
                timeout(5) {
                    if (config.pullRequest != null && config.pullRequest != "") {
                        sh """
                        curl -LO ${FILE_SERVER_URL}/download/cicd/ci-tools/codecov
                        chmod +x codecov
                        ./codecov -f "${covReport}"  -t ${CODECOV_TOKEN} -C ${config.commitID} -P ${config.pullRequest} -b ${BUILD_NUMBER}
                        """
                    } else {
                        sh """
                        curl -LO ${FILE_SERVER_URL}/download/cicd/ci-tools/codecov
                        chmod +x codecov
                        ./codecov -f "${covReport}" -t ${CODECOV_TOKEN} -C ${config.commitID} -b ${BUILD_NUMBER} -B ${config.branch}
                        """
                    }
                }
                sleep(time:120,unit:"SECONDS")
                def response = httpRequest Authorization: CODECOV_API_TOKEN, url: "https://codecov.io/api/gh/pingcap/tidb/commit/${config.commitID}"
                println('Status: '+response.status)
                def obj = readJSON text:response.content
                if (response.status == 200) {
                    println(obj.commit.totals)
                    lines_coverage_message = "Lines coverage ${obj.commit.totals.c.toFloat().round(2)}%."
                    lines_coverage_rate = obj.commit.totals.c
                    println('Coverage: '+obj.commit.totals.c)
                    println("Files count: "+ obj.commit.totals.f)
                    println("Lines count: "+obj.commit.totals.n)
                    println("Hits count: "+obj.commit.totals.h)
                    println("Misses count: "+obj.commit.totals.m)
                    println("Paritials count: "+obj.commit.totals.p)

                    println('Coverage: '+obj.commit.totals.diff[5])
                    println("Files count: "+ obj.commit.totals.diff[0])
                    println("Lines count: "+obj.commit.totals.diff[1])
                    println("Hits count: "+obj.commit.totals.diff[2])
                    println("Misses count: "+obj.commit.totals.diff[3])
                    println("Paritials count: "+obj.commit.totals.diff[4])
                } else {
                    println('Error: '+response.content)
                    println('Status not 200: '+response.status)
                }
                
            }
        }

    }  
    catch (err) {
        println "error found======"
        echo "Caught: ${err}"
        throw err
    } finally {
        def utReport = config.params["utReport"]
        sh """
            wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-ut/agent-ut-codecov.py
            python3 agent-ut-codecov.py \"${config.repo}/${utReport}\" || true

            wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_ut_codecov/tiinsights-agent-ut-codecov.py
            python3 tiinsights-agent-ut-codecov.py ${config.repo} ${config.branch} ${config.commitID} ${config.taskName} \"${config.repo}/${utReport}\" ${lines_coverage_rate} ${BUILD_URL}
        """
        ENV_TEST_SUMMARY = sh(script: "cat test_summary.info", returnStdout: true).trim()
        println ENV_TEST_SUMMARY
        currentBuild.description = "${ENV_TEST_SUMMARY}"
        if (lines_coverage_message != "") {
            currentBuild.description = currentBuild.description + lines_coverage_message
        }
        println currentBuild.description

        junit testResults: "${config.repo}/${utReport}"
        if (currentBuild.result == 'UNSTABLE') {
            currentBuild.result = 'FAILURE'
        }
    }
}

taskConfig = common.loadTaskConfig(INPUT_JSON)
common.runWithPod(taskConfig,runBody) 


