/*
* @GIT_BRANCH(string:repo branch, Required)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
*/

properties([
        parameters([
                string(
                        defaultValue: 'master',
                        name: 'GIT_BRANCH',
                        trim: true
                ),
                booleanParam(
                        defaultValue: false,
                        name: 'FORCE_REBUILD'
                ),
                booleanParam(
                        defaultValue: false,
                        name: 'NEED_MULTIARCH'
                ),
        ]),
        pipelineTriggers([
            parameterizedCron('''
                H H(0-23)/12 * * * % GIT_BRANCH=release-5.0;FORCE_REBUILD=false;NEED_MULTIARCH=false
                H H(0-23)/12 * * * % GIT_BRANCH=release-5.1;FORCE_REBUILD=false;NEED_MULTIARCH=false
                H H(0-23)/12 * * * % GIT_BRANCH=release-5.2;FORCE_REBUILD=false;NEED_MULTIARCH=false
                H H(0-23)/12 * * * % GIT_BRANCH=release-5.3;FORCE_REBUILD=false;NEED_MULTIARCH=false
                H H(0-23)/12 * * * % GIT_BRANCH=release-5.4;FORCE_REBUILD=false;NEED_MULTIARCH=false
                H H(0-23)/12 * * * % GIT_BRANCH=master;FORCE_REBUILD=false;NEED_MULTIARCH=true
            ''')
        ])
])


// ***
// thress type image: noremal, failpoint, debug, multiArch
//   type normal: use binary from build-common: like tidb-server or tikv-server
//   type failpoint: use binary from build-common but enable failpoint: like tidb-server-failpoint or tikv-server-failpoint
//   type debug: use binary from build-common and use diffrent image Dockerfile (use ceontos7 as base image other than alpine)

// type normal & failpoint & debug commonly build a single arch image: just linux-amd64 or just linux-arm64
// type multiArch build a multi arch image: build two arch image: linux-amd64 and linux-arm64 then merge them by manifest-tool
//   example: ${HARBOR_PROJECT_PREFIX}/tidb:master is valid for both linux-amd64 and linux-arm64
//            it contains two images: ${HARBOR_PROJECT_PREFIX}/tidb:master-linux-amd64 and ${HARBOR_PROJECT_PREFIX}/tidb:master-linux-arm64
// ***

string trimPrefix = {
    it.startsWith('release-') ? it.minus('release-').split("-")[0] : it 
}

HARBOR_PROJECT_PREFIX = "hub.pingcap.net/qa"


// for master branch: use default local tag: v6.0.0-nightly
RELEASE_TAG = "v6.0.0-nightly"
if (GIT_BRANCH.startsWith("release-")) {
    RELEASE_TAG = "v"+ trimPrefix(GIT_BRANCH) + ".0-nightly"
}


def get_sha(repo) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${GIT_BRANCH} -s=${FILE_SERVER_URL}").trim()
}


def test_binary_already_build(binary_url) {
    cacheExisted = sh(returnStatus: true, script: """
    if curl --output /dev/null --silent --head --fail ${binary_url}; then exit 0; else exit 1; fi
    """)
    if (cacheExisted == 0) {
        return true
    } else {
        return false
    }
}

