package webchat.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest
class BackendApplicationTests(
    @Autowired private val context: ApplicationContext,
) {
    @Test
    fun contextLoads() {
        assertThat(context.getBean(BackendApplication::class.java)).isNotNull()
    }
}
