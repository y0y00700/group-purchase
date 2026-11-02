package com.example.grouppurchase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GroupPurchaseApplication {

	public static void main(String[] args) {
		SpringApplication.run(GroupPurchaseApplication.class, args);
	}

}
