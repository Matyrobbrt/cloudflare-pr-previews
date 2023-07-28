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

    public static PagedIterable<GHReaction> listReactions(GHPullRequest pr) {
        return pr.owner.root().createRequest()
                .withPreview(SQUIRREL_GIRL)
                .withUrlPath(pr.getIssuesApiRoute(), "reactions")
                .toIterable(GHReaction[].class, null);
    }

    public static void deleteReaction(GHPullRequest pr, GHReaction reaction) throws IOException {
        pr.owner.root()
                .createRequest()
                .method("DELETE")
                .withUrlPath(pr.getIssuesApiRoute(), "reactions", String.valueOf(reaction.getId()))
                .send();
    }

    public static GHReaction createReaction(GHPullRequest pr, ReactionContent content) throws IOException {
        return pr.root().createRequest()
                .method("POST")
                .withPreview(SQUIRREL_GIRL)
                .with("content", content.getContent())
                .withUrlPath(pr.getIssuesApiRoute() + "/reactions")
                .fetch(GHReaction.class);
    }

    public static ObjectReader objectReader(GitHub gitHub) {
        return GitHubClient.getMappingObjectReader(gitHub);
    }
}
