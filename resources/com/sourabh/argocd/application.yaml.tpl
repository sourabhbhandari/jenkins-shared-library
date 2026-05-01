apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: ${APP_NAME}
  namespace: ${ARGO_NAMESPACE}
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: default
  source:
    repoURL: ${REPO_URL}
    targetRevision: ${TARGET_REVISION}
    path: ${CHART_PATH}
    helm:
      releaseName: ${APP_NAME}
      parameters:
        - name: image.repository
          value: ${IMAGE_REPO}
        - name: image.tag
          value: ${IMAGE_TAG}
        - name: image.pullPolicy
          value: IfNotPresent
  destination:
    server: ${DEST_SERVER}
    namespace: ${NAMESPACE}
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - PrunePropagationPolicy=foreground
      - PruneLast=true
    retry:
      limit: 5
      backoff:
        duration: 5s
        factor: 2
        maxDuration: 3m
