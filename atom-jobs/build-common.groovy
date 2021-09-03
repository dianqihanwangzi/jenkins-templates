/*
* @OUTPUT_BINARY(string:binary url on fileserver, transfer througth atom jobs,Required)
* @REPO(string:repo name,eg tidb, Required)
* @PRODUCT(string:product name,eg tidb-ctl,if not set,default was the same as repo name, Optional)
* @ARCH(enumerate:arm64,amd64,Required)
* @OS(enumerate:linux,darwin,Required)
* @GIT_HASH(string:to get correct code from github,Required)
* @GIT_PR(string:generate ref head to pre get code from pr,Optional)
* @RELEASE_TAG(string:for release workflow,what tag to release,Optional)
* @TARGET_BRANCH(string:for daily CI workflow,Optional)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
* @FAILPOINT(bool:build failpoint binary or not,only for tidb,tikv,pd now ,default false,Optional)
* @EDITION(enumerate:,community,enterprise,Required)
*/

properties([
        parameters([
                choice(
                        choices: ['arm64', 'amd64'],
                        name: 'ARCH'
                ),
                choice(
                        choices: ['linux', 'darwin'],
                        name: 'OS'
                ),
                choice(
                        choices: ['community', 'enterprise'],
                        name: 'EDITION'
                ),
                string(
                        defaultValue: '',
                        name: 'OUTPUT_BINARY',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'REPO',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PRODUCT',
                        trim: true,
                ),
                string(
                        defaultValue: '',
                        name: 'GIT_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'GIT_PR',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TARGET_BRANCH',
                        trim: true
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD'
                ),
                booleanParam(
                        name: 'FAILPOINT',
                        defaultValue: false
                )
        ])
])


WS = ""

if (params.PRODUCT.length() <= 1) {
    PRODUCT = REPO
}

failpoint = "false"
if (params.FAILPOINT) {
    failpoint = "true"
}


// check if binary already has been built. 
def ifFileCacheExists() {
    if (params.FORCE_REBUILD){
        return false
    } 
    result = sh(script: "curl -I ${FILE_SERVER_URL}/download/${OUTPUT_BINARY} -X \"HEAD\"|grep \"200 OK\"", returnStatus: true)
    // result equal 0 mean cache file exists
    if (result == 0) {
        echo "file ${FILE_SERVER_URL}/download/${OUTPUT_BINARY} found in cache server,skip build again"
        return true
    }
    return false
}

@NonCPS
boolean isMoreRecentOrEqual( String a, String b ) {
    if (a == b) {
        return true
    }

    [a,b]*.tokenize('.')*.collect { it as int }.with { u, v ->
       Integer result = [u,v].transpose().findResult{ x,y -> x <=> y ?: null } ?: u.size() <=> v.size()
       return (result == 1)
    } 
}

string trimPrefix = {
    it.startsWith('release-') ? it.minus('release-') : it 
}

// choose which go version to use. 
def boolean needUpgradeGoVersion(String tag,String branch) {
    if (tag.startsWith("v") && tag > "v5.1") {
        println "tag=${tag} need upgrade go version"
        return true
    }
    if (branch.startsWith("master") || branch.startsWith("hz-poc")) {
        println "targetBranch=${branch} need upgrade go version"
        return true
    }
    if (branch.startsWith("release-")) {
        if (isMoreRecentOrEqual(trimPrefix(branch), trimPrefix("release-5.1"))) {
            println "targetBranch=${branch} need upgrade go version"
            return true
        }
    }
    return false
}

def goBuildPod = "${GO_BUILD_SLAVE}"
def GO_BIN_PATH = "/usr/local/go/bin"
if (needUpgradeGoVersion(params.RELEASE_TAG,params.TARGET_BRANCH)) {
   goBuildPod = "${GO1160_BUILD_SLAVE}"
   GO_BIN_PATH = "/usr/local/go1.16.4/bin"
}

// choose which node to use.
def nodeLabel = goBuildPod
def containerLabel = "golang"
def binPath = ""
if (params.PRODUCT == "tikv" || params.PRODUCT == "importer") {
    nodeLabel = "build"
    containerLabel = "rust"
} 
if (params.PRODUCT == "tics") {
    nodeLabel = "build_tiflash"
    containerLabel = "tiflash"
} 
if (params.ARCH == "arm64") {
    binPath = "/usr/local/node/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin:${GO_BIN_PATH}"
    nodeLabel = "arm"
    containerLabel = ""
    if (params.PRODUCT == "tics"){
        nodeLabel = "tiflash_build_arm"
        containerLabel = "tiflash"
    }
}
if (params.OS == "darwin") {
    binPath = "/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}"
    nodeLabel = "mac"
    containerLabel = ""
}

