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
    def ws = pwd()
    dir() {
        stage(config.taskName) {
            println config.taskName
        }
        
        stage("Download code from fileserver") {
            sh """
            curl ${config.cacheCodeURL} | tar xz --strip-components=1
            """
        }
        stage("common task") {
            sh config.params["shellScript"]
        }
    }
    currentBuild.result = "SUCCESS"
    currentBuild.description = ""
}

taskConfig = common.loadTaskConfig(INPUT_JSON)
common.runWithPod(taskConfig,runBody) 


