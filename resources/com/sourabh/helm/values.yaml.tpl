# Default values for the chart packaged by this library's helmPackage step.
# helmPackage will rewrite image.repository and image.tag in-place when imageRepo/imageTag
# are passed in.

replicaCount: 1

image:
  repository: ${IMAGE_REPO}
  tag: ${IMAGE_TAG}
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 80
  targetPort: 8080

resources:
  limits:
    cpu: 500m
    memory: 512Mi
  requests:
    cpu: 100m
    memory: 128Mi

ingress:
  enabled: false
