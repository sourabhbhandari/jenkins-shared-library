# jenkins-shared-lib

A Jenkins shared library that gives you a single line of pipeline code for the full
build → SBOM → publish → deploy lifecycle:

```
docker build  →  Trivy SBOM (CycloneDX)  →  upload to MinIO  →  helm package  →  ArgoCD deploy
```

It is registry-agnostic, runs all the heavy tooling (Trivy, MinIO `mc`, yq) inside Docker
so the agent only needs Docker + `kubectl`, and ships templates for an ArgoCD `Application`
resource targeted at the in-cluster API (so it works out-of-the-box with docker-desktop's
Kubernetes).

## Repository layout

```
jenkins-shared-lib/
├── vars/                                  # Pipeline DSL steps (entry points)
│   ├── dockerBuild.groovy / .txt          # Build a Docker image from a Dockerfile
│   ├── pythonBuild.groovy / .txt          # Run the bundled Python orchestrator
│   ├── generateSbom.groovy / .txt         # Trivy → CycloneDX SBOM
│   ├── uploadSbomToMinio.groovy / .txt    # mc cp into MinIO with creds from Jenkins
│   ├── helmPackage.groovy / .txt          # helm lint + package, rewrites image tag
│   ├── argoCdDeploy.groovy / .txt         # render & apply ArgoCD Application
│   ├── ciCdPipeline.groovy / .txt         # one-line composite of the above
│   ├── buildApp.groovy                    # legacy wrapper, forwards to dockerBuild
│   └── deployApp.groovy                   # legacy wrapper, forwards to argoCdDeploy
├── src/com/sourabh/                       # Groovy implementation classes
│   ├── docker/DockerBuilder.groovy
│   ├── sbom/SbomGenerator.groovy
│   ├── sbom/MinioUploader.groovy
│   ├── helm/HelmPackager.groovy
│   ├── argocd/ArgoCdDeployer.groovy
│   ├── utils/Logger.groovy
│   └── utils/Helper.groovy                (existing)
├── resources/com/sourabh/                 # Bundled non-Groovy assets
│   ├── scripts/build.py                   # Python orchestrator (used by pythonBuild)
│   ├── scripts/upload_sbom.py             # standalone MinIO uploader
│   ├── argocd/application.yaml.tpl        # ArgoCD Application template
│   └── helm/values.yaml.tpl               # reference values.yaml
├── examples/
│   ├── Jenkinsfile.simple                 # one-line ciCdPipeline call
│   ├── Jenkinsfile.full                   # explicit per-stage example
│   └── Jenkinsfile.python                 # uses pythonBuild orchestrator
├── test/com/sourabh/
│   └── DockerBuilderTest.groovy           # smoke test
└── README.md
```

The directory layout follows Jenkins' conventions: `vars/` for global pipeline steps,
`src/` for Groovy classes (package = directory), `resources/` for assets that steps load
via `libraryResource(...)`.

## Steps at a glance

| Step | Purpose |
| --- | --- |
| `dockerBuild`        | Build (and optionally push) a Docker image from a Dockerfile. Returns the full image reference. |
| `pythonBuild`        | Run the bundled Python orchestrator (`build.py`) which does docker build + Trivy SBOM + MinIO upload in one process. Returns `[imageRef, sbomPath, minioObject]`. |
| `generateSbom`       | Generate a CycloneDX SBOM with Trivy (run via `aquasec/trivy` Docker image). |
| `uploadSbomToMinio`  | Upload any file (typically the SBOM) to a MinIO bucket. Credentials come from a Jenkins username/password credential. |
| `helmPackage`        | `helm lint` + `helm package`, optionally rewriting `image.repository` / `image.tag` in `values.yaml` via yq. |
| `argoCdDeploy`       | Render the ArgoCD `Application` template, `kubectl apply`, trigger sync, optionally wait until Healthy & Synced. |
| `ciCdPipeline`       | Pre-baked Declarative Pipeline that calls all of the above based on a single config map. |

