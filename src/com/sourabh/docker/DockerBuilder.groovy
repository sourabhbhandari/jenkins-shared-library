package com.sourabh.docker

import com.sourabh.utils.Logger

/**
 * Builds a Docker image from a Dockerfile.
 *
 * Usage from a `vars/` step:
 *   def builder = new DockerBuilder(this, [
 *       dockerfile: 'Dockerfile',
 *       context:    '.',
 *       imageName:  'my-app',
 *       tag:        env.BUILD_NUMBER,
 *       registry:   'localhost:5000',
 *       buildArgs:  ['APP_ENV': 'prod'],
 *       push:       false
 *   ])
 *   def fullRef = builder.build()
 */
class DockerBuilder implements Serializable {
    private static final long serialVersionUID = 1L

    def steps
    Logger log

    String dockerfile
    String context
    String imageName
    String tag
    String registry
    Map<String, String> buildArgs
    boolean push
    boolean pull
    boolean noCache
    String platform
    List<String> extraTags

    DockerBuilder(steps, Map cfg) {
        this.steps      = steps
        this.log        = new Logger(steps, 'docker')

        this.dockerfile = cfg.dockerfile ?: 'Dockerfile'
        this.context    = cfg.context    ?: '.'
        this.imageName  = required(cfg, 'imageName')
        this.tag        = cfg.tag        ?: 'latest'
        this.registry   = cfg.registry   ?: ''
        this.buildArgs  = (cfg.buildArgs ?: [:]) as Map
        this.push       = cfg.push       ?: false
        this.pull       = cfg.pull       ?: false
        this.noCache    = cfg.noCache    ?: false
        this.platform   = cfg.platform   ?: ''
        this.extraTags  = (cfg.extraTags ?: []) as List
    }

    /**
     * Returns the fully-qualified image reference (registry/name:tag) on success.
     */
    String build() {
        log.banner("Docker build :: ${fullRef()}")
        steps.sh "docker version --format '{{.Server.Version}}'"

        if (!steps.fileExists(dockerfile)) {
            steps.error "Dockerfile not found at '${dockerfile}'"
        }

        String cmd = buildCommand()
        log.info "Running: ${cmd}"
        steps.sh cmd

        extraTags.each { String t ->
            String alias = imageRef(t)
            steps.sh "docker tag ${fullRef()} ${alias}"
            log.info "Tagged as ${alias}"
        }

        if (push) {
            pushImage()
        }
        return fullRef()
    }

    /**
     * Returns image digest (sha256:...) for the built tag. Useful for SBOM pinning.
     */
    String digest() {
        String out = steps.sh(
            script: "docker image inspect ${fullRef()} --format '{{index .RepoDigests 0}}' 2>/dev/null || docker image inspect ${fullRef()} --format '{{.Id}}'",
            returnStdout: true
        ).trim()
        return out
    }

    String fullRef() {
        return imageRef(tag)
    }

    String imageRef(String t) {
        return registry ? "${registry}/${imageName}:${t}" : "${imageName}:${t}"
    }

    private String buildCommand() {
        List<String> parts = ['docker', 'build']
        parts << "-f ${shellQuote(dockerfile)}"
        parts << "-t ${shellQuote(fullRef())}"
        if (pull)    parts << '--pull'
        if (noCache) parts << '--no-cache'
        if (platform) parts << "--platform ${shellQuote(platform)}"
        buildArgs.each { k, v ->
            parts << "--build-arg ${shellQuote("${k}=${v}")}"
        }
        parts << shellQuote(context)
        return parts.join(' ')
    }

    private void pushImage() {
        log.info "Pushing ${fullRef()}"
        steps.sh "docker push ${shellQuote(fullRef())}"
        extraTags.each { String t ->
            steps.sh "docker push ${shellQuote(imageRef(t))}"
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    private String required(Map cfg, String key) {
    def value = cfg.get(key)
    if (value == null || value.toString().trim() == '') {
        throw new IllegalArgumentException("DockerBuilder: '${key}' is required. Got: ${cfg}")
    }
    return value.toString()
    }
}
