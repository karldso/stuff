/**
*
* Build one or many packages by Gerrit refspec and deploy to different OpenStack releases.
*
* Expected parameters:
*    UPLOAD_APTLY           This boolean sets whether need to upload to and publish to Aptly packages.
*    BUILD_PACKAGE          This boolean sets whether need to build packages before deployment.
*    SOURCE_CREDENTIALS     Credentials to Git access
*    APTLY_REPO             Aptly repo name to use as a extra repo deploy name. Leave it blank for autogenerated.
*    APTLY_API_URL          Aptly API interface URL
*    APTLY_REPO_URL         Aptly repo URL
*    OPENSTACK_RELEASES     List of openstack release to deploy env. Comma as a delimiter.
*    SOURCES                List of patchsets to be built and delpoy.
*                           Format: <Git URL1> <REFSPEC1>\n<Git URL2> <REFSPEC2>
*    STACK_RECLASS_ADDRESS  Git URL to reclass model to use for deployment.
*    PKG_BUILD_JOB_NAME     Jenkins job name to build pakages. Default: oscore-ci-build-formula-change
*    GERRIT_*               Gerrit trigger plugin variables. 
*    
*    There are 2 options to run the pipeline:
*    1. Manually.
*       In this case need to define SOURCES parameter. See above.
*    2. Automatically by Gerrit trigger.
*       In this case Gerrit trigger adds GERRIT_* paramters to the build and the pipeline will use it.
*       SOURCES parameter should be empty.
*
*/

/**
 * Make generic call using Salt REST API and return parsed JSON
 *
 * @param master   Salt connection object
 * @param uri   URI which will be appended to Salt server base URL
 * @param method    HTTP method to use (default GET)
 * @param data      JSON data to POST or PUT
 * @param headers   Map of additional request headers
 */
def restCall(master, uri, method = 'GET', data = null, headers = [:]) {
    def connection = new URL("${master.url}${uri}").openConnection()
    if (method != 'GET') {
        connection.setRequestMethod(method)
    }

    connection.setRequestProperty('User-Agent', 'jenkins-groovy')
    connection.setRequestProperty('Accept', 'application/json')
    if (master.authToken) {
        // XXX: removeme
        connection.setRequestProperty('X-Auth-Token', master.authToken)
    }

    for (header in headers) {
        connection.setRequestProperty(header.key, header.value)
    }

    if (data) {
        connection.setDoOutput(true)
        connection.setRequestProperty('Content-Type', 'application/json')

        def out = new OutputStreamWriter(connection.outputStream)
        out.write(data)
        out.close()
    }

    if ( connection.responseCode >= 200 && connection.responseCode < 300 ) {
        res = connection.inputStream.text
        try {
            return new groovy.json.JsonSlurperClassic().parseText(res)
        } catch (Exception e) {
            return res
        }
    } else {
        throw (connection.responseCode + ': ' + connection.inputStream.text)
    }
}

/**
 * Make POST request using Salt REST API and return parsed JSON
 *
 * @param master   Salt connection object
 * @param uri   URI which will be appended to Docker server base URL
 * @param data  JSON Data to PUT
 */
def restPost(master, uri, data = null) {
    return restCall(master, uri, 'POST', data, ['Accept': '*/*'])
}


def common = new com.mirantis.mk.Common()
def aptly = new com.mirantis.mk.Aptly()

def buildPackage = true
if (common.validInputParam('BUILD_PACKAGE')) {
    buildPackage = BUILD_PACKAGE.toBoolean()
}

def aptlyRepo = ''
if (common.validInputParam('APTLY_REPO')) {
    aptlyRepo = APTLY_REPO
}

def uploadAptly = true
if (common.validInputParam('UPLOAD_APTLY')) {
    uploadAptly = UPLOAD_APTLY.toBoolean()
}

def pkgBuildJobName = 'oscore-ci-build-formula-change'
if (common.validInputParam('PKG_BUILD_JOB_NAME')) {
    pkgBuildJobName = PKG_BUILD_JOB_NAME
}

def stackDelete = true
if (common.validInputParam('STACK_DELETE')) {
    stackDelete = STACK_DELETE.toBoolean()
}