def startBuildBinary(arch, binary, actualRepo, repo, sha1, failpoint) {

    def paramsBuild = [
    string(name: "ARCH", value: arch),
    string(name: "OS", value: "linux"),
    string(name: "EDITION", value: "community"),
    string(name: "OUTPUT_BINARY", value: binary),
    string(name: "REPO", value: actualRepo),
    string(name: "PRODUCT", value: repo),
    string(name: "GIT_HASH", value: sha1),
    string(name: "RELEASE_TAG", value: RELEASE_TAG),
    string(name: "TARGET_BRANCH", value: GIT_BRANCH),
    [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
    [$class: 'BooleanParameterValue', name: 'FAILPOINT', value: failpoint],
    ]
    println "paramsBuild: ${paramsBuild}"

    build job: "build-common",
        wait: true,
        parameters: paramsBuild
}


def parseBuildInfo(repo) {
    def actualRepo = repo
    if (repo == "br" && GIT_BRANCH == "master") {
        actualRepo = "tidb"
    }
    // Notice: the code of br has been merged to tidb from release-5.2, so we need to use tidb as actual repo
    if (repo == "br" && GIT_BRANCH.startsWith("release-") && GIT_BRANCH >= "release-5.2") {
        actualRepo = "tidb"
    }
    if (repo == "dumpling" && GIT_BRANCH == "master") {
        actualRepo = "tidb"
    }
    // Notice: the code of dumpling has been merged to tidb from release-5.3, so we need to use tidb as actual repo
    if (repo == "dumpling" && GIT_BRANCH.startsWith("release-") && GIT_BRANCH >= "release-5.3") {
        actualRepo = "tidb"
    }
    // Notice: repo ticdc has been renamed to tiflow from 2022/0/01, so we need to use tiflow as actual repo
    if (repo == "ticdc") {
        actualRepo = "tiflow"
    }
    // Notice: dm has been merged to tiflow from release-5.3.0, so we need to use tiflow as actual repo
    if (repo == "dm") {
        actualRepo = "tiflow"
    }
    // if (repo == "tiflash") {
    //     actualRepo = "tics"
    // }
    // TODO: tidb-lightning is so complex !!!
    // tidb-lightning is a part of br, and br is merged to tidb from release-5.2, so we need to use tidb as actual repo
    if (repo == "tidb-lightning") { // Notice: the code of br has been merged to tidb from release-5.2, so we need to use tidb as actual repo
        if (GIT_BRANCH.startsWith("release-") && GIT_BRANCH >= "release-5.2") {
            actualRepo = "tidb"
        } else if (GIT_BRANCH == "master") {
            actualRepo = "tidb"
        } else {
            actualRepo = "br"
        }
    }
    def sha1 =  get_sha(actualRepo)
    if (sha1 == "") {
        println "ERROR: can not get sha1 for ${repo} ${GIT_BRANCH}"
        throw new Exception("can not get sha1 for ${repo} ${GIT_BRANCH}")
    }
    println "repo: ${repo}, actualRepo: ${actualRepo}, sha1: ${sha1}"

    // tics need use tiflash dockerfile
    // tics has not  failpoint or debug image.
    //  repo support enable failpoint: tidb / tikv / pd / br
    //  repo support support debug image(amd64): br / dumpling / pd / ticdc / tidb / tidb-binlog / tidb-lightning / tikv 
    // if (repo == "tics") {
    //     imageName = "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH},${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}"
    //     imageNameAmd64 = "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}-amd64,${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}-amd64"
    //     imageNameArm64 = "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}-arm64,${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}-arm64"
    // }


    // def imageName = ""
    // def imageNameEnableFailpoint = ""
    // def imageNameForDebug = ""
    // def imageNameForDebugEnableFailpoint = ""
    // def imageNameAmd64 = ""
    // def imageNameArm64 = ""\

    // builds/pingcap/test/tidb/3e1cd2733a8e43670b25e7b2e53001eccac78147/centos7/tidb-linux-arm64.tar.gz

    def binaryAmd64 = "builds/pingcap/${repo}/test/${GIT_BRANCH}/${sha1}/linux-amd64/${repo}.tar.gz"
    def binaryArm64 = "builds/pingcap/test/${repo}/${sha1}/centos7/${repo}-linux-arm64.tar.gz"
    def dockerfileAmd64 = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/${repo}"
    def dockerfileArm64 = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-arm64/${repo}"
    def binaryAmd64Failpoint = "builds/pingcap/${repo}/test/failpoint/${GIT_BRANCH}/${sha1}/linux-amd64/${repo}.tar.gz"
    def binaryArm64Failpoint = "builds/pingcap/${repo}/test/failpoint/${GIT_BRANCH}/${sha1}/linux-arm64/${repo}.tar.gz"

    if (repo == "tics") {
        dockerfileAmd64 = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/tiflash"
        dockerfileArm64 = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-arm64/tiflash"
    }
    // if (repo == "tiflash") {
    //     binaryAmd64 = "builds/pingcap/tics/test/${GIT_BRANCH}/${sha1}/linux-amd64/tics.tar.gz"
    //     binaryArm64 = "builds/pingcap/test/tics/${sha1}/centos7/tics-linux-arm64.tar.gz"
    // }
    if (repo == "tidb-lightning") {
        // Notice: the code of br has been merged to tidb from release-5.2, so we need to use tidb binary
        // tar package of tidb build by atom-job include these binaries:
        // 
        // example: download/builds/pingcap/br/master/3e1cd2733a8e43670b25e7b2e53001eccac78147/centos7/br.tar.gz
        binaryAmd64 = "builds/pingcap/br/test/${GIT_BRANCH}/${sha1}/centos7/br-linux-amd64.tar.gz"
        binaryArm64 = "builds/pingcap/br/test/${GIT_BRANCH}/${sha1}/centos7/br-linux-arm64.tar.gz"
        binaryAmd64Failpoint = "builds/pingcap/br/test/failpoint/${GIT_BRANCH}/${sha1}/centos7/br-linux-amd64.tar.gz"
        binaryArm64Failpoint = "builds/pingcap/br/test/failpoint/${GIT_BRANCH}/${sha1}/centos7/br-linux-arm64.tar.gz"
    }

    return  [
        "repo": repo,
        "sha1": sha1,
        "actualRepo": "${actualRepo}",
        "binaryAmd64": binaryAmd64,
        "binaryArm64": binaryArm64,
        "binaryAmd64Failpoint": binaryAmd64Failpoint,
        "binaryArm64Failpoint": binaryArm64Failpoint,
        "dockerfileAmd64": dockerfileAmd64,
        "dockerfileArm64": dockerfileArm64,
        "dockerfileForDebugAmd64": "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/debug-image/${repo}",
        // "dockerfileForDebugArm64": "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-arm64/${repo}",  // TODO: arm64 have not unique debug image Dockerfile
        "imageName": "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}",
        "imageNameEnableFailpoint": "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}-failpoint",
        "imageNameForDebug": "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}-debug",
        "imageNameForDebugEnableFailpoint": "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}-debug-failpoint",
        "imageNameAmd64": "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}-linux-amd64",
        "imageNameArm64": "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}-linux-arm64",
    ]
}


def release_one_normal(repo) {
    def buildInfo = parseBuildInfo(repo)

    def buildRepo = buildInfo.actualRepo
    def buildProduct = repo
    if (repo == "tidb-lightning") {
        buildProduct = "br"
    }
    stage("build binary") {
        if (test_binary_already_build("${FILE_SERVER_URL}/download/${buildInfo.binaryAmd64}") && !params.FORCE_REBUILD) {
            echo "binary(amd64) already build: ${buildInfo.binaryAmd64}"
        } else {
            echo "build binary(amd64): ${buildInfo.binaryAmd64}"
            startBuildBinary("amd64", buildInfo.binaryAmd64, buildRepo, buildProduct, buildInfo.sha1, false)
        }
        if (params.NEED_MULTIARCH) {
            if (test_binary_already_build("${FILE_SERVER_URL}/download/${buildInfo.binaryArm64}") && !params.FORCE_REBUILD) {
                echo "binary already build(arm64): ${buildInfo.binaryArm64}"
            } else {
                echo "build binary(arm64): ${buildInfo.binaryArm64}"
                startBuildBinary("arm64", buildInfo.binaryArm64, buildRepo, buildProduct, buildInfo.sha1, false)
            }
        }
    }

    if (params.NEED_MULTIARCH) {
        println "build multi arch image"
        def multiArchImage = buildInfo.imageName

        def dockerRepo = buildInfo.actualRepo
        def dockerProduct = repo
        def amd64Images = buildInfo.imageNameAmd64
        def arm64Images = buildInfo.imageNameArm64
        if (repo == "tidb-lightning") {
            dockerProduct = "br"
        }
        if (repo == "tics") {
            amd64Images = "${buildInfo.imageNameAmd64},${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}-linux-amd64"
            arm64Images = "${buildInfo.imageNameArm64},${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}-linux-arm64"
        }
        stage("build amd64 image") {
            def paramsDockerAmd64 = [       
            string(name: "ARCH", value: "amd64"),
            string(name: "OS", value: "linux"),
            string(name: "INPUT_BINARYS", value: buildInfo.binaryAmd64),
            string(name: "REPO", value: dockerRepo),
            string(name: "PRODUCT", value: dockerProduct),
            string(name: "RELEASE_TAG", value: RELEASE_TAG),
            string(name: "DOCKERFILE", value: buildInfo.dockerfileAmd64),
            string(name: "RELEASE_DOCKER_IMAGES", value: amd64Images),
            ]
            build job: "docker-common",
                wait: true,
                parameters: paramsDockerAmd64
        }

        stage("build arm64 image") {
            def paramsDockerArm64 = [
                string(name: "ARCH", value: "arm64"),
                string(name: "OS", value: "linux"),
                string(name: "INPUT_BINARYS", value: buildInfo.binaryArm64),
                string(name: "REPO", value: dockerRepo),
                string(name: "PRODUCT", value: dockerProduct),
                string(name: "RELEASE_TAG", value: RELEASE_TAG),
                string(name: "DOCKERFILE", value: buildInfo.dockerfileArm64),
                string(name: "RELEASE_DOCKER_IMAGES", value: arm64Images),
            ]
            build job: "docker-common",
                wait: true,
                parameters: paramsDockerArm64
        }

        stage("manifest multiarch image") {
            // start manifest-tool to make multi arch image
            node("delivery") {
                container("delivery") {
                withCredentials([usernamePassword(credentialsId: 'harbor-pingcap', usernameVariable: 'harborUser', passwordVariable: 'harborPassword')]) {
                    sh """
                    docker login -u ${ harborUser} -p ${harborPassword} hub.pingcap.net
                    cat <<EOF > manifest-${repo}-${GIT_BRANCH}.yaml
image: ${multiArchImage}
manifests:
-
    image: ${buildInfo.imageNameArm64}
    platform:
    architecture: arm64
    os: linux
-
    image: ${buildInfo.imageNameAmd64}
    platform:
    architecture: amd64
    os: linux

EOF
                    cat manifest-${repo}-${GIT_BRANCH}.yaml
                    curl -o manifest-tool ${FILE_SERVER_URL}/download/cicd/tools/manifest-tool-linux-amd64
                    chmod +x manifest-tool
                    ./manifest-tool push from-spec manifest-${repo}-${GIT_BRANCH}.yaml
                    """
                    if (repo == "tics") {
                        sh """
                        docker login -u ${ harborUser} -p ${harborPassword} hub.pingcap.net
                        cat <<EOF > manifest-tiflash-${GIT_BRANCH}.yaml
image: ${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}
manifests:
-
    image: ${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}-linux-arm64
    platform:
    architecture: arm64
    os: linux
-
    image: ${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}-linux-amd64
    platform:
    architecture: amd64
    os: linux

EOF
                        cat manifest-${repo}-${GIT_BRANCH}.yaml
                        curl -o manifest-tool ${FILE_SERVER_URL}/download/cicd/tools/manifest-tool-linux-amd64
                        chmod +x manifest-tool
                        ./manifest-tool push from-spec manifest-tiflash-${GIT_BRANCH}.yaml
                        """
                    }
                }
                archiveArtifacts artifacts: "manifest-${repo}-${GIT_BRANCH}.yaml", fingerprint: true
                if (repo == "tics") {
                    archiveArtifacts artifacts: "manifest-tiflash-${GIT_BRANCH}.yaml", fingerprint: true
                }
            }
            println "multi arch image: ${multiArchImage}"
            } 
        }
    } else {
        println "build single arch image ${repo} (linux amd64)"
        def dockerProduct = repo
        def amd64Images = buildInfo.imageNameAmd64
        if (repo == "tidb-lightning") {
            dockerProduct = "br"
        }
        if (repo == "tics") {
            amd64Images = "${buildInfo.imageName},${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}"
        }
        def paramsDockerAmd64 = [       
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: buildInfo.binaryAmd64),
        string(name: "REPO", value: buildInfo.actualRepo),
        string(name: "PRODUCT", value: dockerProduct),
        string(name: "RELEASE_TAG", value: RELEASE_TAG),
        string(name: "DOCKERFILE", value: buildInfo.dockerfileAmd64),
        string(name: "RELEASE_DOCKER_IMAGES", value: amd64Images),
        ]
        build job: "docker-common",
            wait: true,
            parameters: paramsDockerAmd64
    }
}


