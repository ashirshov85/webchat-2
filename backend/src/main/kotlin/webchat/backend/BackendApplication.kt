package webchat.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BackendApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<BackendApplication>(*args)
}
