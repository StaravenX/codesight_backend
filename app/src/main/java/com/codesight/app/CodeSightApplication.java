package com.codesight.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan("com.codesight")
public class CodeSightApplication {

	public static void main(String[] args) {
		SpringApplication.run(CodeSightApplication.class, args);
	}

}
