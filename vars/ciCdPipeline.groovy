/**
 * End-to-end CI/CD pipeline composed from the library steps:
 *
 *   1. dockerBuild     - build (and optionally push) the image from the Dockerfile
 *   2. generateSbom    - generate a CycloneDX SBOM via Trivy
 *   3. uploadSbomToMinio - upload SBOM to MinIO
 *   4. helmPackage     - lint + package the Helm chart, override image repo/tag
 *   5. argoCdDeploy    - render & apply ArgoCD Application, sync, wait for Healthy
 *
 * Designed to be called as a single line from a `Jenkinsfile`:
 *
 *   @Library('jenkins-shared-lib@main') _
 *   ciCdPipeline(
 *     app: [
 *       name:       'my-app',
 *       dockerfile: 'Dockerfile',
 *       context:    '.',
 *       chartDir:   'charts/my-app'
 *     ],
 *     registry: [
 *       url:           'localhost:5000',
 *       credentialsId: 'docker-registry-creds',  // optional; omit for unauth registries
 *       push:          true
 *     ],
 *     sbom: [
 *       format: 'cyclonedx'
 *     ],
 *     minio: [
 *       endpoint:      'http://localhost:9001',
 *       bucket:        'sboms',
 *       credentialsId: 'minio-creds'
 *     ],
 *     argocd: [
 *       repoUrl:        'https://github.com/sourabh/my-app-config.git',
 *       targetRevision: 'main',
 *       chartPath:      'charts/my-app',
 *       namespace:      'my-app'
 *     ]
 *   )
 *
 * The whole pipeline is wrapped in a `pipeline { agent any ... }` block so
 * users only need to define their library and call this single function.
 */
def call(Map cfg = [:]) {
    Map app      = (cfg.app      ?: [:]) as Map
    Map registry = (cfg.registry ?: [:]) as Map
    Map sbom     = (cfg.sbom     ?: [:]) as Map
    Map minio    = (cfg.minio    ?: [:]) as Map
    Map argocd   = (cfg.argocd   ?: [:]) as Map

    String appName = app.name ?: error('ciCdPipeline: app.name is required')

    pipeline {
        agent any
        options {
            timestamps()
            // ansiColor('xterm') requires the AnsiColor plugin; uncomment if you've installed it.
            disableConcurrentBuilds()
        }
        environment {
            APP_NAME = "${appName}"
            IMAGE_TAG = "${env.BUILD_NUMBER}"
        }
        stages {
            stage('Checkout') {
                steps { checkout scm }
            }
            stage('Build image') {
                steps {
                    script {
                        env.BUILT_IMAGE = dockerBuild(
                            imageName:     appName,
                            dockerfile:    app.dockerfile ?: 'Dockerfile',
                            context:       app.context    ?: '.',
                            tag:           env.IMAGE_TAG,
                            registry:      registry.url   ?: '',
                            push:          registry.push  ?: false,
                            credentialsId: registry.credentialsId ?: null,
                            buildArgs:     (app.buildArgs ?: [:]) as Map
                        )
                        echo "Built image: ${env.BUILT_IMAGE}"
                    }
                }
            }
            stage('Generate SBOM') {
                steps {
                    script {
                        env.SBOM_PATH = generateSbom(
                            imageRef:   env.BUILT_IMAGE,
                            format:     sbom.format     ?: 'cyclonedx',
                            outputDir:  sbom.outputDir  ?: 'sbom',
                            outputFile: sbom.outputFile ?: 'sbom.cdx.json'
                        )
                    }
                    archiveArtifacts artifacts: "${env.SBOM_PATH}", fingerprint: true
                }
            }
            stage('Upload SBOM to MinIO') {
                when { expression { return minio.endpoint } }
                steps {
                    script {
                        String objectKey = "${env.JOB_NAME}/${env.BUILD_NUMBER}/${(sbom.outputFile ?: 'sbom.cdx.json')}"
                        uploadSbomToMinio(
                            endpoint:      minio.endpoint,
                            bucket:        minio.bucket ?: 'sboms',
                            objectKey:     objectKey,
                            file:          env.SBOM_PATH,
                            credentialsId: minio.credentialsId ?: 'minio-creds'
                        )
                        env.SBOM_OBJECT = objectKey
                    }
                }
            }
            stage('Package Helm chart') {
                when { expression { return app.chartDir } }
                steps {
                    script {
                        env.HELM_PKG = helmPackage(
                            chartDir:   app.chartDir,
                            version:    "1.0.${env.BUILD_NUMBER}",
                            appVersion: env.IMAGE_TAG,
                            imageRepo:  registry.url ? "${registry.url}/${appName}" : appName,
                            imageTag:   env.IMAGE_TAG
                        )
                    }
                    archiveArtifacts artifacts: "${env.HELM_PKG}", fingerprint: true
                }
            }
            stage('Deploy via ArgoCD') {
                when { expression { return argocd.repoUrl } }
                steps {
                    argoCdDeploy(
                        appName:        appName,
                        namespace:      argocd.namespace      ?: appName,
                        argoNamespace:  argocd.argoNamespace  ?: 'argocd',
                        repoUrl:        argocd.repoUrl,
                        targetRevision: argocd.targetRevision ?: 'HEAD',
                        chartPath:      argocd.chartPath      ?: app.chartDir,
                        destServer:     argocd.destServer     ?: 'https://kubernetes.default.svc',
                        imageRepo:      registry.url ? "${registry.url}/${appName}" : appName,
                        imageTag:       env.IMAGE_TAG,
                        sync:           argocd.sync != null ? argocd.sync : true,
                        waitHealthy:    argocd.waitHealthy != null ? argocd.waitHealthy : true,
                        waitTimeoutSec: argocd.waitTimeoutSec ?: 300
                    )
                }
            }
        }
        post {
            always {
                echo "Pipeline finished for ${appName} (build #${env.BUILD_NUMBER})"
            }
            success {
                echo "Image: ${env.BUILT_IMAGE}"
                echo "SBOM:  ${env.SBOM_PATH}"
                script {
                    if (env.SBOM_OBJECT) echo "MinIO: ${minio.endpoint}/${minio.bucket}/${env.SBOM_OBJECT}"
                }
            }
        }
    }
}
