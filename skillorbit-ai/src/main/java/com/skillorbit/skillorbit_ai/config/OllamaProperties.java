package com.skillorbit.skillorbit_ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Binds ollama.api-url and ollama.model (set via OLLAMA_API_URL / OLLAMA_MODEL). */
@Component
@ConfigurationProperties(prefix = "ollama")
public class OllamaProperties {
    private String apiUrl = "http://localhost:11434";
    private String model = "llama3.2:3b";

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
