package com.harshaandra.agentplane.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI agentplaneOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AGENTPLANE Control Plane API")
                        .description("Kubernetes control plane for LLM agent workloads: run submission, "
                                + "orchestration, tracing and analytics.")
                        .version("v1")
                        .contact(new Contact().name("AGENTPLANE"))
                        .license(new License().name("Apache 2.0")));
    }
}
