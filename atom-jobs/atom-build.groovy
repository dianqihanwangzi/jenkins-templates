properties([
    parameters([
        string(
            defaultValue: 'tidb',
            name: 'REPO',
            trim: true
        ),
        string(
            defaultValue: 'http://fileserver.pingcap.net/download/builds/pingcap/devops/cachecode/tidb/086a63f4713416ae5976eecc5a4d7c40f6121079/tidb.tar.gz',
            name: 'CACHE_CODE_FILESERVER_URL',
            trim: true
        ),
        string(
            defaultValue: '086a63f4713416ae5976eecc5a4d7c40f6121079',
            name: 'COMMIT_ID',
            trim: true,
        ),
        text(
            defaultValue: 'make',
            name: 'BUILD_CMD',
            trim: true
        ),
        string(
            defaultValue: 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest',
            name: 'BUILD_ENV',
            trim: true,
        ),
        string(
            defaultValue: 'bin',
            name: 'OUTPUT_DIR',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'TRIGGER_EVENT',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'BRANCH',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'TASK_NAME',
            trim: true
        ),
    ])
])

def run_with_pod(Closure body) {
    def label = "atom-build-atom-job" + UUID.randomUUID().toString()
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'build', alwaysPullImage: true,
                            image: BUILD_ENV, ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '2Gi',
                            resourceLimitCpu: '4000m', resourceLimitMemory: "4Gi",
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes: [
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                    nfsVolume(mountPath: '/nfs/cache', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs', readOnly: false),
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}


try {
    run_with_pod {
        container("build") {
            def ws = pwd()
            stage("${TASK_NAME}") {
                println "${TASK_NAME}"
            }

            stage("Download code from fileserver") {
                sh '''
                    wget -q -c ${CACHE_CODE_FILESERVER_URL} -O - | tar -xz
                '''
            }

            stage("Build") {
                dir("${ws}/${REPO}") {
                    sh BUILD_CMD
                }

            }

            stage("Upload") {
                def filepath = "builds/pingcap/devops/atom-build/${REPO}/${COMMIT_ID}/${REPO}.tar.gz"
                def sha256sumFilepath = "builds/pingcap/devops/atom-build/${REPO}/${COMMIT_ID}/${REPO}.tar.gz.sha256sum"
                sh """
                    tar -czf ${REPO}.tar.gz ${REPO}/${OUTPUT_DIR}
                    sha256sum ${REPO}.tar.gz > ${REPO}.tar.gz.sha256sum   
                    curl -F ${filepath}=@${REPO}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }

            stage("Print url") {
                println "${REPO} ${COMMIT_ID}"
                println "Atom build output url: ${FILE_SERVER_URL}/download/builds/pingcap/devops/atom-build/${REPO}/${COMMIT_ID}/${REPO}.tar.gz"
            }

            currentBuild.result = "SUCCESS"
            currentBuild.description = "build success"
        }
    }
}

catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
    currentBuild.description = "build aborted"
} catch (hudson.AbortException e) {
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
        currentBuild.description = "build aborted"
    } else {
        currentBuild.result = "FAILURE"
        currentBuild.description = "build failed"
    }
} catch (InterruptedException e) {
    println e
    currentBuild.result = "ABORTED"
    currentBuild.description = "build aborted"
}
catch (Exception e) {
    if (e.getMessage().equals("hasBeenCached")) {
        currentBuild.result = "SUCCESS"
        currentBuild.description = "build success"
    } else {
        currentBuild.result = "FAILURE"
        currentBuild.description = "build failed"
        slackcolor = 'danger'
        echo "${e}"
    }
}