// define git url and git ref.
repo = "git@github.com:pingcap/${REPO}.git"
if (REPO == "tikv" || REPO == "importer" || REPO == "pd") {
    repo = "git@github.com:tikv/${REPO}.git"
}
specRef = "+refs/heads/*:refs/remotes/origin/*"
if (params.GIT_PR.length() >= 1) {
   specRef = "+refs/pull/${GIT_PR}/*:refs/remotes/origin/pr/${GIT_PR}/*"
}
def checkoutCode(hash,repo) {
    checkout changelog: false, poll: true,
                    scm: [$class: 'GitSCM', branches: [[name: "${hash}"]], doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CheckoutOption', timeout: 30],
                                    [$class: 'CloneOption', timeout: 60],
                                    [$class: 'PruneStaleBranch'],
                                    [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: ''],
                                    [$class: 'CleanBeforeCheckout']], submoduleCfg: [],
                        userRemoteConfigs: [[credentialsId: 'guoyu-test-ssh',
                                            refspec      : specRef,
                                            url          : repo]]]
    if (repo != "git@github.com:PingCAP-CBG/tidb-dashboard-distro.git") {
        sh "git checkout ${GIT_HASH}"
    }
}


// define build script here.
TARGET = "output" 
buildsh = [:]
buildsh["tidb-ctl"] = """
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go build -o binarys/${PRODUCT}
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp binarys/${PRODUCT} ${TARGET}/bin/            
"""

