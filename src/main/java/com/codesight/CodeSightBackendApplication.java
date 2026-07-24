package com.codesight;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.codesight.*.mapper")
@SpringBootApplication
public class CodeSightApplication {

	public static void main(String[] args) {
		SpringApplication.run(CodeSightApplication.class, args);
	}

}
