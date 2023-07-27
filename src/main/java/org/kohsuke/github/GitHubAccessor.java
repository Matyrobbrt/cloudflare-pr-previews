package org.kohsuke.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;

import java.io.IOException;

public class GitHubAccessor {
    public static GHArtifact[] getArtifacts(GitHub gitHub, String repo, long runId) throws IOException {
        return gitHub.createRequest()
                .withUrlPath("/repos/" + repo + "/actions/runs/" + runId, "artifacts")
                .fetchStream(input -> {
                    final ObjectReader mapper = objectReader(gitHub);
                    final JsonNode node = mapper.readTree(input);
                    input.close();
                    return mapper.readValue(node.get("artifacts"), GHArtifact[].class);
                });
    }

    public static ObjectReader objectReader(GitHub gitHub) {
        return GitHubClient.getMappingObjectReader(gitHub);
    }
}