// only tikv / pd / br / tidb support enable failpoint
def release_one_enable_failpoint(repo) {
    stage("build failpoint image") {
        def buildInfo = parseBuildInfo(repo)
        def buildRepo = buildInfo.actualRepo
        def buildProduct = repo
        if (repo == "tidb-lightning") {
            buildProduct = "br"
        }
        if (test_binary_already_build("${FILE_SERVER_URL}/download/${buildInfo.binaryAmd64Failpoint}") && !params.FORCE_REBUILD) {
            echo "binary(amd64) already build: ${buildInfo.binaryAmd64Failpoint}"
        } else {
            echo "build binary(amd64): ${buildInfo.binaryAmd64Failpoint}"
            startBuildBinary("amd64", buildInfo.binaryAmd64Failpoint, buildRepo, buildProduct, buildInfo.sha1, true)
        }

        def dockerRepo = buildInfo.actualRepo
        def dockerProduct = repo
        if (repo == "tidb-lightning") {
            dockerProduct = "br"
        }

        println "build single arch image (linux amd64) with failpoint"
        println "image with binary enable failpoint: ${buildInfo.imageNameAmd64Failpoint}"
        
        def paramsDockerFailpoint = [       
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: buildInfo.binaryAmd64Failpoint),
        string(name: "REPO", value: dockerRepo),
        string(name: "PRODUCT", value: dockerProduct),
        string(name: "RELEASE_TAG", value: RELEASE_TAG),
        string(name: "DOCKERFILE", value: buildInfo.dockerfileAmd64),
        string(name: "RELEASE_DOCKER_IMAGES", value: buildInfo.imageNameEnableFailpoint),
        ]
        println "paramsDockerFailpoint: ${paramsDockerFailpoint}"
        build job: "docker-common",
            wait: true,
            parameters: paramsDockerFailpoint
    }
}

