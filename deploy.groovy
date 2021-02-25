@Library('jkns-lib@main')_
import org.devopslab.Constants

pipeline {
    agent {
        node {        // select nodes where azure-cli is installed
            label 'Python3'
        }
    }

    options {
        timestamps()
        disableConcurrentBuilds()             // allow no more than one run at any given moment
        timeout(time: 30, unit: 'MINUTES')    // java.util.concurrent.TimeUnit
    }

    parameters {
        string(name: 'CD_GIT_URL', defaultValue: 'git@github.com:pasuinthesky/Dashboard.git', description: 'The repo where the CD.yml exists.')
        string(name: 'CD_BRANCH', defaultValue: 'qa', description: 'The branch where the CD.yml should be read from.')
    }

    environment {
        CD_GIT_CREDENTIAL      = 'Github'
    }

    stages {
        stage('Clean up workspace') {
            steps {
                // CAUTION -- turn this on for debug purpose only to avoid security leak.
                //sh 'printenv' 
                cleanWs()
            }
        }

        stage('Load infra-spec into config') {
            steps {
                script {
                    println('Load infra-spec from Github')
                    infraSpec = loadInfraSpec()
                //println (config.environment.dev.region) // test
                }
            }
        }

        stage('Load CD.yml') {
            steps {
                script {
                    println('Load CD.yml from Github')
                    config = loadGitYaml(
                        branch: params.CD_BRANCH,
                        url: params.CD_GIT_URL,
                        credentialsId: env.CD_GIT_CREDENTIAL,
                        file: 'CD.yml',
                        subdir: Constants.CD_SUBDIR
                    )
                    //println (config.artifact.type) // debug
                }
            }
        }

        stage('Construct deployment') {
            steps {
                script {
                    println('Construct deployment')

                    deploy = [:]
                    deploy.orgName = infraSpec.orgName
                    deploy.deployTargeEnv = config.deployTargeEnv
                    deploy << config.artifact
                    deploy << config.environments[config.deployTargeEnv]
                    deploy << infraSpec.environments[config.deployTargeEnv]
                    deploy << infraSpec.templates[deploy.kind]
                    deploy << infraSpec.artifactTypes[deploy.pkgType]
                    //println (deploy) // debug

                    sh "mkdir ${Constants.DEPLOY_SUBDIR}"

                    def paramObj = readJSON file: Constants.INFRA_SPEC_SUBDIR + '/arm/' + deploy.param

                    paramObj.parameters.vmName.value = [deploy.orgName, env.JOB_BASE_NAME, env.BUILD_ID].join('-')
                    paramObj.parameters.vmSize.value = deploy.size
                    paramObj.parameters.adminPassword.reference.keyVault.id = deploy.adminUserKey
                    paramObj.parameters.adminSshKey.reference.keyVault.id = deploy.adminUserKey
                    paramObj.parameters.dnsLabelPrefix.value = paramObj.parameters.vmName.value
                    
                    // Sorry this is a bad one, maybe someone can improve it later.
                    paramObj.parameters.configAppUri.value = 'https://raw.githubusercontent.com/pasuinthesky/infra-spec/main/script/' + deploy.script
                    paramObj.parameters.configAppCmd.value = 'bash ' + deploy.script

                    deploy.paramRuntime = [Constants.DEPLOY_SUBDIR, deploy.param].join('/')
                    writeJSON file: deploy.paramRuntime, 
                        json: paramObj, 
                        pretty: 4

                    deploy.cmd = """
                        az group deployment create \
                        --resource-group """ + deploy.resourceGroup + """ \
                        --parameters @./""" + deploy.paramRuntime + """ \
                        --template-file ./""" + Constants.INFRA_SPEC_SUBDIR + "/arm/" + deploy.arm
                //println (deploy) // debug
                }
            }
        }

        stage('Provision infrastructure') {
            when { 
                expression { false }
            }
            steps {
                script {
                    println('Provision infrastructure')
                    deploy.stdOut = runAzCli (
                        sp: 'devops-lab-jenkins-' + deploy.deployTargeEnv, 
                        script: deploy.cmd
                    )
                    def jsonObj = readJSON text: deploy.stdOut
                    println (jsonObj)
                }
            }
        }

        stage('Deploy artifact') {
            steps {
                script {
                    println('Deploy artifact')
                    // Please fill in code
                }
            }
        }

        stage('Perform test') {
            steps {
                script {
                    println('Perform test')
                    // Please fill in code
                }
            }
        }

        stage('Tear down') {
            when {
                expression { false }
            }
            steps {
                script {
                    println('Tear down')
                    // Please fill in code
                }
            }
        }
    }

    /**
     * 构建后行为
     */
    post {
        // 总是执行
        always {
            echo 'Always'
        }

        // 条件执行
        success {
            echo currentBuild.description = 'Success'    // currentBuild.description 会将信息带回控制面板
            /**
            emailext (
                subject: "SUCCESSFUL: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
                body: """<p>SUCCESSFUL: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p>
                    <p>Check console output at &QUOT;<a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>&QUOT;</p>""",
                recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']]
            )
            */
        }

        failure {
            echo  currentBuild.description = 'Failure'
            /**
            emailext (
                subject: "FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
                body: """<p>FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p>
                    <p>Check console output at &QUOT;<a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>&QUOT;</p>""",
                recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']]
            )
            */
        }

        aborted {
            echo currentBuild.description = 'Aborted'
            /**
            emailext (
                subject: "Aborted: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
                body: """<p>ABORTED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p>
                    <p>Check console output at &QUOT;<a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>&QUOT;</p>""",
                recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']]
            )
            */
        }
    }
}
