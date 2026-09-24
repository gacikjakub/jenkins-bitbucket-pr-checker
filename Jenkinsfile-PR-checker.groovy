/* Collect, plan, persist assignment comments, optionally deliver email. */
def orchestrator
Map config
long collectedAt

pipeline {
    agent any // Unix agent with curl
    options {
        disableConcurrentBuilds()
        timestamps()
        skipDefaultCheckout(true)
        timeout(time: 60, unit: 'MINUTES')
    }
    triggers { cron('H * * * *') }
    stages {
        stage('Load configuration') {
            steps {
                checkout scm
                script {
                    orchestrator = load('pr-checker/Orchestrator.groovy')
                    config = orchestrator.loadConfiguration('pr-checker.yaml')
                    collectedAt = System.currentTimeMillis()
                    dir('pr-checker-output') { deleteDir() }
                }
            }
        }
        stage('Collect repositories in parallel') {
            steps {
                script {
                    Map branches = [:]
                    for (Map repo : config['repositories']) {
                        branches[repo['id']] = orchestrator.repositoryBranch(config, repo, collectedAt)
                    }
                    parallel branches
                }
            }
        }
        stage('Aggregate and plan globally') {
            steps {
                script {
                    List snapshots = []
                    for (Map repo : config['repositories']) {
                        snapshots.add(readJSON(
                            file: "pr-checker-output/repos/${repo['id']}/snapshot.json",
                            returnPojo: true))
                    }
                    Map plan = orchestrator.aggregateAndPlan(config, snapshots, collectedAt)
                    writeJSON(file: 'pr-checker-output/plan.json', json: plan, pretty: 2)
                    orchestrator.printScores(plan)
                }
            }
        }
        stage('Persist assignment comments') {
            steps {
                script {
                    Map plan = readJSON(file: 'pr-checker-output/plan.json', returnPojo: true)
                    withCredentials([usernamePassword(credentialsId: config['bitbucket']['credentialsId'],
                        usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASS')]) {
                        orchestrator.persistAssignments(config, plan)
                    }
                    writeJSON(file: 'pr-checker-output/plan.json', json: plan, pretty: 2)
                }
            }
        }
        stage('Render email previews') {
            steps {
                script {
                    Map plan = readJSON(file: 'pr-checker-output/plan.json', returnPojo: true)
                    Map notificationState = orchestrator.prepareNotifications(config, plan)
                    orchestrator.writeReports(config, plan)
                    writeJSON(file: 'pr-checker-output/plan.json', json: plan, pretty: 2)
                    orchestrator.deliverNotifications(config, plan, notificationState)
                }
                archiveArtifacts(artifacts: 'pr-checker-output/**/*.json,pr-checker-output/**/*.html',
                    fingerprint: false)
            }
        }
    }
}
