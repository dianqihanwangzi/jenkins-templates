// properties([
//         parameters([
//                 string(
//                         defaultValue: '',
//                         name: 'PIPELINE_YAML',
//                         trim: true
//                 ),
//         ])
// ])

def get_sha(repo,branch) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
}

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

        pipelineSpec = common.loadPipelineConfig(PIPELINE_YAML, "", "")
        commitID = get_sha(pipelineSpec.repo,pipelineSpec.defaultRef)
        println "commitID: ${commitID}"
        if (commitID == "" || commitID == null) {
            throw "commitID is empty"
        }
        common.runPipeline(pipelineSpec, "daily", pipelineSpec.defaultRef, commitID, "", "")
    }
}