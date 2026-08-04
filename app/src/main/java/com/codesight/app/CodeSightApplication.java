package com.codesight.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.context.annotation.ComponentScan;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.codesight")
@MapperScan(basePackages = "com.codesight", annotationClass = Mapper.class)
public class CodeSightApplication {

	public static void main(String[] args) {
		SpringApplication.run(CodeSightApplication.class, args);
	}

}
