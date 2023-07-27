package com.matyrobbrt.actions.cfprpreviews;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

public class CFApi {
    private final HttpClient client;
    private final String apiToken, accountId;
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES);

    public CFApi(String apiToken, String accountId) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.apiToken = apiToken;
        this.accountId = accountId;
    }

    public List<Deployment> getDeployments(String projectName) throws IOException, InterruptedException {
        return mapper.readValue(client.send(HttpRequest.newBuilder()
                .uri(URI.create("https://api.cloudflare.com/client/v4/accounts/" + accountId + "/pages/projects/" + projectName + "/deployments"))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body(), new TypeReference<WithResult<List<Deployment>>>() {})
                .result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Deployment {
        public List<String> aliases;
        public String url;
        public List<Stage> stages;
        public String id;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Stage {
            public String name;
            public String status;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WithResult<T> {
        public T result;
    }

}
