package com.personal.bookmark_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BookmarkBackendApplication {

    public static void main(String[] args) {
        com.personal.bookmark_backend.time.ApplicationTime.initializeUtc();
        SpringApplication.run(BookmarkBackendApplication.class, args);
    }

}
