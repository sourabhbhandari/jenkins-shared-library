package com.sourabh

import com.sourabh.docker.DockerBuilder

/**
 * Tiny smoke test runnable with `groovy` on the CLI:
 *
 *   groovy -cp src test/com/sourabh/DockerBuilderTest.groovy
 *
 * For full Jenkins-pipeline-style unit tests, use https://github.com/jenkinsci/JenkinsPipelineUnit
 * which we omit here to keep dependencies minimal. The intent of this file is to lock the
 * shape of `DockerBuilder.fullRef()` and the build command construction.
 */
class FakeSteps {
    List<String> commands = []
    boolean shouldExist = true
    String pwdValue = '/workspace'

    def sh(arg) {
        if (arg instanceof Map) {
            commands << (arg.script as String)
            return ''
        }
        commands << (arg as String)
    }
    def echo(String s) {}
    def error(String s) { throw new RuntimeException(s) }
    boolean fileExists(String p) { return shouldExist }
    String pwd() { return pwdValue }
    Map env = [PIPELINE_DEBUG: 'false']
}

def steps = new FakeSteps()
def b = new DockerBuilder(steps, [
    imageName: 'demo',
    tag: '7',
    registry: 'localhost:5000',
    buildArgs: ['FOO': 'bar'],
    noCache: true
])

assert b.fullRef() == 'localhost:5000/demo:7'
b.build()
def buildCmd = steps.commands.find { it.startsWith('docker build') }
assert buildCmd.contains("-f 'Dockerfile'")
assert buildCmd.contains("-t 'localhost:5000/demo:7'")
assert buildCmd.contains('--no-cache')
assert buildCmd.contains("--build-arg 'FOO=bar'")

println "OK — DockerBuilder smoke test passed"
