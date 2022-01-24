// properties([
//         parameters([
//                 string(
//                         defaultValue: '',
//                         name: 'PIPELINE_YAML',
//                         trim: true
//                 ),
//         ])
// ])

common = {}
commitID = ""
taskStartTimeInMillis = System.currentTimeMillis()

pullRequestAuthor = ghprbPullAuthorLogin
triggerGithubID = ghprbPullAuthorLogin
if (ghprbTriggerAuthorLogin != null && ghprbTriggerAuthorLogin != "") {
    triggerGithubID = ghprbTriggerAuthorLogin
}


node("lightweight_pod") {
    container("golang") {
        checkout scm
        common = load "tipipeline/common.groovy"
        // sh """
        // wget http://fileserver.pingcap.net/download/cicd/debug/common.groovy
        // """
        // common = load "common.groovy"

        pipelineSpec = common.loadPipelineConfig(PIPELINE_YAML, pullRequestAuthor, triggerGithubID)
        common.runPipeline(pipelineSpec, "verify", ghprbTargetBranch, ghprbActualCommit, ghprbPullId, triggerGithubID)
    }
}