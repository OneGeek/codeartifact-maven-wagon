package com.ekotrope.maven;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.temporal.ChronoUnit.HOURS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
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
    // Store statically since wagonRepo is shared and mutated in WagonTransporter in ways we can't control
    private static final Map<String, CodeArtifactRepoInfo> sharedRepoInfo = new ConcurrentHashMap<>();
    private CodeArtifactRepoInfo codeArtifactRepoInfo = null;

    @Override
    protected String getURL(Repository repository)
    {
        storeCodeArtifactInfoIfNeeded(repository);

        return codeArtifactRepoInfo != null
            ? codeArtifactRepoInfo.endpoint
            : repository.getUrl();
    }

    /** Inject auth token at last possible moment to avoid anything else being able to mess with it */
    @Override
    public void setHeaders(HttpUriRequest method)
    {
        super.setHeaders(method);

        if (codeArtifactRepoInfo != null)
        {
            String basicAuth = Base64.getEncoder().encodeToString(("aws:" + codeArtifactRepoInfo.token).getBytes(UTF_8));
            method.setHeader("Authorization", "Basic " + basicAuth);
        }
    }

    private void storeCodeArtifactInfoIfNeeded(Repository repository)
    {
        if (codeArtifactRepoInfo == null)
        {
            String url = repository.getUrl();

            if (url.startsWith("codeartifact:"))
            {
                codeArtifactRepoInfo = new CodeArtifactRepoInfo(repository);
                sharedRepoInfo.put(repository.getId(), codeArtifactRepoInfo);
            }
            else if (sharedRepoInfo.containsKey(repository.getId()))
            {
                // New wagon instance, URL already rewritten — use cached info
                codeArtifactRepoInfo = sharedRepoInfo.get(repository.getId());
            }
        }
    }
}
