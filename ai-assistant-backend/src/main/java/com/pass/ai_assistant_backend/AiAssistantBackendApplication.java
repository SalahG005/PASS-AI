package com.pass.ai_assistant_backend;

import com.pass.ai_assistant_backend.ai.OllamaProperties;
import com.pass.ai_assistant_backend.ai.ScaffoldPreviewProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ OllamaProperties.class, ScaffoldPreviewProperties.class })
public class AiAssistantBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiAssistantBackendApplication.class, args);
	}

}
