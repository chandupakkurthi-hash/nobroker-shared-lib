def call(Map config) {
    def fullTag = ""

    pipeline {
        agent any
        environment {
            AWS_REG      = 'eu-north-1'
            AWS_ACC      = '824033491491'
            IMG_NAME     = "${config.serviceName}"
            ECR_URL      = "${AWS_ACC}.dkr.ecr.${AWS_REG}.amazonaws.com"
            MANIFEST_REPO = "github.com/chandupakkurthi-hash/nobroker-manifests.git"
        }
        stages {
            
            stage('Build & Test') {
                steps {
                    script {
                        def appVersion = sh(script: "grep -m1 '<version>' pom.xml | sed -E 's/.*<version>(.*)<\\/version>.*/\\1/'", returnStdout: true).trim()
                        def gitCommit = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                        fullTag = "${appVersion}-${env.BUILD_NUMBER}-${gitCommit}"
                        sh "docker build -t ${IMG_NAME}:${fullTag} ."
                    }
                }
            }
            
            stage('Publish to ECR') {
                steps {
                    script {
                        def envTag = (env.BRANCH_NAME == 'main' || env.BRANCH_NAME == 'master') ? "production" : (env.BRANCH_NAME == 'staging' ? "staging" : "qa")
                        withCredentials([usernamePassword(credentialsId: 'aws-ecr-creds', passwordVariable: 'AWS_SECRET_ACCESS_KEY', usernameVariable: 'AWS_ACCESS_KEY_ID')]) {
                            sh "aws ecr get-login-password --region ${AWS_REG} | docker login --username AWS --password-stdin ${ECR_URL}"
                            def finalEcrTag = "${fullTag}-${envTag}"
                            sh "docker tag ${IMG_NAME}:${fullTag} ${ECR_URL}/${IMG_NAME}:${finalEcrTag}"
                            sh "docker push ${ECR_URL}/${IMG_NAME}:${finalEcrTag}"
                        }
                    }
                }
            }

            stage('Update Manifests') {
                steps {
                    script {
                        def envName = (env.BRANCH_NAME == 'main' || env.BRANCH_NAME == 'master') ? "production" : (env.BRANCH_NAME == 'staging' ? "staging" : "qa")
                        
                        def manifestDir = "manifest-${env.BUILD_NUMBER}"
                        withCredentials([usernamePassword(credentialsId: 'github-creds', passwordVariable: 'GITHUB_TOKEN', usernameVariable: 'GITHUB_USER')]) {
                            sh """
                                git clone https://${GITHUB_USER}:${GITHUB_TOKEN}@${MANIFEST_REPO} ${manifestDir}
                                cd ${manifestDir}/microservices/env/${envName}
                                sed -i "s/tag: .*/tag: ${fullTag}-${envName}/" ${IMG_NAME}.yaml
                                git config user.email "jenkins@nobroker.com"
                                git config user.name "Jenkins CI"
                                git add ${IMG_NAME}.yaml
                                git commit -m "Deploy ${IMG_NAME} version ${fullTag} to ${envName}"
                                git pull --rebase origin main
                                git push origin main
                            """
                        }
                    }
                }
            }
        }
        post {
            always {
                script {
                    def envTag = (env.BRANCH_NAME == 'main' || env.BRANCH_NAME == 'master') ? "production" : (env.BRANCH_NAME == 'staging' ? "staging" : "qa")
                    def finalEcrTag = "${fullTag}-${envTag}"

                    sh "docker rmi ${IMG_NAME}:${fullTag} || true"
                    sh "docker rmi ${ECR_URL}/${IMG_NAME}:${finalEcrTag} || true"
                    cleanWs()
                }
            }
        }
    }
}
