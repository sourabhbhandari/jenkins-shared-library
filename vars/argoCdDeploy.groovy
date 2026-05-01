import com.sourabh.argocd.ArgoCdDeployer

/**
 * Render an ArgoCD Application manifest from the bundled template, apply it, and (optionally)
 * sync + wait for the application to become Healthy.
 *
 * Required:
 *   appName
 *   repoUrl
 *   chartPath
 *
 * Optional:
 *   namespace       (default = appName)
 *   argoNamespace   (default 'argocd')
 *   targetRevision  (default 'HEAD')
 *   destServer      (default 'https://kubernetes.default.svc' - in-cluster, e.g. docker-desktop)
 *   imageRepo       - rendered into values via Helm parameters
 *   imageTag
 *   sync            (default true)
 *   waitHealthy     (default true)
 *   waitTimeoutSec  (default 300)
 *   templateResource (override template path)
 */
def call(Map cfg = [:]) {
    new ArgoCdDeployer(this, cfg).deploy()
}
