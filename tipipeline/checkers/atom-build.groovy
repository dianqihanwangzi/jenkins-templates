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
node("${GO1160_BUILD_SLAVE}") {
    container("golang") {
        checkout scm
        common = load "tipipeline/common.groovy"
    }
} 
def runBody = {config ->
    def ws = pwd()
    stage(config.taskName) {
        println config.taskName
    }

    stage("Download code from fileserver") {
        sh """
            wget -q -c ${config.cacheCodeURL} -O - | tar -xz
        """
    }

    stage("Build") {
        dir("${ws}/${config.repo}") {
            sh config.params["shellScript"]
        }

    }

    stage("Upload") {
        def filepath = "builds/pingcap/devops/atom-build/${config.repo}/${config.commitID}/${config.repo}.tar.gz"
        def sha256sumFilepath = "builds/pingcap/devops/atom-build/${config.repo}/${config.commitID}/${config.repo}.tar.gz.sha256sum"
        def outputDir = config.params["outputDir"]
        sh """
            tar -czf ${config.repo}.tar.gz ${config.repo}/${outputDir}
            sha256sum ${config.repo}.tar.gz > ${config.repo}.tar.gz.sha256sum   
            curl -F ${filepath}=@${config.repo}.tar.gz ${FILE_SERVER_URL}/upload
        """
    }

    stage("Print url") {
        println "${config.repo} ${config.commitID}"
        println "Atom build output url: ${FILE_SERVER_URL}/download/builds/pingcap/devops/atom-build/${config.repo}/${config.commitID}/${config.repo}.tar.gz"
    }

    currentBuild.result = "SUCCESS"
}

taskConfig = common.loadTaskConfig(INPUT_JSON)
common.runWithPod(taskConfig,runBody) 


