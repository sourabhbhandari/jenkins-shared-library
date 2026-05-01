package com.sourabh.argocd

import com.sourabh.utils.Logger

/**
 * Renders an ArgoCD `Application` manifest from a template (resources/com/sourabh/argocd/application.yaml.tpl)
 * and applies it to the cluster via `kubectl`. Optionally syncs and waits for health.
 *
 * Assumes the Jenkins agent has a kubeconfig pointing at the docker-desktop cluster
 * (or equivalent) where ArgoCD is running.
 *
 * Usage:
 *   def deployer = new ArgoCdDeployer(this, [
 *       appName:        'my-app',
 *       namespace:      'my-app',
 *       argoNamespace:  'argocd',
 *       repoUrl:        'https://github.com/sourabh/my-app-config.git',
 *       targetRevision: 'main',
 *       chartPath:      'charts/my-app',
 *       imageTag:       env.BUILD_NUMBER,
 *       imageRepo:      'my-app',
 *       destServer:     'https://kubernetes.default.svc',
 *       sync:           true,
 *       waitHealthy:    true
 *   ])
 *   deployer.deploy()
 */
class ArgoCdDeployer implements Serializable {
    private static final long serialVersionUID = 1L

    def steps
    Logger log

    String appName
    String namespace
    String argoNamespace
    String repoUrl
    String targetRevision
    String chartPath
    String destServer
    String imageRepo
    String imageTag
    boolean sync
    boolean waitHealthy
    int waitTimeoutSec
    String templateResource     // path inside the library `resources/`

    ArgoCdDeployer(steps, Map cfg) {
        this.steps          = steps
        this.log            = new Logger(steps, 'argocd')

        this.appName        = required(cfg, 'appName')
        this.namespace      = cfg.namespace      ?: appName
        this.argoNamespace  = cfg.argoNamespace  ?: 'argocd'
        this.repoUrl        = required(cfg, 'repoUrl')
        this.targetRevision = cfg.targetRevision ?: 'HEAD'
        this.chartPath      = required(cfg, 'chartPath')
        this.destServer     = cfg.destServer     ?: 'https://kubernetes.default.svc'
        this.imageRepo      = cfg.imageRepo      ?: ''
        this.imageTag       = cfg.imageTag       ?: ''
        this.sync           = cfg.sync != null ? cfg.sync : true
        this.waitHealthy    = cfg.waitHealthy != null ? cfg.waitHealthy : true
        this.waitTimeoutSec = (cfg.waitTimeoutSec ?: 300) as int
        this.templateResource = cfg.templateResource ?: 'com/sourabh/argocd/application.yaml.tpl'
    }

    void deploy() {
        log.banner("ArgoCD deploy :: ${appName} (revision=${targetRevision})")

        String manifest = renderManifest()
        String manifestPath = "argocd-${appName}.yaml"
        steps.writeFile file: manifestPath, text: manifest
        log.info "Wrote manifest to ${manifestPath}"

        steps.sh "kubectl apply -n ${shellQuote(argoNamespace)} -f ${shellQuote(manifestPath)}"

        if (sync) {
            // Trigger sync. We use kubectl annotate to request a refresh, then `argocd app sync`
            // if the CLI is available; otherwise we patch the spec to force-sync via auto-sync.
            String syncScript = """
                set -eu
                if command -v argocd >/dev/null 2>&1; then
                  argocd app sync ${shellQuote(appName)} --grpc-web || true
                else
                  kubectl -n ${shellQuote(argoNamespace)} annotate application ${shellQuote(appName)} \\
                    argocd.argoproj.io/refresh=hard --overwrite
                fi
            """.stripIndent()
            steps.sh syncScript
        }

        if (waitHealthy) {
            log.info "Waiting up to ${waitTimeoutSec}s for ${appName} to be Healthy & Synced"
            String waitScript = """
                set -eu
                deadline=\$((\$(date +%s) + ${waitTimeoutSec}))
                while [ \$(date +%s) -lt \$deadline ]; do
                  health=\$(kubectl -n ${shellQuote(argoNamespace)} get application ${shellQuote(appName)} -o jsonpath='{.status.health.status}' 2>/dev/null || echo Unknown)
                  sync=\$(kubectl -n ${shellQuote(argoNamespace)} get application ${shellQuote(appName)} -o jsonpath='{.status.sync.status}' 2>/dev/null || echo Unknown)
                  echo "[argocd] health=\$health sync=\$sync"
                  if [ "\$health" = "Healthy" ] && [ "\$sync" = "Synced" ]; then
                    echo "[argocd] application is healthy and synced"
                    exit 0
                  fi
                  sleep 5
                done
                echo "[argocd] timed out waiting for ${appName} to become healthy"
                kubectl -n ${shellQuote(argoNamespace)} get application ${shellQuote(appName)} -o yaml || true
                exit 1
            """.stripIndent()
            steps.sh waitScript
        }
    }

    private String renderManifest() {
        String tpl = steps.libraryResource(templateResource)
        Map<String, String> vars = [
            APP_NAME       : appName,
            NAMESPACE      : namespace,
            ARGO_NAMESPACE : argoNamespace,
            REPO_URL       : repoUrl,
            TARGET_REVISION: targetRevision,
            CHART_PATH     : chartPath,
            DEST_SERVER    : destServer,
            IMAGE_REPO     : imageRepo,
            IMAGE_TAG      : imageTag
        ]
        String rendered = tpl
        vars.each { k, v -> rendered = rendered.replace("\${${k}}", v ?: '') }
        return rendered
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    private static String required(Map cfg, String key) {
        if (!cfg[key]) {
            throw new IllegalArgumentException("ArgoCdDeployer: '${key}' is required")
        }
        return cfg[key]
    }
}
