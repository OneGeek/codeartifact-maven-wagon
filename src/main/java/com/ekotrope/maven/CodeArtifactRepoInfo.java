package com.ekotrope.maven;

import static java.time.temporal.ChronoUnit.HOURS;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.wagon.repository.Repository;

import com.amazonaws.services.codeartifact.AWSCodeArtifact;
import com.amazonaws.services.codeartifact.AWSCodeArtifactClientBuilder;
import com.amazonaws.services.codeartifact.model.GetAuthorizationTokenRequest;
import com.amazonaws.services.codeartifact.model.GetRepositoryEndpointRequest;
import com.amazonaws.services.codeartifact.model.PackageFormat;

final class CodeArtifactRepoInfo
{
    // "/" is not an acceptable character in either the domain or repository name, and owner is strictly numeric, so it's a safe delimiter
    private static final Pattern URL_FORMAT = Pattern.compile("codeartifact:(?<domain>.*)/(?<owner>.*)/(?<repositoryName>.*)");

    private static final AWSCodeArtifact codeartifact = AWSCodeArtifactClientBuilder.defaultClient();

    final String domain;
    final String owner;
    final String repositoryName;
    final String endpoint;
    final String token;

    CodeArtifactRepoInfo(Repository repository)
    {
        Matcher urlPartsMatcher = URL_FORMAT.matcher(repository.getUrl());
        boolean found = urlPartsMatcher.find();

        if (found)
        {
            this.domain = urlPartsMatcher.group("domain");
            this.owner = urlPartsMatcher.group("owner");
            this.repositoryName = urlPartsMatcher.group("repositoryName");
        }
        else
        {
            throw new RuntimeException(
                "Malformed codeartifact repository url, must be \"codeartifact:domain/owner/repsitoryName\", was \"" + repository.getUrl() + "\"");
        }

        this.endpoint = getCodeArtifactEndpoint();
        this.token = getCodeArtifactToken();
    }

    private String getCodeArtifactEndpoint()
    {
        return codeartifact.getRepositoryEndpoint(new GetRepositoryEndpointRequest()
            .withDomain(domain)
            .withDomainOwner(owner)
            .withRepository(repositoryName)
            .withFormat(PackageFormat.Maven)
        ).getRepositoryEndpoint();
    }

    private String getCodeArtifactToken()
    {
        return codeartifact.getAuthorizationToken(new GetAuthorizationTokenRequest()
            .withDomain(domain)
            .withDomainOwner(owner)
            .withDurationSeconds(Duration.of(8, HOURS).getSeconds())
        ).getAuthorizationToken();
    }
}
