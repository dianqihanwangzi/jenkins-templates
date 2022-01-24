// properties([
//         parameters([
//                 string(
//                         defaultValue: '',
//                         name: 'PIPELINE_YAML',
//                         trim: true
//                 ),
//         ])
// ])

REF = "refs/heads/master"
COMMIT_ID = "493fe783d76f36f71295d2b4fa8fc8caacbf73c7"

BRANCH = REF
if (REF.startsWith("refs/heads/")) {
    BRANCH = REF.replaceAll("refs/heads/","")
}

PULL_REQUEST_AUTHOR = ""

common = {}
commitID = ""
taskStartTimeInMillis = System.currentTimeMillis()

node("lightweight_pod") {
    container("golang") {
        checkout scm
        common = load "tipipeline/common.groovy"
        // sh """
        // wget http://fileserver.pingcap.net/download/cicd/debug/common.groovy
        // """
        // common = load "common.groovy"

        pipelineSpec = common.loadPipelineConfig(PIPELINE_YAML, PULL_REQUEST_AUTHOR, "")
        common.runPipeline(pipelineSpec, "merge", BRANCH, COMMIT_ID, "", "")
        updateCommitIDStatus(pipelineSpec)
    }
}

def updateCommitIDStatus(pipeline) {
    node("github-status-updater") {
        stage("Update commit status") {
            container("github-status-updater") {
                if (currentBuild.result == "ABORTED") {
                    DESCRIPTION = 'Jenkins job aborted'
                    // Commit state. Possible values are 'pending', 'success', 'error' or 'failure'
                    STATUS = 'error'
                } else if (currentBuild.result == "FAILURE") {
                    DESCRIPTION = 'Jenkins job failed'
                    STATUS = 'failure'
                } else if (currentBuild.result == "SUCCESS") {
                    DESCRIPTION = 'Jenkins job success'
                    STATUS = 'success'
                } else {
                    DESCRIPTION = 'Jenkins job meets something wrong'
                    STATUS = 'error'
                }
                sh """
            cat > env_param.conf <<EOF
export org=${pipeline.owner}
export repo=${pipeline.repo}
export COMMIT_ID="${pipeline.commitID}"
export STATUS="${STATUS}"
export DESCRIPTION="${DESCRIPTION}"
EOF
                """
                withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                    sh """#!/bin/bash
                    set +x
                    source env_param.conf
                    github-status-updater \
                        -action update_state \
                        -token ${TOKEN} \
                        -owner ${pipeline.owner} \
                        -repo ${pipeline.repo} \
                        -ref  ${pipeline.commitID} \
                        -state ${STATUS} \
                        -context ${pipeline.repo}/merge-ci \
                        -description "${DESCRIPTION}" \
                        -url "${RUN_DISPLAY_URL}"
                    """
                }
            }
        }
    }
}