def sources
if (common.validInputParam('SOURCES')) {
    sources = SOURCES
} else if (common.validInputParam('GERRIT_REFSPEC')) {
        sources = "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT} ${GERRIT_REFSPEC}" 
} else {
    common.errorMsg("SOURCES or GERRIT_* parameters are empty.")
    currentBuild.result = 'FAILURE'
}


node('python') {
    def aptlyServer = ['url': APTLY_API_URL]
    wrap([$class: 'BuildUser']) {
        if (env.BUILD_USER_ID) {
          buidDescr = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
        } else {
          buidDescr = "jenkins-${JOB_NAME}-${BUILD_NUMBER}"
        }
    }
    currentBuild.description = buidDescr
    if (aptlyRepo == ''){
        aptlyRepo = buidDescr
    }

    dir('build-area'){
        deleteDir()
    }
    if (buildPackage) {
        stage('Build packages') {            
            for (source in sources.tokenize('\n')) {
                sourceArr = source.tokenize(' ')
                deployBuild = build(job: pkgBuildJobName, propagate: false, parameters: [
                    [$class: 'StringParameterValue', name: 'SOURCE_URL', value: "${sourceArr[0]}"],
                    [$class: 'StringParameterValue', name: 'SOURCE_REFSPEC', value: "${sourceArr[1]}"],
                ])
                if (deployBuild.result == 'SUCCESS'){
                    common.infoMsg("${source} has been build successfully ${deployBuild}")
                } else {
                    error("Cannot build ${source}, please check ${deployBuild.absoluteUrl}")
                }

                step ([$class: 'CopyArtifact',
                    projectName: "${deployBuild.getProjectName()}",
                    filter: 'build-area/*.deb',
                    selector: [$class: 'SpecificBuildSelector', buildNumber: "${deployBuild.getId()}"],
                    ])
                archiveArtifacts artifacts: 'build-area/*.deb'
            }
        }
    }

    dir('build-area'){
        if (uploadAptly && buildPackage) {
            try {
                stage('upload to Aptly') {
                  def buildSteps = [:]
                  restPost(aptlyServer, '/api/repos', "{\"Name\": \"${aptlyRepo}\"}")
                  def debFiles = sh script: 'ls *.deb', returnStdout: true
                  for (file in debFiles.tokenize()) {
                    buildSteps[file.split('_')[0]] = aptly.uploadPackageStep(
                          file,
                          APTLY_API_URL,
                          aptlyRepo,
                          true
                      )
                  }
                  parallel buildSteps
                }

                stage('publish to Aptly') {
                    restPost(aptlyServer, '/api/publish/:.', "{\"SourceKind\": \"local\", \"Sources\": [{\"Name\": \"${aptlyRepo}\"}], \"Architectures\": [\"amd64\"], \"Distribution\": \"${aptlyRepo}\"}")
                }
            } catch (Exception e) {
                currentBuild.result = 'FAILURE'
                throw e
            }
        }
    }

    if (OPENSTACK_RELEASES) {
        def deploy_release = [:]
        def testBuilds = [:]
        URI aptlyUri = new URI(APTLY_REPO_URL)
        def aptlyHost = aptlyUri.getHost()
        def extraRepo = "deb [ arch=amd64 trusted=yes ] ${APTLY_REPO_URL} ${aptlyRepo} main,1200,origin ${aptlyHost}"
        stage('Deploying environment and testing'){
            for (openstack_release in OPENSTACK_RELEASES.tokenize(',')) {
                def release = openstack_release
                deploy_release["OpenStack ${release} deployment"] = {
                    node('oscore-testing') {
                        testBuilds["${release}"] = build job: "oscore-formula-virtual_mcp11_aio-${release}-stable", propagate: false, parameters: [
                            [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: "${STACK_RECLASS_ADDRESS}"],
                            [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: "stable/${release}"],
                            [$class: 'TextParameterValue', name: 'BOOTSTRAP_EXTRA_REPO_PARAMS', value: extraRepo],
                            [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: stackDelete],
                        ]
                    }
                }
            }
            parallel deploy_release
        }
        def notToPromote
        stage('Managing deployment results') {
            for (k in testBuilds.keySet()) {
                if (testBuilds[k].result != 'SUCCESS') {
                    notToPromote = true
                }
                println(k + ': ' + testBuilds[k].result)
            }
        }
        if (notToPromote) {
            currentBuild.result = 'FAILURE'
        }
    }
// end of node
}


