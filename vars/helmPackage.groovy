import com.sourabh.helm.HelmPackager

/**
 * Lint and package a Helm chart, optionally rewriting the image repo/tag in values.yaml.
 *
 * Required:
 *   chartDir
 *
 * Optional:
 *   version, appVersion
 *   imageRepo, imageTag (rewrites values.yaml in-place)
 *   outputDir (default 'helm-pkg')
 *   lint (default true)
 *
 * Returns: path to the produced .tgz
 */
def call(Map cfg = [:]) {
    return new HelmPackager(this, cfg).packageChart()
}
