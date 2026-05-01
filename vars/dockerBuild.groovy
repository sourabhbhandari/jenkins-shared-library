import com.sourabh.docker.DockerBuilder

/**
 * Build a Docker image from a Dockerfile.
 *
 * Required:
 *   imageName  - image name (e.g. 'my-app')
 *
 * Optional:
 *   dockerfile  (default 'Dockerfile')
 *   context     (default '.')
 *   tag         (default 'latest')
 *   registry    (default '' - no registry prefix)
 *   buildArgs   (default [:])
 *   push        (default false)
 *   pull        (default false)
 *   noCache     (default false)
 *   platform    (default '')
 *   extraTags   (default [])
 *   credentialsId - Jenkins credentials for registry login (optional)
 *
 * Returns: full image reference (registry/name:tag) of the built image.
 */
def call(Map config = [:]) {
    DockerBuilder builder = new DockerBuilder(this, config)

    String built
    if (config.credentialsId && config.push && config.registry) {
        withCredentials([usernamePassword(
            credentialsId: config.credentialsId,
            usernameVariable: 'REG_USER',
            passwordVariable: 'REG_PASS'
        )]) {
            sh "echo \"\$REG_PASS\" | docker login ${config.registry} -u \"\$REG_USER\" --password-stdin"
            try {
                built = builder.build()
            } finally {
                sh "docker logout ${config.registry} || true"
            }
        }
    } else {
        built = builder.build()
    }
    return built
}
