package com.example.grouppurchase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest
class GroupPurchaseApplicationTests {

	@Autowired
	private Environment environment;

	@Test
	void contextLoads() {
		assertThat(environment.getProperty("spring.application.name")).isEqualTo("group-purchase");
	}

}
