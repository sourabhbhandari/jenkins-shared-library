package com.sourabh.sbom

import com.sourabh.utils.Logger

/**
 * Uploads a file (typically the SBOM) to a MinIO bucket.
 *
 * Authentication: pass `accessKeyVar` / `secretKeyVar` containing the names of Jenkins
 * credentials (Secret text). The class will use `withCredentials` to inject them as env
 * vars, never echoing them.
 *
 * Implementation: uses the `mc` MinIO client run via Docker for portability.
 *
 * Usage:
 *   def uploader = new MinioUploader(this, [
 *       endpoint:      'http://localhost:9001',
 *       bucket:        'sboms',
 *       objectKey:     "sboms/${env.JOB_NAME}/${env.BUILD_NUMBER}/sbom.cdx.json",
 *       file:          'sbom/sbom.cdx.json',
 *       accessKeyVar:  'MINIO_ACCESS_KEY',
 *       secretKeyVar:  'MINIO_SECRET_KEY'
 *   ])
 *   uploader.upload()
 */
class MinioUploader implements Serializable {
    private static final long serialVersionUID = 1L

    def steps
    Logger log

    String endpoint
    String bucket
    String objectKey
    String file
    String accessKeyVar
    String secretKeyVar
    String alias
    boolean createBucketIfMissing
    String mcImage

    MinioUploader(steps, Map cfg) {
        this.steps                 = steps
        this.log                   = new Logger(steps, 'minio')

        this.endpoint              = required(cfg, 'endpoint')
        this.bucket                = required(cfg, 'bucket')
        this.objectKey             = required(cfg, 'objectKey')
        this.file                  = required(cfg, 'file')
        this.accessKeyVar          = cfg.accessKeyVar ?: 'MINIO_ACCESS_KEY'
        this.secretKeyVar          = cfg.secretKeyVar ?: 'MINIO_SECRET_KEY'
        this.alias                 = cfg.alias        ?: 'sbomstore'
        this.createBucketIfMissing = cfg.createBucketIfMissing != null ? cfg.createBucketIfMissing : true
        this.mcImage               = cfg.mcImage      ?: 'minio/mc:latest'
    }

    void upload() {
        log.banner("Uploading ${file} -> ${endpoint}/${bucket}/${objectKey}")
        if (!steps.fileExists(file)) {
            steps.error "MinioUploader: file '${file}' does not exist"
        }

        String hostPath = steps.pwd()
        // We pass credentials through env vars; mc reads them via `mc alias set`.
        // The credentials are referenced from the wrapping `vars/uploadSbomToMinio.groovy`
        // step using `withCredentials`.
        String script = """
            set -eu
            docker run --rm \\
              --network host \\
              -e MC_ACCESS=\$${accessKeyVar} \\
              -e MC_SECRET=\$${secretKeyVar} \\
              -v ${shellQuote(hostPath)}:/work \\
              -w /work \\
              --entrypoint sh \\
              ${shellQuote(mcImage)} -c '
                set -eu
                mc alias set ${shellQuote(alias)} ${shellQuote(endpoint)} "\$MC_ACCESS" "\$MC_SECRET" >/dev/null
                ${createBucketIfMissing ? "mc mb --ignore-existing ${shellQuote("${alias}/${bucket}")}" : ":" }
                mc cp ${shellQuote(file)} ${shellQuote("${alias}/${bucket}/${objectKey}")}
              '
        """.stripIndent()
        steps.sh script

        log.info "Uploaded SBOM to ${bucket}/${objectKey}"
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    private static String required(Map cfg, String key) {
        if (!cfg[key]) {
            throw new IllegalArgumentException("MinioUploader: '${key}' is required")
        }
        return cfg[key]
    }
}
