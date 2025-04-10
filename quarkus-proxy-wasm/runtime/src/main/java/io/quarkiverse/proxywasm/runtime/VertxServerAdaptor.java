package io.quarkiverse.proxywasm.runtime;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.roastedroot.proxywasm.ArrayBytesProxyMap;
import io.roastedroot.proxywasm.ArrayProxyMap;
import io.roastedroot.proxywasm.ProxyMap;
import io.roastedroot.proxywasm.plugin.GrpcCallResponseHandler;
import io.roastedroot.proxywasm.plugin.HttpCallResponse;
import io.roastedroot.proxywasm.plugin.HttpCallResponseHandler;
import io.roastedroot.proxywasm.plugin.HttpRequestAdaptor;
import io.roastedroot.proxywasm.plugin.ServerAdaptor;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Alternative
@Priority(200)
@ApplicationScoped
public class VertxServerAdaptor implements ServerAdaptor {

    @Inject
    Vertx vertx;

    HttpClient client;

    @PostConstruct
    public void setup() {
        this.client = vertx.createHttpClient();
    }

    @PreDestroy
    public void close() {
        client.close();
    }

    @Override
    public Runnable scheduleTick(long delay, Runnable task) {
        var id = vertx.setPeriodic(delay, x -> task.run());
        return () -> {
            vertx.cancelTimer(id);
        };
    }

    @Inject
    Instance<VertxHttpRequestAdaptor> httpRequestAdaptors;

    @Override
    public HttpRequestAdaptor httpRequestAdaptor(Object context) {
        return httpRequestAdaptors.get();
    }

    @Override
    public Runnable scheduleHttpCall(
            String method,
            String host,
            int port,
            URI uri,
            ProxyMap headers,
            byte[] body,
            ProxyMap trailers,
            int timeout,
            HttpCallResponseHandler handler)
            throws InterruptedException {
        var f =
                client.request(HttpMethod.valueOf(method), port, host, uri.toString())
                        .compose(
                                req -> {
                                    for (var e : headers.entries()) {
                                        req.headers().add(e.getKey(), e.getValue());
                                    }
                                    req.idleTimeout(timeout);
                                    return req.send(Buffer.buffer(body));
                                })
                        .onComplete(
                                resp -> {
                                    if (resp.succeeded()) {
                                        HttpClientResponse result = resp.result();
                                        result.bodyHandler(
                                                bodyHandler -> {
                                                    var h = ProxyMap.of();
                                                    result.headers()
                                                            .forEach(
                                                                    e ->
                                                                            h.add(
                                                                                    e.getKey(),
                                                                                    e.getValue()));
                                                    handler.call(
                                                            new HttpCallResponse(
                                                                    result.statusCode(),
                                                                    h,
                                                                    bodyHandler.getBytes()));
                                                });
                                    } else {
                                        handler.call(
                                                new HttpCallResponse(
                                                        500,
                                                        ProxyMap.of(),
                                                        resp.cause().getMessage().getBytes()));
                                    }
                                });

        return () -> {
            // There doesn't seem to be a way to cancel the request.
        };
    }


    @Override
    public Runnable scheduleGrpcCall(String host, int port, String serviceName, String methodName, ProxyMap headers, byte[] message, int timeoutMillis, GrpcCallResponseHandler handler) throws InterruptedException {

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();

        // Construct method descriptor (assuming unary request/response and protobuf)
        MethodDescriptor<byte[], byte[]> methodDescriptor =
                MethodDescriptor.<byte[], byte[]>newBuilder()
                        .setType(MethodDescriptor.MethodType.UNARY)
                        .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
                        .build();

        ClientCall<byte[], byte[]> call = channel.newCall(methodDescriptor, CallOptions.DEFAULT.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS));

        Metadata metadata = new Metadata();
        for (Map.Entry<String, String> entry : headers.entries()) {
            Metadata.Key<String> key = Metadata.Key.of(entry.getKey(), Metadata.ASCII_STRING_MARSHALLER);
            metadata.put(key, entry.getValue());
        }


        call.start(new ClientCall.Listener<>() {
            @Override
            public void onHeaders(Metadata headers) {
                ArrayBytesProxyMap trailerMap = new ArrayBytesProxyMap();
                for (var key : headers.keys()) {
                    var value = headers.get(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER));
                    trailerMap.add(key, value);
                }
                handler.onHeaders(trailerMap);
            }

            @Override
            public void onMessage(byte[] data) {
                handler.onMessage(data);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                ArrayBytesProxyMap trailerMap = new ArrayBytesProxyMap();
                for (var key : trailers.keys()) {
                    var value = trailers.get(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER));
                    trailerMap.add(key, value);
                }
                handler.onTrailers(trailerMap);
                handler.onClose(status.getCode().value());
            }
        }, metadata);
        call.sendMessage(message);
        call.halfClose();
        call.request(1); // Request a single response
        return () -> {
            call.cancel("shutdown", null);
        };
    }
}
