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

tcmsHost = "https://tcms.pingcap.net/"

planExecIDs = []

def triggerPlan(String planName) {
    planNameEncode = URLEncoder.encode(planName, "UTF-8")
    getPlanIDURL = "${tcmsHost}api/v1/plans/autocomplete?type=GITHUB&query=${planName}&count=1&pretty"
    getPlanID = httpRequest url: getPlanIDURL, httpMode: 'GET'
    planResp = readJSON text: getPlanID.content
    if (!planResp["data"][0]) {
        return
    }
    planIntID = planResp["data"][0]["id"].toInteger()
    println "${planName} id: ${planIntID}"
    def triggerData = [
            type: "ONCE",
            name: "ci-trigger",
            summary: "trigger from ci",
            planID: planIntID
        ]
    def json = groovy.json.JsonOutput.toJson(triggerData)
    triggerURL = "${tcmsHost}api/v1/triggers"
    withCredentials([string(credentialsId: 'tcms-token', variable: 'TOKEN')]) {
        trigger = httpRequest customHeaders: [[name: 'Authorization', value: "Bearer ${TOKEN}"]], contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: json, url: triggerURL
    }
    triggerResp = readJSON text: trigger.content
    triggerID = triggerResp["id"].toString()
    sleep(3)
    getPlanExecIDURL = "${tcmsHost}api/v1/plan-executions?trigger_ids=${triggerID}&pretty"
    getPlanExecID = httpRequest url: getPlanExecIDURL, httpMode: 'GET'
    planExecResp = readJSON text: getPlanExecID.content
    if (!planExecResp["data"][0]) {
        return
    }
    planExecID = planExecResp["data"][0]["id"].toString()
    planExecIDs.add(planExecID)
}

taskConfig = common.loadTaskConfig(INPUT_JSON)


def plansString = taskConfig.params["plans"]
def plans = plansString.split(",")
for (plan in plans) {
    triggerPlan(plan)
}

node("lightweight_pod") {
    container("golang") {
        stage("start pipeline"){
            println "start tcms plans:"
            for (execID in planExecIDs) {
                println "https://tcms.pingcap.net/dashboard/executions/plan/${execID}"
            }
        }
    }
}

timeout(time: taskConfig.timeout, unit: 'MINUTES') {
    stage("wait for plans execution to end"){
        currentExecIDs = planExecIDs
        currentNum = currentExecIDs.size()
        resultMap = [:]
        success = true
        finishedStatus = ["SUCCESS","FAILURE","SKIPPED","OMITTED","TIMEOUT","CANCELLED","ERROR"]
        errorfinishedStatus = ["FAILURE","TIMEOUT","CANCELLED","ERROR"]
        while (currentNum != 0) {
            sleep(60)
            finishedExecIDs = []
            for (execID in currentExecIDs) {
                getExecStatusURL = "${tcmsHost}api/v1/plan-executions/${execID}"
                getExecStatus = httpRequest url: getExecStatusURL, httpMode: 'GET'
                execStatusResp = readJSON text: getExecStatus.content
                status = execStatusResp["status"].toString()
                if (finishedStatus.contains(status)) {
                    finishedExecIDs.add(execID)
                    resultMap[execID] = status
                    if (errorfinishedStatus.contains(status)) {
                        success = false
                    }
                }
            }
            for (id in finishedExecIDs) {
                currentExecIDs.remove(id)
                currentNum = currentNum -1
            }
        }
    }
}

def runBody = {config ->
    stage("get result") {
        println "execution result:"
        for (result in resultMap) {
            println "https://tcms.pingcap.net/dashboard/executions/plan/${result.key}, status: ${result.value}"
        }
    }
    if (!success) {
        currentBuild.result = "FAILURE"
    }
}

taskConfig = common.loadTaskConfig(INPUT_JSON)
common.runWithPod(taskConfig,runBody) 



