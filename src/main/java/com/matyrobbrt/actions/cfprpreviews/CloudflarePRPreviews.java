package com.matyrobbrt.actions.cfprpreviews;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matyrobbrt.actions.cfprpreviews.util.AuthUtil;
import com.matyrobbrt.actions.cfprpreviews.util.DeploymentStatus;
import com.matyrobbrt.actions.cfprpreviews.util.GitHubVars;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHArtifact;
import org.kohsuke.github.GHDeploymentState;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHReaction;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubAccessor;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.ReactionContent;
import org.kohsuke.github.authorization.AuthorizationProvider;
import org.kohsuke.github.function.InputStreamFunction;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CloudflarePRPreviews {
    public static void main(String[] args) throws Exception {
        final GitHub api = buildApi();
        final JsonNode run = payload().get("workflow_run");
        if (!run.get("conclusion").asText().equals("success")) {
            System.err.println("Target build workflow run failed! Check logs at " + run.get("html_url").asText());
            System.exit(1);
        }
        final long id = run.get("id").asLong();

        final GHArtifact[] artifacts = GitHubAccessor.getArtifacts(api, GitHubVars.REPOSITORY.get(), id);
        final JsonNode actualPayload = artifacts[0].download(firstEntry(input -> new ObjectMapper().readValue(input, JsonNode.class)));
        final Path sitePath = Path.of("site-upload");
        artifacts[1].download(input -> {
            try (final ZipInputStream zis = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        final Path fp = sitePath.resolve(entry.getName());
                        createDirs(fp);
                        try (final OutputStream os = Files.newOutputStream(fp)) {
                            zis.transferTo(os);
                        }
                    }
                }
            }
            return sitePath;
        });

        final GHRepository repo = api.getRepository(GitHubVars.REPOSITORY.get());
        final JsonNode pr = actualPayload.get("pull_request");
        final int prNumber = pr.get("number").asInt();
        final String sha = pr.get("head").get("sha").asText();
        final var deployedCommit = repo.getCommit(sha);

        final var ghDeployment = repo.createDeployment("pull/" + prNumber + "/head")
                .autoMerge(false)
                .description("Cloudflare Pages")
                .productionEnvironment(false)
                .environment(GitHubVars.PROJECT_NAME.get() + " (Preview)")
                .create();

        final File logsFile = new File("wrangler_logs.txt");
        System.out.println("\uD83D\uDEA7 Starting wrangler deployment... log file will be uploaded as `wrangler-logs` artifact with 3-day retention.");
        final int result = new ProcessBuilder()
                .directory(new File("."))
                .redirectOutput(logsFile)
                .command("npx", "wrangler@3", "pages", "deploy", sitePath.toAbsolutePath().toString(),
                        "--project-name=\"" + GitHubVars.PROJECT_NAME.get() + "\"",
                        "--branch=\"pr-" + prNumber + "\"",
                        "--commit-hash=\"" + sha + "\"",
                        "--commit-message=\"Deploy from " + sha + "\"")
                .redirectError(logsFile)
                .start()
                .waitFor();
        if (result != 0) {
            System.out.println("Wrangler logs:");
            for (String line : Files.readString(logsFile.toPath()).split(System.lineSeparator())) {
                System.out.println("\t" + line);
            }

            deployedCommit.createComment("Failed to publish to Pages. Check Action logs for more details.");
            System.exit(1);
        }
        System.out.println("\uD83D\uDE80 Deployment was successful!");

        final CFApi cfApi = new CFApi(System.getenv("CLOUDFLARE_API_TOKEN"), System.getenv("CLOUDFLARE_ACCOUNT_ID"));
        var deployments = cfApi.getDeployments(GitHubVars.PROJECT_NAME.get());
        if (deployments.isEmpty()) {
            System.err.println("CF API returned no deployments. This shouldn't be possible.");
            System.exit(1);
        }

        var deployment = deployments.get(0); // Last is most recent
        var status = deployment.getStatus();
        if (status == DeploymentStatus.PENDING) {
            for (int i = 0; i < 3; i++) { // Retry 3 more times
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                deployments = cfApi.getDeployments(GitHubVars.PROJECT_NAME.get());
                if (deployments.isEmpty()) {
                    System.err.println("CF API returned no deployments. This shouldn't be possible.");
                    System.exit(1);
                }
                deployment = deployments.get(0); // Last is most recent
                status = deployment.getStatus();

                if (status != DeploymentStatus.PENDING) {
                    break;
                }
            }
        }

        System.out.println("Deployment status: " + status);
        System.out.println("Deployment link: " + deployment.url);

        ghDeployment.createStatus(status.state).logUrl("https://dash.cloudflare.com/" + System.getenv("CLOUDFLARE_ACCOUNT_ID") + "/pages/view/" + GitHubVars.PROJECT_NAME.get() + "/" + deployment.id)
                .autoInactive(false).create();

        System.out.println("Commenting with deployment links...");
        final String message = """
# Deploying with Cloudflare Pages

| Name                    | Result |
| ----------------------- | - |
| **Last commit:**        | [%s](%s) |
| **Status**:             | %s |
| **Preview URL**:        | %s |
| **PR Preview URL**: | %s |""".formatted(
                sha, deployedCommit.getHtmlUrl(),
                status.message,
                deployment.url,
                deployment.aliases == null || deployment.aliases.isEmpty() ? deployment.url : deployment.aliases.get(0)
        );
        deployedCommit.createComment(message);

        final var selfUser = getSelfApp().getSlug() + "[bot]";
        final var ghPr = repo.getPullRequest(prNumber);
        editOrPost(selfUser, ghPr, message);
        if (ghPr.getBody() == null || !ghPr.getBody().contains("Preview URL: ")) {
            ghPr.setBody((ghPr.getBody() != null ? ghPr.getBody() + "\n" : "") + "\n------------------\nPreview URL: " + deployment.aliases.get(0));
        }
        final ReactionContent newReaction = switch (status) {
            case SUCCESS -> ReactionContent.ROCKET;
            case PENDING, FAILURE -> ReactionContent.CONFUSED;
        };

        System.out.println("Reacting to PR...");
        for (GHReaction reaction : GitHubAccessor.listReactions(ghPr)) {
            if (reaction.getUser().getLogin().equals(selfUser)) {
                if (reaction.getContent() == newReaction) {
                    return;
                } else {
                    GitHubAccessor.deleteReaction(ghPr, reaction);
                }
            }
        }
        GitHubAccessor.createReaction(ghPr, newReaction);
    }

    private static void editOrPost(String selfUser, GHPullRequest pr, String message) throws IOException {
        for (org.kohsuke.github.GHIssueComment next : pr.listComments()) {
            if (next.getUser().getLogin().equals(selfUser)) {
                next.update(message);
                return;
            }
        }
        pr.comment(message);
    }

    private static void createDirs(Path path) throws IOException {
        final Path parent = path.getParent();
        if (Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
    }

    private static <Z> InputStreamFunction<Z> firstEntry(InputStreamFunction<Z> fun) {
        return input -> {
            try (final ZipInputStream zis = new ZipInputStream(input)) {
                zis.getNextEntry();
                return fun.apply(zis);
            }
        };
    }

    public static GitHub buildApi() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        final PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(AuthUtil.parsePKCS(GitHubVars.GH_APP_KEY.get())));
        final String appId = GitHubVars.GH_APP_NAME.get();

        final AuthorizationProvider authorizationProvider = AuthUtil.jwt(appId, key, app ->
                app.getInstallationByOrganization(GitHubVars.REPOSITORY_OWNER.get())
                        .createToken().create());

        return new GitHubBuilder()
                .withAuthorizationProvider(authorizationProvider)
                .build();
    }

    public static GHApp getSelfApp() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        final PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(AuthUtil.parsePKCS(GitHubVars.GH_APP_KEY.get())));
        final String appId = GitHubVars.GH_APP_NAME.get();
        return new GitHubBuilder()
                .withJwtToken(AuthUtil.refreshJWT(appId, key))
                .build().getApp();
    }

    private static JsonNode payload() throws IOException {
        try (final InputStream in = Files.newInputStream(Path.of(GitHubVars.EVENT_PATH.get()))) {
            return new ObjectMapper().readTree(in);
        }
    }
}
