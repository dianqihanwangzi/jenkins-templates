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

now = Calendar.getInstance().getTime().getTime()/1000
timestamp = now.intValue().toString()
triggerCI = httpRequest url: tcmsHost + "api/v1/dailyci/trigger", httpMode: 'POST'
ciResp = readJSON text: triggerCI.content
id = ciResp["id"].toString()
ciFinished = false
ciDuration = 0
while(!ciFinished) {
    sleep(300)
    ciDuration = ciDuration +300
    // ci breaks when timeout(23 hours)
    if (ciDuration > 115200) {
        echo "daily ci timeout reached, stop waiting task complete and collect result now."
        break
    }
    statusCI = httpRequest tcmsHost + "api/v1/dailyci/trigger/" + id
    statusResp = readJSON text: statusCI.content
    ciFinished = statusResp["finished"].toBoolean()
}

def runBody = {config ->

    sh """
    wget -O ./${config.branch}.xml ${tcmsHost}api/v1/dailyci?id=${id}&branch=${config.branch}
    mkdir -p tcms-report/
    cp ./${config.branch}.xml tcms-report/
    """
    sleep(20)
    sh """
    cd tcms-report/
    wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_tcms/tiinsight-agent-tcms.py
    python3 tiinsight-agent-tcms.py "${config.repo}" ${config.branch} "empty" "${config.branch}.xml"

    wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-tcms/agent-tcms.py
    python3 agent-tcms.py ${config.branch} tcms.xml ${BUILD_URL}
    """
    ENV_TCMS_SUMMARY = sh(script: "cat tcms_summary.info", returnStdout: true).trim()
    println ENV_TCMS_SUMMARY
    currentBuild.description = "${ENV_TCMS_SUMMARY}"
    junit testResults: "tcms-report/${config.branch}.xml"
}

taskConfig = common.loadTaskConfig(INPUT_JSON)
common.runWithPod(taskConfig,runBody) 


