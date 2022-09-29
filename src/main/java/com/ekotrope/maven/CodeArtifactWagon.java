package com.ekotrope.maven;

import static java.time.temporal.ChronoUnit.HOURS;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.providers.http.HttpWagon;
import org.apache.maven.wagon.proxy.ProxyInfoProvider;
import org.apache.maven.wagon.repository.Repository;
import org.codehaus.plexus.component.annotations.Component;

import com.amazonaws.services.codeartifact.AWSCodeArtifact;
import com.amazonaws.services.codeartifact.AWSCodeArtifactClientBuilder;
import com.amazonaws.services.codeartifact.model.GetAuthorizationTokenRequest;
import com.amazonaws.services.codeartifact.model.GetRepositoryEndpointRequest;
import com.amazonaws.services.codeartifact.model.PackageFormat;

@Component(role=Wagon.class, hint="codeartifact", instantiationStrategy="per-lookup")
public class CodeArtifactWagon extends HttpWagon
{
    private static String CODEARTIFACT_SCHEME = "codeartifact:";

    // "/" is not an acceptable character in either the domain or repository name, and owner is strictly numeric, so it's a safe delimiter
    private static final Pattern URL_FORMAT = Pattern.compile("codeartifact:(?<domain>.*)/(?<owner>.*)/(?<repositoryName>.*)");

    @Override
    public void connect(Repository repository, AuthenticationInfo authenticationInfo, ProxyInfoProvider proxyInfoProvider ) throws AuthenticationException, ConnectionException
    {
        if (repository.getUrl().startsWith(CODEARTIFACT_SCHEME))
        {
            Matcher urlPartsMatcher = URL_FORMAT.matcher(repository.getUrl());
            boolean found = urlPartsMatcher.find();

            if (found)
            {
                String domain = urlPartsMatcher.group("domain");
                String owner = urlPartsMatcher.group("owner");
                String repositoryName = urlPartsMatcher.group("repositoryName");

                repository.setUrl(getCodeArtifactEndpoint(domain, owner, repositoryName));

                authenticationInfo = new AuthenticationInfo();
                authenticationInfo.setUserName("aws");
                authenticationInfo.setPassword(getCodeArtifactToken(domain, owner));
            }
            else
            {
                throw new RuntimeException("Malformed repository url, must be \"codeartifact:domain/owner/repsitoryName\"");
            }
        }

        super.connect(repository, authenticationInfo, proxyInfoProvider);
    }

    private String getCodeArtifactToken(String domain, String owner)
    {
        AWSCodeArtifact codeartifact = AWSCodeArtifactClientBuilder.defaultClient();

        return codeartifact.getAuthorizationToken(new GetAuthorizationTokenRequest()
                .withDomain(domain)
                .withDomainOwner(owner)
                .withDurationSeconds(Duration.of(8, HOURS).toSeconds())
            ).getAuthorizationToken();
    }

    private String getCodeArtifactEndpoint(String domain, String owner, String repositoryName)
    {
        AWSCodeArtifact codeartifact = AWSCodeArtifactClientBuilder.defaultClient();

        return codeartifact.getRepositoryEndpoint(new GetRepositoryEndpointRequest()
                .withDomain(domain)
                .withDomainOwner(owner)
                .withRepository(repositoryName)
                .withFormat(PackageFormat.Maven)
            ).getRepositoryEndpoint();
    }
}
