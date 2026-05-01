def call(Map config = [:]) {
    pipeline {
        agent any

        stages {
            stage('Build') {
                steps {
                    echo "Building ${config.appName}"
                    sh "echo Build step here"
                }
            }

            stage('Test') {
                steps {
                    echo "Running tests"
                    sh "echo Test step here"
                }
            }
        }
    }
}