buildsh["tidb"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [ ${EDITION} == 'enterprise' ]; then
    export TIDB_EDITION=Enterprise
fi;
sed -i 's/# LDFLAGS/LDFLAGS/g' Makefile.common
sed -i 's/UbiSQL-v1.0.0-rc1/YiDB/g' Makefile.common
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make clean
if [ ${failpoint} == 'true' ]; then
    make failpoint-enable   
fi;
make 
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp binarys/tidb-ctl ${TARGET}/bin/ || true
cp bin/* ${TARGET}/bin/ 

"""

buildsh["tidb-binlog"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make clean
git checkout .
make
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["pd"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
git checkout .
if [ ${EDITION} == 'enterprise' ]; then
    export TIDB_EDITION=Enterprise
fi;
if [ ${failpoint} == 'true' ]; then
    make failpoint-enable
fi;
DASHBOARD_DISTRIBUTION_DIR=\${ws}/resource/yidb make pd-server
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["tidb-tools"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make clean
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["ticdc"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["br"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
if [ ${REPO} == "tidb" ]; then
    make build_tools
else
    make build
fi;
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["dumpling"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["tidb-enterprise-tools"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make syncer
make loader
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["tics"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [ ${EDITION} == 'enterprise' ]; then
    export TIFLASH_EDITION=Enterprise
fi;
if [ ${OS} == 'darwin' ]; then
    mkdir -p release-darwin/build/
    [ -f "release-darwin/build/build-release.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-release.sh > release-darwin/build/build-release.sh
    [ -f "release-darwin/build/build-cluster-manager.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-cluster-manager.sh > release-darwin/build/build-cluster-manager.sh
    [ -f "release-darwin/build/build-tiflash-proxy.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-tiflash-proxy.sh > release-darwin/build/build-tiflash-proxy.sh
    [ -f "release-darwin/build/build-tiflash-release.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-tiflash-release.sh > release-darwin/build/build-tiflash-release.sh
    chmod +x release-darwin/build/*
    ./release-darwin/build/build-release.sh
    ls -l ./release-darwin/tiflash/
    mv release-darwin ${TARGET}
else
    NPROC=12 release-centos7/build/build-release.sh
    mv release-centos7 ${TARGET}
fi
rm -rf ${TARGET}/build-release || true
"""

buildsh["tikv"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [ ${EDITION} == 'enterprise' ]; then
    export TIKV_EDITION=Enterprise
    export ROCKSDB_SYS_SSE=0
fi;
if [ ${OS} == 'linux' ]; then
    grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
    if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
        echo using gcc 8
        source /opt/rh/devtoolset-8/enable
    fi;
fi;
if [ ${failpoint} == 'true' ]; then
    CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 make fail_release
else
    CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 make dist_release
fi;
wait
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin
"""

buildsh["importer"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
ROCKSDB_SYS_SSE=0 make release
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp target/release/tikv-importer ${TARGET}/bin
"""

buildsh["monitoring"] = """
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go build -o pull-monitoring  cmd/monitoring.go
./pull-monitoring  --config=monitoring.yaml --auto-push --tag=${RELEASE_TAG} --token=\$TOKEN
rm -rf ${TARGET}
mkdir -p ${TARGET}
mv monitor-snapshot/${RELEASE_TAG}/operator/* ${TARGET}
"""

buildsh["tidb-test"] = """
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
if [ -d "partition_test/build.sh" ]; then
    cd partition_test
    bash build.sh
    cd ..
fi;
if [ -d "coprocessor_test/build.sh" ]; then
    cd coprocessor_test
    bash build.sh
    cd ..
fi;
if [ -d "concurrent-sql/build.sh" ]; then
    cd concurrent-sql
    bash build.sh
    cd ..
fi;
"""

buildsh["enterprise-plugin"] = """
rsync -av --progress ./ ./enterprise-plugin --exclude enterprise-plugin
git clone https://github.com/pingcap/tidb.git --depth=1
cd tidb/cmd/pluginpkg
go build 
cd ../../..
go mod tidy
tidb/cmd/pluginpkg/pluginpkg -pkg-dir whitelist -out-dir whitelist
md5sum whitelist-1.so > whitelist-1.so.md5
curl -F builds/pingcap/tidb-plugins/test/${RELEASE_TAG}/centos7/whitelist-1.so.md5=@whitelist-1.so.md5 ${FILE_SERVER_URL}/upload
curl -F builds/pingcap/tidb-plugins/test/${RELEASE_TAG}/centos7/whitelist-1.so=@whitelist-1.so ${FILE_SERVER_URL}/upload
go mod tidy
tidb/cmd/pluginpkg/pluginpkg -pkg-dir enterprise-plugin/audit -out-dir enterprise-plugin/audit
md5sum audit-1.so > audit-1.so.md5
curl -F builds/pingcap/tidb-plugins/test/${RELEASE_TAG}/centos7/audit-1.so.md5=@audit-1.so.md5 ${FILE_SERVER_URL}/upload
curl -F builds/pingcap/tidb-plugins/test/${RELEASE_TAG}/centos7/audit-1.so=@audit-1.so ${FILE_SERVER_URL}/upload
rm -rf ${TARGET}
mkdir ${TARGET}/bin
"""

buildsh["tiem"] = """
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
make 
"""

def packageBinary() {
    //  pd,tidb,tidb-test 非release版本，和代码一起打包
    if ((PRODUCT == "pd" || PRODUCT == "tidb" || PRODUCT == "tidb-test" ) && RELEASE_TAG.length() < 1) {
        sh """
        tar --exclude=${TARGET}.tar.gz -czvf ${TARGET}.tar.gz *
        curl -F ${OUTPUT_BINARY}=@${TARGET}.tar.gz ${FILE_SERVER_URL}/upload
        """
    }else {
        sh """
        cd ${TARGET}
        tar --exclude=${TARGET}.tar.gz -czvf ${TARGET}.tar.gz *
        curl -F ${OUTPUT_BINARY}=@${TARGET}.tar.gz ${FILE_SERVER_URL}/upload
        """
    }
}

def release() {
    // if has built,skip build.
    if (ifFileCacheExists()) {
        return
    }
    checkoutCode(GIT_HASH,repo)
    dir("resource") {
        checkoutCode("main","git@github.com:PingCAP-CBG/tidb-dashboard-distro.git")
    }
    // some build need this token.
    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
        if (REPO == "pd") {
            def WS = pwd()
            sh """
            sudo yum install java-1.8.0-openjdk-devel -y
            wget https://nodejs.org/dist/v12.22.6/node-v12.22.6-linux-x64.tar.gz
            tar -xvf node-v12.22.6-linux-x64.tar.gz
            export PATH=\$PATH:${WS}/node-v12.22.6-linux-x64/bin
            ls -l ${WS}/node-v12.22.6-linux-x64/bin
            sudo echo \$PATH
            npm install -g yarn

            if [ ${RELEASE_TAG}x != ''x ];then
                for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
                git tag -f ${RELEASE_TAG} ${GIT_HASH}
                git branch -D refs/tags/${RELEASE_TAG} || true
                git checkout -b refs/tags/${RELEASE_TAG}
            fi;
            git checkout .
            if [ ${EDITION} == 'enterprise' ]; then
                export TIDB_EDITION=Enterprise
            fi;
            if [ ${failpoint} == 'true' ]; then
                make failpoint-enable
            fi;
            DASHBOARD_DISTRIBUTION_DIR=${WS}/resource/yidb make pd-server
            rm -rf ${TARGET}
            mkdir -p ${TARGET}/bin    
            cp bin/* ${TARGET}/bin/   
            """
        }else {
            sh buildsh[params.PRODUCT]
        }
    }
    packageBinary()
}


stage("Build ${PRODUCT}") {
    node(nodeLabel) {
        dir("go/src/github.com/pingcap/${PRODUCT}") {
            if (containerLabel != "") {
                container(containerLabel){
                    release()
                }
            }else {
                release()
            }
        }
    }
}
