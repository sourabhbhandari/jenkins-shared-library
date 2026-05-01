package com.sourabh.utils

class Helper implements Serializable {
    def steps

    Helper(steps) {
        this.steps = steps
    }

    def sayHello() {
        steps.echo "Hello from Helper class"
    }
}