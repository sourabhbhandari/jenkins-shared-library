package com.sourabh.sbom

import com.sourabh.utils.Logger

/**
 * Generates a CycloneDX SBOM for a built Docker image using Trivy.
 *
 * Trivy is invoked via Docker to avoid requiring it to be installed on the agent.
 * The image being scanned is mounted from the host's Docker socket.
 *
 * Usage:
 *   def gen = new SbomGenerator(this, [
 *       imageRef:   'my-app:1.0.0',
 *       outputDir:  'sbom',
 *       outputFile: 'sbom.cdx.json',
 *       format:     'cyclonedx',
 *       trivyVersion: '0.50.0'
 *   ])
 *   String path = gen.generate()
 */
class SbomGenerator implements Serializable {
    private static final long serialVersionUID = 1L

    def steps
    Logger log

    String imageRef
    String outputDir
    String outputFile
    String format          // cyclonedx | spdx-json
    String trivyVersion
    String severity        // optional vuln gating; not generation
    boolean useDockerized

    SbomGenerator(steps, Map cfg) {
        this.steps         = steps
        this.log           = new Logger(steps, 'sbom')

        this.imageRef      = required(cfg, 'imageRef')
        this.outputDir     = cfg.outputDir     ?: 'sbom'
        this.outputFile    = cfg.outputFile    ?: 'sbom.cdx.json'
        this.format        = cfg.format        ?: 'cyclonedx'
        this.trivyVersion  = cfg.trivyVersion  ?: '0.50.0'
        this.severity      = cfg.severity      ?: ''
        this.useDockerized = cfg.useDockerized != null ? cfg.useDockerized : true
    }

    String generate() {
        log.banner("Generating SBOM (${format}) for ${imageRef}")
        steps.sh "mkdir -p ${shellQuote(outputDir)}"

        String hostPath = steps.pwd()
        String relOut   = "${outputDir}/${outputFile}"

        String trivyCmd = useDockerized ? dockerizedCmd(hostPath, relOut) : nativeCmd(relOut)
        log.info "Running: ${trivyCmd}"
        steps.sh trivyCmd

        if (!steps.fileExists(relOut)) {
            steps.error "SBOM file not produced at ${relOut}"
        }
        log.info "SBOM written to ${relOut}"
        return relOut
    }

    private String dockerizedCmd(String hostPath, String relOut) {
        // Uses the official Aqua Trivy image. Mounts cwd at /work and Docker socket so it can
        // read images from the local Docker daemon.
        return [
            'docker run --rm',
            "-v /var/run/docker.sock:/var/run/docker.sock",
            "-v ${shellQuote(hostPath)}:/work",
            "-w /work",
            "aquasec/trivy:${trivyVersion}",
            "image",
            "--format ${format}",
            "--output ${shellQuote(relOut)}",
            shellQuote(imageRef)
        ].join(' ')
    }

    private String nativeCmd(String relOut) {
        return [
            'trivy image',
            "--format ${format}",
            "--output ${shellQuote(relOut)}",
            shellQuote(imageRef)
        ].join(' ')
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    private static String required(Map cfg, String key) {
        if (!cfg[key]) {
            throw new IllegalArgumentException("SbomGenerator: '${key}' is required")
        }
        return cfg[key]
    }
}
