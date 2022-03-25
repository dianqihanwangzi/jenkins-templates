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
    getPlanIDURL = "${tcmsHost}api/v1/plans/autocomplete?type=GITHUB&query=${planName}&count=1&pretty"
    getPlanIDURL = URLEncoder.encode(getPlanIDURL, "UTF-8")
    getPlanID = httpRequest url: getPlanIDURL, httpMode: 'GET'
    planResp = readJSON text: getPlanID.content
    if (planResp["data"].length() <= 0) {
        return
    }
    planIntID = planResp["data"][0]["id"].toInteger()
    def triggerData = [
            type: "ONCE",
            name: "ci-trigger-${planName}",
            summary: "trigger from ci",
            planID: planIntID
        ]
    def json = groovy.json.JsonOutput.toJson(triggerData)
    triggerURL = "${tcmsHost}api/v1/triggers"
    trigger = httpRequest authentication: 'tcms-token', contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: json, url: triggerURL
    triggerResp = readJSON text: trigger.content
    triggerID = triggerResp["id"].toString()
    sleep(3)
    getPlanExecIDURL = "${tcmsHost}api/v1/plan-executions?trigger_ids=${triggerID}&pretty"
    getPlanExecIDURL = URLEncoder.encode(getPlanExecIDURL, "UTF-8")
    getPlanExecID = httpRequest url: getPlanExecIDURL, httpMode: 'GET'
    planExecResp = readJSON text: getPlanExecID.content
    if (planExecResp["data"].length() <= 0) {
        return
    }
    planExecID = planExecResp["data"][0]["id"].toString()
    planExecIDs.add(planExecID)
}



def runBody = {config ->
    def plansString = config.params["plans"]
    def plans = plansString.split(",")
    for (plan in plans) {
        triggerPlan(plan)
    }

    currentExecIDs = planExecIDs
    finishedStatus = ["SUCCESS","FAILURE","SKIPPED","OMITTED","TIMEOUT","CANCELLED","ERROR"]
    while (currentExecIDs.length() != 0) {
        for (execID in currentExecIDs) {
            getExecStatusURL = "${tcmsHost}api/v1/plan-executions/${execID}"
            getExecStatus = httpRequest url: getExecStatusURL, httpMode: 'GET'
            execStatusResp = readJSON text: getExecStatus.content
            status = triggerResp["status"].toString()
            if (finishedStatus.contains(status)) {
                currentExecIDs.remove(execID)
            }
        }
        sleep(60)
    }

    println "execution result:"
    for (execID in planExecIDs) {
        println "https://tcms.pingcap.net/dashboard/executions/plan/${execID}"
    }
}

taskConfig = common.loadTaskConfig(INPUT_JSON)
common.runWithPod(taskConfig,runBody) 



