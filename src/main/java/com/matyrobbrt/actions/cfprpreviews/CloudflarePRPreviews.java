package com.matyrobbrt.actions.cfprpreviews;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matyrobbrt.actions.cfprpreviews.util.AuthUtil;
import com.matyrobbrt.actions.cfprpreviews.util.GitHubVars;
import org.kohsuke.github.GHArtifact;
import org.kohsuke.github.GHDeploymentState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubAccessor;
import org.kohsuke.github.GitHubBuilder;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CloudflarePRPreviews {
    public static void main(String[] args) throws Exception {
        final GitHub api = buildApi();
        final JsonNode run = payload().get("workflow_run");
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

        final var ghDeployment = repo.createDeployment(GitHubVars.REF.get())
                .autoMerge(false)
                .description("Cloudflare Pages")
                .productionEnvironment(false)
                .environment(GitHubVars.PROJECT_NAME.get() + " (Preview)")
                .create();

        final int result = new ProcessBuilder()
                .directory(new File("."))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .command("npx", "wrangler@3", "pages", "deploy", sitePath.toAbsolutePath().toString(),
                        "--project-name=\"" + GitHubVars.PROJECT_NAME.get() + "\"",
                        "--branch=\"pr-" + prNumber + "\"",
                        "--commit-hash=\"" + sha + "\"",
                        "--commit-message=\"Deploy from " + sha + "\"")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
                .waitFor();
        if (result != 0) {
            deployedCommit.createComment("Failed to publish to Pages. Check Action logs for more details.");
            return;
        }

        final CFApi cfApi = new CFApi(System.getenv("CLOUDFLARE_API_TOKEN"), System.getenv("CLOUDFLARE_ACCOUNT_ID"));
        final var deployments = cfApi.getDeployments(GitHubVars.PROJECT_NAME.get());
        if (deployments.isEmpty()) {
            // how?
            return;
        }
        final var deployment = deployments.get(0); // Last is most recent
        deployedCommit.createComment("""
# Deploying with Cloudflare Pages

| Name                    | Result |
| ----------------------- | - |
| **Last commit:**        | [%s](%s) |
| **Status**:             | %s |
| **Preview URL**:        | %s |
| **Branch Preview URL**: | %s |""".formatted(
            sha, deployedCommit.getHtmlUrl(),
            buildStatusMessage(deployment),
            deployment.url,
            deployment.aliases.get(0)
        ));

        ghDeployment.createStatus(deployment.stages.stream()
                .filter(s -> s.name.equals("deploy"))
                .findFirst().map(s -> switch (s.status) {
                    case "success" -> GHDeploymentState.SUCCESS;
                    case "failure" -> GHDeploymentState.FAILURE;
                    default -> GHDeploymentState.IN_PROGRESS;
                })
                .orElse(GHDeploymentState.IN_PROGRESS)
        ).logUrl("https://dash.cloudflare.com/" + System.getenv("CLOUDFLARE_ACCOUNT_ID") + "/pages/view/" + GitHubVars.PROJECT_NAME.get() + "/" + deployment.id)
                .autoInactive(false).create();

        final var ghPr = repo.getPullRequest(prNumber);
        if (ghPr.getBody() == null || !ghPr.getBody().contains("Preview URL: ")) {
            ghPr.setBody((ghPr.getBody() != null ? ghPr.getBody() + "\n" : "") + "\n------------------\nPreview URL: " + deployment.aliases.get(0));
        }
    }

    private static String buildStatusMessage(CFApi.Deployment deployment) {
        final CFApi.Deployment.Stage stage = deployment.stages.stream()
                .filter(s -> s.name.equals("deploy"))
                .findFirst().orElse(null);
        String status = "⚡️  Deployment in progress...";
        if (stage == null) {
            return status;
        }
        return switch (stage.status) {
            case "success" -> "✅  Deploy successful!";
            case "failure" -> "\uD83D\uDEAB  Deployment failed";
            default -> status;
        };
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

    private static JsonNode payload() throws IOException {
        try (final InputStream in = Files.newInputStream(Path.of(GitHubVars.EVENT_PATH.get()))) {
            return new ObjectMapper().readTree(in);
        }
    }
}
