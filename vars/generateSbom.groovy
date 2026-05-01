import com.sourabh.sbom.SbomGenerator

/**
 * Generate a CycloneDX SBOM for a Docker image using Trivy.
 *
 * Required:
 *   imageRef  - full image reference (e.g. 'my-app:1.0.0')
 *
 * Optional:
 *   format        (default 'cyclonedx'; also 'spdx-json')
 *   outputDir     (default 'sbom')
 *   outputFile    (default 'sbom.cdx.json')
 *   trivyVersion  (default '0.50.0')
 *   useDockerized (default true; set false to use a system-installed trivy)
 *
 * Returns: relative path to the generated SBOM.
 */
def call(Map cfg = [:]) {
    return new SbomGenerator(this, cfg).generate()
}