Every step is documented with its own `.txt` file that Jenkins surfaces in the **Pipeline Syntax → Snippet Generator** UI.

## Prerequisites on the Jenkins agent

The agent that runs the pipeline must have:

- Docker (with the daemon socket reachable — `/var/run/docker.sock`)
- `helm` CLI (3.x)
- `kubectl` with a kubeconfig pointing at your docker-desktop cluster
- (Optional) `argocd` CLI logged-in, for nicer sync output
- Python 3.x — only needed if you use the `pythonBuild` step

Trivy, MinIO `mc`, and yq are all run via Docker, so they don't need to be installed on the agent.

## Required Jenkins credentials

Create these in **Manage Jenkins → Credentials** before using the library:

| ID (default) | Type | Purpose |
| --- | --- | --- |
| `minio-creds`           | Username with password | Username = MinIO access key, password = MinIO secret key |
| `docker-registry-creds` | Username with password | Only if pushing to a private registry; can be omitted otherwise |

(The credential IDs are configurable per-step via `credentialsId`.)

## Registering the library in Jenkins

There are three ways to register a shared library. Pick whichever matches your workflow.

### 1) Global Pipeline Library (most common)

This makes the library available to every pipeline on the controller without an explicit `@Library` annotation.

1. Push this repository to a Git host that Jenkins can reach (GitHub, GitLab, Bitbucket, or a local Gitea).
2. In Jenkins go to **Manage Jenkins → System → Global Pipeline Libraries** and click **Add**.
3. Fill in:
   - **Name:** `jenkins-shared-lib`
   - **Default version:** `main` (or a tag like `v1.0.0`)
   - **Retrieval method:** *Modern SCM* → *Git*
   - **Project Repository:** the URL of this repo
   - **Credentials:** add a credential if the repo is private
4. Optional: tick **Load implicitly** if you want `@Library('jenkins-shared-lib')` to be implied for every pipeline.
5. Optional: tick **Allow default version to be overridden** to let pipelines pin a different ref.
6. Click **Save**.

In a `Jenkinsfile`:

```groovy
@Library('jenkins-shared-lib@main') _   // version after @ is optional

ciCdPipeline(
  app:      [name: 'my-app', dockerfile: 'Dockerfile', chartDir: 'charts/my-app'],
  registry: [url: 'localhost:5000', push: true],
  minio:    [endpoint: 'http://localhost:9001', bucket: 'sboms', credentialsId: 'minio-creds'],
  argocd:   [repoUrl: 'https://github.com/sourabh/my-app-config.git', chartPath: 'charts/my-app']
)
```

### 2) Folder-level library

If you only want the library available inside a specific Jenkins folder:

1. Open the folder → **Configure** → **Pipeline Libraries** → **Add**.
2. Same fields as above. Folder libraries override globals with the same name.

### 3) Dynamic load (no admin config needed)

For ad-hoc usage without registering at all:

```groovy
library identifier: 'jenkins-shared-lib@main',
        retriever: modernSCM([
            $class: 'GitSCMSource',
            remote: 'https://github.com/sourabh/jenkins-shared-lib.git'
            // credentialsId: 'github-creds'   // for private repos
        ])

ciCdPipeline(...)
```

This is handy for prototyping; the `@Library` annotation form is preferred for production
because it loads the library before the pipeline parses, which catches symbol resolution
errors earlier.

### Configuration as Code (CasC)

