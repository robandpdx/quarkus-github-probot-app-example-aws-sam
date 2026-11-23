package com.example;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubapp.runtime.config.PrivateKeyConverter;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

class GitHubAppSecretsConfigSourceFactoryTest {

    private static final String SECRET_ARN = "arn:aws:secretsmanager:us-east-1:123456789012:secret:github-app-AbCdEf";
    private static final GetSecretValueRequest REQUEST = GetSecretValueRequest.builder().secretId(SECRET_ARN).build();
    private static final String PEM = "-----BEGIN RSA PRIVATE KEY-----\nline-one\nline-two\n-----END RSA PRIVATE KEY-----";
    private static final String SECRET = """
            {"APP_ID":"123456","WEBHOOK_SECRET":"remote-webhook","PRIVATE_KEY":"-----BEGIN RSA PRIVATE KEY-----\\nline-one\\nline-two\\n-----END RSA PRIVATE KEY-----"}
            """;

    private final SecretsManagerClient client = mock(SecretsManagerClient.class);
    private final Supplier<SecretsManagerClient> clientSupplier = mock();

    @Test
    void doesNotContactAwsWithoutSecretArn() {
        SmallRyeConfig config = buildConfig(Map.of("QUARKUS_GITHUB_APP_APP_ID", "local-id"));

        assertEquals("local-id", config.getValue("quarkus.github-app.app-id", String.class));
        verifyNoInteractions(clientSupplier, client);
    }

    @Test
    void doesNotContactAwsWhenAllValuesAreConfigured() {
        SmallRyeConfig config = buildConfig(Map.of(
                "GITHUB_APP_SECRET_ARN", SECRET_ARN,
                "QUARKUS_GITHUB_APP_APP_ID", "local-id",
                "QUARKUS_GITHUB_APP_WEBHOOK_SECRET", "local-webhook",
                "QUARKUS_GITHUB_APP_PRIVATE_KEY", "local-key"));

        assertEquals("local-id", config.getValue("quarkus.github-app.app-id", String.class));
        assertEquals("local-webhook", config.getValue("quarkus.github-app.webhook-secret", String.class));
        assertEquals("local-key", config.getValue("quarkus.github-app.private-key", String.class));
        verifyNoInteractions(clientSupplier, client);
    }

    @Test
    void loadsAndCachesAllValuesFromSecret() {
        stubSecret(SECRET);
        SmallRyeConfig config = buildConfig(Map.of("GITHUB_APP_SECRET_ARN", SECRET_ARN));

        assertEquals("123456", config.getValue("quarkus.github-app.app-id", String.class));
        assertEquals("remote-webhook", config.getValue("quarkus.github-app.webhook-secret", String.class));
        assertEquals(PEM, config.getValue("quarkus.github-app.private-key", String.class));
        assertEquals(PEM, config.getValue("quarkus.github-app.private-key", String.class));
        verify(client).getSecretValue(REQUEST);
        verify(client).close();
    }

