package com.example;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.microprofile.config.spi.ConfigSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.PropertiesConfigSource;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

public class GitHubAppSecretsConfigSourceFactory implements ConfigSourceFactory {

    private static final Map<String, String> SECRET_KEYS = Map.of(
            "quarkus.github-app.app-id", "APP_ID",
            "quarkus.github-app.webhook-secret", "WEBHOOK_SECRET",
            "quarkus.github-app.private-key", "PRIVATE_KEY");

    private final Supplier<SecretsManagerClient> clientSupplier;

    public GitHubAppSecretsConfigSourceFactory() {
        this(() -> SecretsManagerClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10)))
                .build());
    }

    GitHubAppSecretsConfigSourceFactory(Supplier<SecretsManagerClient> clientSupplier) {
        this.clientSupplier = clientSupplier;
    }

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        String secretArn = configuredValue(context, "github.app.secret-arn");
        if (secretArn == null || secretArn.isBlank()) {
            return List.of();
        }

        List<String> missingProperties = SECRET_KEYS.keySet().stream()
                .filter(name -> configuredValue(context, name) == null)
                .toList();
        if (missingProperties.isEmpty()) {
            return List.of();
        }

        String secretString;
        try (SecretsManagerClient client = clientSupplier.get()) {
            secretString = client.getSecretValue(GetSecretValueRequest.builder()
                    .secretId(secretArn).build()).secretString();
        }
        if (secretString == null) {
            throw new IllegalStateException("The GitHub App secret must contain a JSON SecretString, not SecretBinary.");
        }

        JsonNode secret;
        try {
            secret = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(secretString);
        } catch (JsonProcessingException e) {
            // Jackson's exception can contain secret values, so do not propagate it.
            throw new IllegalStateException("The GitHub App secret must be a valid JSON object.");
        }
        if (secret == null || !secret.isObject()) {
            throw new IllegalStateException("The GitHub App secret must be a JSON object.");
        }

        Map<String, String> properties = new HashMap<>();
        for (String property : missingProperties) {
            String key = SECRET_KEYS.get(property);
            JsonNode value = secret.get(key);
            if (value == null || !value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalStateException("The GitHub App secret requires a non-blank string field: " + key);
            }
            properties.put(property, "PRIVATE_KEY".equals(key) ? decodePrivateKey(value.textValue()) : value.textValue());
        }
        return List.of(new PropertiesConfigSource(properties, "AWS Secrets Manager GitHub App", 270));
    }

    private static String decodePrivateKey(String value) {
        if (isPemPrivateKey(value)) {
            return value;
        }

        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(value.replaceAll("\\s+", "")), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The GitHub App secret field PRIVATE_KEY must be PEM or valid base64-encoded PEM.");
        }
        if (!isPemPrivateKey(decoded)) {
            throw new IllegalStateException("The decoded GitHub App secret field PRIVATE_KEY must contain an unencrypted RSA PEM private key.");
        }
        return decoded;
    }

    private static boolean isPemPrivateKey(String value) {
        return value.contains("-----BEGIN RSA PRIVATE KEY-----") || value.contains("-----BEGIN PRIVATE KEY-----");
    }

    private static String configuredValue(ConfigSourceContext context, String name) {
        ConfigValue value = context.getValue(name);
        return value == null ? null : value.getValue();
    }
}
