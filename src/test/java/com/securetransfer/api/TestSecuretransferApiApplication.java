package com.securetransfer.api;

import org.springframework.boot.SpringApplication;

public class TestSecuretransferApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(SecuretransferApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
