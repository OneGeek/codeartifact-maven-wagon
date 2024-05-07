package com.ekotrope.maven;

import com.amazonaws.services.codeartifact.AWSCodeArtifact;
import com.amazonaws.services.codeartifact.AWSCodeArtifactClientBuilder;
import com.amazonaws.services.codeartifact.model.GetAuthorizationTokenRequest;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Random;

public class CodeArtifactTokenManager {
    private static CodeArtifactTokenManager instance;
    private String cachedToken = "";

    private CodeArtifactTokenManager() {
        // Private constructor to prevent instantiation from outside the class.
    }

    public static synchronized CodeArtifactTokenManager getInstance() {
        if (instance == null) {
            instance = new CodeArtifactTokenManager();
        }
        return instance;
    }

    public synchronized String getToken(String domain, String owner)
    {
        if (cachedToken.isEmpty()) {
            setUpCodeArtifactToken(domain, owner);
        }
        return cachedToken;
    }

    private void setUpCodeArtifactToken(String domain, String owner)
    {
        AWSCodeArtifact codeartifact = AWSCodeArtifactClientBuilder.defaultClient();

        cachedToken = codeartifact.getAuthorizationToken(new GetAuthorizationTokenRequest()
            .withDomain(domain)
            .withDomainOwner(owner)
            .withDurationSeconds(Duration.of(8, ChronoUnit.HOURS).getSeconds())
        ).getAuthorizationToken();
    }
}
