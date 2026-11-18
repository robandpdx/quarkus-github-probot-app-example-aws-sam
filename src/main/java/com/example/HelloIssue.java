package com.example;

import java.io.IOException;
import java.io.StringReader;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.GitHubEvent;

/**
 * Listens for newly opened issues and greets them with a comment.
 */
@Singleton
public class HelloIssue {

    static final String COMMENT = "Hello word!";

    private static final Logger LOG = Logger.getLogger(HelloIssue.class);

    @Inject
    GitHubClientProvider githubClients;

    // Keep the webhook request open until processing finishes so Lambda cannot freeze the work.
    void onOpen(@Observes GitHubEvent event) throws IOException {
        if (!"issues".equals(event.getEvent()) || !"opened".equals(event.getAction())) {
            return;
        }

        try {
            Long installationId = event.getInstallationId();
            if (installationId == null) {
                throw new IllegalStateException("The issue webhook is missing its GitHub App installation ID");
            }

            GitHub github = githubClients.getInstallationClient(installationId);
            GHEventPayload.Issue issuePayload = github.parseEventPayload(
                    new StringReader(event.getPayload()), GHEventPayload.Issue.class);
            LOG.infof("Commenting on issue #%d in %s", issuePayload.getIssue().getNumber(),
                    issuePayload.getRepository().getFullName());
            issuePayload.getIssue().comment(COMMENT);
        } catch (IOException | IllegalStateException e) {
            LOG.errorf(e, "Unable to process GitHub delivery %s", event.getDeliveryId());
            throw e;
        }
    }
}
