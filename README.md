# Codeartifact Maven Wagon
Consume and deploy artifacts from an AWS CodeArtifact repository, using the standard AWS credentials provider
## Credentials
This wagon uses [AWS default credential provider chain](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html) when 
authenticating to the AWS SDK in order to retrieve the CodeArtifact endpoint url and auth token.
## Usage
To allow a Maven project to access a CodeArtifact repository, add this wagon as an extension to the build section of the project's pom.xml
```xml
<build>
    <extensions>
        <extension>
            <groupId>com.ekotrope</groupId>
            <artifactId>codeartifact-maven-wagon</artifactId>
            <version>1.0.0</version>
        </extension>
    </extensions>
</build>
```

Then, declare any CodeArtifact repositories, using the following format for the url `codeartifact:domain/owner/repositoryName`.

```xml
<repositories>
    <repository>
        <id>my-repo</id>
        <url>codeartifact:my-domain/111122223333/my-repo</url> 
    </repository>
</repositories>
```
## Building
To build this project and run its test, you need to have a CodeArtifact repository you can use containing some dependency only available from that
repository. Then you must supply the following properties.
* codeartifact.test.domain
* codeartifact.test.owner
* codeartifact.test.repositoryName
* codeartifact.test.dep.groupId
* codeartifact.test.dep.artifactId
* codeartifact.test.dep.version