def release_one_debug(repo) {
    stage("build amd64 debug image") {
        def buildInfo = parseBuildInfo(repo)
        def buildRepo = buildInfo.actualRepo
        def buildProduct = repo
        if (repo == "tidb-lightning") {
            buildProduct = "br"
        }
        if (test_binary_already_build("${FILE_SERVER_URL}/download/${buildInfo.binaryAmd64}") && !FORCE_REBUILD) {
            echo "binary(amd64) already build: ${buildInfo.binaryAmd64}"
        } else {
            echo "build binary(amd64): ${buildInfo.binaryAmd64}"
            startBuildBinary("amd64", buildInfo.binaryAmd64, buildRepo, buildProduct, buildInfo.sha1, false)
        }
        def dockerRepo = buildInfo.actualRepo
        def dockerProduct = repo
        if (repo == "tidb-lightning") {
            dockerProduct = "br"
        }

        println "build single arch image (linux amd64) with debug dockerfile"
        println "debug image: ${buildInfo.imageNameForDebug}"
        def paramsDocker = [       
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: buildInfo.binaryAmd64),
        string(name: "REPO", value: dockerRepo),
        string(name: "PRODUCT", value: dockerProduct),
        string(name: "RELEASE_TAG", value: RELEASE_TAG),
        string(name: "DOCKERFILE", value: buildInfo.dockerfileForDebugAmd64),
        string(name: "RELEASE_DOCKER_IMAGES", value: buildInfo.imageNameForDebug),
        ]
        println "paramsDocker: ${paramsDocker}"
        build job: "docker-common",
            wait: true,
            parameters: paramsDocker
    }
    
}


