package org.kohsuke.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;

import java.io.IOException;

import static org.kohsuke.github.internal.Previews.SQUIRREL_GIRL;

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

    public static PagedIterable<GHReaction> listReactions(GitHub root, GHPullRequest pr) {
        final var repo = pr.getRepository();
        return root.createRequest()
                .withPreview(SQUIRREL_GIRL)
                .withUrlPath("/repos/" + repo.getOwnerName() + "/" + repo.getName() + "/issues/" + pr.getNumber() + "/reactions")
                .toIterable(GHReaction[].class, null);
    }

    public static ObjectReader objectReader(GitHub gitHub) {
        return GitHubClient.getMappingObjectReader(gitHub);
    }
}