If you manage Jenkins via [JCasC](https://www.jenkins.io/projects/jcasc/), add:

```yaml
unclassified:
  globalLibraries:
    libraries:
      - name: "jenkins-shared-lib"
        defaultVersion: "main"
        implicit: false
        allowVersionOverride: true
        retriever:
          modernSCM:
            scm:
              git:
                remote: "https://github.com/sourabh/jenkins-shared-lib.git"
                # credentialsId: "github-creds"
```

## End-to-end usage example

A consuming application repo only needs three things:

1. A `Dockerfile`
2. A Helm chart at `charts/<app>/`
3. A `Jenkinsfile` like this:

```groovy
@Library('jenkins-shared-lib@main') _

ciCdPipeline(
  app: [
    name:       'my-app',
    dockerfile: 'Dockerfile',
    context:    '.',
    chartDir:   'charts/my-app'
  ],
  registry: [
    url:  'localhost:5000',
    push: true
  ],
  sbom:  [ format: 'cyclonedx' ],
  minio: [
    endpoint:      'http://localhost:9001',
    bucket:        'sboms',
    credentialsId: 'minio-creds'
  ],
  argocd: [
    repoUrl:        'https://github.com/sourabh/my-app-config.git',
    targetRevision: 'main',
    chartPath:      'charts/my-app',
    namespace:      'my-app'
  ]
)
```

That's it — running this Jenkinsfile produces:

- `localhost:5000/my-app:<build#>` pushed to the local registry
- `sbom/sbom.cdx.json` archived as a build artifact
- `<bucket>/<job>/<build#>/sbom.cdx.json` in MinIO at the configured endpoint
- `helm-pkg/my-app-1.0.<build#>.tgz` archived
- An ArgoCD `Application` synced into the docker-desktop cluster, using the new image tag

See `examples/Jenkinsfile.full` for the explicit per-stage version of the same pipeline,
and `examples/Jenkinsfile.python` for the Python-orchestrator variant.

## How the ArgoCD deployment works

`argoCdDeploy` renders `resources/com/sourabh/argocd/application.yaml.tpl` with your values
and `kubectl apply`s it into the `argocd` namespace. The default destination server is
`https://kubernetes.default.svc` — the in-cluster API, which is exactly what ArgoCD running
on docker-desktop talks to. Because ArgoCD's `automated.selfHeal: true` is set in the
template, ArgoCD will continuously reconcile the cluster state to match the chart in Git;
the step also triggers an immediate sync and waits until the application reports
`Healthy & Synced` (configurable timeout, default 5 minutes).

The image tag is passed as a Helm `parameter` in the Application spec, so you don't need to
push a `values.yaml` change to Git on every build — just bump the tag.

## Running the Python orchestrator locally

You can run the orchestrator outside Jenkins for debugging:

```bash
cat > build-config.json <<'EOF'
{
  "imageName": "my-app",
  "tag": "dev",
  "registry": "localhost:5000",
  "dockerfile": "Dockerfile",
  "context": ".",
  "push": false,
  "sbom": { "format": "cyclonedx", "outputDir": "sbom", "outputFile": "sbom.cdx.json" },
  "minio": { "endpoint": "http://localhost:9001", "bucket": "sboms", "objectKey": "local/sbom.cdx.json" }
}
EOF

export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin

python3 resources/com/sourabh/scripts/build.py \
  --config build-config.json \
  --output build-output.json
```

## Testing the library

The repo includes a tiny smoke test you can run with `groovy`:

```bash
groovy -cp src test/com/sourabh/DockerBuilderTest.groovy
```

For deeper testing, integrate
[JenkinsPipelineUnit](https://github.com/jenkinsci/JenkinsPipelineUnit) — the structure of
this library (steps in `vars/`, logic in `src/`) makes it easy to unit-test the classes
without booting Jenkins.

## Versioning

Tag releases on this repo: `v1.0.0`, `v1.1.0`, etc. Pin pipelines to a tag for stability
and bump intentionally:

```groovy
@Library('jenkins-shared-lib@v1.0.0') _
```

## Security notes

- All credentials flow through Jenkins' `withCredentials` so they never appear in build logs.
- The Trivy and `mc` containers are pinned to specific tags via the step parameters.
- The ArgoCD template enables `automated.prune` and `selfHeal` — review whether this matches
  your operational policy before adopting in production.
