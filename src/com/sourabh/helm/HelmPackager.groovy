package com.sourabh.helm

import com.sourabh.utils.Logger

/**
 * Lints and packages a Helm chart, optionally rewriting the image tag in values.yaml.
 *
 * Usage:
 *   def helm = new HelmPackager(this, [
 *       chartDir:    'charts/my-app',
 *       version:     '1.0.0',
 *       appVersion:  env.BUILD_NUMBER,
 *       outputDir:   'helm-pkg',
 *       imageTag:    env.BUILD_NUMBER,
 *       imageRepo:   'localhost:5000/my-app'
 *   ])
 *   String tgz = helm.package()
 */
class HelmPackager implements Serializable {
    private static final long serialVersionUID = 1L

    def steps
    Logger log

    String chartDir
    String version
    String appVersion
    String outputDir
    String imageTag
    String imageRepo
    boolean lint

    HelmPackager(steps, Map cfg) {
        this.steps      = steps
        this.log        = new Logger(steps, 'helm')

        this.chartDir   = required(cfg, 'chartDir')
        this.version    = cfg.version    ?: ''
        this.appVersion = cfg.appVersion ?: ''
        this.outputDir  = cfg.outputDir  ?: 'helm-pkg'
        this.imageTag   = cfg.imageTag   ?: ''
        this.imageRepo  = cfg.imageRepo  ?: ''
        this.lint       = cfg.lint != null ? cfg.lint : true
    }

    String packageChart() {
        log.banner("Helm package :: ${chartDir}")

        if (!steps.fileExists("${chartDir}/Chart.yaml")) {
            steps.error "Chart.yaml not found in ${chartDir}"
        }

        steps.sh "mkdir -p ${shellQuote(outputDir)}"

        if (imageRepo || imageTag) {
            updateValuesYaml()
        }

        if (lint) {
            steps.sh "helm lint ${shellQuote(chartDir)}"
        }

        List<String> parts = ['helm', 'package', shellQuote(chartDir), '-d', shellQuote(outputDir)]
        if (version)    parts << "--version ${shellQuote(version)}"
        if (appVersion) parts << "--app-version ${shellQuote(appVersion)}"
        steps.sh parts.join(' ')

        // Find produced .tgz
        String tgz = steps.sh(
            script: "ls -1t ${shellQuote(outputDir)}/*.tgz | head -n1",
            returnStdout: true
        ).trim()
        log.info "Packaged chart: ${tgz}"
        return tgz
    }

    private void updateValuesYaml() {
        String values = "${chartDir}/values.yaml"
        if (!steps.fileExists(values)) {
            log.warn "values.yaml not found at ${values}; skipping image override"
            return
        }
        // Use yq if available, else sed-fallback. yq via Docker for portability.
        String hostPath = steps.pwd()
        String relValues = values
        String yqScript = """
            set -eu
            docker run --rm -v ${shellQuote(hostPath)}:/work -w /work mikefarah/yq:4 \\
              eval -i '${imageRepo  ? ".image.repository = \"${imageRepo}\"" : ''}${imageRepo && imageTag ? ' | ' : ''}${imageTag ? ".image.tag = \"${imageTag}\"" : ''}' ${shellQuote(relValues)}
        """.stripIndent()
        steps.sh yqScript
        log.info "Updated ${values}: image.repository=${imageRepo}, image.tag=${imageTag}"
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    private static String required(Map cfg, String key) {
        if (!cfg[key]) {
            throw new IllegalArgumentException("HelmPackager: '${key}' is required")
        }
        return cfg[key]
    }
}
