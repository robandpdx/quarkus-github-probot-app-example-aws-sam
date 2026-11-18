package com.example;

import static io.quarkiverse.githubapp.testing.GitHubAppTesting.given;
import static io.quarkiverse.githubapp.testing.GitHubAppTesting.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHEvent;

import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GitHubAppTest
class HelloIssueTest {

    @Test
    void commentsHelloOnNewIssue() throws IOException {
        when().payloadFromClasspath("/issue-opened.json")
                .event(GHEvent.ISSUES)
                .then().github(mocks -> {
                    verify(mocks.issue(750705278))
                            .comment("Hello word!");
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void ignoresClosedIssue() throws IOException {
        when().payloadFromClasspath("/issue-closed.json")
                .event(GHEvent.ISSUES)
                .then().github(mocks -> {
                    verify(mocks.issue(750705278), never())
                            .comment("Hello word!");
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }

    @Test
    void reportsCommentFailureBeforeResponding() throws IOException {
        int status = given().github(mocks -> {
            doThrow(new IOException("GitHub is unavailable"))
                    .when(mocks.issue(750705278)).comment("Hello word!");
        }).when().payloadFromClasspath("/issue-opened.json")
                .event(GHEvent.ISSUES, true)
                .statusCode();

        assertEquals(500, status);
    }

    @Test
    void ignoresOtherEventTypes() throws IOException {
        when().payloadFromClasspath("/issue-opened.json")
                .event(GHEvent.PULL_REQUEST)
                .then().github(mocks -> {
                    verify(mocks.issue(750705278), never())
                            .comment("Hello word!");
                    verifyNoMoreInteractions(mocks.ghObjects());
                });
    }
}
