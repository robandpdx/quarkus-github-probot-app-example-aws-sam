package com.example;

import java.io.IOException;

import org.jboss.logging.Logger;
import org.kohsuke.github.GHEventPayload;

import io.quarkiverse.githubapp.event.Issue;

/**
 * Listens for newly opened issues and greets them with a comment.
 */
public class HelloIssue {

    static final String COMMENT = "Hello word!";

    private static final Logger LOG = Logger.getLogger(HelloIssue.class);

    void onOpen(@Issue.Opened GHEventPayload.Issue issuePayload) throws IOException {
        LOG.infof("Commenting on issue #%d in %s", issuePayload.getIssue().getNumber(),
                issuePayload.getRepository().getFullName());
        issuePayload.getIssue().comment(COMMENT);
    }
}
