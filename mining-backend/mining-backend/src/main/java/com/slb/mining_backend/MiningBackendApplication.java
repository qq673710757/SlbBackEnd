package com.slb.mining_backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.slb.mining_backend") // <-- 添加此行
@MapperScan("com.slb.mining_backend.modules.*.mapper")
@EnableCaching
@EnableScheduling
public class MiningBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(MiningBackendApplication.class, args);
	}

}
