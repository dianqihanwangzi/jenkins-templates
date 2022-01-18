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


