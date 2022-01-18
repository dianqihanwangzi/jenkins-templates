properties([
        parameters([
                string(
                        defaultValue: '{}',
                        name: 'INPUT_JSON',
                        trim: true
                ),
        ])
])

@NonCPS // has to be NonCPS or the build breaks on the call to .each
def parseBuildResult(list) {
    def total_test = 0
    def failed_test = 0
    def success_test = 0

    list.each { item ->
        echo "${item}"
        if (item.status == "success") {
            success_test += 1
        } else {
            failed_test += 1
        }
    }
    total_test = success_test + failed_test

    println "total_test: ${total_test}"
    println "success_test: ${success_test}"
    println "failed_test: ${failed_test}"

    def resp_str = ""
    if (failed_test > 0) {
        resp_str = "failed ${failed_test}, success ${success_test}, total ${total_test}"
    } else {
        resp_str = "all ${total_test} tests passed"
    }

    return resp_str      
}

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
        def summary = [:]
        def resultStrValid = false
        def triggerJobName = config.params["itJobName"]

        stage("Debug INFO") {
            println config.taskName
            println "trigger job name: ${triggerJobName}"
        }

        stage("Trigger job") {
            default_params = [
                    string(name: 'triggered_by_upstream_ci', value: config.triggerEvent),
                    booleanParam(name: 'release_test', value: true),
                    string(name: 'release_test__release_branch', value: config.branch),
                    string(name: 'release_test__tidb_commit', value: config.commitID),
            ]
            dir("${ws}/${config.pipelineName}") {
                    result = build(job: "${triggerJobName}", parameters: default_params, wait: true, propagate: false)
                    buildResultInStr = result.getDescription()
                    if (result.getResult() != "SUCCESS") {
                        currentBuild.result = "FAILURE"
                    }
                    if (result.getDescription() != null && result.getDescription() != "") {
                        println result.getDescription()
                        def jsonObj = readJSON text: result.getDescription()
                        writeJSON file: 'result.json', json: jsonObj, pretty: 4
                        sh 'cat result.json'
                        sh """
                        wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_daily_it/tiinsights-agent-daily-it.py
                        python3 tiinsights-agent-daily-it.py ${config.triggerEvent} ${triggerJobName} ${config.commitID} ${config.branch} "result.json"
                        """
                        archiveArtifacts artifacts: 'result.json', fingerprint: true
                        summary_info = parseBuildResult(jsonObj)
                        currentBuild.description = summary_info
                    } else {
                        "println not found valid result contains subtask info"
                    }
            }
        }
    }  
    catch (err) {
        throw err
    }
}

taskConfig = common.loadTaskConfig(INPUT_JSON)
common.runWithPod(taskConfig,runBody) 


