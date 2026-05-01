/**
 * Run the Python build orchestrator script bundled with this library
 * (resources/com/sourabh/scripts/build.py).
 *
 * The script reads a JSON config and orchestrates: docker build -> trivy SBOM -> minio upload.
 * It is intended to be runnable on a Jenkins agent that has Python 3 + Docker installed.
 *
 * Usage:
 *   pythonBuild(
 *     imageName:   'my-app',
 *     tag:         env.BUILD_NUMBER,
 *     registry:    'localhost:5000',
 *     dockerfile:  'Dockerfile',
 *     context:     '.',
 *     sbomFormat:  'cyclonedx',
 *     minioEndpoint: 'http://localhost:9001',
 *     minioBucket:   'sboms',
 *     minioCredentialsId: 'minio-creds',     // username/password credential
 *     pythonBin:   'python3'
 *   )
 *
 * Returns: a Map with build metadata { imageRef, sbomPath, minioObject }.
 */
def call(Map cfg = [:]) {
    String imageName  = required(cfg, 'imageName')
    String tag        = cfg.tag        ?: env.BUILD_NUMBER ?: 'latest'
    String registry   = cfg.registry   ?: ''
    String dockerfile = cfg.dockerfile ?: 'Dockerfile'
    String context    = cfg.context    ?: '.'
    String sbomFormat = cfg.sbomFormat ?: 'cyclonedx'
    String sbomFile   = cfg.sbomFile   ?: 'sbom.cdx.json'
    String sbomDir    = cfg.sbomDir    ?: 'sbom'
    String pythonBin  = cfg.pythonBin  ?: 'python3'

    String minioEndpoint = cfg.minioEndpoint ?: ''
    String minioBucket   = cfg.minioBucket   ?: ''
    String minioCredId   = cfg.minioCredentialsId ?: 'minio-creds'
    String objectKey     = cfg.minioObjectKey ?: "${env.JOB_NAME ?: imageName}/${tag}/${sbomFile}"

    // Materialize the Python script onto the workspace
    String script = libraryResource('com/sourabh/scripts/build.py')
    writeFile file: '.jenkins-shared-lib/build.py', text: script
    sh 'chmod +x .jenkins-shared-lib/build.py'

    // Build a config JSON for the script
    Map config = [
        imageName : imageName,
        tag       : tag,
        registry  : registry,
        dockerfile: dockerfile,
        context   : context,
        sbom      : [
            format    : sbomFormat,
            outputDir : sbomDir,
            outputFile: sbomFile
        ],
        minio     : minioEndpoint ? [
            endpoint : minioEndpoint,
            bucket   : minioBucket,
            objectKey: objectKey
        ] : null,
        push      : cfg.push ?: false
    ]
    writeFile file: '.jenkins-shared-lib/build-config.json',
              text: groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(config))

    // Run with credentials injected into env if minio is configured
    Closure runScript = {
        sh """
            ${pythonBin} .jenkins-shared-lib/build.py \\
              --config .jenkins-shared-lib/build-config.json \\
              --output .jenkins-shared-lib/build-output.json
        """.stripIndent()
    }

    if (minioEndpoint) {
        withCredentials([usernamePassword(
            credentialsId: minioCredId,
            usernameVariable: 'MINIO_ACCESS_KEY',
            passwordVariable: 'MINIO_SECRET_KEY'
        )]) {
            runScript()
        }
    } else {
        runScript()
    }

    String outJson = readFile('.jenkins-shared-lib/build-output.json').trim()
    return new groovy.json.JsonSlurper().parseText(outJson)
}

private static String required(Map cfg, String key) {
    if (!cfg[key]) {
        throw new IllegalArgumentException("pythonBuild: '${key}' is required")
    }
    return cfg[key]
}
