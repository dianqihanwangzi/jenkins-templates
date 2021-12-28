properties([
    pipelineTriggers([cron('H 2 * * *')]),
])

def run_with_pod(Closure body) {
    def label = "check-all-image-by-trivy-" + UUID.randomUUID().toString()
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'trivy', alwaysPullImage: true,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '1000m', resourceRequestMemory: '2Gi',
                            resourceLimitCpu: '1000m', resourceLimitMemory: "2Gi",
                            command: '/bin/sh -c', args: 'cat',
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}


def check_image(image) {
    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'GITHUB_TOKEN')]) {
        sh "trivy image ${image}"
    }
}

releaseRepos = ["dumpling","br","ticdc","tidb-binlog","tics","tidb","tikv","pd"]

run_with_pod {
    container("trivy") {
        stage("prepare trivy") {
            sh "sudo rpm -ivh https://github.com/aquasecurity/trivy/releases/download/v0.22.0/trivy_0.22.0_Linux-64bit.rpm"
        }
        def releaseRepos = ["dumpling","br","ticdc","tidb-binlog","tics","tidb","tikv","pd","tidb-lightning"]
        for (item in releaseRepos) {
            stage("check ${item} image"){
                def image = "hub.pingcap.net/qa/${item}:master"
                check_image(image)
            }       
        }
    }
}