    @Test
    void environmentOverridesIndividualFieldsAndOnlyMissingFieldsAreRequired() {
        stubSecret("""
                {"WEBHOOK_SECRET":"remote-webhook","PRIVATE_KEY":"sensitive-invalid-base64!"}
                """);
        SmallRyeConfig config = buildConfig(Map.of(
                "GITHUB_APP_SECRET_ARN", SECRET_ARN,
                "QUARKUS_GITHUB_APP_APP_ID", "local-id",
                "QUARKUS_GITHUB_APP_PRIVATE_KEY", "local-key"));

        assertEquals("local-id", config.getValue("quarkus.github-app.app-id", String.class));
        assertEquals("remote-webhook", config.getValue("quarkus.github-app.webhook-secret", String.class));
        assertEquals("local-key", config.getValue("quarkus.github-app.private-key", String.class));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void suppliesAUsablePrivateKeyFromPlainOrBase64Pem(boolean encoded, boolean wrapped)
            throws GeneralSecurityException, JsonProcessingException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PrivateKey expected = generator.generateKeyPair().getPrivate();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(expected.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        String secretValue = pem;
        if (encoded) {
            Base64.Encoder encoder = wrapped ? Base64.getMimeEncoder() : Base64.getEncoder();
            secretValue = encoder.encodeToString(pem.getBytes(StandardCharsets.UTF_8));
        }
        stubSecret(new ObjectMapper().writeValueAsString(Map.of("PRIVATE_KEY", secretValue)));
        SmallRyeConfig config = buildConfig(Map.of(
                "GITHUB_APP_SECRET_ARN", SECRET_ARN,
                "QUARKUS_GITHUB_APP_APP_ID", "local-id",
                "QUARKUS_GITHUB_APP_WEBHOOK_SECRET", "local-webhook"));

        String configuredKey = config.getValue("quarkus.github-app.private-key", String.class);
        assertEquals(pem, configuredKey);
        assertArrayEquals(expected.getEncoded(), new PrivateKeyConverter().convert(configuredKey).getEncoded());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sensitive-invalid-base64!", "c2Vuc2l0aXZlLW5vdC1wZW0=", "/w=="})
    void rejectsInvalidPrivateKeyEncodingWithoutLeakingValues(String privateKey) throws JsonProcessingException {
        stubSecret(new ObjectMapper().writeValueAsString(Map.of("PRIVATE_KEY", privateKey)));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> buildConfig(Map.of(
                "GITHUB_APP_SECRET_ARN", SECRET_ARN,
                "QUARKUS_GITHUB_APP_APP_ID", "local-id",
                "QUARKUS_GITHUB_APP_WEBHOOK_SECRET", "local-webhook")));

        assertFalse(error.toString().contains(privateKey));
        assertFalse(error.toString().contains("sensitive"));
        assertNull(error.getCause());
        verify(client).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sensitive-invalid-json",
            "[]",
            "null",
            "",
            "{}",
            "{\"APP_ID\":123456}",
            "{\"APP_ID\":\" \"}",
            "{\"APP_ID\":\"123456\",\"WEBHOOK_SECRET\":\"sensitive-value\"} trailing-data"
    })
    void rejectsMalformedOrIncompleteSecretsWithoutLeakingValues(String secret) {
        stubSecret(secret);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> buildConfig(Map.of("GITHUB_APP_SECRET_ARN", SECRET_ARN)));

        assertFalse(error.toString().contains("sensitive-"));
        assertEquals(null, error.getCause());
        verify(client).close();
    }

    @Test
    void rejectsBinarySecret() {
        stubSecret(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> buildConfig(Map.of("GITHUB_APP_SECRET_ARN", SECRET_ARN)));

        assertEquals("The GitHub App secret must contain a JSON SecretString, not SecretBinary.", error.getMessage());
    }

    @Test
    void propagatesAwsFailuresInsteadOfSilentlyContinuing() {
        AwsServiceException failure = SecretsManagerException.builder().message("Access denied").build();
        when(clientSupplier.get()).thenReturn(client);
        when(client.getSecretValue(REQUEST)).thenThrow(failure);

        assertSame(failure, assertThrows(AwsServiceException.class,
                () -> buildConfig(Map.of("GITHUB_APP_SECRET_ARN", SECRET_ARN))));
        verify(client).close();
    }

    private void stubSecret(String secret) {
        when(clientSupplier.get()).thenReturn(client);
        when(client.getSecretValue(REQUEST)).thenReturn(GetSecretValueResponse.builder().secretString(secret).build());
    }

    private SmallRyeConfig buildConfig(Map<String, String> environment) {
        return new SmallRyeConfigBuilder()
                .withSources(new EnvConfigSource(environment, 300))
                .withSources(new GitHubAppSecretsConfigSourceFactory(clientSupplier))
                .build();
    }
}
