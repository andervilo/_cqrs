package com.cqrs.ordercommand;

import org.springframework.boot.SpringApplication;

public class TestMsOrderCommandApplication {

	public static void main(String[] args) {
		SpringApplication.from(MsOrderCommandApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
