package com.aamend.spark.gov.nexus

import java.io.File
import java.nio.file.Files

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.maven.repository.internal._
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.deployment.DeployRequest
import org.eclipse.aether.impl._
import org.eclipse.aether.repository.{Authentication, LocalRepository, RemoteRepository}
import org.eclipse.aether.resolution._
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.util.repository.AuthenticationBuilder
import org.eclipse.aether.{DefaultRepositorySystemSession, RepositorySystem}

import scala.collection.JavaConversions._
import scala.util.{Success, Try}

class Nexus() {

  val config: Config = ConfigFactory.load()
  val nexusRepositoryReleasesUrl: String = config.getString("nexus.repository.url")
  val nexusUsername: String = config.getString("nexus.repository.username")
  val nexusPassword: String = config.getString("nexus.repository.password")

  val localRepoTemp: File = Files.createTempDirectory("pipeline").toFile
  localRepoTemp.deleteOnExit()

  val locator = new DefaultServiceLocator
  locator.addService(classOf[ArtifactDescriptorReader], classOf[DefaultArtifactDescriptorReader])
  locator.addService(classOf[VersionResolver], classOf[DefaultVersionResolver])
  locator.addService(classOf[VersionRangeResolver], classOf[DefaultVersionRangeResolver])
  locator.addService(classOf[MetadataGeneratorFactory], classOf[SnapshotMetadataGeneratorFactory])
  locator.addService(classOf[MetadataGeneratorFactory], classOf[VersionsMetadataGeneratorFactory])
  locator.addService(classOf[RepositoryConnectorFactory], classOf[BasicRepositoryConnectorFactory])
  locator.addService(classOf[TransporterFactory], classOf[FileTransporterFactory])
  locator.addService(classOf[TransporterFactory], classOf[HttpTransporterFactory])

  val repositorySystem: RepositorySystem = locator.getService(classOf[RepositorySystem])
  val repositoryLocal = new LocalRepository(localRepoTemp)

  val session: DefaultRepositorySystemSession = MavenRepositorySystemUtils.newSession
  session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(session, repositoryLocal))

  val authentication: Authentication = new AuthenticationBuilder().addUsername(nexusUsername).addPassword(nexusPassword).build
  val repositoryReleases: RemoteRepository = new RemoteRepository.Builder("nexus-releases", "default", nexusRepositoryReleasesUrl).setAuthentication(authentication).build()

  def deploy(artifacts: List[Artifact]): Unit = {
    val deployRequest = new DeployRequest
    deployRequest.setRepository(repositoryReleases)
    artifacts.foreach(a => {
      deployRequest.addArtifact(a.toAether)
    })
    repositorySystem.deploy(session, deployRequest)
  }

  def getNextVersion(artifact: Artifact): Version = {
    val versions = getReleaseVersions(artifact)
    if (versions.isEmpty) {
      artifact.version.copy(buildNumber = Some(0))
    } else {
      Version(versions.last).increment
    }
  }

  private def getReleaseVersions(artifact: Artifact): List[String] = {
    val min = artifact.version.copy(buildNumber = Some(0))
    val max = artifact.version.copy(minorVersion = artifact.version.minorVersion + 1, buildNumber = Some(0))
    getReleaseVersions(artifact, s"[$min,$max)")
  }

  /**
    * Retrieve all artifact versions from nexus given the provided maven version pattern
    *
    * @see <a href="https://docs.oracle.com/middleware/1212/core/MAVEN/maven_version.htm#MAVEN402">https://docs.oracle.com/middleware/1212/core/MAVEN/maven_version.htm#MAVEN402</a>
    * @param artifact the artifact to retrieve, containing GroupId and ArtifactId as well as classifier
    * @param versionRange  the maven version specific pattern, could be exact match [1.0.0] or range [1,2.0)
    * @return the list of all matching versions available on nexus, ordered by version number DESC
    * @throws VersionRangeResolutionException if any issue occurred querying nexus for versions
    */
  private def getReleaseVersions(artifact: Artifact, versionRange: String): List[String] = {
    val request: VersionRangeRequest = new VersionRangeRequest

    val aether = new DefaultArtifact(
      artifact.groupId,
      artifact.artifactId,
      "pom",
      versionRange
    )

    request.setArtifact(aether)
    request.setRepositories(List(repositoryReleases))
    val result: VersionRangeResult = repositorySystem.resolveVersionRange(session, request)
    result
      .getVersions
      .toList
      .map(v => Try(Version.apply(v.toString)))
      .collect { case Success(str) => str }
      .sorted
      .map(_.toString)
  }

}