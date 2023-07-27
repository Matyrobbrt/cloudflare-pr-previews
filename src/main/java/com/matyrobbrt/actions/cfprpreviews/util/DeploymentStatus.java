package com.matyrobbrt.actions.cfprpreviews.util;

import org.kohsuke.github.GHDeploymentState;

public enum DeploymentStatus {
    FAILURE(GHDeploymentState.FAILURE, "\uD83D\uDEAB  Deployment failed"),
    SUCCESS(GHDeploymentState.SUCCESS, "✅  Deploy successful!"),
    PENDING(GHDeploymentState.PENDING, "⚡️  Deployment in progress...");

    public final GHDeploymentState state;
    public final String message;

    DeploymentStatus(GHDeploymentState state, String message) {
        this.state = state;
        this.message = message;
    }
}
