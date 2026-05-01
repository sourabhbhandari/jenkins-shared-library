def call(String env) {
    stage('Deploy') {
        steps {
            echo "Deploying to ${env}"
            sh "echo Deploy logic here"
        }
    }
}