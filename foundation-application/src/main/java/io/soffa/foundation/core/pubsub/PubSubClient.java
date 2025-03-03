package io.soffa.foundation.core.pubsub;


import io.soffa.foundation.core.messages.Message;
import jakarta.validation.constraints.NotNull;
import lombok.SneakyThrows;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.concurrent.CompletableFuture;

public interface PubSubClient {


    @SneakyThrows
    void subscribe(@NonNull String subject, boolean broadcast, MessageHandler messageHandler);

    <T> CompletableFuture<T> request(@NonNull String subject, @NotNull Message message, Class<T> expectedClass);

    void publish(@NonNull String subject, @NotNull Message message);

    @SneakyThrows
    void broadcast(@NonNull String target, @NotNull Message message);


    // <I, O, T extends Query<I, O>> T proxy(@NonNull String subjet, @NotNull Class<T> operationClass);

    void setDefaultBroadcast(String value);

    /*
    @SuppressWarnings("unchecked")
    default <T> T createClient(Class<T> clientInterface, String subject) {

        Map<Method, String> mapping = new HashMap<>();

        for (Method method : clientInterface.getDeclaredMethods()) {
            BindOperation binding = method.getAnnotation(BindOperation.class);
            if (binding != null) {
                mapping.put(method, binding.value().getName());
            }
        }

        if (mapping.isEmpty()) {
            throw new TechnicalException("No method found with annotation @BindOperation");
        }

        return (T) java.lang.reflect.Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class[]{clientInterface},
            (proxy, method, args) -> {
                if ("hashCode".equals(method.getName())) {
                    return clientInterface.getName().hashCode();
                }
                if ("equals".equals(method.getName())) {
                    return method.equals(args[0]);
                }
                if (!mapping.containsKey(method)) {
                    throw new TechnicalException("This method has no @BindOperation annotation");
                }
                return request(subject, createMessage(mapping.get(method), args), method.getReturnType()).get(30, TimeUnit.SECONDS);
            });
    }

    */

}


