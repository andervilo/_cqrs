package com.cqrs.orderquery;

import org.springframework.boot.SpringApplication;

public class TestMsOrderQueryApplication {

	public static void main(String[] args) {
		SpringApplication.from(MsOrderQueryApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
