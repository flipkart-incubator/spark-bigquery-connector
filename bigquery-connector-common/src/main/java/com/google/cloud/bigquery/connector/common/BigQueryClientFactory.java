package com.google.cloud.bigquery.connector.common;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.api.gax.rpc.HeaderProvider;
import com.google.auth.Credentials;
import com.google.auth.oauth2.ExternalAccountCredentials;
import com.google.cloud.bigquery.storage.v1.BigQueryReadClient;
import com.google.cloud.bigquery.storage.v1.BigQueryReadSettings;
import com.google.cloud.bigquery.storage.v1beta2.BigQueryWriteClient;
import com.google.cloud.bigquery.storage.v1beta2.BigQueryWriteSettings;
import com.google.common.base.Objects;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Since Guice recommends to avoid injecting closeable resources (see
 * https://github.com/google/guice/wiki/Avoid-Injecting-Closable-Resources), this factory creates
 * and caches clients and also closes them during JVM shutdown.
 */
public class BigQueryClientFactory implements Serializable {
  private static final Logger log = LoggerFactory.getLogger(BigQueryClientFactory.class);
  private static final Map<BigQueryClientFactory, BigQueryReadClient> readClientMap =
      new HashMap<>();
  private static final Map<BigQueryClientFactory, BigQueryWriteClient> writeClientMap =
      new HashMap<>();

  private final Credentials credentials;
  // using the user agent as HeaderProvider is not serializable
  private final HeaderProvider headerProvider;
  private final BigQueryConfig bqConfig;

  @Inject
  public BigQueryClientFactory(
      BigQueryCredentialsSupplier bigQueryCredentialsSupplier,
      HeaderProvider headerProvider,
      BigQueryConfig bqConfig) {
    // using Guava's optional as it is serializable
    this.credentials = bigQueryCredentialsSupplier.getCredentials();
    this.headerProvider = headerProvider;
    this.bqConfig = bqConfig;
  }

  public BigQueryReadClient getBigQueryReadClient() {
    synchronized (readClientMap) {
      if (!readClientMap.containsKey(this)) {
        BigQueryReadClient bigQueryReadClient =
            createBigQueryReadClient(this.bqConfig.getEndpoint());
        Runtime.getRuntime()
            .addShutdownHook(new Thread(() -> shutdownBigQueryReadClient(bigQueryReadClient)));
        readClientMap.put(this, bigQueryReadClient);
      }
    }

    return readClientMap.get(this);
  }

  public BigQueryWriteClient getBigQueryWriteClient() {
    synchronized (writeClientMap) {
      if (!writeClientMap.containsKey(this)) {
        BigQueryWriteClient bigQueryWriteClient = createBigQueryWriteClient();
        Runtime.getRuntime()
            .addShutdownHook(new Thread(() -> shutdownBigQueryWriteClient(bigQueryWriteClient)));
        writeClientMap.put(this, bigQueryWriteClient);
      }
    }

    return writeClientMap.get(this);
  }

  @Override
  public int hashCode() {
    // Here, credentials is an instance of GoogleCredentials which can be one out of
    // GoogleCredentials, UserCredentials, ServiceAccountCredentials, ExternalAccountCredentials or
    // ImpersonatedCredentials (See the class BigQueryCredentialsSupplier which supplies these
    // Credentials). Subclasses of the abstract class ExternalAccountCredentials do not have the
    // hashCode method defined on them and hence we get the byte array of the
    // ExternalAccountCredentials first and then compare their hashCodes.
    if (credentials instanceof ExternalAccountCredentials) {
      return Objects.hashCode(
          BigQueryUtil.getCredentialsByteArray(credentials), headerProvider, bqConfig);
    }

    return Objects.hashCode(credentials, headerProvider, bqConfig);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BigQueryClientFactory)) {
      return false;
    }

    BigQueryClientFactory that = (BigQueryClientFactory) o;

    if (Objects.equal(headerProvider, that.headerProvider)
        && Objects.equal(
            new BigQueryClientFactoryConfig(bqConfig),
            new BigQueryClientFactoryConfig(that.bqConfig))) {
      // Here, credentials and that.credentials are instances of GoogleCredentials which can be one
      // of GoogleCredentials, UserCredentials, ServiceAccountCredentials,
      // ExternalAccountCredentials or ImpersonatedCredentials (See the class
      // BigQueryCredentialsSupplier which supplies these Credentials). Subclasses of
      // ExternalAccountCredentials do not have an equals method defined on them and hence we
      // serialize and compare byte arrays if either of the credentials are instances of
      // ExternalAccountCredentials
      return BigQueryUtil.areCredentialsEqual(credentials, that.credentials);
    }

    return false;
  }

  private BigQueryReadClient createBigQueryReadClient(Optional<String> endpoint) {
    try {
      InstantiatingGrpcChannelProvider.Builder transportBuilder =
          BigQueryReadSettings.defaultGrpcTransportProviderBuilder()
              .setHeaderProvider(headerProvider);
      setProxyConfig(transportBuilder);
      endpoint.ifPresent(
          e -> {
            log.info("Overriding endpoint to: ", e);
            transportBuilder.setEndpoint(e);
          });
      BigQueryReadSettings.Builder clientSettings =
          BigQueryReadSettings.newBuilder()
              .setTransportChannelProvider(transportBuilder.build())
              .setCredentialsProvider(FixedCredentialsProvider.create(credentials));
      return BigQueryReadClient.create(clientSettings.build());
    } catch (IOException e) {
      throw new UncheckedIOException("Error creating BigQueryStorageReadClient", e);
    }
  }

  private BigQueryWriteClient createBigQueryWriteClient() {
    try {
      InstantiatingGrpcChannelProvider.Builder transportBuilder =
          BigQueryWriteSettings.defaultGrpcTransportProviderBuilder()
              .setHeaderProvider(headerProvider);
      setProxyConfig(transportBuilder);
      BigQueryWriteSettings.Builder clientSettings =
          BigQueryWriteSettings.newBuilder()
              .setTransportChannelProvider(transportBuilder.build())
              .setCredentialsProvider(FixedCredentialsProvider.create(credentials));
      return BigQueryWriteClient.create(clientSettings.build());
    } catch (IOException e) {
      throw new BigQueryConnectorException("Error creating BigQueryWriteClient", e);
    }
  }

  private void setProxyConfig(InstantiatingGrpcChannelProvider.Builder transportBuilder) {
    BigQueryProxyConfig proxyConfig = bqConfig.getBigQueryProxyConfig();
    if (proxyConfig.getProxyUri().isPresent()) {
      transportBuilder.setChannelConfigurator(
          BigQueryProxyTransporterBuilder.createGrpcChannelConfigurator(
              proxyConfig.getProxyUri(),
              proxyConfig.getProxyUsername(),
              proxyConfig.getProxyPassword()));
    }
  }

  private void shutdownBigQueryReadClient(BigQueryReadClient bigQueryReadClient) {
    if (bigQueryReadClient != null && !bigQueryReadClient.isShutdown()) {
      bigQueryReadClient.shutdown();
    }
  }

  private void shutdownBigQueryWriteClient(BigQueryWriteClient bigQueryWriteClient) {
    if (bigQueryWriteClient != null && !bigQueryWriteClient.isShutdown()) {
      bigQueryWriteClient.shutdown();
    }
  }
}
