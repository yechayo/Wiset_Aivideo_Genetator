package com.comic;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan({"com.comic.repository", "com.comic.mapper"})
public class ComicApplication {
    public static void main(String[] args) {
        SpringApplication.run(ComicApplication.class, args);
    }
}
