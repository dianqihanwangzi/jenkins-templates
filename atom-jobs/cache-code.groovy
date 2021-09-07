/*
* input
* @REPO(string: github repo name, Required)
* @PULL_ID(int: pull request id, Optional)
* @COMMIT_ID(string: commit id request id, Required)
* @BRANCH(string: branch name, Optional)
*
*
* output
* @CACHE_CODE_URL(string: cache code tar file download url)
* @CACHE_CODE_SHA256_URL(string: cache code tar file sha256 sum file url)
*
* doc
* https://docs.google.com/document/d/1hcs4F6BUKiiLHetLMXM2323_J5_yLdG5OyXddNt2-Vk/edit?usp=sharing
*/

properties([
    parameters([
        string(
            defaultValue: '',
            name: 'REPO',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'PULL_ID',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'COMMIT_ID',
            trim: true,
        ),
        string(
            defaultValue: '',
            name: 'BRANCH',
            trim: true
        ),
        booleanParam(
            defaultValue: false,
            name: 'FORCE_CACHE'
        ),
    ])
])


repoUrlMap = [
        tidb    : 'git@github.com:pingcap/tidb.git',
        tikv    : 'git@github.com:tikv/tikv.git',
        pd      : 'git@github.com:tikv/pd.git',
        cdc     : 'git@github.com:pingcap/ticdc.git',
        br      : 'git@github.com:pingcap/br.git',
        dumpling: 'git@github.com:pingcap/dumpling.git',
        binlog  : 'git@github.com:pingcap/tidb-binlog.git',
        dm      : 'git@github.com:pingcap/dm.git',
        tics    : 'git@github.com:pingcap/tics.git',
        tiem    : 'git@github.com:pingcap-inc/tiem.git',
        docs-cn : 'git@github.com:pingcap/docs-cn.git',
]

def repoValid = repoUrlMap.containsKey(REPO)
assert repoValid

def run_with_pod(Closure body) {
    def label = "cache-code-atom-job"
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def pod_go_docker_image = "hub-new.pingcap.net/jenkins/centos7_golang-1.16"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '2Gi',
                            resourceLimitCpu: '4000m', resourceLimitMemory: "4Gi",
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOMODCACHE', value: '/nfs/cache/mod'),
                                      containerEnvVar(key: 'GOPATH', value: '/go')],
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

def doCheckout(repoUrl, commit, refspec) {
    checkout(changelog: false, poll: false, scm: [
            $class           : "GitSCM",
            branches         : [
                    [name: "${commit}"],
            ],
            userRemoteConfigs: [
                    [
                            url          : repoUrl,
                            refspec      : refspec,
                            credentialsId: "github-sre-bot-ssh",
                    ]
            ],
            extensions       : [
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
            ],
    ])
}

def checkoutCode() {
    def resSpec = "+refs/heads/*:refs/remotes/origin/*"
    // retrieve pull request code
    if (PULL_ID != null && PULL_ID != "") {
        resSpec = "+refs/pull/${PULL_ID}/*:refs/remotes/origin/pr/${PULL_ID}/*"
    } else {
        // retrieve one branch
        resSpec = "+refs/heads/${BRANCH}:refs/remotes/origin/${BRANCH}"
    }

    try {
        doCheckout(repoUrlMap[REPO], COMMIT_ID, resSpec)
    } catch (info) {
        retry(2) {
            echo "checkout failed, retry.."
            sleep 5
            if (sh(returnStatus: true, script: '[ -d .git ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                echo ".git not exist or not a valid git dir. Delete dir..."
                deleteDir()
            }
            doCheckout(repoUrlMap[REPO], COMMIT_ID, resSpec)
        }
    }
}

try {
    run_with_pod {
        container("golang") {
            def ws = pwd()
            def cacheCodeUrl = "${FILE_SERVER_URL}/download/builds/pingcap/devops/cachecode/${REPO}/${COMMIT_ID}/${REPO}.tar.gz"

            stage("Search cache") {
                cacheExisted = sh(returnStatus: true, script: """
				    if curl --output /dev/null --silent --head --fail ${cacheCodeUrl}; then exit 0; else exit 1; fi
				    """)
                if (cacheExisted == 0 && !FORCE_CACHE.toBoolean() ) {
                    println "cache code already existed\n\ncache download url: ${cacheCodeUrl}"
                    throw new RuntimeException("hasBeenCached")
                }
            }

            stage("Do checkout") {
                dir("/home/jenkins/agent/code-archive") {
                    def repoDailyCache = "/home/jenkins/agent/ci-cached-code-daily/src-${REPO}.tar.gz"
                    container("golang") {
                        if (fileExists(repoDailyCache)) {
                            timeout(2) {
                                sh """
                                cp -R ${repoDailyCache}*  ./
                                mkdir -p ${ws}/${REPO}
                                tar -xzf src-${REPO}.tar.gz -C ${ws}/${REPO} --strip-components=1
                                """
                            }
                        }
                    }
                }
                dir("${ws}/${REPO}") {
                    checkoutCode()
                }
            }

            stage("Upload") {
                def filepath = "builds/pingcap/devops/cachecode/${REPO}/${COMMIT_ID}/${REPO}.tar.gz"
                def sha256sumFilepath = "builds/pingcap/devops/cachecode/${REPO}/${COMMIT_ID}/${REPO}.tar.gz.sha256sum"
                sh """
                tar -C ${ws} -czf ${REPO}.tar.gz ${REPO}
                sha256sum ${REPO}.tar.gz > ${REPO}.tar.gz.sha256sum            
                ls -lh ${REPO}.tar.gz
                curl -F ${filepath}=@${REPO}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }

            stage("Print url") {
                println "tidb ${COMMIT_ID}"
                println "download cached code url: ${FILE_SERVER_URL}/download/builds/pingcap/devops/cachecode/${REPO}/${COMMIT_ID}/${REPO}.tar.gz"
            }

            currentBuild.result = "SUCCESS"
        }
    }

}

catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println e
    currentBuild.result = "ABORTED"
}
catch (Exception e) {
    if (e.getMessage().equals("hasBeenCached")) {
        currentBuild.result = "SUCCESS"
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
}