def release_master_monitoring() {
    def sha1 = get_sha("monitoring")
    def binary = "builds/pingcap/monitoring/test/master/${sha1}/linux/monitoring.tar.gz"
    def arch = "amd64"
    def paramsBuild = [
        string(name: "ARCH", value: arch),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: "monitoring"),
        string(name: "PRODUCT", value: "monitoring"),
        string(name: "GIT_HASH", value: sha1),
        string(name: "RELEASE_TAG", value: "master"),
        string(name: "TARGET_BRANCH", value: "master"),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
    ]
    println "paramsBuild: ${paramsBuild}"

    build job: "build-common",
        wait: true,
        parameters: paramsBuild

    def imageNameAmd64 = "${HARBOR_PROJECT_PREFIX}/monitoring:master-amd64"
    def paramsDockerAmd64 = [       
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: binary),
        string(name: "REPO", value: "monitoring"),
        string(name: "PRODUCT", value: "monitoring"),
        string(name: "RELEASE_TAG", value: ""),
        string(name: "DOCKERFILE", value: ""),
        string(name: "RELEASE_DOCKER_IMAGES", value: imageNameAmd64),
    ]
    build job: "docker-common",
        wait: true,
        parameters: paramsDockerAmd64
    def imageNameArm64 = "${HARBOR_PROJECT_PREFIX}/monitoring:master-arm64"
    def paramsDockerArm64 = [       
        string(name: "ARCH", value: "arm64"),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: binary),
        string(name: "REPO", value: "monitoring"),
        string(name: "PRODUCT", value: "monitoring"),
        string(name: "RELEASE_TAG", value: ""),
        string(name: "DOCKERFILE", value: ""),
        string(name: "RELEASE_DOCKER_IMAGES", value: imageNameArm64),
        ]
    build job: "docker-common",
        wait: true,
        parameters: paramsDockerArm64
    def multiArchImage = "${HARBOR_PROJECT_PREFIX}/monitoring:master"
    stage("manifest multiarch image") {
            // start manifest-tool to make multi arch image
            node("delivery") {
                container("delivery") {
                withCredentials([usernamePassword(credentialsId: 'harbor-pingcap', usernameVariable: 'harborUser', passwordVariable: 'harborPassword')]) {
                    sh """
                    docker login -u ${ harborUser} -p ${harborPassword} hub.pingcap.net
                    cat <<EOF > manifest-monitoring-master.yaml
image: ${multiArchImage}
manifests:
-
    image: ${imageNameArm64}
    platform:
    architecture: arm64
    os: linux
-
    image: ${imageNameAmd64}
    platform:
    architecture: amd64
    os: linux

EOF
                    cat manifest-monitoring-master.yaml
                    curl -o manifest-tool ${FILE_SERVER_URL}/download/cicd/tools/manifest-tool-linux-amd64
                    chmod +x manifest-tool
                    ./manifest-tool push from-spec manifest-monitoring-master.yaml
                    """
                }
                archiveArtifacts artifacts: "manifest-monitoring-master.yaml", fingerprint: true
                }
            }
            println "multi arch image: ${multiArchImage}"
        }
    
}



node("${GO_BUILD_SLAVE}") {
    container("golang") {
        builds = [:]
        if ("${GIT_BRANCH}" == "master") {
            builds["monitoring"] = {
                release_master_monitoring()
            }
        }
        releaseRepos = ["tics"]
        for (item in releaseRepos) {
            def String product = "${item}"
            builds["${item}-build"] = {
                release_one_normal(product)
            }
        }
        releaseReposMultiArch = ["tidb","tikv","pd", "br", "tidb-lightning", "ticdc", "dumpling", "tidb-binlog", "dm"]
        for (item in releaseReposMultiArch) {
            def String product = "${item}"
            def String stageName = "${product}-multi-arch"
            if (!params.NEED_MULTIARCH) {
                stageName = "${product}"
            }
            builds[stageName] = {
                release_one_normal(product)
                release_one_debug(product)
            }
        }
        failpointRepos = ["tidb","pd","tikv","br", "tidb-lightning"]
        for (item in failpointRepos) {
            def String product = "${item}"
            builds["${item}-failpoint"] = {
                release_one_enable_failpoint(product)
            }
        }
        parallel builds
    }
}
