import com.sourabh.sbom.MinioUploader

/**
 * Upload a file to MinIO using credentials from the Jenkins credential store.
 *
 * The credential MUST be a "Username with password" credential where:
 *   - Username = MinIO access key
 *   - Password = MinIO secret key
 *
 * Required:
 *   endpoint      - e.g. 'http://localhost:9001'
 *   bucket
 *   objectKey
 *   file
 *   credentialsId - Jenkins credential id (default 'minio-creds')
 *
 * Optional:
 *   alias                  (default 'sbomstore')
 *   createBucketIfMissing  (default true)
 *   mcImage                (default 'minio/mc:latest')
 */
def call(Map cfg = [:]) {
    String credentialsId = cfg.credentialsId ?: 'minio-creds'

    withCredentials([usernamePassword(
        credentialsId: credentialsId,
        usernameVariable: 'MINIO_ACCESS_KEY',
        passwordVariable: 'MINIO_SECRET_KEY'
    )]) {
        // The class reads $MINIO_ACCESS_KEY / $MINIO_SECRET_KEY from the env at sh-time.
        new MinioUploader(this, cfg + [
            accessKeyVar: 'MINIO_ACCESS_KEY',
            secretKeyVar: 'MINIO_SECRET_KEY'
        ]).upload()
    }
}
