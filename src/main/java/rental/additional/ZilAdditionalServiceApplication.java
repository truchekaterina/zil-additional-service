package rental.additional;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ZilAdditionalServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ZilAdditionalServiceApplication.class, args);
	}

}
